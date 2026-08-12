package folio

import folio.CursorBytes.*
import folio.FolioError.*

import scala.compiletime.summonFrom
import scala.util.hashing.MurmurHash3

opaque type Cursor = String

object Cursor:

  private val flagDirection: Byte = 0x01
  private val flagPositionKeyset: Byte = 0x02
  private val flagReservedMask: Byte = 0xfc.toByte

  extension (flags: Byte) private def isSet(mask: Byte): Boolean = (flags & mask) != 0

  private val maxKeysetArity: Int = 16

  def apply(value: String): Cursor = value

  inline def encode[FIELD: FieldSchema](decoded: DecodedCursor, query: Query[FIELD])(using
      codec: CursorCodec
  ): Cursor =
    encodeWithFingerprint(decoded, computeFingerprint(query))

  inline def decode[FIELD: FieldSchema](cursor: Cursor, query: Query[FIELD])(using
      codec: CursorCodec
  ): Either[CursorDecodingError, DecodedCursor] =
    decodeWithFingerprint(cursor, query, computeFingerprint(query), summonKeysetMetadata[FIELD])

  /** Compute the stale-cursor fingerprint for a [[Query]] given the absentable-field names contributed by an in-scope
    * [[KeysetField]] (empty when keyset pagination is not in use).
    */
  private[folio] def fingerprintFor[FIELD: FieldSchema](
      query: Query[FIELD],
      absentableFieldNames: Set[String]
  ): Int =
    hash(query, absentableFieldNames.toList.sorted.mkString(","))

  /** Build the field metadata needed to decode and validate cursors from a resolved [[KeysetField]]. */
  private[folio] def keysetMetadataFor[FIELD: FieldSchema](
      keysetField: KeysetField[FIELD, ?]
  ): KeysetMetadata[FIELD] =
    KeysetMetadata(
      Some(keysetField.field),
      keysetField.absentableFields,
      keysetField.fields.view.mapValues(_.acceptsVariant).toMap
    )

  private[folio] case class KeysetMetadata[FIELD](
      uniqueField: Option[FIELD],
      absentableFields: Set[FIELD],
      variantAcceptors: Map[FIELD, AnchorValue => Boolean]
  )

  private[folio] object KeysetMetadata:
    def empty[FIELD]: KeysetMetadata[FIELD] = KeysetMetadata(None, Set.empty, Map.empty)

  private[folio] def encodeWithFingerprint(decoded: DecodedCursor, fingerprint: Int)(using
      codec: CursorCodec
  ): Cursor =
    val payload = byte(buildFlags(decoded)) ++ intBigEndian(fingerprint) ++ positionBytes(decoded.position)
    codec.encode(payload.toArray)

  private[folio] def decodeWithFingerprint[FIELD: FieldSchema](
      cursor: Cursor,
      query: Query[FIELD],
      fingerprint: Int,
      metadata: KeysetMetadata[FIELD]
  )(using codec: CursorCodec): Either[CursorDecodingError, DecodedCursor] =
    decodeBytes(cursor, fingerprint).flatMap: decoded =>
      for
        _ <- validateKeysetArity(decoded, query, metadata)
        _ <- validateAbsentSlots(decoded, query, metadata)
        _ <- validateVariantSlots(decoded, query, metadata)
      yield decoded

  private def decodeBytes(cursor: Cursor, fingerprint: Int)(using
      codec: CursorCodec
  ): Either[CursorDecodingError, DecodedCursor] =
    codec
      .decode(cursor)
      .flatMap: bytes =>
        decodeProgram(fingerprint).runA(ReaderState(bytes, 0))

  private def decodeProgram(fingerprint: Int): Read[DecodedCursor] =
    for
      flags <- readByte
      _ <-
        Either
          .cond(!flags.isSet(flagReservedMask), (), CursorDecodingError.MalformedCursor("reserved flag bits set"))
          .liftRead
      hashValue <- readHash
      _ <- Either.cond(hashValue == fingerprint, (), CursorDecodingError.StaleCursor).liftRead
      isKeyset = flags.isSet(flagPositionKeyset)
      position <- if isKeyset then readKeysetPosition else readOffsetPosition
      _ <- requireExhausted
    yield DecodedCursor(decodeDirection(flags), position)

  private def expectedCursorFields[FIELD](
      query: Query[FIELD],
      metadata: KeysetMetadata[FIELD]
  ): List[FIELD] =
    val orderingFields = query.ordering.map(_.field).toList
    metadata.uniqueField match
      case Some(uniqueField) if !orderingFields.contains(uniqueField) => orderingFields :+ uniqueField
      case _                                                          => orderingFields

  private def validateKeysetArity[FIELD: FieldSchema](
      decoded: DecodedCursor,
      query: Query[FIELD],
      metadata: KeysetMetadata[FIELD]
  ): Either[CursorDecodingError, Unit] =
    decoded.position match
      case Position.Keyset(values) if values.nonEmpty =>
        val expected = expectedCursorFields(query, metadata).size
        Either.cond(
          values.sizeIs == expected,
          (),
          CursorDecodingError.IncompatibleCursor("keyset arity does not match query")
        )
      case _ => Right(())

  private def validateAbsentSlots[FIELD: FieldSchema](
      decoded: DecodedCursor,
      query: Query[FIELD],
      metadata: KeysetMetadata[FIELD]
  ): Either[CursorDecodingError, Unit] =
    decoded.position match
      case Position.Keyset(values) if values.nonEmpty =>
        expectedCursorFields(query, metadata)
          .zip(values)
          .collectFirst:
            case (field, AnchorValue.Absent) if !metadata.absentableFields.contains(field) =>
              CursorDecodingError.IncompatibleCursor(
                s"anchor has Absent value in non-absentable field '${field.name}'"
              )
          .toLeft(())
      case _ => Right(())

  /** Reject a decoded anchor slot carrying a [[FieldValue]] variant the field's codec cannot consume (e.g. a forged
    * `StringV` in a `Long` id slot). The fingerprint pins the query shape but not per-slot variants, so without this a
    * forged cursor would reach the driver as a mismatched bind instead of failing as a [[CursorDecodingError]].
    * `Absent` slots pass here; [[validateAbsentSlots]] handles them.
    */
  private def validateVariantSlots[FIELD: FieldSchema](
      decoded: DecodedCursor,
      query: Query[FIELD],
      metadata: KeysetMetadata[FIELD]
  ): Either[CursorDecodingError, Unit] =
    decoded.position match
      case Position.Keyset(values) if values.nonEmpty =>
        expectedCursorFields(query, metadata)
          .zip(values)
          .collectFirst:
            case (field, value) if metadata.variantAcceptors.get(field).exists(!_(value)) =>
              CursorDecodingError.IncompatibleCursor(
                s"anchor value for field '${field.name}' has incompatible type"
              )
          .toLeft(())
      case _ => Right(())

  extension (cursor: Cursor) def value: String = cursor

  private def buildFlags(decoded: DecodedCursor): Byte =
    val directionBit = decoded.direction match
      case Direction.Forward  => 0
      case Direction.Backward => flagDirection.toInt
    val positionBit = decoded.position match
      case _: Position.Keyset => flagPositionKeyset.toInt
      case _: Position.Offset => 0
    (directionBit | positionBit).toByte

  private def decodeDirection(flags: Byte): Direction =
    if flags.isSet(flagDirection) then Direction.Backward else Direction.Forward

  private def anchorValueBytes(value: AnchorValue): EncodedBytes = value match
    case AnchorValue.Present(fieldValue) => fieldValueBytes(fieldValue)
    case AnchorValue.Absent              => byte(tagAbsent)

  private val readAnchorValue: Read[AnchorValue] =
    readByte.flatMap:
      case `tagIntV`       => readInt.map(FieldValue.IntV(_).present)
      case `tagLongV`      => readLong.map(FieldValue.LongV(_).present)
      case `tagStringV`    => readString.map(FieldValue.StringV(_).present)
      case `tagTimestampV` => readTimestamp.map(FieldValue.TimestampV(_).present)
      case `tagAbsent`     => Right(AnchorValue.Absent: AnchorValue).liftRead
      case _               => Left(CursorDecodingError.MalformedCursor("unknown keyset value type")).liftRead

  private def readAnchorValues(count: Int): Read[List[AnchorValue]] =
    List
      .fill(count)(readAnchorValue)
      .foldRight(Read.pure(List.empty[AnchorValue])): (readValue, readValues) =>
        readValue.flatMap(value => readValues.map(value :: _))

  private def positionBytes(position: Position): EncodedBytes = position match
    case Position.Offset(offset)       => unsignedVarint(offset)
    case Position.Keyset(anchorValues) =>
      unsignedVarint(anchorValues.size.toLong) ++
        anchorValues.flatMap(anchorValueBytes)

  private val readOffsetPosition: Read[Position] =
    readUnsignedVarint.flatMap: value =>
      Position.Offset(value).left.map(_ => CursorDecodingError.MalformedCursor("negative offset")).liftRead

  private val readKeysetPosition: Read[Position] =
    readUnsignedVarint.flatMap: count =>
      Either
        .cond(
          count >= 0L && count <= maxKeysetArity.toLong,
          count.toInt,
          CursorDecodingError.MalformedCursor("keyset arity exceeds limit")
        )
        .liftRead
        .flatMap(readAnchorValues(_).map(Position.Keyset.apply))

  private def orderingPart[FIELD: FieldSchema](ordering: Vector[OrderBy[FIELD]]): String =
    ordering.map(orderBy => s"${orderBy.field.name}:${orderPart(orderBy.order)}").mkString(",")

  private def orderPart(order: Order): String =
    order match
      case Order.Ascending  => "A"
      case Order.Descending => "D"

  private def hash[FIELD: FieldSchema](query: Query[FIELD], absentPart: String): Int =
    val limit = query.limit.value.toString
    val orderingFingerprint = orderingPart(query.ordering)
    val filter = CanonicalFilters.fingerprintPart(query.filters)
    MurmurHash3.stringHash(s"$limit;$orderingFingerprint;$filter;$absentPart")

  private inline def computeFingerprint[FIELD: FieldSchema](query: Query[FIELD]): Int =
    summonFrom:
      case keysetField: KeysetField[FIELD, ?] => fingerprintFor(query, keysetField.absentableFields.map(_.name))
      case _                                  => fingerprintFor(query, Set.empty)

  private inline def summonKeysetMetadata[FIELD: FieldSchema]: KeysetMetadata[FIELD] =
    summonFrom:
      case keysetField: KeysetField[FIELD, ?] => keysetMetadataFor(keysetField)
      case _                                  => KeysetMetadata.empty[FIELD]

extension (cursor: Cursor)
  inline def decode[FIELD: FieldSchema](query: Query[FIELD])(using
      CursorCodec
  ): Either[CursorDecodingError, DecodedCursor] =
    Cursor.decode(cursor, query)

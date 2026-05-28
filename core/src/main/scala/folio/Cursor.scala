package folio

import cats.data.Chain
import cats.syntax.either.*
import cats.syntax.traverse.*
import folio.CursorBytes.*
import folio.FolioError.*

import scala.collection.immutable.ListSet
import scala.util.hashing.MurmurHash3

opaque type Cursor = String

object Cursor:

  private val flagDirection: Byte = 0x01
  private val flagPositionKeyset: Byte = 0x02
  private val flagReservedMask: Byte = 0xfc.toByte

  private val tagIntV: Byte = 0x01
  private val tagLongV: Byte = 0x02
  private val tagStringV: Byte = 0x03
  private val tagTimestampV: Byte = 0x04

  // Must move together with CursorAdvance.keysetAware when multi-column keyset lands.
  private val maxKeysetArity: Int = 1

  def apply(value: String): Cursor = value

  def encode[FIELD: FieldSchema](decoded: DecodedCursor, query: Query[FIELD])(using codec: CursorCodec): Cursor =
    val payload = byte(buildFlags(decoded)) ++ intBigEndian(hash(query)) ++ positionBytes(decoded.position)
    codec.encode(payload.toList.toArray)

  def decode[FIELD: FieldSchema](cursor: Cursor, query: Query[FIELD])(using
      codec: CursorCodec
  ): Either[CursorDecodingError, DecodedCursor] =
    codec
      .decode(cursor)
      .flatMap: bytes =>
        decodeProgram(query).runA(ReaderState(bytes, 0))

  private def decodeProgram[FIELD: FieldSchema](query: Query[FIELD]): Read[DecodedCursor] =
    for
      flags <- readByte("flags")
      _ <- Either.cond((flags & flagReservedMask) == 0, (), CursorDecodingError.MalformedFlags(flags)).liftRead
      hashValue <- readHash
      _ <- Either.cond(hashValue == hash(query), (), CursorDecodingError.StaleCursor).liftRead
      isKeyset = (flags & flagPositionKeyset) != 0
      position <- if isKeyset then readKeysetPosition else readOffsetPosition
      _ <- requireExhausted
    yield DecodedCursor(decodeDirection(flags), position)

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
    if (flags & flagDirection) != 0 then Direction.Backward else Direction.Forward

  private def keysetValueBytes(value: KeysetValue): Chain[Byte] = value match
    case KeysetValue.IntV(intValue)             => byte(tagIntV) ++ intBytes(intValue)
    case KeysetValue.LongV(longValue)           => byte(tagLongV) ++ longBytes(longValue)
    case KeysetValue.StringV(stringValue)       => byte(tagStringV) ++ stringBytes(stringValue)
    case KeysetValue.TimestampV(timestampValue) => byte(tagTimestampV) ++ timestampBytes(timestampValue)

  private val readKeysetValue: Read[KeysetValue] =
    readByte("keyset tag").flatMap:
      case `tagIntV`       => readInt("IntV value").map(KeysetValue.IntV.apply)
      case `tagLongV`      => readLong("LongV value").map(KeysetValue.LongV.apply)
      case `tagStringV`    => readString("StringV").map(KeysetValue.StringV.apply)
      case `tagTimestampV` => readTimestamp("TimestampV").map(KeysetValue.TimestampV.apply)
      case other           => Left(CursorDecodingError.UnknownKeysetTag(other)).liftRead

  private def readKeysetValues(count: Int): Read[List[KeysetValue]] =
    List.fill(count)(readKeysetValue).sequence

  private def positionBytes(position: Position): Chain[Byte] = position match
    case Position.Offset(offset)       => unsignedVarint(offset)
    case Position.Keyset(keysetValues) =>
      unsignedVarint(keysetValues.size.toLong) ++
        Chain.fromSeq(keysetValues).flatMap(keysetValueBytes)

  private val readOffsetPosition: Read[Position] =
    readUnsignedVarint("offset").flatMap: value =>
      Position.Offset(value).leftMap(_ => CursorDecodingError.MalformedOffset(value)).liftRead

  private val readKeysetPosition: Read[Position] =
    readUnsignedVarint("keyset count").flatMap: count =>
      Either
        .cond(
          count >= 0L && count <= maxKeysetArity.toLong,
          count.toInt,
          CursorDecodingError.KeysetArityExceeded(count, maxKeysetArity)
        )
        .liftRead
        .flatMap(readKeysetValues(_).map(Position.Keyset.apply))

  private def sortPart[FIELD: FieldSchema](sortBys: ListSet[SortBy[FIELD]]): String =
    sortBys.map(sortBy => s"${sortBy.field.name}:${orderPart(sortBy.order)}").mkString(",")

  private def orderPart(order: Order): String =
    order match
      case Order.Ascending  => "A"
      case Order.Descending => "D"

  private def singleFilterPart[FIELD: FieldSchema](filter: FilterBy[FIELD]): String =
    val filterType = filter match
      case _: FilterBy.ExactMatch[?] => "exact"
    s"${filter.field.name}:$filterType:${filter.value}"

  private def hash[FIELD: FieldSchema](query: Query[FIELD]): Int =
    val limit = query.limit.value.toString
    val sort = sortPart(query.sortBys)
    val filter = query.filters.toSeq.map(singleFilterPart).sorted.mkString(",")
    MurmurHash3.stringHash(s"$limit;$sort;$filter")

extension (cursor: Cursor)
  def decode[FIELD: FieldSchema](query: Query[FIELD])(using CursorCodec): Either[CursorDecodingError, DecodedCursor] =
    Cursor.decode(cursor, query)

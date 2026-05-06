package folio

import folio.FolioError.*
import scala.collection.immutable.ListSet
import scala.util.hashing.MurmurHash3

opaque type Cursor = String

object Cursor:
  def apply(value: String): Cursor = value

  private val numberOfParts = 4
  private val partSeparator = ";"
  private val fieldSeparator = ":"
  private val listSeparator = ","

  private val keysetCursorType = "k"
  private val offsetCursorType = "o"

  private val forwardDirection = "F"
  private val backwardDirection = "B"

  def encode[FIELD: FieldSchema](decoded: DecodedCursor, query: Query[FIELD])(using codec: CursorCodec): Cursor =
    val direction = directionPart(decoded.direction)
    val cursorType = positionType(decoded.position)
    val offset = positionOffset(decoded.position)
    codec.encode(s"$direction$partSeparator$cursorType$partSeparator$offset$partSeparator${hash(query)}")

  def decode[FIELD: FieldSchema](cursor: Cursor, query: Query[FIELD])(using
      codec: CursorCodec
  ): Either[CursorDecodingError, DecodedCursor] =
    for
      raw <- codec.decode(cursor)
      split = raw.split(partSeparator, -1)
      parts <- Either.cond(
        split.length == numberOfParts,
        split,
        CursorDecodingError.InvalidFormat(numberOfParts, split.length)
      )
      Array(directionString, cursorType, offsetString, fingerprint) = parts
      _ <- Either.cond(fingerprint == hash(query), (), CursorDecodingError.StaleCursor)
      direction <- parseDirection(directionString)
      cursorPosition <- parsePosition(cursorType, offsetString)
    yield DecodedCursor(direction, cursorPosition)

  extension (cursor: Cursor) def value: String = cursor

  private def directionPart(direction: Direction): String =
    direction match
      case Direction.Forward  => forwardDirection
      case Direction.Backward => backwardDirection

  private def parseDirection(directionString: String): Either[CursorDecodingError, Direction] =
    directionString match
      case `forwardDirection`  => Right(Direction.Forward)
      case `backwardDirection` => Right(Direction.Backward)
      case other               => Left(CursorDecodingError.UnknownDirection(other))

  private def positionType(position: Position): String =
    position match
      case _: Position.Keyset => keysetCursorType
      case _: Position.Offset => offsetCursorType

  private def positionOffset(position: Position): String =
    position match
      case Position.Keyset(lastId) => lastId.map(_.toString).getOrElse("")
      case Position.Offset(offset) => offset.toString

  private def limitPart(limit: Option[Limit]): String =
    limit.map(_.value.toString).getOrElse("")

  private def sortPart[FIELD: FieldSchema](sortBys: ListSet[SortBy[FIELD]]): String =
    sortBys.map(sortBy => s"${sortBy.field.name}$fieldSeparator${orderPart(sortBy.order)}").mkString(listSeparator)

  private def orderPart(order: Order): String =
    order match
      case Order.Ascending  => "A"
      case Order.Descending => "D"

  private def filterPart[FIELD: FieldSchema](filters: Set[FilterBy[FIELD]]): String =
    filters.toSeq.map(singleFilterPart).sorted.mkString(listSeparator)

  private def singleFilterPart[FIELD: FieldSchema](filter: FilterBy[FIELD]): String =
    val filterType = filter match
      case _: FilterBy.ExactMatch[?] => "exact"
    s"${filter.field.name}$fieldSeparator$filterType$fieldSeparator${filter.value}"

  private def parsePosition(
      cursorType: String,
      offsetString: String
  ): Either[CursorDecodingError, Position] =
    val offsetLong: String => Either[CursorDecodingError, Long] =
      _.toLongOption.toRight(CursorDecodingError.MalformedOffset(offsetString))

    cursorType match
      case `keysetCursorType` if offsetString.isEmpty => Right(Position.Keyset(None))
      case `keysetCursorType`                         => offsetLong(offsetString).map(id => Position.Keyset(Some(id)))
      case `offsetCursorType`                         => offsetLong(offsetString).map(offset => Position.Offset(offset))
      case _                                          => Left(CursorDecodingError.UnknownCursorType(cursorType))

  private def hash[FIELD: FieldSchema](query: Query[FIELD]): String =
    val limit = limitPart(query.limit)
    val sort = sortPart(query.sortBys)
    val filter = filterPart(query.filters)

    MurmurHash3.stringHash(s"$limit$partSeparator$sort$partSeparator$filter").toString

extension (cursor: Cursor)
  def decode[FIELD: FieldSchema](query: Query[FIELD])(using CursorCodec): Either[CursorDecodingError, DecodedCursor] =
    Cursor.decode(cursor, query)

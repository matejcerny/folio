package folio

import folio.FolioError.*
import scala.collection.immutable.ListSet
import scala.util.hashing.MurmurHash3

opaque type Cursor = String

object Cursor:
  def apply(value: String): Cursor = value

  private val numberOfParts = 3
  private val partSeparator = ";"
  private val fieldSeparator = ":"
  private val listSeparator = ","

  private val idCursorType = "d"
  private val incrementalCursorType = "i"

  def encode[FIELD: FieldSchema](position: CursorPosition, query: Query[FIELD])(using codec: CursorCodec): Cursor =
    codec.encode(s"${positionType(position)}$partSeparator${positionOffset(position)}$partSeparator${hash(query)}")

  def decode[FIELD: FieldSchema](cursor: Cursor, query: Query[FIELD])(using
      codec: CursorCodec
  ): Either[CursorDecodingError, CursorPosition] =
    for
      raw <- codec.decode(cursor)
      split = raw.split(partSeparator, -1)
      parts <- Either.cond(
        split.length == numberOfParts,
        split,
        CursorDecodingError.InvalidFormat(numberOfParts, split.length)
      )
      Array(cursorType, offsetString, fingerprint) = parts
      _ <- Either.cond(fingerprint == hash(query), (), CursorDecodingError.StaleCursor)
      cursorPosition <- parsePosition(cursorType, offsetString)
    yield cursorPosition

  extension (cursor: Cursor) def value: String = cursor

  private def positionType(position: CursorPosition): String =
    position match
      case _: CursorPosition.Id          => idCursorType
      case _: CursorPosition.Incremental => incrementalCursorType

  private def positionOffset(position: CursorPosition): String =
    position match
      case CursorPosition.Id(lastId)          => lastId.map(_.value.toString).getOrElse("")
      case CursorPosition.Incremental(offset) => offset.value.toString

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
  ): Either[CursorDecodingError, CursorPosition] =
    val offsetLong: String => Either[CursorDecodingError, Long] =
      _.toLongOption.toRight(CursorDecodingError.MalformedOffset(offsetString))

    cursorType match
      case `idCursorType` if offsetString.isEmpty => Right(CursorPosition.Id(None))
      case `idCursorType`          => offsetLong(offsetString).map(id => CursorPosition.Id(Some(Offset.LastId(id))))
      case `incrementalCursorType` =>
        offsetLong(offsetString).map(offset => CursorPosition.Incremental(Offset.Incremental(offset)))
      case _ => Left(CursorDecodingError.UnknownCursorType(cursorType))

  private def hash[FIELD: FieldSchema](query: Query[FIELD]): String =
    val limit = limitPart(query.limit)
    val sort = sortPart(query.sortBys)
    val filter = filterPart(query.filters)

    MurmurHash3.stringHash(s"$limit$partSeparator$sort$partSeparator$filter").toString

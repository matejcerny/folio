package folio

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.immutable.ListSet
import scala.util.Try
import scala.util.hashing.MurmurHash3

opaque type Cursor = String

object Cursor:
  private val numberOfParts = 3
  private val partSeparator = ";"
  private val fieldSeparator = ":"
  private val listSeparator = ","

  private val idCursorType = "d"
  private val incrementalCursorType = "i"

  def encode[FIELD: FieldSchema](position: CursorPosition, query: Query[FIELD]): Cursor =
    val fingerprint = hash(query)
    val raw = s"${positionType(position)}$partSeparator${positionOffset(position)}$partSeparator$fingerprint"
    Base64.getUrlEncoder.withoutPadding.encodeToString(raw.getBytes(StandardCharsets.UTF_8))

  def decode[FIELD: FieldSchema](cursor: Cursor, query: Query[FIELD]): Either[String, CursorPosition] =
    for
      raw <- Try(String(Base64.getUrlDecoder.decode(cursor: String), StandardCharsets.UTF_8)).toEither.left
        .map(_ => "Invalid cursor: not valid base64url")
      split = raw.split(partSeparator, -1)
      parts <- Either.cond(split.length == numberOfParts, split, "Invalid cursor format")
      Array(cursorType, offsetString, fingerprint) = parts
      _ <- Either.cond(fingerprint == hash(query), (), "cursor is stale: query parameters changed")
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

  private def parsePosition(cursorType: String, offsetString: String): Either[String, CursorPosition] =
    val offsetLong: String => Either[String, Long] = _.toLongOption.toRight("Invalid cursor: malformed offset")

    cursorType match
      case `idCursorType` if offsetString.isEmpty => Right(CursorPosition.Id(None))
      case `idCursorType`          => offsetLong(offsetString).map(id => CursorPosition.Id(Some(Offset.LastId(id))))
      case `incrementalCursorType` =>
        offsetLong(offsetString).map(offset => CursorPosition.Incremental(Offset.Incremental(offset)))
      case _ => Left(s"Unknown cursor type: $cursorType")

  private def hash[FIELD: FieldSchema](query: Query[FIELD]): String =
    val limit = limitPart(query.limit)
    val sort = sortPart(query.sortBys)
    val filter = filterPart(query.filters)

    MurmurHash3.stringHash(s"$limit$partSeparator$sort$partSeparator$filter").toString

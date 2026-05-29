package folio

import scala.collection.immutable.ListSet

import cats.Applicative
import cats.syntax.functor.*
import cats.syntax.traverse.*
import folio.FolioError.*
import scala.compiletime.summonFrom

case class Page[T](
    limit: Limit,
    previousCursor: Option[Cursor],
    nextCursor: Option[Cursor],
    data: Seq[T]
)

object Page:

  def empty[T](limit: Limit): Page[T] = Page(limit, previousCursor = None, nextCursor = None, data = Seq.empty)

  /** Build a page using whichever pagination strategy [[Position.fromQuery]] selects.
    *
    * `fetchRows` should fetch `query.limit + 1` rows for hasMore detection (callers can use [[Limit.fetchLimit]] from
    * the supplied [[ResolvedQuery]]). The extra row is dropped before the page is returned.
    *
    * Keyset is selected when `KeysetField[FIELD, T]` is in scope; otherwise offset-only is used.
    *
    * When `KeysetField[FIELD, T]` is in scope and `query.sortBys` is empty, the default ascending id sort is
    * materialized into [[ResolvedQuery.sortBys]] so callers always receive a deterministic ordering for keyset queries.
    */
  inline def withPagination[F[_]: Applicative, T, FIELD: FieldSchema](
      query: Query[FIELD],
      fetchRows: ResolvedQuery[FIELD] => F[Seq[T]]
  )(using CursorCodec): F[Either[CursorDecodingError, Page[T]]] =
    val (advance, defaultSortBys): (CursorAdvance[FIELD, T], ListSet[SortBy[FIELD]]) = summonFrom:
      case keysetField: KeysetField[FIELD, T] =>
        (CursorAdvance.keysetAware[FIELD, T](keysetField), ListSet(keysetField.field.ascending))
      case _ =>
        (CursorAdvance.offsetOnly[FIELD, T], ListSet.empty[SortBy[FIELD]])

    // Resolve the fallback "first position" here so summonFrom inside Position.fromQuery
    // sees the concrete FIELD's KeysetField at the inline call site.
    val firstPosition = Position.fromQuery(query)

    paginate(query, fetchRows, advance, firstPosition, defaultSortBys)

  private def paginate[F[_]: Applicative, T, FIELD: FieldSchema](
      query: Query[FIELD],
      fetchRows: ResolvedQuery[FIELD] => F[Seq[T]],
      advance: CursorAdvance[FIELD, T],
      firstPosition: Position,
      defaultSortBys: ListSet[SortBy[FIELD]]
  )(using CursorCodec): F[Either[CursorDecodingError, Page[T]]] =
    val currentDecodedCursor: Either[CursorDecodingError, DecodedCursor] = query.cursor match
      case Some(cursor) =>
        Cursor
          .decode(cursor, query)
          .flatMap: decoded =>
            (firstPosition, decoded.position) match
              case (_: Position.Keyset, _: Position.Keyset) => Right(decoded)
              case (_: Position.Offset, _: Position.Offset) => Right(decoded)
              case _                                        =>
                Left(
                  CursorDecodingError.StrategyMismatch(
                    expected = firstPosition,
                    actual = decoded.position
                  )
                )
      case None => Right(DecodedCursor(Direction.Forward, firstPosition))

    currentDecodedCursor.traverse: current =>
      val limit = query.limit
      val isBackward = current.direction == Direction.Backward
      val baseSortBys = if query.sortBys.nonEmpty then query.sortBys else defaultSortBys

      val (sortBys, fetchPosition, reverseDisplay) = current match
        case DecodedCursor(Direction.Backward, keyset: Position.Keyset) => (baseSortBys.flipOrder, keyset, true)
        case DecodedCursor(Direction.Backward, offset: Position.Offset) => (baseSortBys, offset, false)
        case DecodedCursor(Direction.Forward, position)                 => (baseSortBys, position, false)

      fetchRows(ResolvedQuery(query.filters, sortBys, limit.fetchLimit, fetchPosition))
        .map: rowsPlusOne =>
          val hasMore = limit.hasMore(rowsPlusOne)
          val rows = rowsPlusOne.take(limit.value)
          val ordered = if reverseDisplay then rows.reverse else rows

          if ordered.isEmpty then Page.empty(limit)
          else
            val nextCursor = Option.when(isBackward || hasMore):
              DecodedCursor(Direction.Forward, advance.next(fetchPosition, sortBys, ordered, limit)).encode(query)
            val previousCursor = Option.when((isBackward && hasMore) || (!isBackward && !current.isFirst)):
              DecodedCursor(Direction.Backward, advance.previous(fetchPosition, sortBys, ordered, limit)).encode(query)

            Page(limit, previousCursor, nextCursor, ordered)

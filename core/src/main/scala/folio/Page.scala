package folio

import scala.collection.immutable.ListSet

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
    * Cursor-decoding failures are raised through [[FolioEffect.raiseError]]. Pure cursor decoding remains available
    * through [[Cursor.decode]], which returns `Either`.
    *
    * Keyset is selected when `KeysetField[FIELD, T]` is in scope; otherwise offset-only is used.
    *
    * When `KeysetField[FIELD, T]` is in scope and `query.sortBys` is empty, the default ascending id sort is
    * materialized into [[ResolvedQuery.sortBys]] so callers always receive a deterministic ordering for keyset queries.
    */
  inline def withPagination[F[_]: FolioEffect, T, FIELD: FieldSchema](
      query: Query[FIELD],
      fetchRows: ResolvedQuery[FIELD] => F[Seq[T]]
  )(using CursorCodec): F[Page[T]] =
    // Resolve KeysetField[FIELD, T] exactly once and derive every keyset-dependent input from it.
    // Re-summoning KeysetField[FIELD, ?] further down would be ambiguous if multiple row models share the same FIELD.
    summonFrom:
      case keysetField: KeysetField[FIELD, T] =>
        paginate(
          query,
          fetchRows,
          CursorAdvance.keysetAware[FIELD, T](keysetField),
          Position.fromQueryKeyset(query, keysetField),
          ListSet(keysetField.field.ascending),
          Cursor.fingerprintFor(query, keysetField.absentableFields.map(_.name)),
          Cursor.keysetMetadataFor(keysetField)
        )
      case _ =>
        paginate(
          query,
          fetchRows,
          CursorAdvance.offsetOnly[FIELD, T],
          Position.Offset.First,
          ListSet.empty[SortBy[FIELD]],
          Cursor.fingerprintFor(query, Set.empty),
          Cursor.KeysetMetadata.empty[FIELD]
        )

  private def paginate[F[_]: FolioEffect, T, FIELD: FieldSchema](
      query: Query[FIELD],
      fetchRows: ResolvedQuery[FIELD] => F[Seq[T]],
      advance: CursorAdvance[FIELD, T],
      firstPosition: Position,
      defaultSortBys: ListSet[SortBy[FIELD]],
      fingerprint: Int,
      keysetMetadata: Cursor.KeysetMetadata[FIELD]
  )(using CursorCodec): F[Page[T]] =
    val currentDecodedCursor =
      query.cursor
        .map:
          Cursor
            .decodeWithFingerprint(_, query, fingerprint, keysetMetadata)
            .flatMap: decoded =>
              (firstPosition, decoded.position) match
                case (_: Position.Keyset, _: Position.Keyset) => Right(decoded)
                case (_: Position.Offset, _: Position.Offset) => Right(decoded)
                case _ => Left(CursorDecodingError.IncompatibleCursor("cursor strategy does not match query"))
        .getOrElse(Right(DecodedCursor(Direction.Forward, firstPosition)))

    currentDecodedCursor match
      case Left(error)    => FolioEffect[F].raiseError(error)
      case Right(current) =>
        val limit = query.limit
        val isBackward = current.direction == Direction.Backward
        val baseSortBys = if query.sortBys.nonEmpty then query.sortBys else defaultSortBys

        val (fetchPosition, reverseDisplay) = current match
          case DecodedCursor(Direction.Backward, keyset: Position.Keyset) => (keyset, true)
          case DecodedCursor(Direction.Backward, offset: Position.Offset) => (offset, false)
          case DecodedCursor(Direction.Forward, position)                 => (position, false)

        val fetched = fetchRows(
          ResolvedQuery(query.filters, baseSortBys, limit.fetchLimit, fetchPosition, current.direction)
        )
        FolioEffect[F].map(fetched): rowsPlusOne =>
          val hasMore = limit.hasMore(rowsPlusOne)
          val rows = rowsPlusOne.take(limit.value)
          val ordered = if reverseDisplay then rows.reverse else rows

          val page =
            if ordered.isEmpty then Page.empty(limit)
            else
              val nextCursor = Option.when(isBackward || hasMore):
                Cursor.encodeWithFingerprint(
                  DecodedCursor(Direction.Forward, advance.next(fetchPosition, baseSortBys, ordered, limit)),
                  fingerprint
                )
              val hasPreviousPage = fetchPosition match
                // ADR 0003: offset is absolute and direction is a no-op, so a previous page
                // exists iff we are not already at the start. hasMore measures forward rows
                // for an offset fetch and must not gate the previous cursor (would self-loop
                // at offset 0).
                case offset: Position.Offset => !offset.isFirst
                case _: Position.Keyset      => (isBackward && hasMore) || (!isBackward && !current.isFirst)
              val previousCursor = Option.when(hasPreviousPage):
                Cursor.encodeWithFingerprint(
                  DecodedCursor(Direction.Backward, advance.previous(fetchPosition, baseSortBys, ordered, limit)),
                  fingerprint
                )

              Page(limit, previousCursor, nextCursor, ordered)
          page

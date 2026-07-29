package folio

import folio.FolioError.*
import scala.compiletime.summonFrom

case class Page[T](
    limit: Limit,
    previousCursor: Option[Cursor],
    nextCursor: Option[Cursor],
    data: Seq[T]
):

  /** Map the page data, preserving the limit and both cursors.
    *
    * Drivers commonly decode an internal row model and expose another; the cursors describe the query, not the row
    * type, so they carry over unchanged.
    */
  def map[U](f: T => U): Page[U] = Page(limit, previousCursor, nextCursor, data.map(f))

object Page:

  def empty[T](limit: Limit): Page[T] = Page(limit, previousCursor = None, nextCursor = None, data = Seq.empty)

  /** Build a page using whichever pagination strategy [[Position.fromQuery]] selects.
    *
    * `fetchRows` should fetch [[ResolvedQuery.fetchLimit]] rows (one more than the page size) for hasMore detection.
    * The extra row is dropped before the page is returned.
    *
    * Cursor-decoding failures are raised through [[FolioEffect.raiseError]]. Pure cursor decoding remains available
    * through [[Cursor.decode]], which returns `Either`.
    *
    * Keyset is selected when `KeysetField[FIELD, T]` is in scope; otherwise offset-only is used.
    *
    * When `KeysetField[FIELD, T]` is in scope and `query.ordering` is empty, the default ascending id ordering is
    * materialized into [[ResolvedQuery.ordering]] so callers always receive a deterministic ordering for keyset
    * queries.
    */
  inline def withPagination[F[_]: FolioEffect, T, FIELD: FieldSchema](
      query: Query[FIELD],
      fetchRows: ResolvedQuery[FIELD] => F[Seq[T]]
  )(using CursorCodec): F[Page[T]] =
    summonFrom:
      case keysetField: KeysetField[FIELD, T] =>
        withPagination(query, fetchRows, Some(keysetField))
      case _ => withPagination(query, fetchRows, None)

  /** Build a page using an explicitly supplied keyset definition.
    *
    * This is the primitive entry point for adapters that also need the keyset definition when rendering a
    * [[ResolvedQuery]]. Passing the same `Option` to both layers avoids resolving contextual metadata independently.
    * `None` selects offset-only pagination even when a `KeysetField[FIELD, T]` is in scope.
    */
  def withPagination[F[_]: FolioEffect, T, FIELD: FieldSchema](
      query: Query[FIELD],
      fetchRows: ResolvedQuery[FIELD] => F[Seq[T]],
      keyset: Option[KeysetField[FIELD, T]]
  )(using CursorCodec): F[Page[T]] =
    OrderBy.validateFields(query.ordering) match
      case Left(error) => FolioEffect[F].raiseError(error)
      case Right(_)    =>
        keyset match
          case Some(keysetField) =>
            paginate(
              query,
              fetchRows,
              CursorAdvance.keysetAware[FIELD, T](keysetField),
              Position.fromQueryKeyset(query, keysetField),
              Vector(keysetField.field.ascending),
              Cursor.fingerprintFor(query, keysetField.absentableFields.map(_.name)),
              Cursor.keysetMetadataFor(keysetField)
            )
          case None =>
            paginate(
              query,
              fetchRows,
              CursorAdvance.offsetOnly[FIELD, T],
              Position.Offset.First,
              Vector.empty[OrderBy[FIELD]],
              Cursor.fingerprintFor(query, Set.empty),
              Cursor.KeysetMetadata.empty[FIELD]
            )

  private def paginate[F[_]: FolioEffect, T, FIELD: FieldSchema](
      query: Query[FIELD],
      fetchRows: ResolvedQuery[FIELD] => F[Seq[T]],
      advance: CursorAdvance[FIELD, T],
      firstPosition: Position,
      defaultOrdering: Vector[OrderBy[FIELD]],
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
        val baseOrdering = if query.ordering.nonEmpty then query.ordering else defaultOrdering

        val (fetchPosition, reverseDisplay) = current match
          case DecodedCursor(Direction.Backward, keyset: Position.Keyset) => (keyset, true)
          case DecodedCursor(Direction.Backward, offset: Position.Offset) => (offset, false)
          case DecodedCursor(Direction.Forward, position)                 => (position, false)

        val fetched = fetchRows(
          ResolvedQuery(query.filters, baseOrdering, limit, fetchPosition, current.direction)
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
                  DecodedCursor(Direction.Forward, advance.next(fetchPosition, baseOrdering, ordered, limit)),
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
                  DecodedCursor(Direction.Backward, advance.previous(fetchPosition, baseOrdering, ordered, limit)),
                  fingerprint
                )

              Page(limit, previousCursor, nextCursor, ordered)
          page

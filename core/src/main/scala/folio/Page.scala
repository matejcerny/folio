package folio

import folio.FolioError.*
import scala.compiletime.summonFrom

case class Page[T](
    limit: Limit,
    previousCursor: Option[Cursor],
    nextCursor: Option[Cursor],
    data: Seq[T]
):

  /** Map the page data. Cursors describe the query, not the row type, so they carry over unchanged. */
  def map[U](f: T => U): Page[U] = Page(limit, previousCursor, nextCursor, data.map(f))

object Page:

  def empty[T](limit: Limit): Page[T] = Page(limit, previousCursor = None, nextCursor = None, data = Seq.empty)

  /** Build a page using whichever pagination strategy [[Position.fromQuery]] selects.
    *
    * `fetchRows` should fetch [[ResolvedQuery.fetchLimit]] rows (one more than the page size) for hasMore detection.
    * The extra row is dropped before the page is returned.
    *
    * Cursor-decoding failures are raised through [[FolioEffect.raiseError]]; [[Cursor.decode]] stays pure.
    *
    * Keyset is selected when `KeysetField[FIELD, T]` is in scope, otherwise offset-only. With a keyset field and an
    * empty `query.ordering`, the default ascending id ordering is materialized so keyset queries stay deterministic.
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
    * The primitive for adapters that also need the keyset definition when rendering a [[ResolvedQuery]], so both layers
    * see the same `Option`. `None` selects offset-only even when a `KeysetField[FIELD, T]` is in scope.
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
                // ADR 0003: offset is absolute, so a previous page exists iff we are not at the start.
                // hasMore measures forward rows and must not gate it (would self-loop at offset 0).
                case offset: Position.Offset => !offset.isFirst
                case _: Position.Keyset      => (isBackward && hasMore) || (!isBackward && !current.isFirst)
              val previousCursor = Option.when(hasPreviousPage):
                Cursor.encodeWithFingerprint(
                  DecodedCursor(Direction.Backward, advance.previous(fetchPosition, baseOrdering, ordered, limit)),
                  fingerprint
                )

              Page(limit, previousCursor, nextCursor, ordered)
          page

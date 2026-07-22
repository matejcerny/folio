/*
 * Copyright (c) 2026 Matej Cerny
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package folio.skunk

import scala.compiletime.summonFrom

import cats.effect.{ Concurrent, Resource }
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import skunk.{ AppliedFragment, Decoder, Session, Void }
import skunk.codec.all.{ int4, int8, text, timestamptz }
import skunk.implicits.*

import folio.*
import folio.cats.given

/** Skunk-backed cursor pagination.
  *
  * [[withPagination]] is the end-to-end entry point; [[buildSql]] exposes its pure SQL-composition layer.
  */
object Pagination:

  /** Paginate an opaque `select` using the supplied Skunk session and decoder.
    *
    * An in-scope `KeysetField[FIELD, T]` makes keyset pagination available; unsupported orderings fall back to offset.
    * The caller owns the `Session` lifecycle. Cursor, SQL, and session failures are raised in `F`.
    */
  inline def withPagination[F[_]: Concurrent, T, FIELD: FieldSchema](
      query: Query[FIELD],
      session: Session[F],
      decoder: Decoder[T]
  )(
      select: AppliedFragment
  )(using CursorCodec): F[Page[T]] =
    summonFrom:
      case keysetField: KeysetField[FIELD, T] =>
        val keyset = Some(keysetField)
        Page.withPagination[F, T, FIELD](
          query,
          fetchFromSession(_, session, decoder, select, keyset),
          keyset
        )
      case _ =>
        val keyset = None
        Page.withPagination[F, T, FIELD](query, fetchFromSession(_, session, decoder, select, keyset), keyset)

  /** Paginate an opaque `select` using a session drawn from the supplied resource and decoder.
    *
    * Behaves like the `Session[F]` overload, but acquires a session for the duration of this call and releases it
    * afterwards. This fits a pooled `Resource[F, Session[F]]` checkout; the `Session[F]` overload remains the primitive
    * when the caller already holds a session and owns its lifecycle. Cursor, SQL, and session failures are raised in
    * `F`.
    */
  inline def withPagination[F[_]: Concurrent, T, FIELD: FieldSchema](
      query: Query[FIELD],
      session: Resource[F, Session[F]],
      decoder: Decoder[T]
  )(
      select: AppliedFragment
  )(using CursorCodec): F[Page[T]] =
    session.use(withPagination[F, T, FIELD](query, _, decoder)(select))

  private[folio] def fetchFromSession[F[_]: Concurrent, T, FIELD: FieldSchema](
      resolved: ResolvedQuery[FIELD],
      session: Session[F],
      decoder: Decoder[T],
      select: AppliedFragment,
      keysetField: Option[KeysetField[FIELD, ?]]
  ): F[Seq[T]] =
    buildSql(resolved, select, keysetField) match
      case Right(applied) =>
        session
          .prepare(applied.fragment.query(decoder))
          .flatMap(_.stream(applied.argument, chunkSize = resolved.fetchLimit.value).compile.toList.widen[Seq[T]])
      case Left(error) =>
        Concurrent[F].raiseError(error)

  /** Compose parameterized keyset or offset SQL for a resolved query.
    *
    * The user's `select` is wrapped as an opaque subquery (ADR 0004):
    * {{{
    *   SELECT * FROM ( <select> ) AS usersql
    *   WHERE <keyset predicate>
    *   ORDER BY <order fields>
    *   LIMIT <resolved limit>
    * }}}
    *
    * The subquery must project every order and keyset column under its `FieldSchema` name.
    *
    * Values are bound through `AppliedFragment`; the SQL shape can still differ for the first page and `Absent`
    * anchors. Pass the same `KeysetField` used to resolve the query, or `None` for offset-only pagination. A missing
    * keyset field or mismatched anchor arity returns `Left` (ADR 0005).
    *
    * `resolved.filters` is intentionally not rendered; apply filtering inside `select`. Filters still participate in
    * the cursor fingerprint (ADR 0006). Offset queries append the unique field when available; without one, the caller
    * must supply a total order (ADR 0007).
    *
    * Keyset values use fixed Skunk codecs (`int4`, `int8`, `text`, `timestamptz`). UUID, numeric/BigDecimal, and plain
    * timestamp fields require a compatible cast in `select`.
    */
  def buildSql[FIELD: FieldSchema](
      resolved: ResolvedQuery[FIELD],
      select: AppliedFragment,
      keyset: Option[KeysetField[FIELD, ?]]
  ): Either[FolioError, AppliedFragment] =
    OrderBy.validateFields(resolved.ordering).flatMap { _ =>
      resolved.position match
        case offset: Position.Offset         => Right(renderOffset(resolved, select, offset, keyset))
        case keysetPosition: Position.Keyset =>
          keyset match
            case Some(keysetField) =>
              renderKeyset(resolved, select, keysetPosition, keysetField.field, keysetField.absentableFields)
            case None =>
              Left(
                FolioError.InvalidQuery(
                  "Position.Keyset requires Some(keysetField); pass the KeysetField used to resolve the query"
                )
              )
    }

  private def renderKeyset[FIELD: FieldSchema](
      resolved: ResolvedQuery[FIELD],
      select: AppliedFragment,
      keyset: Position.Keyset,
      idField: FIELD,
      absentableFields: Set[FIELD]
  ): Either[FolioError, AppliedFragment] =
    val cursorFields = CursorAdvance.cursorFieldsFor(resolved.ordering, idField)

    // Empty is the first-page anchor; otherwise exact arity prevents zip from truncating the predicate.
    Either.cond(
      keyset.values.isEmpty || keyset.values.size == cursorFields.size, {
        val whereClause =
          if keyset.values.isEmpty then AppliedFragment.empty
          else
            raw(" WHERE ") |+| keysetPredicate(
              resolved.ordering,
              resolved.direction,
              cursorFields,
              keyset.values,
              absentableFields
            )

        val orderByClause = raw(" ORDER BY ") |+| orderBy(resolved.ordering, cursorFields, resolved.direction)

        wrap(select) |+| whereClause |+| orderByClause |+| limitClause(resolved)
      },
      FolioError.InvalidQuery(
        s"Keyset anchor arity mismatch: ${cursorFields.size} cursor field(s) but ${keyset.values.size} anchor value(s)"
      )
    )

  private def renderOffset[FIELD: FieldSchema](
      resolved: ResolvedQuery[FIELD],
      select: AppliedFragment,
      offset: Position.Offset,
      keyset: Option[KeysetField[FIELD, ?]]
  ): AppliedFragment =
    // Offset is absolute, so use forward ordering and append the unique field when available.
    val orderFields = keyset match
      case Some(keysetField) => CursorAdvance.cursorFieldsFor(resolved.ordering, keysetField.field)
      case None              => resolved.ordering.toList.map(_.field)
    val orderingClause = orderBy(resolved.ordering, orderFields, Direction.Forward)
    val orderByClause =
      if orderFields.isEmpty then AppliedFragment.empty
      else raw(" ORDER BY ") |+| orderingClause

    wrap(select) |+| orderByClause |+| raw(" OFFSET ") |+| sql"$int8".apply(offset.offset) |+| limitClause(resolved)

  private def wrap(select: AppliedFragment): AppliedFragment =
    raw("SELECT * FROM (") |+| select |+| raw(") AS usersql")

  private def limitClause[FIELD](resolved: ResolvedQuery[FIELD]): AppliedFragment =
    // fetchLimit is the page size plus one, so withPagination can detect a further page.
    raw(" LIMIT ") |+| sql"$int4".apply(resolved.fetchLimit.value)

  // === Keyset predicate ===

  /** Lexicographic seek expanded for mixed directions and NULL placement: `strict_1 OR (eq_1 AND strict_2) OR ...`.
    * Equality uses `IS NOT DISTINCT FROM` so NULL equals NULL.
    */
  private def keysetPredicate[FIELD: FieldSchema](
      ordering: Vector[OrderBy[FIELD]],
      direction: Direction,
      cursorFields: List[FIELD],
      values: List[KeysetValue],
      absentableFields: Set[FIELD]
  ): AppliedFragment =
    val fieldsWithValues = cursorFields.zip(values)
    val disjuncts = fieldsWithValues.indices.toList.map: rung =>
      val equalityRungs = (0 until rung).toList.map: earlier =>
        val (field, value) = fieldsWithValues(earlier)
        equalityRung(columnReference(field), value)

      val (field, value) = fieldsWithValues(rung)
      val order = orderFor(ordering, field)
      val strict = strictStep(columnReference(field), value, order, direction, absentableFields.contains(field))

      if equalityRungs.isEmpty then strict
      else parens(joinFragments(equalityRungs :+ strict, raw(" AND ")))

    joinFragments(disjuncts, raw(" OR "))

  private def equalityRung(column: AppliedFragment, value: KeysetValue): AppliedFragment =
    column |+| raw(" IS NOT DISTINCT FROM ") |+| bindValue(value)

  /** Direction-aware strict seek. Absentable values sort last forward and first backward; an `Absent` anchor becomes
    * `FALSE` forward or `IS NOT NULL` backward.
    */
  private def strictStep(
      column: AppliedFragment,
      value: KeysetValue,
      order: Order,
      direction: Direction,
      isAbsentable: Boolean
  ): AppliedFragment =
    val absent = value == KeysetValue.Absent
    val inner =
      if isAbsentable then
        (order, direction, absent) match
          case (Order.Ascending, Direction.Forward, false) =>
            column |+| raw(" > ") |+| bindValue(value) |+| raw(" OR ") |+| column |+| raw(" IS NULL")
          case (Order.Descending, Direction.Forward, false) =>
            column |+| raw(" < ") |+| bindValue(value) |+| raw(" OR ") |+| column |+| raw(" IS NULL")
          case (Order.Ascending, Direction.Forward, true)   => raw("FALSE")
          case (Order.Descending, Direction.Forward, true)  => raw("FALSE")
          case (Order.Ascending, Direction.Backward, false) =>
            column |+| raw(" < ") |+| bindValue(value)
          case (Order.Descending, Direction.Backward, false) =>
            column |+| raw(" > ") |+| bindValue(value)
          case (Order.Ascending, Direction.Backward, true)  => column |+| raw(" IS NOT NULL")
          case (Order.Descending, Direction.Backward, true) => column |+| raw(" IS NOT NULL")
      else
        val operator = (order, direction) match
          case (Order.Ascending, Direction.Forward)   => " > "
          case (Order.Descending, Direction.Forward)  => " < "
          case (Order.Ascending, Direction.Backward)  => " < "
          case (Order.Descending, Direction.Backward) => " > "
        column |+| raw(operator) |+| bindValue(value)

    parens(inner)

  // === Ordering ===
  private def orderBy[FIELD: FieldSchema](
      ordering: Vector[OrderBy[FIELD]],
      cursorFields: List[FIELD],
      direction: Direction
  ): AppliedFragment =
    val clauses = cursorFields.map: field =>
      val appended = !ordering.exists(_.field == field)
      orderByClause(columnReference(field), orderFor(ordering, field), direction, appended)
    joinFragments(clauses, raw(", "))

  /** Appended unique fields use their default ascending order without a NULLS clause. Order fields reverse both order
    * and NULL placement for backward traversal (ADRs 0001 and 0003).
    */
  private def orderByClause(
      column: AppliedFragment,
      order: Order,
      direction: Direction,
      appended: Boolean
  ): AppliedFragment =
    val keyword =
      if appended then
        direction match
          case Direction.Forward  => "ASC"
          case Direction.Backward => "DESC"
      else
        (order, direction) match
          case (Order.Ascending, Direction.Forward)   => "ASC NULLS LAST"
          case (Order.Descending, Direction.Forward)  => "DESC NULLS LAST"
          case (Order.Ascending, Direction.Backward)  => "DESC NULLS FIRST"
          case (Order.Descending, Direction.Backward) => "ASC NULLS FIRST"
    column |+| raw(" " + keyword)

  private def orderFor[FIELD](ordering: Vector[OrderBy[FIELD]], field: FIELD): Order =
    ordering.find(_.field == field).map(_.order).getOrElse(Order.Default)

  // === Rendering ===

  /** A qualified, safely quoted identifier. */
  private def columnReference[FIELD: FieldSchema](field: FIELD): AppliedFragment =
    raw("usersql." + quoteIdentifier(field.name))

  private def quoteIdentifier(name: String): String =
    "\"" + name.replace("\"", "\"\"") + "\""

  private def bindValue(value: KeysetValue): AppliedFragment =
    value match
      case KeysetValue.IntV(intValue)             => sql"$int4".apply(intValue)
      case KeysetValue.LongV(longValue)           => sql"$int8".apply(longValue)
      case KeysetValue.StringV(stringValue)       => sql"$text".apply(stringValue)
      case KeysetValue.TimestampV(timestampValue) => sql"$timestamptz".apply(timestampValue)
      case KeysetValue.Absent                     => raw("NULL")

  private def parens(fragment: AppliedFragment): AppliedFragment =
    raw("(") |+| fragment |+| raw(")")

  /** Splice raw, trusted SQL text (identifiers, keywords, operators) with no bound parameter. */
  private def raw(text: String): AppliedFragment = sql"#$text".apply(Void)

  private def joinFragments(parts: List[AppliedFragment], separator: AppliedFragment): AppliedFragment =
    parts match
      case Nil          => AppliedFragment.empty
      case head :: tail => tail.foldLeft(head)((accumulated, part) => accumulated |+| separator |+| part)

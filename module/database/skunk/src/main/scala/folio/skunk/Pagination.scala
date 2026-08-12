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

  /** Like the `Session[F]` overload, but checks out a session for the duration of this call and releases it after. */
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
    *   WHERE <filters> AND ( <keyset predicate> )
    *   ORDER BY <order fields>
    *   LIMIT <resolved limit>
    * }}}
    *
    * The subquery must project every filter, order, and keyset column under its `FieldSchema` name.
    *
    * Pass the same `KeysetField` used to resolve the query, or `None` for offset-only pagination. A missing keyset
    * field or mismatched anchor arity returns `Left` (ADR 0005). A `Position.Keyset` with unregistered cursor fields
    * also returns `Left`: their absentability is unknown, so neither the seek nor the `NULLS` placement can be
    * rendered. Offset skips that check — an unregistered order field is what selects the offset fallback.
    *
    * Bound parameters appear in the order inner `SELECT`, filters, keyset or offset, fetch limit. Offset queries append
    * the unique field when available; without one, the caller must supply a total order (ADR 0007).
    *
    * Only a field registered as absentable renders an explicit `NULLS LAST` forward / `NULLS FIRST` backward; anything
    * else has unknown absentability and takes PostgreSQL's default placement, which avoids a mismatch with an otherwise
    * compatible B-tree index (ADR 0010).
    *
    * Filter and keyset values use fixed Skunk codecs (`int4`, `int8`, `text`, `timestamptz`). UUID, numeric/BigDecimal,
    * and plain timestamp fields require a compatible cast in `select`.
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
              val cursorFields = CursorAdvance.cursorFieldsFor(resolved.ordering, keysetField.field)
              val unregisteredFields = cursorFields.filterNot(keysetField.fields.contains)
              val registrationError = FolioError.InvalidQuery(
                s"Position.Keyset has unregistered cursor field(s): ${unregisteredFields.map(_.name).mkString(", ")}"
              )
              Either
                .cond(unregisteredFields.isEmpty, (), registrationError)
                .flatMap(_ =>
                  renderKeyset(resolved, select, keysetPosition, keysetField.field, keysetField.absentableFields)
                )
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
        val seek = Option.when(keyset.values.nonEmpty):
          keysetPredicate(resolved.ordering, resolved.direction, cursorFields, keyset.values, absentableFields)

        val orderingClause =
          raw(" ORDER BY ") |+| orderBy(resolved.ordering, cursorFields, resolved.direction, absentableFields)

        wrap(select) |+| where(filterPredicate(resolved.filters), seek) |+| orderingClause |+| limitClause(resolved)
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
    // Only a registered absentable field is known absentable; anything unregistered here is unknown (ADR 0010).
    val absentableFields = keyset.map(_.absentableFields).getOrElse(Set.empty[FIELD])
    val orderingClause = orderBy(resolved.ordering, orderFields, Direction.Forward, absentableFields)
    val orderByClause =
      if orderFields.isEmpty then AppliedFragment.empty
      else raw(" ORDER BY ") |+| orderingClause

    wrap(select) |+| where(filterPredicate(resolved.filters), None) |+|
      orderByClause |+| raw(" OFFSET ") |+| sql"$int8".apply(offset.offset) |+| limitClause(resolved)

  private def wrap(select: AppliedFragment): AppliedFragment =
    raw("SELECT * FROM (") |+| select |+| raw(") AS usersql")

  private def limitClause[FIELD](resolved: ResolvedQuery[FIELD]): AppliedFragment =
    // fetchLimit is the page size plus one, so withPagination can detect a further page.
    raw(" LIMIT ") |+| sql"$int4".apply(resolved.fetchLimit.value)

  /** The single outer `WHERE`, or nothing when the query has neither filters nor an anchor.
    *
    * Filters come first to keep the parameter order [[buildSql]] documents. The seek is a disjunction, so it is
    * parenthesized before being ANDed — otherwise its trailing rungs would escape the filters and leak rows.
    */
  private def where(filters: Option[AppliedFragment], seek: Option[AppliedFragment]): AppliedFragment =
    val conjuncts = (filters, seek) match
      case (Some(filterConjunction), Some(keysetSeek)) => List(filterConjunction, parens(keysetSeek))
      case (filterConjunction, keysetSeek)             => filterConjunction.toList ++ keysetSeek.toList

    if conjuncts.isEmpty then AppliedFragment.empty
    else raw(" WHERE ") |+| joinFragments(conjuncts, raw(" AND "))

  // === Filters ===

  /** Conjunction of a query's exact-match filters, or `None` when the query has none.
    *
    * Filters render in [[folio.CanonicalFilters]] order, so the SQL text does not depend on `Set` iteration order and
    * the prepared-statement cache keeps working. Two filters on the same field are two ANDed predicates, since
    * [[folio.FilterBy.ExactMatch]] identity is `(field, encoded value)`.
    */
  private[skunk] def filterPredicate[FIELD: FieldSchema](filters: Set[FilterBy[FIELD]]): Option[AppliedFragment] =
    val predicates = CanonicalFilters.sorted(filters).map(filterComparison).toList
    Option.when(predicates.nonEmpty)(joinFragments(predicates, raw(" AND ")))

  /** The operator a single filter renders with. Matching keeps the next `FilterBy` case an exhaustivity error here
    * instead of a silent mis-render.
    */
  private def filterComparison[FIELD: FieldSchema](filter: FilterBy[FIELD]): AppliedFragment = filter match
    case _: FilterBy.ExactMatch[?, ?] =>
      columnReference(filter.field) |+| raw(" = ") |+| bindFieldValue(filter.encodedValue)

  // === Keyset predicate ===

  /** Lexicographic seek expanded for mixed directions and NULL placement: `strict_1 OR (eq_1 AND strict_2) OR ...`.
    * Equality uses `IS NOT DISTINCT FROM` so NULL equals NULL.
    */
  private def keysetPredicate[FIELD: FieldSchema](
      ordering: Vector[OrderBy[FIELD]],
      direction: Direction,
      cursorFields: List[FIELD],
      values: List[AnchorValue],
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

  private def equalityRung(column: AppliedFragment, value: AnchorValue): AppliedFragment =
    column |+| raw(" IS NOT DISTINCT FROM ") |+| bindValue(value)

  /** Direction-aware strict seek. Absentable values sort last forward and first backward; an `Absent` anchor becomes
    * `FALSE` forward or `IS NOT NULL` backward.
    */
  private def strictStep(
      column: AppliedFragment,
      value: AnchorValue,
      order: Order,
      direction: Direction,
      isAbsentable: Boolean
  ): AppliedFragment =
    val absent = value == AnchorValue.Absent
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
      direction: Direction,
      absentableFields: Set[FIELD]
  ): AppliedFragment =
    val clauses = cursorFields.map: field =>
      orderByClause(
        columnReference(field),
        orderFor(ordering, field),
        direction,
        absentableFields.contains(field)
      )
    joinFragments(clauses, raw(", "))

  /** Backward traversal reverses both the order and the `Absent` placement (ADRs 0001 and 0003).
    *
    * The `NULLS` clause is emitted only for a known-absentable field — the same set drives the seek predicate, so
    * `ORDER BY` and the predicate agree (ADR 0010).
    */
  private def orderByClause(
      column: AppliedFragment,
      order: Order,
      direction: Direction,
      isAbsentable: Boolean
  ): AppliedFragment =
    val effectiveOrder = if direction == Direction.Backward then order.flip else order
    val orderKeyword = effectiveOrder match
      case Order.Ascending  => "ASC"
      case Order.Descending => "DESC"
    val nullsPlacement =
      if !isAbsentable then ""
      else
        direction match
          case Direction.Forward  => " NULLS LAST"
          case Direction.Backward => " NULLS FIRST"
    column |+| raw(" " + orderKeyword + nullsPlacement)

  private def orderFor[FIELD](ordering: Vector[OrderBy[FIELD]], field: FIELD): Order =
    ordering.find(_.field == field).map(_.order).getOrElse(Order.Default)

  // === Rendering ===

  /** A qualified, safely quoted identifier. */
  private def columnReference[FIELD: FieldSchema](field: FIELD): AppliedFragment =
    raw("usersql." + quoteIdentifier(field.name))

  private def quoteIdentifier(name: String): String =
    "\"" + name.replace("\"", "\"\"") + "\""

  private def bindValue(value: AnchorValue): AppliedFragment =
    value match
      case AnchorValue.Present(fieldValue) => bindFieldValue(fieldValue)
      case AnchorValue.Absent              => raw("NULL")

  /** Binds a value at its own PostgreSQL type using the fixed codec mapping documented on [[buildSql]]. */
  private def bindFieldValue(value: FieldValue): AppliedFragment =
    value match
      case FieldValue.IntV(intValue)             => sql"$int4".apply(intValue)
      case FieldValue.LongV(longValue)           => sql"$int8".apply(longValue)
      case FieldValue.StringV(stringValue)       => sql"$text".apply(stringValue)
      case FieldValue.TimestampV(timestampValue) => sql"$timestamptz".apply(timestampValue)

  private def parens(fragment: AppliedFragment): AppliedFragment =
    raw("(") |+| fragment |+| raw(")")

  /** Splice raw, trusted SQL text (identifiers, keywords, operators) with no bound parameter. */
  private def raw(text: String): AppliedFragment = sql"#$text".apply(Void)

  private def joinFragments(parts: List[AppliedFragment], separator: AppliedFragment): AppliedFragment =
    parts match
      case Nil          => AppliedFragment.empty
      case head :: tail => tail.foldLeft(head)((accumulated, part) => accumulated |+| separator |+| part)

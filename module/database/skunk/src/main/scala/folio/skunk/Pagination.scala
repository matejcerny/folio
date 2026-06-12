package folio.skunk

import scala.collection.immutable.ListSet

import skunk.{ AppliedFragment, Void }
import skunk.codec.all.{ int4, int8, text, timestamptz }
import skunk.implicits.*

import folio.*

/** Skunk-backed cursor pagination.
  *
  * This object hosts folio-skunk's public API. Phase 1 ships only [[buildSql]] (L1) — the pure, effect-free SQL
  * composition core. The effectful [[withPagination]] (L3) wiring arrives in a later phase.
  */
object Pagination:

  /** Compose the wrapped, parameterized keyset/offset SQL for a resolved query.
    *
    * The user's `select` is treated as an opaque block and wrapped (see ADR 0004):
    * {{{
    *   SELECT * FROM ( <select> ) AS usersql
    *    WHERE <keyset predicate>
    *    ORDER BY <sortBys, direction-aware>
    *    LIMIT <limit>
    * }}}
    *
    * Keyset values, the offset, and the limit are bound as Skunk parameters via `AppliedFragment.|+|`, never baked into
    * the SQL text: the template stays stable across cursor advances (prepared-statement-cache stability), only the
    * bound arguments change.
    *
    * `buildSql` is a low-level escape hatch for users who want their own error mapping, observability hooks, or
    * multi-statement transactions.
    *
    * ==Keyset metadata==
    *
    * Pass the same `KeysetField[FIELD, T]` you use for keyset pagination as `Some(keysetField)` to render the keyset
    * predicate; pass `None` only for an offset-positioned query to produce the offset form. The metadata is an explicit
    * argument rather than summoned so the escape hatch stays usable when several row models share one `FIELD` enum (a
    * wildcard `summon[KeysetField[FIELD, ?]]` would be ambiguous), mirroring `Page.withPagination` (see ADR 0005).
    *
    * ==Failures (`Left`)==
    *
    *   - `None` paired with a `Position.Keyset` resolved query — the keyset predicate needs the id metadata only the
    *     `KeysetField` carries, so this returns a `Left` rather than rendering SQL with the id tiebreaker dropped (or
    *     an empty `ORDER BY` when no sort fields are present).
    *   - A non-empty `Position.Keyset` anchor whose value count differs from the cursor-field count (the sort fields
    *     plus the appended id tiebreaker) — returns a `Left` rather than letting the predicate-building `zip` silently
    *     drop or ignore values.
    *
    * An empty anchor (`Position.Keyset(Nil)`) is the valid first-page request. (See ADR 0005.)
    *
    * ==Not applied: filters==
    *
    * `resolved.filters` is ignored here: the user's opaque `select` is their filtering escape hatch. Filters still feed
    * the cursor fingerprint in core for stale detection. The contract that sort/keyset columns must be projected by the
    * inner `select` will ''tighten'' when filter rendering lands (a previously-valid `select` could then start
    * erroring). (See ADR 0006.)
    *
    * ==Keyset value codecs==
    *
    * The `KeysetValue` -> Skunk-codec mapping is hard-coded, with gaps: `uuid`-as-`StringV` (yields
    * `operator does not exist: uuid > text`), `numeric`/`BigDecimal`, and plain `timestamp` are unsupported.
    * Workaround: use `timestamptz`, or cast/alias the column in the `select` projection. A per-field codec override is
    * deferred and forward-compatible (it arrives as an additional input, not a change to this signature).
    */
  def buildSql[FIELD: FieldSchema](
      resolved: ResolvedQuery[FIELD],
      select: AppliedFragment,
      keyset: Option[KeysetField[FIELD, ?]]
  ): Either[String, AppliedFragment] =
    (resolved.position, keyset) match
      case (_: Position.Keyset, None) =>
        Left("Position.Keyset requires Some(keysetField); pass the KeysetField used to resolve the query")
      case (_, Some(keysetField)) =>
        render(resolved, select, Some(keysetField.field), keysetField.absentableFields)
      case (_, None) =>
        render(resolved, select, None, Set.empty[FIELD])

  private def render[FIELD: FieldSchema](
      resolved: ResolvedQuery[FIELD],
      select: AppliedFragment,
      idField: Option[FIELD],
      absentableFields: Set[FIELD]
  ): Either[String, AppliedFragment] =
    resolved.position match
      case offset: Position.Offset => Right(renderOffset(resolved, select, offset))
      case keyset: Position.Keyset => renderKeyset(resolved, select, keyset, idField, absentableFields)

  private def renderKeyset[FIELD: FieldSchema](
      resolved: ResolvedQuery[FIELD],
      select: AppliedFragment,
      keyset: Position.Keyset,
      idField: Option[FIELD],
      absentableFields: Set[FIELD]
  ): Either[String, AppliedFragment] =
    // Cursor fields are the canonical sort fields with the id appended as a tiebreaker when it is not already a
    // sort field (mirrors CursorAdvance.cursorFieldsFor, which builds Position.Keyset.values in the same order).
    val cursorFields = idField match
      case Some(id) => CursorAdvance.cursorFieldsFor(resolved.sortBys, id)
      case None     => resolved.sortBys.toList.map(_.field)

    // A Nil anchor is the first-page keyset (no WHERE). A non-empty anchor must carry exactly one value per cursor
    // field: keysetPredicate zips the two, so a shorter anchor silently drops trailing rungs (including the id
    // tiebreaker) and a longer one ignores the extras. Guard before rendering rather than emit truncated SQL.
    Either.cond(
      keyset.values.isEmpty || keyset.values.size == cursorFields.size, {
        val whereClause =
          if keyset.values.isEmpty then AppliedFragment.empty
          else
            raw(" WHERE ") |+| keysetPredicate(
              resolved.sortBys,
              resolved.direction,
              cursorFields,
              keyset.values,
              absentableFields
            )

        val orderByClause = raw(" ORDER BY ") |+| orderBy(resolved.sortBys, cursorFields, resolved.direction)

        wrap(select) |+| whereClause |+| orderByClause |+| limitClause(resolved)
      },
      s"Keyset anchor arity mismatch: ${cursorFields.size} cursor field(s) but ${keyset.values.size} anchor value(s)"
    )

  private def renderOffset[FIELD: FieldSchema](
      resolved: ResolvedQuery[FIELD],
      select: AppliedFragment,
      offset: Position.Offset
  ): AppliedFragment =
    // Offset is absolute, so direction is a no-op: the ORDER BY always uses the canonical forward orientation.
    val sortFields = resolved.sortBys.toList.map(_.field)
    val orderByClause =
      if sortFields.isEmpty then AppliedFragment.empty
      else raw(" ORDER BY ") |+| orderBy(resolved.sortBys, sortFields, Direction.Forward)

    wrap(select) |+| orderByClause |+| raw(" OFFSET ") |+| sql"$int8".apply(offset.offset) |+| limitClause(resolved)

  /** `SELECT * FROM ( <select> ) AS usersql` — the user's SELECT as an opaque subquery. */
  private def wrap(select: AppliedFragment): AppliedFragment =
    raw("SELECT * FROM (") |+| select |+| raw(") AS usersql")

  private def limitClause[FIELD](resolved: ResolvedQuery[FIELD]): AppliedFragment =
    // resolved.limit is already the bounded fetch size (limit + 1); do not add 1 again.
    raw(" LIMIT ") |+| sql"$int4".apply(resolved.limit.value)

  // === Keyset WHERE generation ===
  /** Expanded disjunction (never a row-value tuple comparison, which can express neither mixed ASC/DESC nor NULLS
    * placement):
    *
    * strict_1 OR (eq_1 AND strict_2) OR (eq_1 AND eq_2 AND strict_3) ...
    *
    * Equality rungs use IS NOT DISTINCT FROM so NULL = NULL is true. The strict-after step is direction- and
    * Absent-dependent (see strictStep).
    */
  private def keysetPredicate[FIELD: FieldSchema](
      sortBys: ListSet[SortBy[FIELD]],
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
      val order = orderFor(sortBys, field)
      val strict = strictStep(columnReference(field), value, order, direction, absentableFields.contains(field))

      if equalityRungs.isEmpty then strict
      else parens(joinFragments(equalityRungs :+ strict, raw(" AND ")))

    joinFragments(disjuncts, raw(" OR "))

  private def equalityRung(column: AppliedFragment, value: KeysetValue): AppliedFragment =
    column |+| raw(" IS NOT DISTINCT FROM ") |+| bindValue(value)

  /** The direction- and Absent-aware strict-step, returned parenthesized so it composes safely inside the `AND`/`OR`
    * disjunction. For an absentable field the column itself may be NULL, so the present case adds an `OR col IS NULL`
    * rung and the Absent anchor collapses to `FALSE` (forward) / `col IS NOT NULL` (backward). A non-absentable field's
    * anchor is never Absent and the column is never NULL, so it is a plain comparison.
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

  // === ORDER BY generation ===
  private def orderBy[FIELD: FieldSchema](
      sortBys: ListSet[SortBy[FIELD]],
      cursorFields: List[FIELD],
      direction: Direction
  ): AppliedFragment =
    val clauses = cursorFields.map: field =>
      val appended = !sortBys.exists(_.field == field)
      orderByClause(columnReference(field), orderFor(sortBys, field), direction, appended)
    joinFragments(clauses, raw(", "))

  /** The appended id tiebreaker (a cursor field that is not a sort field) is non-Absent, so it carries no NULLS clause;
    * with its default ascending canonical order it emits ASC forward / DESC backward. Genuine sort fields carry the
    * full direction-aware NULLS placement: Absent sorts last forward, first backward (ADR 0001 + ADR 0003).
    */
  private def orderByClause(
      column: AppliedFragment,
      order: Order,
      direction: Direction,
      appended: Boolean
  ): AppliedFragment =
    val keyword =
      if appended then
        (order, direction) match
          case (Order.Ascending, Direction.Forward)   => "ASC"
          case (Order.Ascending, Direction.Backward)  => "DESC"
          case (Order.Descending, Direction.Forward)  => "DESC"
          case (Order.Descending, Direction.Backward) => "ASC"
      else
        (order, direction) match
          case (Order.Ascending, Direction.Forward)   => "ASC NULLS LAST"
          case (Order.Descending, Direction.Forward)  => "DESC NULLS LAST"
          case (Order.Ascending, Direction.Backward)  => "DESC NULLS FIRST"
          case (Order.Descending, Direction.Backward) => "ASC NULLS FIRST"
    column |+| raw(" " + keyword)

  private def orderFor[FIELD](sortBys: ListSet[SortBy[FIELD]], field: FIELD): Order =
    sortBys.find(_.field == field).map(_.order).getOrElse(Order.Default)

  // === Column rendering ===

  /** `usersql."<name>"`, with the identifier double-quoted and any embedded double quote doubled. Injection-safe by
    * construction (total, not regex-dependent); transparent for ordinary snake_case names, correct for reserved words
    * and mixed case.
    */
  private def columnReference[FIELD: FieldSchema](field: FIELD): AppliedFragment =
    raw("usersql." + quoteIdentifier(field.name))

  private def quoteIdentifier(name: String): String =
    "\"" + name.replace("\"", "\"\"") + "\""

  // === Keyset value -> Skunk codec mapping (hard-coded) ===
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

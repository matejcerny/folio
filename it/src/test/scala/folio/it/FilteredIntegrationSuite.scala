package folio.it

import cats.syntax.foldable.*
import cats.syntax.traverse.*

import folio.*

/** Deterministic Postgres integration tests for filtered pagination.
  *
  * [[IntegrationSuite]] proves the pagination algorithms against real Postgres ordering; this suite proves that a
  * filter set narrows those walks — every page here is a page of matching rows, and the non-matching rows of the shared
  * dataset must never appear. That is the end-to-end claim the pure SQL-shape suites (`FilterPredicateSuite`,
  * `FilteredPaginationSuite`) cannot make: real `=` comparison at the column's own type, the filter ANDed with a
  * parenthesized keyset disjunction, and cursors that stay valid across a filtered walk.
  *
  * The dataset repeats `name`, `created_at`, and `group_id` values so a single filter matches several rows and the walk
  * spans multiple pages. Ids stay unique — they are the keyset tiebreaker.
  *
  * {{{
  * id  name   created_at  description  last_seen  group_id
  * 1   alice  at(1)       d1           at(10)     100
  * 2   alice  at(2)       d2           NULL       100
  * 3   alice  at(3)       d3           at(30)     200
  * 4   alice  at(3)       d4           NULL       200
  * 5   alice  at(3)       d5           at(50)     200
  * 6   bob    at(1)       d6           at(60)     100
  * 7   bob    at(2)       d7           NULL       300
  * 8   carol  at(3)       d8           at(80)     300
  * }}}
  */
object FilteredIntegrationSuite extends RowsSuite:

  private val alice1 = Row(1, "alice", at(1), "d1", Some(at(10)), 100, "p1")
  private val alice2 = Row(2, "alice", at(2), "d2", None, 100, "p2")
  private val alice3 = Row(3, "alice", at(3), "d3", Some(at(30)), 200, "p3")
  private val alice4 = Row(4, "alice", at(3), "d4", None, 200, "p4")
  private val alice5 = Row(5, "alice", at(3), "d5", Some(at(50)), 200, "p5")
  private val bob6 = Row(6, "bob", at(1), "d6", Some(at(60)), 100, "p6")
  private val bob7 = Row(7, "bob", at(2), "d7", None, 300, "p7")
  private val carol8 = Row(8, "carol", at(3), "d8", Some(at(80)), 300, "p8")

  private val dataset: List[Row] = List(alice1, alice2, alice3, alice4, alice5, bob6, bob7, carol8)

  // `Set` is invariant, so a filter set built outside an expected-type position needs the explicit element type.
  private def filters(first: FilterBy[RowField], rest: FilterBy[RowField]*): Set[FilterBy[RowField]] =
    Set[FilterBy[RowField]](first) ++ rest

  private val aliceOnly = filters(FilterBy.ExactMatch(RowField.Name, "alice"))

  // === Keyset walks, one filter, one supported value type per case ===

  // String equality against `text`. name = 'alice' keeps 1..5 and drops bob6/bob7/carol8, so the keyset walk over
  // [1,2,3,4,5] pages [1,2] [3,4] [5] and comes back [3,4] [1,2]. Pages 2 and 3 are the interesting ones: their SQL is
  // `name = $1 AND ( <keyset disjunction> )`, so a missing parenthesis around the disjunction would leak bob/carol rows
  // into them.
  test("String filter: name = 'alice', keyset by Id ASC, limit 2 — filtered walk out and back"): session =>
    val query = Query(filters = aliceOnly, limit = 2.items).orderBy(RowField.Id.ascending)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(alice1, alice2), List(alice3, alice4), List(alice5)),
      expectedBackwardData = List(List(alice3, alice4), List(alice1, alice2))
    )

  // Long equality against `bigint`, on a field that is NOT registered in KeysetField: filters need no registration, so
  // this still paginates by keyset (ordering by group_id would be what forces the offset branch). group_id = 200 keeps
  // 3,4,5.
  test("Long filter: group_id = 200 (unregistered field), keyset by Id ASC, limit 2"): session =>
    val query = Query(filters = filters(FilterBy.ExactMatch(RowField.GroupId, 200L)), limit = 2.items)
      .orderBy(RowField.Id.ascending)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(alice3, alice4), List(alice5)),
      expectedBackwardData = List(List(alice3, alice4))
    )

  // OffsetDateTime equality against `timestamptz`. created_at = at(3) keeps 3,4,5,8 — note carol8, so this case also
  // shows the filter is not secretly "name = alice".
  test("OffsetDateTime filter: created_at = at(3), keyset by Id ASC, limit 2"): session =>
    val query = Query(filters = filters(FilterBy.ExactMatch(RowField.CreatedAt, at(3))), limit = 2.items)
      .orderBy(RowField.Id.ascending)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(alice3, alice4), List(alice5, carol8)),
      expectedBackwardData = List(List(alice3, alice4))
    )

  // Filtering the column the query orders by pins every ordered value to one constant, so the seek's first rung
  // (`created_at > $1`) can never match and the appended id tiebreaker alone has to drive the walk. Same four rows as
  // above, reached through a two-rung seek instead of a one-rung one.
  test("filter on the ordered column: created_at = at(3), keyset by CreatedAt ASC, Id ASC, limit 2"): session =>
    val query = Query(filters = filters(FilterBy.ExactMatch(RowField.CreatedAt, at(3))), limit = 2.items)
      .orderBy(RowField.CreatedAt.ascending, RowField.Id.ascending)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(alice3, alice4), List(alice5, carol8)),
      expectedBackwardData = List(List(alice3, alice4))
    )

  // === Conjunction ===

  // Two filters on different fields are ANDed: name = 'alice' AND group_id = 100 keeps only 1,2 (bob6 shares group 100,
  // alice3..5 share the name). limit 1 forces a cursor between them, so the conjunction has to survive the seek too.
  test("two filters are ANDed: name = 'alice' AND group_id = 100, keyset by Id ASC, limit 1"): session =>
    val query = Query(
      filters = filters(FilterBy.ExactMatch(RowField.Name, "alice"), FilterBy.ExactMatch(RowField.GroupId, 100L)),
      limit = 1.items
    ).orderBy(RowField.Id.ascending)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(alice1), List(alice2)),
      expectedBackwardData = List(List(alice1))
    )

  // Same field, two value types: ExactMatch identity is (field, encoded value), so Int 3 and Long 3 stay two predicates
  // and render as `id = $1 AND id = $2` bound through int4 and int8. Postgres widens the int4 parameter to compare
  // against the bigint column, so both hold for row 3 and the page is exactly that row. This is also the only case that
  // binds folio's `IntV` against a real column.
  test("same field, Int and Long: id = 3 (int4) AND id = 3 (int8) — two predicates, one row"): session =>
    val query = Query(
      filters = filters(FilterBy.ExactMatch(RowField.Id, 3), FilterBy.ExactMatch(RowField.Id, 3L)),
      limit = 2.items
    ).orderBy(RowField.Id.ascending)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(alice3)),
      expectedBackwardData = Nil
    )

  // === The absentable boundary, filtered ===

  // IntegrationSuite's case B with a filter on top. Among the alice rows, present last_seen 1(10) 3(30) 5(50) sort
  // before the NULLs 2,4 (ADR 0001), so the canonical order is [1,3,5,2,4] => pages [1,3] [5,2] [4]. Backward crosses
  // the boundary in reverse (ADR 0003) => [5,2] [1,3]. Both the absent-anchor rungs (`FALSE` forward, `IS NOT NULL`
  // backward) are ANDed with the filter here.
  test("absentable boundary under a filter: name = 'alice', keyset by LastSeen ASC, Id ASC, limit 2"): session =>
    val query = Query(filters = aliceOnly, limit = 2.items)
      .orderBy(RowField.LastSeen.ascending, RowField.Id.ascending)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(alice1, alice3), List(alice5, alice2), List(alice4)),
      expectedBackwardData = List(List(alice5, alice2), List(alice1, alice3))
    )

  // === Offset strategy ===

  // Description is unregistered, so this takes the offset branch — the filter has to be applied there too, and the
  // offsets have to count filtered rows only. Alice's distinct descriptions d1..d5 give a total order => pages [1,2]
  // [3,4] [5], backward [3,4] [1,2]. If the filter were dropped on this branch, page 2 would start at bob-or-carol
  // rows instead.
  test("offset strategy under a filter: name = 'alice', offset by Description ASC, limit 2"): session =>
    val query = Query(filters = aliceOnly, limit = 2.items).orderBy(RowField.Description.ascending)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(List(alice1, alice2), List(alice3, alice4), List(alice5)),
      expectedBackwardData = List(List(alice3, alice4), List(alice1, alice2))
    )

  // === Edges ===

  // A filter that matches nothing yields one empty page with no cursors in either direction — not an error, and not the
  // unfiltered result.
  test("a filter matching no row yields one empty page with no cursors"): session =>
    val query = Query(filters = filters(FilterBy.ExactMatch(RowField.Name, "zed")), limit = 2.items)
      .orderBy(RowField.Id.ascending)
    checkWalk(
      session,
      dataset,
      query,
      expectedForwardData = List(Nil),
      expectedBackwardData = Nil
    )

  // Filters feed the cursor fingerprint, so a cursor minted under one filter set is stale under another: replaying it
  // fails before any SQL runs instead of silently paginating a different result set.
  test("a cursor minted under one filter set is stale under another"): session =>
    val aliceQuery = Query(filters = aliceOnly, limit = 2.items).orderBy(RowField.Id.ascending)
    val groupQuery = Query(filters = filters(FilterBy.ExactMatch(RowField.GroupId, 200L)), limit = 2.items)
      .orderBy(RowField.Id.ascending)
    for
      _ <- reset(session, dataset)
      firstPage <- page(session, aliceQuery)
      replayed <- firstPage.nextCursor.traverse(cursor => page(session, groupQuery.copy(cursor = Some(cursor))).attempt)
    yield replayed match
      case Some(Left(FolioError.CursorDecodingError.StaleCursor)) => success
      case other => failure(s"expected the alice cursor to be stale under the group_id filter, got $other")

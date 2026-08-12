package folio.it

import cats.effect.IO
import cats.syntax.foldable.*
import skunk.{ AppliedFragment, Session, Void }
import skunk.codec.all.text
import skunk.implicits.*

import folio.*
import folio.skunk.Pagination

/** Planner proof for ADR 0010: folio's `ORDER BY` for a registered *required* field no longer blocks an otherwise
  * compatible default B-tree index.
  *
  * A default B-tree index is stored ascending nulls last, so it satisfies only `ORDER BY id ASC NULLS LAST` forward and
  * `ORDER BY id DESC NULLS FIRST` backward. The planner matches the requested placement exactly — no `NOT NULL` proof
  * relaxes it — so the pre-0010 `ORDER BY "id" DESC NULLS LAST` forced a full scan plus a top-N sort.
  *
  * The fixture is a session-local temporary table dropped at commit, so this suite is independent of the shared `rows`
  * fixture and of suite execution order.
  */
object OrderByPlanSuite extends RowsSuite:

  private val createTable =
    sql"""CREATE TEMP TABLE order_by_plan_rows (
            id bigint PRIMARY KEY
          ) ON COMMIT DROP""".command

  private val insertIds =
    sql"INSERT INTO order_by_plan_rows (id) VALUES (1), (2), (3), (4), (5), (6)".command

  private val analyze = sql"ANALYZE order_by_plan_rows".command

  // Six rows are cheap enough that a sequential scan would win on cost alone, making the plan say nothing about
  // index-order compatibility. `LOCAL` scopes it to the transaction.
  private val disableSeqScan = sql"SET LOCAL enable_seqscan = off".command

  private val planSelect: AppliedFragment = sql"SELECT id FROM order_by_plan_rows".apply(Void)

  // The Rows fixture registers Id as the required unique field (Rows.scala), which is exactly the case under test.
  private val keyset: KeysetField[RowField, Row] = summon

  private val descendingFirstPage: ResolvedQuery[RowField] =
    ResolvedQuery(Set.empty, Vector(RowField.Id.descending), 2.items, Position.Keyset.First, Direction.Forward)

  /** The `EXPLAIN` output lines for an applied fragment, parameters and all. */
  private def explain(session: Session[IO], statement: AppliedFragment): IO[List[String]] =
    val explained = sql"EXPLAIN (COSTS OFF) ".apply(Void) |+| statement
    session.prepare(explained.fragment.query(text)).flatMap(_.stream(explained.argument, 16).compile.toList)

  test("folio's ORDER BY for the required unique field scans the primary key instead of sorting"): session =>
    session.transaction.use: _ =>
      for
        _ <- session.execute(createTable)
        _ <- session.execute(insertIds)
        _ <- session.execute(analyze)
        _ <- session.execute(disableSeqScan)
        folioSql <- IO.fromEither(Pagination.buildSql(descendingFirstPage, planSelect, Some(keyset)))
        folioPlan <- explain(session, folioSql)
        // Positive control: the placement folio used to emit. If this stops sorting, the setup has gone vacuous and the
        // assertions above prove nothing.
        controlPlan <- explain(
          session,
          sql"SELECT id FROM order_by_plan_rows ORDER BY id DESC NULLS LAST LIMIT 3".apply(Void)
        )
      yield List(
        expect(folioPlan.forall(!_.contains("Sort"))),
        expect(folioPlan.exists(_.contains("Scan Backward using order_by_plan_rows_pkey"))),
        expect(controlPlan.exists(_.contains("Sort")))
      ).combineAll

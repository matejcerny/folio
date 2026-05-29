package folio

import scala.collection.immutable.ListSet
import scala.util.Try

import weaver.SimpleIOSuite

object PositionSuite extends SimpleIOSuite:

  private given KeysetField[TestField, Any] = KeysetField(TestField.Id, _ => 0L)

  // Variant where only CreatedAt has an extractor registered (no Name extractor),
  // used to exercise non-id keyset selection and unregistered-secondary fallback.
  private object WithCreatedAt:
    given KeysetField[TestField, Any] =
      KeysetField(TestField.Id, (_: Any) => 0L).withField(TestField.CreatedAt, _ => "")

  pureTest("IdField present + primary sort is id field returns Keyset(Nil)"):
    val query = Query.empty[TestField].copy(sortBys = ListSet(TestField.Id.ascending))
    val position = Position.fromQuery(query)

    expect.same(Position.Keyset(Nil), position)

  pureTest("IdField present + primary sort has no extractor returns Offset.First"):
    val query = Query.empty[TestField].copy(sortBys = ListSet(TestField.CreatedAt.descending))
    val position = Position.fromQuery(query)

    expect.same(Position.Offset.First, position)

  pureTest("IdField present + no sort returns Keyset(Nil)"):
    val position = Position.fromQuery(Query.empty[TestField])

    expect.same(Position.Keyset(Nil), position)

  pureTest("no IdField always returns Offset.First"):
    val position = Position.fromQuery(Query.empty[TestFieldNoId])

    expect.same(Position.Offset.First, position)

  pureTest("no IdField with sort still returns Offset.First"):
    val query = Query.empty[TestFieldNoId].copy(sortBys = ListSet(TestFieldNoId.Timestamp.ascending))
    val position = Position.fromQuery(query)

    expect.same(Position.Offset.First, position)

  pureTest("non-id keyset: primary sort with extractor registered returns Keyset.First"):
    import WithCreatedAt.given
    val query = Query.empty[TestField].copy(sortBys = ListSet(TestField.CreatedAt.descending))
    val position = Position.fromQuery(query)

    expect.same(Position.Keyset.First, position)

  pureTest("non-id keyset: secondary sort without extractor falls back to Offset.First"):
    import WithCreatedAt.given
    val query = Query
      .empty[TestField]
      .copy(
        sortBys = ListSet(TestField.CreatedAt.ascending, TestField.Name.ascending)
      )
    val position = Position.fromQuery(query)

    expect.same(Position.Offset.First, position)

  pureTest("non-id keyset: all sort fields registered (CreatedAt + Id) returns Keyset.First"):
    import WithCreatedAt.given
    val query = Query
      .empty[TestField]
      .copy(
        sortBys = ListSet(TestField.CreatedAt.ascending, TestField.Id.ascending)
      )
    val position = Position.fromQuery(query)

    expect.same(Position.Keyset.First, position)

  pureTest("Offset.apply rejects negative offsets"):
    expect.same(Left("Offset must be non-negative, got -1"), Position.Offset(-1L))

  pureTest("Offset.apply accepts zero"):
    expect.same(Right(Position.Offset.First), Position.Offset(0L))

  pureTest("Offset.unsafe throws IllegalArgumentException on negative input"):
    Try(Position.Offset.unsafe(-5L)).toEither match
      case Left(error: IllegalArgumentException) =>
        expect(clue(error.getMessage).contains("Offset must be non-negative, got -5"))
      case other => failure(s"expected IllegalArgumentException, got $other")

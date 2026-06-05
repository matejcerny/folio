package folio

import scala.collection.immutable.ListSet

enum TestField derives FieldSchema.SnakeCase:
  case Id, Name, CreatedAt, Description, LastSeen

enum TestFieldNoId derives FieldSchema.SnakeCase:
  case Timestamp, Source

enum AliasField:
  case Id, IdAlias, Other

case class Row(id: Long, name: String, createdAt: String, description: String, lastSeen: Option[String])

case class EventRow(timestamp: String, source: String)

given KeysetField[TestField, Row] =
  KeysetField
    .uniqueBy(TestField.Id, (row: Row) => row.id)
    .withField(TestField.Name, _.name)
    .withField(TestField.CreatedAt, _.createdAt)
    .withField(TestField.LastSeen, _.lastSeen)

extension (e: weaver.Expect)
  def sameR[A](expected: A, actual: Either[?, A]): weaver.Expectations =
    e.same(Right(expected), actual)
  def sameL[E](expected: E, actual: Either[E, ?]): weaver.Expectations =
    e.same(Left(expected), actual)

object TestFixtures:

  val emptyQueryWithId: Query[TestField] = Query.empty[TestField]

  val emptyQueryNoId: Query[TestFieldNoId] = Query.empty[TestFieldNoId]

  val queryWithIdSort: Query[TestField] =
    Query.empty[TestField].copy(sortBys = ListSet(TestField.Id.ascending))

  val fullyPopulatedQuery: Query[TestField] =
    Query(
      filters = Set(FilterBy.ExactMatch(TestField.Name, "alice")),
      cursor = None,
      limit = 20.items,
      sortBys = ListSet(TestField.CreatedAt.descending, TestField.Id.ascending)
    )

  val rowExtract: (TestField, Row) => Option[String] = (field, row) =>
    field match
      case TestField.Id          => Some(f"${row.id}%020d")
      case TestField.Name        => Some(row.name)
      case TestField.CreatedAt   => Some(row.createdAt)
      case TestField.Description => Some(row.description)
      case TestField.LastSeen    => row.lastSeen

  val eventExtract: (TestFieldNoId, EventRow) => Option[String] = (field, row) =>
    field match
      case TestFieldNoId.Timestamp => Some(row.timestamp)
      case TestFieldNoId.Source    => Some(row.source)

  // description == createdAt so sort-by-Description orders rows the same as sort-by-CreatedAt;
  // existing realistic-data assertions stay green when offset queries switch to the unregistered Description field.
  // lastSeen is None for ids 5..9 so paginating by LastSeen exercises the Absent boundary.
  val rows: Vector[Row] = Vector(
    Row(0, "alice", "2024-01-05", "2024-01-05", Some("2024-02-01")),
    Row(1, "bob", "2024-01-03", "2024-01-03", Some("2024-02-02")),
    Row(2, "alice", "2024-01-01", "2024-01-01", Some("2024-02-03")),
    Row(3, "charlie", "2024-01-08", "2024-01-08", Some("2024-02-04")),
    Row(4, "bob", "2024-01-02", "2024-01-02", Some("2024-02-05")),
    Row(5, "alice", "2024-01-06", "2024-01-06", None),
    Row(6, "charlie", "2024-01-04", "2024-01-04", None),
    Row(7, "bob", "2024-01-07", "2024-01-07", None),
    Row(8, "alice", "2024-01-09", "2024-01-09", None),
    Row(9, "charlie", "2024-01-10", "2024-01-10", None)
  )

  val events: Vector[EventRow] = Vector(
    EventRow("2024-01-01T00:00:00", "api"),
    EventRow("2024-01-02T00:00:00", "web"),
    EventRow("2024-01-03T00:00:00", "api"),
    EventRow("2024-01-04T00:00:00", "web"),
    EventRow("2024-01-05T00:00:00", "api"),
    EventRow("2024-01-06T00:00:00", "web"),
    EventRow("2024-01-07T00:00:00", "api"),
    EventRow("2024-01-08T00:00:00", "web")
  )

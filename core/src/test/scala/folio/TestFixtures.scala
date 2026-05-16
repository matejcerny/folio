package folio

import folio.FolioError.*

import scala.collection.immutable.ListSet

enum TestField derives FieldSchema.SnakeCase:
  case Id, Name, CreatedAt

enum TestFieldNoId derives FieldSchema.SnakeCase:
  case Timestamp, Source

given CursorCodec with
  def encode(raw: String): String = raw
  def decode(cursor: Cursor): Either[CursorDecodingError, String] = Right(cursor.value)

case class Row(id: Long, name: String, createdAt: String)

case class EventRow(timestamp: String, source: String)

given KeysetField[TestField, Row] = KeysetField(TestField.Id, _.id)

object TestFixtures:

  val emptyQueryWithId: Query[TestField] = Query.empty[TestField]

  val emptyQueryNoId: Query[TestFieldNoId] = Query.empty[TestFieldNoId]

  val queryWithIdSort: Query[TestField] =
    Query.empty[TestField].copy(sortBys = ListSet(TestField.Id.ascending))

  val fullyPopulatedQuery: Query[TestField] =
    Query(
      filters = Set(FilterBy.ExactMatch(TestField.Name, "alice")),
      cursor = None,
      limit = Limit(20),
      sortBys = ListSet(TestField.CreatedAt.descending, TestField.Id.ascending)
    )

  val rowExtract: (TestField, Row) => String = (field, row) =>
    field match
      case TestField.Id        => f"${row.id}%020d"
      case TestField.Name      => row.name
      case TestField.CreatedAt => row.createdAt

  val eventExtract: (TestFieldNoId, EventRow) => String = (field, row) =>
    field match
      case TestFieldNoId.Timestamp => row.timestamp
      case TestFieldNoId.Source    => row.source

  val rows: Vector[Row] = Vector(
    Row(0, "alice", "2024-01-05"),
    Row(1, "bob", "2024-01-03"),
    Row(2, "alice", "2024-01-01"),
    Row(3, "charlie", "2024-01-08"),
    Row(4, "bob", "2024-01-02"),
    Row(5, "alice", "2024-01-06"),
    Row(6, "charlie", "2024-01-04"),
    Row(7, "bob", "2024-01-07"),
    Row(8, "alice", "2024-01-09"),
    Row(9, "charlie", "2024-01-10")
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

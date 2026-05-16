package folio

import scala.collection.immutable.ListSet

import folio.FolioError.*

enum TestField:
  case Id, Name, CreatedAt

given FieldSchema[TestField] with
  def name(field: TestField): String =
    field match
      case TestField.Id        => "id"
      case TestField.Name      => "name"
      case TestField.CreatedAt => "created_at"

  def fromName(name: String): Either[String, TestField] =
    name match
      case "id"         => Right(TestField.Id)
      case "name"       => Right(TestField.Name)
      case "created_at" => Right(TestField.CreatedAt)
      case other        => Left(s"Unknown field: $other")

given IdField[TestField] with
  def idField: TestField = TestField.Id

enum TestFieldNoId:
  case Timestamp, Source

given FieldSchema[TestFieldNoId] with
  def name(field: TestFieldNoId): String =
    field match
      case TestFieldNoId.Timestamp => "timestamp"
      case TestFieldNoId.Source    => "source"

  def fromName(name: String): Either[String, TestFieldNoId] =
    name match
      case "timestamp" => Right(TestFieldNoId.Timestamp)
      case "source"    => Right(TestFieldNoId.Source)
      case other       => Left(s"Unknown field: $other")

given CursorCodec with
  def encode(raw: String): String = raw
  def decode(cursor: Cursor): Either[CursorDecodingError, String] = Right(cursor.value)

object TestFixtures:

  val emptyQueryWithId: Query[TestField] = Query.empty[TestField]

  val emptyQueryNoId: Query[TestFieldNoId] = Query.empty[TestFieldNoId]

  val queryWithIdSort: Query[TestField] =
    Query.empty[TestField].copy(sortBys = ListSet(TestField.Id.ascending))

  val queryWithNonIdSort: Query[TestField] =
    Query.empty[TestField].copy(sortBys = ListSet(TestField.CreatedAt.descending))

  val queryWithFilter: Query[TestField] =
    Query.empty[TestField].copy(filters = Set(FilterBy.ExactMatch(TestField.Name, "alice")))

  val fullyPopulatedQuery: Query[TestField] =
    Query(
      filters = Set(FilterBy.ExactMatch(TestField.Name, "alice")),
      cursor = None,
      limit = Some(Limit(20)),
      sortBys = ListSet(TestField.CreatedAt.descending, TestField.Id.ascending)
    )

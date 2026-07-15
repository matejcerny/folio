package example

import folio.*

import java.time.OffsetDateTime
import scala.collection.immutable.ListSet

case class Message(id: Long, enqueuedAt: OffsetDateTime, lastReadAt: Option[OffsetDateTime])

// Step 1: Define the fields your entity can be sorted/filtered by +
// derive schema which maps enum cases to column name strings used in cursors.
enum MessageField derives FieldSchema.SnakeCase:
  case Id, EnqueuedAt, LastReadAt

// Step 2: Designate the unique field and how to extract it from a row — opts in to keyset pagination.
// Register additional sort fields via `.withField(...)` so keyset works on those columns too;
// `T => Option[V]` marks the field as absentable, so missing values encode as `KeysetValue.Absent`
// and sort after present values regardless of direction.
given KeysetField[MessageField, Message] =
  KeysetField
    .uniqueBy(MessageField.Id, (message: Message) => message.id)
    .withField(MessageField.EnqueuedAt, _.enqueuedAt)
    .withField(MessageField.LastReadAt, _.lastReadAt)

@main def runKeysetExample(): Unit =
  val rows = Seq(
    Message(1, OffsetDateTime.parse("2024-01-01T00:00:00Z"), Some(OffsetDateTime.parse("2024-01-02T00:00:00Z"))),
    Message(2, OffsetDateTime.parse("2024-01-03T00:00:00Z"), None),
    Message(3, OffsetDateTime.parse("2024-01-05T00:00:00Z"), Some(OffsetDateTime.parse("2024-01-06T00:00:00Z")))
  )

  val query = Query(
    filters = Set.empty,
    sortBys = ListSet(MessageField.LastReadAt.descending),
    limit = 2.items
  )

  // With KeysetField in scope and LastReadAt registered as absentable via `.withField` (Option overload),
  // this picks keyset; the cursor anchor is (lastReadAt, id) and a missing lastReadAt encodes as Absent.
  val page = Page.withPagination[FolioEffect.Id, Message, MessageField](query, _ => rows)
  println(s"Next:      ${page.nextCursor.map(_.value)}")
  println(s"Previous:  ${page.previousCursor.map(_.value)}")
  page.nextCursor.foreach: cursor =>
    Cursor.decode(cursor, query) match
      case Right(decoded) => println(s"Next decoded: $decoded")
      case Left(error)    => println(s"Decode error: $error")

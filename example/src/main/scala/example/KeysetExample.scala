package example

import folio.*

import scala.collection.immutable.ListSet

case class Message(id: Long, enqueuedAt: String, lastReadAt: String)

// Step 1: Define the fields your entity can be sorted/filtered by +
// derive schema which maps enum cases to column name strings used in cursors.
enum MessageField derives FieldSchema.SnakeCase:
  case Id, EnqueuedAt, LastReadAt

// Step 2: Designate the id field and how to extract it from a row — opts in to keyset pagination.
// Register additional sort fields via `.withField(...)` so keyset works on those columns too;
// the id field always serves as the deterministic tiebreaker.
given KeysetField[MessageField, Message] =
  KeysetField(MessageField.Id, (message: Message) => message.id)
    .withField(MessageField.EnqueuedAt, _.enqueuedAt)
    .withField(MessageField.LastReadAt, _.lastReadAt)

@main def runKeysetExample(): Unit =
  val rows = Seq(
    Message(1, "2024-01-01", "2024-01-02"),
    Message(2, "2024-01-03", "2024-01-04"),
    Message(3, "2024-01-05", "2024-01-06")
  )

  val query = Query(
    filters = Set.empty,
    sortBys = ListSet(MessageField.EnqueuedAt.ascending),
    limit = 2.items
  )

  // With KeysetField in scope and EnqueuedAt registered via `.withField`,
  // this picks keyset; the cursor anchor is (enqueuedAt, id).
  Page
    .withPagination[cats.Id, Message, MessageField](query, _ => rows)
    .fold(
      error => println(s"Error:     $error"),
      page =>
        println(s"Next:      ${page.nextCursor.map(_.value)}")
        println(s"Previous:  ${page.previousCursor.map(_.value)}")
        page.nextCursor.foreach: cursor =>
          Cursor.decode(cursor, query) match
            case Right(decoded) => println(s"Next decoded: $decoded")
            case Left(error)    => println(s"Decode error: $error")
    )

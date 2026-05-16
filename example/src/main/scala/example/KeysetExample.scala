package example

import folio.*

import scala.collection.immutable.ListSet

// Step 1: Define the fields your entity can be sorted/filtered by.
enum MessageField:
  case Id, EnqueuedAt, LastReadAt

// Step 2: Provide FieldSchema — maps enum cases to column name strings used in cursors.
given FieldSchema[MessageField] = FieldSchema.fromMapping:
  case MessageField.Id         => "msg_id"
  case MessageField.EnqueuedAt => "enqueued_at"
  case MessageField.LastReadAt => "last_read_at"

case class Message(id: Long, enqueuedAt: String, lastReadAt: String)

// Step 3: Designate the id field and how to extract it from a row — opts in to keyset pagination.
given KeysetField[MessageField, Message] = KeysetField(MessageField.Id, _.id)

@main def runKeysetExample(): Unit =
  val limit = Limit(2)
  val query = Query(
    filters = Set(FilterBy.ExactMatch(MessageField.LastReadAt, "2024-01-01")),
    cursor = None,
    limit = Some(limit),
    sortBys = ListSet(MessageField.Id.ascending)
  )

  val initial = query.toCursor()
  println(s"Initial:   ${initial.value}")

  // Build a Page of results using the unified pagination helper.
  // With KeysetField in scope and primary sort = id, this picks keyset.
  val rows = Seq(
    Message(1, "2024-01-01", "2024-01-02"),
    Message(2, "2024-01-03", "2024-01-04"),
    Message(3, "2024-01-05", "2024-01-06")
  )
  Page
    .withPagination[cats.Id, Message, MessageField](query, _ => rows)
    .fold(
      error => println(s"Error:     $error"),
      page =>
        println(s"Next:      ${page.nextCursor.map(_.value)}")
        println(s"Previous:  ${page.previousCursor.map(_.value)}")
    )

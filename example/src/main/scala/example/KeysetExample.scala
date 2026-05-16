package example

import folio.*

import scala.collection.immutable.ListSet

// Step 1: Define the fields your entity can be sorted/filtered by.
enum MessageField:
  case Id, EnqueuedAt, LastReadAt

// Step 2: Provide FieldSchema — maps enum cases to column name strings used in cursors.
given FieldSchema[MessageField] with
  def name(field: MessageField): String = field match
    case MessageField.Id         => "msg_id"
    case MessageField.EnqueuedAt => "enqueued_at"
    case MessageField.LastReadAt => "last_read_at"

  def fromName(name: String): Either[String, MessageField] = name match
    case "msg_id"       => Right(MessageField.Id)
    case "enqueued_at"  => Right(MessageField.EnqueuedAt)
    case "last_read_at" => Right(MessageField.LastReadAt)
    case other          => Left(s"Unknown field: $other")

// Step 3: Identify which field is the id (used for keyset pagination).
given IdField[MessageField] with
  def idField: MessageField = MessageField.Id

case class Message(id: Long, body: String)

// Step 4: Provide RowId so keyset pagination can read the id off each row.
given RowId[Message] = RowId(_.id)

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
  // With IdField + RowId in scope and primary sort = id, this picks keyset.
  val rows = Seq(Message(1, "hi"), Message(2, "hello"), Message(3, "hey"))
  Page
    .withPagination[cats.Id, Message, MessageField](query, _ => rows)
    .fold(
      error => println(s"Error:     $error"),
      page =>
        println(s"Next:      ${page.nextCursor.map(_.value)}")
        println(s"Previous:  ${page.previousCursor.map(_.value)}")
    )

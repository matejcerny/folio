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

@main def runKeysetExample(): Unit =
  val query = Query(
    filters = Set(FilterBy.ExactMatch(MessageField.LastReadAt, "2024-01-01")),
    cursor = None,
    limit = Some(Limit(20)),
    sortBys = ListSet(MessageField.Id.ascending)
  )

  val position = query.cursorPosition
  val encoded = Cursor.encode(position, query)
  val roundtrip = Cursor.decode(encoded, query)

  println(s"Position:  $position")
  println(s"Encoded:   ${encoded.value}")
  println(s"Roundtrip: $roundtrip")

  // Demonstrate stale cursor detection
  val differentQuery = query.copy(limit = Some(Limit(50)))
  val staleResult = Cursor.decode[MessageField](encoded, differentQuery)
  println(s"Stale:     $staleResult")

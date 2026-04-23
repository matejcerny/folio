package example

import folio.*

import scala.collection.immutable.ListSet

// Step 1: Define the fields your entity can be sorted/filtered by — only offset-based pagination is available.
enum EventField:
  case Timestamp, Source

// Step 2: Provide FieldSchema — maps enum cases to column name strings used in cursors.
given FieldSchema[EventField] with
  def name(field: EventField): String = field match
    case EventField.Timestamp => "event_ts"
    case EventField.Source    => "event_source"

  def fromName(name: String): Either[String, EventField] = name match
    case "event_ts"     => Right(EventField.Timestamp)
    case "event_source" => Right(EventField.Source)
    case other          => Left(s"Unknown field: $other")

@main def runOffsetOnlyExample(): Unit =
  val query = Query(
    filters = Set.empty,
    cursor = None,
    limit = Some(Limit(10)),
    sortBys = ListSet(EventField.Timestamp.ascending)
  )

  val position = query.cursorPosition
  val encoded = Cursor.encode(position, query)
  val roundtrip = Cursor.decode(encoded, query)

  println(s"Position:  $position")
  println(s"Encoded:   ${encoded.value}")
  println(s"Roundtrip: $roundtrip")

package example

import folio.*

import scala.collection.immutable.ListSet

// Step 1: Define the fields your entity can be sorted/filtered by
enum EventField:
  case Timestamp, Source

// Step 2: Provide FieldSchema — maps enum cases to column name strings used in cursors.
// Use `derives FieldSchema.SnakeCase` or create a custom mapping
given FieldSchema[EventField] = FieldSchema.fromMapping:
  case EventField.Timestamp => "event_ts"
  case EventField.Source    => "event_source"

case class Event(timestamp: String, source: String)

@main def runOffsetOnlyExample(): Unit =
  val rows = Seq(Event("2024-01-01", "api"), Event("2024-01-02", "api"))

  val query = Query(
    filters = Set.empty,
    sortBys = ListSet(EventField.Timestamp.ascending),
    limit = 1.items
  )

  // Build a Page of results using the pagination helper.
  Page
    .withPagination[cats.Id, Event, EventField](query, _ => rows)
    .fold(
      error => println(s"Error:     $error"),
      page =>
        println(s"Next:      ${page.nextCursor.map(_.value)}")
        println(s"Previous:  ${page.previousCursor.map(_.value)}")
    )

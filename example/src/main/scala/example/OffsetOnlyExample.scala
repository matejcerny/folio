package example

import folio.*

import scala.collection.immutable.ListSet

// Step 1: Define the fields your entity can be sorted/filtered by — only offset-based pagination is available.
enum EventField:
  case Timestamp, Source

// Step 2: Provide FieldSchema — maps enum cases to column name strings used in cursors.
given FieldSchema[EventField] = FieldSchema.fromMapping:
  case EventField.Timestamp => "event_ts"
  case EventField.Source    => "event_source"

case class Event(timestamp: String, source: String)

@main def runOffsetOnlyExample(): Unit =
  val limit = Limit(1)
  val query = Query(
    filters = Set.empty,
    cursor = None,
    limit = Some(limit),
    sortBys = ListSet(EventField.Timestamp.ascending)
  )

  val initial = query.toCursor()
  println(s"Initial:   ${initial.value}")

  // Build a Page of results using the offset helper.
  val rows = Seq(Event("2024-01-01", "api"), Event("2024-01-02", "api"))
  Page
    .withPagination[cats.Id, Event, EventField](query, _ => rows)
    .fold(
      error => println(s"Error:     $error"),
      page =>
        println(s"Next:      ${page.nextCursor.map(_.value)}")
        println(s"Previous:  ${page.previousCursor.map(_.value)}")
    )

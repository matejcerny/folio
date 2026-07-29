# Quick Example

This page walks you through a minimal working example.

## Runnable Example with Scala CLI

Save the following as `folio-example.scala` and run it with `scala-cli run folio-example.scala`:

```scala
//> using dep io.github.matejcerny::folio-core:{{ projectVersion }}

import folio.*

case class Message(id: Long, topic: String, enqueuedAt: String)

// Step 1: Define the fields your entity can be ordered/filtered by +
// derive schema which maps enum cases to column name strings used in cursors.
enum MessageField derives FieldSchema.SnakeCase:
  case Id, Topic, EnqueuedAt

// Step 2: Designate the unique field and how to extract it from a row — opts in to keyset pagination.
// Only *ordering* fields need registering; Topic is filtered on, which needs none.
given KeysetField[MessageField, Message] = KeysetField.uniqueBy(MessageField.Id, _.id)

@main def runKeysetExample(): Unit =
  val rows = Seq(
    Message(1, "alerts", "2024-01-01"),
    Message(2, "digest", "2024-01-03"),
    Message(3, "alerts", "2024-01-05")
  )

  // Filters are typed (the value needs a FieldValueCodec) and conjunctive: every filter is ANDed.
  val query = Query(
    filters = Set(FilterBy.ExactMatch(MessageField.Topic, "alerts")),
    limit = 2.items
  ).orderBy(MessageField.Id.ascending)

  // Build a Page of results using the pagination helper.
  // With KeysetField in scope and primary order = id, this picks keyset.
  // folio-core never filters rows itself: it hands your fetch function a ResolvedQuery carrying the
  // filters, the resolved position, and fetchLimit. A driver module renders them — folio-skunk emits
  // `WHERE usersql."topic" = $1` as a bound parameter — while an in-memory fetch applies them itself.
  val page = Page.withPagination[FolioEffect.Id, Message, MessageField](
    query,
    _ => rows.filter(_.topic == "alerts")
  )
  println(s"Next:      ${page.nextCursor.map(_.value)}")
  println(s"Previous:  ${page.previousCursor.map(_.value)}")
```

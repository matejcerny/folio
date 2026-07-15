# Quick Example

This page walks you through a minimal working example.

## Runnable Example with Scala CLI

Save the following as `folio-example.scala` and run it with `scala-cli run folio-example.scala`:

```scala
//> using dep io.github.matejcerny::folio-core:{{ projectVersion }}

import folio.*

case class Message(id: Long, enqueuedAt: String, lastReadAt: String)

// Step 1: Define the fields your entity can be ordered/filtered by +
// derive schema which maps enum cases to column name strings used in cursors.
enum MessageField derives FieldSchema.SnakeCase:
  case Id, EnqueuedAt, LastReadAt

// Step 2: Designate the unique field and how to extract it from a row — opts in to keyset pagination.
given KeysetField[MessageField, Message] = KeysetField.uniqueBy(MessageField.Id, _.id)

@main def runKeysetExample(): Unit =
  val rows = Seq(
    Message(1, "2024-01-01", "2024-01-02"),
    Message(2, "2024-01-03", "2024-01-04"),
    Message(3, "2024-01-05", "2024-01-06")
  )

  val query = Query(
    filters = Set(FilterBy.ExactMatch(MessageField.LastReadAt, "2024-01-01")),
    limit = Limit(2)
  ).orderBy(MessageField.Id.ascending)

  // Build a Page of results using the pagination helper.
  // With KeysetField in scope and primary order = id, this picks keyset.
  val page = Page.withPagination[FolioEffect.Id, Message, MessageField](query, _ => rows)
  println(s"Next:      ${page.nextCursor.map(_.value)}")
  println(s"Previous:  ${page.previousCursor.map(_.value)}")
```

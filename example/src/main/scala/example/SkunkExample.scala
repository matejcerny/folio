package example

import cats.effect.{ IO, IOApp }
import cats.syntax.foldable.*
import org.typelevel.otel4s.metrics.Meter.Implicits.noop
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import skunk.codec.all.{ int8, text, timestamptz }
import skunk.implicits.*
import skunk.{ Codec, Command, Session, Void }
import folio.*
import folio.skunk.Pagination

import java.time.OffsetDateTime

// Message, MessageField, and KeysetField[MessageField, Message] are defined in KeysetExample.scala.
// Run with: sbt "example/runMain example.SkunkExample"
// Requires Postgres: docker compose up -d postgres
object SkunkExample extends IOApp.Simple:

  private val messageCodec: Codec[Message] =
    (int8 *: text *: timestamptz *: timestamptz.opt).to[Message]

  // The same ordering and page size, walked twice: once over every message, once restricted to one topic.
  private val allMessages: Query[MessageField] =
    Query(limit = 2.items).orderBy(MessageField.EnqueuedAt.ascending)

  // Filters are conjunctive and typed: `ExactMatch(Topic, "alerts")` renders as `WHERE usersql."topic" = $1` bound
  // through Skunk's `text` codec. folio-skunk applies filters before pagination, so pages contain matching rows only.
  private val alertsOnly: Query[MessageField] =
    allMessages.copy(filters = Set(FilterBy.ExactMatch(MessageField.Topic, "alerts")))

  def run: IO[Unit] =
    Session
      .Builder[IO]
      .withHost("localhost")
      .withPort(5432)
      .withUserAndPassword("folio", "folio")
      .withDatabase("folio")
      .single
      .use: session =>
        setup(session) *>
          IO.println("=== All messages (EnqueuedAt ASC, page size 2) ===") *>
          walkForward(session, allMessages, pageNumber = 1) *>
          IO.println("=== Only topic = 'alerts' (same ordering, same page size) ===") *>
          walkForward(session, alertsOnly, pageNumber = 1) *>
          IO.println(
            "The digest messages (ids 2 and 4) never appear in the second walk: the filter reached SQL, " +
              "not just the cursor fingerprint."
          )

  private def walkForward(session: Session[IO], query: Query[MessageField], pageNumber: Int): IO[Unit] =
    Pagination
      .withPagination[IO, Message, MessageField](query, session, messageCodec)(
        sql"SELECT id, topic, enqueued_at, last_read_at FROM messages".apply(Void)
      )
      .flatMap: currentPage =>
        val rendered = currentPage.data.map(message => s"id=${message.id}(${message.topic})").mkString(", ")
        IO.println(s"Page $pageNumber: $rendered") *>
          (currentPage.nextCursor match
            case Some(nextCursor) =>
              walkForward(session, query.copy(cursor = Some(nextCursor)), pageNumber + 1)
            case None => IO.println("End of results."))

  // --- test data setup ---

  // The example owns this table, so drop it rather than CREATE IF NOT EXISTS: a table left behind by an older version
  // of this example would be missing the `topic` column.
  private val dropTable: Command[Void] = sql"DROP TABLE IF EXISTS messages".command

  private val createTable: Command[Void] =
    sql"""CREATE TABLE messages (
            id           bigint      NOT NULL,
            topic        text        NOT NULL,
            enqueued_at  timestamptz NOT NULL,
            last_read_at timestamptz
          )""".command

  private val insertMessage: Command[Message] =
    sql"INSERT INTO messages (id, topic, enqueued_at, last_read_at) VALUES ($messageCodec)".command

  private val dataset: List[Message] = List(
    Message(1, "alerts", ts("2024-01-01"), Some(ts("2024-01-02"))),
    Message(2, "digest", ts("2024-01-03"), None),
    Message(3, "alerts", ts("2024-01-05"), Some(ts("2024-01-06"))),
    Message(4, "digest", ts("2024-01-07"), None),
    Message(5, "alerts", ts("2024-01-09"), Some(ts("2024-01-10")))
  )

  private def ts(day: String): OffsetDateTime = OffsetDateTime.parse(s"${day}T00:00:00Z")

  private def setup(session: Session[IO]): IO[Unit] =
    session.execute(dropTable).void *>
      session.execute(createTable).void *>
      session.prepare(insertMessage).flatMap(prepared => dataset.traverse_(prepared.execute))

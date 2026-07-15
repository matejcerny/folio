package example

import cats.effect.{ IO, IOApp }
import cats.syntax.foldable.*
import org.typelevel.otel4s.metrics.Meter.Implicits.noop
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import skunk.codec.all.{ int8, timestamptz }
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
    (int8 *: timestamptz *: timestamptz.opt).to[Message]

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
          IO.println("=== Paginating messages (EnqueuedAt ASC, page size 2) ===") *>
          walkForward(session, Query(limit = 2.items).orderBy(MessageField.EnqueuedAt.ascending), pageNumber = 1)

  private def walkForward(session: Session[IO], query: Query[MessageField], pageNumber: Int): IO[Unit] =
    Pagination
      .withPagination[IO, Message, MessageField](query, session, messageCodec)(
        sql"SELECT id, enqueued_at, last_read_at FROM messages".apply(Void)
      )
      .flatMap: currentPage =>
        IO.println(s"Page $pageNumber: ${currentPage.data.map(message => s"id=${message.id}").mkString(", ")}") *>
          (currentPage.nextCursor match
            case Some(nextCursor) =>
              walkForward(session, query.copy(cursor = Some(nextCursor)), pageNumber + 1)
            case None => IO.println("End of results."))

  // --- test data setup ---

  private val createTable: Command[Void] =
    sql"""CREATE TABLE IF NOT EXISTS messages (
            id           bigint      NOT NULL,
            enqueued_at  timestamptz NOT NULL,
            last_read_at timestamptz
          )""".command

  private val insertMessage: Command[Message] =
    sql"INSERT INTO messages (id, enqueued_at, last_read_at) VALUES ($messageCodec)".command

  private val dataset: List[Message] = List(
    Message(1, OffsetDateTime.parse("2024-01-01T00:00:00Z"), Some(OffsetDateTime.parse("2024-01-02T00:00:00Z"))),
    Message(2, OffsetDateTime.parse("2024-01-03T00:00:00Z"), None),
    Message(3, OffsetDateTime.parse("2024-01-05T00:00:00Z"), Some(OffsetDateTime.parse("2024-01-06T00:00:00Z"))),
    Message(4, OffsetDateTime.parse("2024-01-07T00:00:00Z"), None),
    Message(5, OffsetDateTime.parse("2024-01-09T00:00:00Z"), Some(OffsetDateTime.parse("2024-01-10T00:00:00Z")))
  )

  private def setup(session: Session[IO]): IO[Unit] =
    session.execute(createTable).void *>
      session.execute(sql"TRUNCATE TABLE messages".command).void *>
      session.prepare(insertMessage).flatMap(prepared => dataset.traverse_(prepared.execute))

package folio.cats

import cats.effect.IO
import weaver.SimpleIOSuite

import folio.*

object FolioEffectInstancesSuite extends SimpleIOSuite:

  private enum EventField derives FieldSchema.SnakeCase:
    case Timestamp

  private case class Event(timestamp: String)

  test("derives FolioEffect for Cats Effect IO"):
    import folio.cats.given

    val effect = FolioEffect[IO]
    val error = FolioError.CursorDecodingError.StaleCursor

    for
      mapped <- effect.map(IO.pure(41))(_ + 1)
      raised <- effect.raiseError[Int](error).attempt
    yield expect.all(
      mapped == 42,
      raised == Left(error)
    )

  test("supports direct folio-core pagination in IO"):
    import folio.cats.given

    val rows = Seq(Event("2026-07-15"), Event("2026-07-16"))

    Page
      .withPagination[IO, Event, EventField](Query.empty[EventField], _ => IO.pure(rows))
      .map(page => expect.same(rows, page.data))

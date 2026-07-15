package folio

import cats.syntax.foldable.*
import weaver.FunSuite

object PageSuite extends FunSuite:

  private val previous = Cursor("previous")
  private val next = Cursor("next")

  test("Page.map maps the data and preserves the limit and both cursors"):
    val page = Page(25.items, Some(previous), Some(next), Seq(1, 2, 3))
    val mapped = page.map(_.toString)
    List(
      expect.same(Seq("1", "2", "3"), mapped.data),
      expect.same(25.items, mapped.limit),
      expect.same(Some(previous), mapped.previousCursor),
      expect.same(Some(next), mapped.nextCursor)
    ).combineAll

  test("Page.map on an empty page keeps it empty and cursor-less"):
    val mapped = Page.empty[Int](10.items).map(_.toString)
    List(
      expect.same(Seq.empty[String], mapped.data),
      expect.same(None, mapped.previousCursor),
      expect.same(None, mapped.nextCursor)
    ).combineAll

  test("ResolvedQuery.fetchLimit is the page limit plus one"):
    val resolved =
      ResolvedQuery[Int](Set.empty, Vector.empty, 25.items, Position.Keyset(Nil), Direction.Forward)
    List(
      expect.same(25, resolved.limit.value),
      expect.same(26, resolved.fetchLimit.value)
    ).combineAll

package folio.skunk

import java.time.OffsetDateTime

import cats.syntax.foldable.*
import skunk.AppliedFragment

import folio.*
import weaver.SimpleIOSuite

/** Pure tests for [[Pagination.filterPredicate]]: the SQL text, the bound-parameter types, and the bound values of the
  * filter conjunction on its own, before it is composed into a `WHERE` clause.
  */
object FilterPredicateSuite extends SimpleIOSuite:

  enum MessageField derives FieldSchema.SnakeCase:
    case Id, Name, EnqueuedAt

  // Hand-written schema exercising identifier quoting: a reserved word and a name with an embedded double quote.
  enum QuoteField:
    case Reserved, Weird

  given FieldSchema[QuoteField] = FieldSchema.fromMapping:
    case QuoteField.Reserved => "order"
    case QuoteField.Weird    => "a\"b"

  private val instant: OffsetDateTime = OffsetDateTime.parse("2024-01-01T00:00:00Z")

  private def sqlOf[FIELD: FieldSchema](filters: Set[FilterBy[FIELD]]): Option[String] =
    Pagination.filterPredicate(filters).map(_.fragment.sql)

  private def typesOf[FIELD: FieldSchema](filters: Set[FilterBy[FIELD]]): List[String] =
    Pagination.filterPredicate(filters).toList.flatMap(_.fragment.encoder.types.map(_.name))

  private def valuesOf[FIELD: FieldSchema](filters: Set[FilterBy[FIELD]]): List[Option[String]] =
    Pagination.filterPredicate(filters).toList.flatMap(bindings)

  private def bindings(applied: AppliedFragment): List[Option[String]] =
    applied.fragment.encoder.encode(applied.argument).map(_.map(_.value))

  pureTest("no filters render no predicate at all"):
    expect.same(None, sqlOf(Set.empty[FilterBy[MessageField]]))

  pureTest("a single exact match renders `column = $n` with the value bound, never interpolated"):
    val filters = Set[FilterBy[MessageField]](FilterBy.ExactMatch(MessageField.Name, "alice"))
    List(
      expect.same(Some("""usersql."name" = $1"""), sqlOf(filters)),
      expect.same(List("text"), typesOf(filters)),
      expect.same(List(Some("alice")), valuesOf(filters))
    ).combineAll

  pureTest("each FieldValue variant binds through its fixed Skunk codec, with no cast on the column"):
    def typesFor(filter: FilterBy[MessageField]): List[String] = typesOf(Set(filter))
    List(
      expect.same(List("int4"), typesFor(FilterBy.ExactMatch(MessageField.Id, 7))),
      expect.same(List("int8"), typesFor(FilterBy.ExactMatch(MessageField.Id, 7L))),
      expect.same(List("text"), typesFor(FilterBy.ExactMatch(MessageField.Name, "alice"))),
      expect.same(List("timestamptz"), typesFor(FilterBy.ExactMatch(MessageField.EnqueuedAt, instant))),
      expect.same(
        Some("""usersql."enqueued_at" = $1"""),
        sqlOf(Set(FilterBy.ExactMatch(MessageField.EnqueuedAt, instant)))
      )
    ).combineAll

  pureTest("multiple filters are ANDed in canonical field-name order, whatever the Set order was"):
    val name = FilterBy.ExactMatch(MessageField.Name, "alice")
    val enqueuedAt = FilterBy.ExactMatch(MessageField.EnqueuedAt, instant)
    val id = FilterBy.ExactMatch(MessageField.Id, 7L)
    val oneOrder = Set[FilterBy[MessageField]](name, id, enqueuedAt)
    val anotherOrder = Set[FilterBy[MessageField]](enqueuedAt, name, id)
    List(
      expect.same(
        Some("""usersql."enqueued_at" = $1 AND usersql."id" = $2 AND usersql."name" = $3"""),
        sqlOf(oneOrder)
      ),
      expect.same(List("timestamptz", "int8", "text"), typesOf(oneOrder)),
      expect.same(sqlOf(oneOrder), sqlOf(anotherOrder)),
      expect.same(typesOf(oneOrder), typesOf(anotherOrder)),
      expect.same(valuesOf(oneOrder), valuesOf(anotherOrder))
    ).combineAll

  pureTest("two filters on the same field stay two ANDed predicates, ordered by their encoded value"):
    // Encoded values are length-delimited, so the shorter payload sorts first — see folio.CanonicalFilters.
    val filters = Set[FilterBy[MessageField]](
      FilterBy.ExactMatch(MessageField.Name, "alice"),
      FilterBy.ExactMatch(MessageField.Name, "bob")
    )
    List(
      expect.same(Some("""usersql."name" = $1 AND usersql."name" = $2"""), sqlOf(filters)),
      expect.same(List("text", "text"), typesOf(filters)),
      expect.same(List(Some("bob"), Some("alice")), valuesOf(filters))
    ).combineAll

  pureTest("same field, different value types: distinct predicates, each bound at its own type"):
    val filters = Set[FilterBy[MessageField]](
      FilterBy.ExactMatch(MessageField.Id, 7),
      FilterBy.ExactMatch(MessageField.Id, 7L)
    )
    List(
      expect.same(Some("""usersql."id" = $1 AND usersql."id" = $2"""), sqlOf(filters)),
      expect.same(List("int4", "int8"), typesOf(filters)),
      expect.same(List(Some("7"), Some("7")), valuesOf(filters))
    ).combineAll

  pureTest("filter identifiers are qualified and double-quoted; embedded quotes doubled"):
    val filters = Set[FilterBy[QuoteField]](
      FilterBy.ExactMatch(QuoteField.Reserved, 1),
      FilterBy.ExactMatch(QuoteField.Weird, "x")
    )
    expect.same(Some("""usersql."a""b" = $1 AND usersql."order" = $2"""), sqlOf(filters))

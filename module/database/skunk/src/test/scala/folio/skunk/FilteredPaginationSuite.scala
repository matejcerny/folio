package folio.skunk

import java.time.OffsetDateTime

import cats.syntax.foldable.*
import skunk.{ AppliedFragment, Void }
import skunk.codec.all.int8
import skunk.implicits.*

import folio.*
import weaver.SimpleIOSuite

/** Pure SQL-shape tests for filtered [[Pagination.buildSql]] queries: how the filter conjunction composes with the
  * keyset and offset branches. [[FilterPredicateSuite]] pins the conjunction on its own; [[PaginationSuite]] pins the
  * unfiltered templates this suite must leave alone.
  */
object FilteredPaginationSuite extends SimpleIOSuite:

  enum MessageField derives FieldSchema.SnakeCase:
    case Id, Name, EnqueuedAt, LastReadAt

  final case class Message(id: Long, name: String, enqueuedAt: OffsetDateTime, lastReadAt: Option[OffsetDateTime])

  given KeysetField[MessageField, Message] =
    KeysetField
      .uniqueBy(MessageField.Id, (message: Message) => message.id)
      .withField(MessageField.EnqueuedAt, (message: Message) => message.enqueuedAt)
      .withField(MessageField.LastReadAt, (message: Message) => message.lastReadAt)

  // No KeysetField in scope for this enum, so it only ever takes the offset branch: a reserved word and a name with an
  // embedded double quote, to check that filter columns are quoted like every other identifier.
  enum QuoteField:
    case Reserved, Weird

  given FieldSchema[QuoteField] = FieldSchema.fromMapping:
    case QuoteField.Reserved => "order"
    case QuoteField.Weird    => "a\"b"

  private val messageKeyset: KeysetField[MessageField, Message] = summon

  private val select: AppliedFragment = sql"SELECT * FROM messages".apply(Void)
  private val instant: OffsetDateTime = OffsetDateTime.parse("2024-01-01T00:00:00Z")

  /** `enqueued_at DESC` ordering, so the id tiebreaker is appended and the seek has two rungs. */
  private val anchor: List[AnchorValue] = List(FieldValue.TimestampV(instant).present, FieldValue.LongV(5).present)

  private val nameFilter = Set[FilterBy[MessageField]](FilterBy.ExactMatch(MessageField.Name, "alice"))

  private def resolved(
      filters: Set[FilterBy[MessageField]],
      position: Position,
      direction: Direction = Direction.Forward
  ): ResolvedQuery[MessageField] =
    ResolvedQuery(filters, Vector(MessageField.EnqueuedAt.descending), 10.items, position, direction)

  private def sqlOf(query: ResolvedQuery[MessageField]): String =
    Pagination.buildSql(query, select, Some(messageKeyset)) match
      case Right(applied) => applied.fragment.sql
      case Left(error)    => s"buildSql failed: $error"

  private def typesOf(query: ResolvedQuery[MessageField]): List[String] =
    Pagination.buildSql(query, select, Some(messageKeyset)) match
      case Right(applied) => applied.fragment.encoder.types.map(_.name)
      case Left(error)    => List(s"buildSql failed: $error")

  private def valuesOf(query: ResolvedQuery[MessageField]): List[Option[String]] =
    Pagination.buildSql(query, select, Some(messageKeyset)) match
      case Right(applied) => bindings(applied)
      case Left(error)    => List(Some(s"buildSql failed: $error"))

  private def bindings(applied: AppliedFragment): List[Option[String]] =
    applied.fragment.encoder.encode(applied.argument).map(_.map(_.value))

  // === Filtered keyset: first page, forward, backward ===

  pureTest("filtered first page: the filters are the whole WHERE, and the appended id tiebreaker survives"):
    val query = resolved(nameFilter, Position.Keyset.First)
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE usersql."name" = $1 ORDER BY usersql."enqueued_at" DESC, usersql."id" ASC LIMIT $2""",
        sqlOf(query)
      ),
      expect.same(List("text", "int4"), typesOf(query))
    ).combineAll

  pureTest("filtered anchored forward keyset: the seek is parenthesized under the filter, bare without it"):
    // AND binds tighter than OR: without the parentheses the later rungs of the seek would escape the filter and the
    // filtered page would leak rows the filter excludes.
    val filtered = resolved(nameFilter, Position.Keyset(anchor))
    val unfiltered = resolved(Set.empty, Position.Keyset(anchor))
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE usersql."name" = $1 AND ((usersql."enqueued_at" < $2) OR (usersql."enqueued_at" IS NOT DISTINCT FROM $3 AND (usersql."id" > $4))) ORDER BY usersql."enqueued_at" DESC, usersql."id" ASC LIMIT $5""",
        sqlOf(filtered)
      ),
      expect.same(List("text", "timestamptz", "timestamptz", "int8", "int4"), typesOf(filtered)),
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."enqueued_at" < $1) OR (usersql."enqueued_at" IS NOT DISTINCT FROM $2 AND (usersql."id" > $3)) ORDER BY usersql."enqueued_at" DESC, usersql."id" ASC LIMIT $4""",
        sqlOf(unfiltered)
      ),
      expect.same(List("timestamptz", "timestamptz", "int8", "int4"), typesOf(unfiltered))
    ).combineAll

  pureTest("filtered anchored backward keyset: the seek reverses, the filter keeps its place and its parameters"):
    val query = resolved(nameFilter, Position.Keyset(anchor), Direction.Backward)
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE usersql."name" = $1 AND ((usersql."enqueued_at" > $2) OR (usersql."enqueued_at" IS NOT DISTINCT FROM $3 AND (usersql."id" < $4))) ORDER BY usersql."enqueued_at" ASC, usersql."id" DESC LIMIT $5""",
        sqlOf(query)
      ),
      expect.same(List("text", "timestamptz", "timestamptz", "int8", "int4"), typesOf(query))
    ).combineAll

  pureTest("filtered absentable seek: an Absent anchor binds no parameter, the filter still binds first"):
    val query = ResolvedQuery(
      nameFilter,
      Vector(MessageField.LastReadAt.ascending),
      10.items,
      Position.Keyset(List(AnchorValue.Absent, FieldValue.LongV(5).present)),
      Direction.Forward
    )
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE usersql."name" = $1 AND ((FALSE) OR (usersql."last_read_at" IS NOT DISTINCT FROM NULL AND (usersql."id" > $2))) ORDER BY usersql."last_read_at" ASC NULLS LAST, usersql."id" ASC LIMIT $3""",
        sqlOf(query)
      ),
      expect.same(List("text", "int8", "int4"), typesOf(query))
    ).combineAll

  // === Filtered offset ===

  pureTest("filtered offset: WHERE precedes ORDER BY/OFFSET, so the filter binds before the offset"):
    val query = resolved(nameFilter, Position.Offset.unsafe(40))
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE usersql."name" = $1 ORDER BY usersql."enqueued_at" DESC, usersql."id" ASC OFFSET $2 LIMIT $3""",
        sqlOf(query)
      ),
      expect.same(List("text", "int8", "int4"), typesOf(query))
    ).combineAll

  // === Filter matrix inside a composed query ===

  pureTest("every supported codec binds in canonical order: field name, then value type"):
    val query = resolved(
      Set[FilterBy[MessageField]](
        FilterBy.ExactMatch(MessageField.Name, "alice"),
        FilterBy.ExactMatch(MessageField.Id, 7),
        FilterBy.ExactMatch(MessageField.EnqueuedAt, instant),
        FilterBy.ExactMatch(MessageField.Id, 8L)
      ),
      Position.Keyset.First
    )
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE usersql."enqueued_at" = $1 AND usersql."id" = $2 AND usersql."id" = $3 AND usersql."name" = $4 ORDER BY usersql."enqueued_at" DESC, usersql."id" ASC LIMIT $5""",
        sqlOf(query)
      ),
      expect.same(List("timestamptz", "int4", "int8", "text", "int4"), typesOf(query)),
      expect.same(List(Some("7"), Some("8")), valuesOf(query).slice(1, 3))
    ).combineAll

  pureTest("two filters on the same field stay two predicates in the composed query"):
    val query = resolved(
      Set[FilterBy[MessageField]](
        FilterBy.ExactMatch(MessageField.Name, "alice"),
        FilterBy.ExactMatch(MessageField.Name, "bob")
      ),
      Position.Offset.unsafe(0)
    )
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE usersql."name" = $1 AND usersql."name" = $2 ORDER BY usersql."enqueued_at" DESC, usersql."id" ASC OFFSET $3 LIMIT $4""",
        sqlOf(query)
      ),
      expect.same(List("text", "text", "int8", "int4"), typesOf(query)),
      // Shorter encoded value first — see folio.CanonicalFilters.
      expect.same(List(Some("bob"), Some("alice"), Some("0"), Some("11")), valuesOf(query))
    ).combineAll

  pureTest("Set insertion order changes neither the composed SQL nor the bound parameters"):
    val name = FilterBy.ExactMatch(MessageField.Name, "alice")
    val enqueuedAt = FilterBy.ExactMatch(MessageField.EnqueuedAt, instant)
    val id = FilterBy.ExactMatch(MessageField.Id, 7L)
    val oneOrder = resolved(Set[FilterBy[MessageField]](name, id, enqueuedAt), Position.Keyset(anchor))
    val anotherOrder = resolved(Set[FilterBy[MessageField]](enqueuedAt, name, id), Position.Keyset(anchor))
    List(
      expect.same(sqlOf(oneOrder), sqlOf(anotherOrder)),
      expect.same(typesOf(oneOrder), typesOf(anotherOrder)),
      expect.same(valuesOf(oneOrder), valuesOf(anotherOrder)),
      expect.same(List("timestamptz", "int8", "text", "timestamptz", "timestamptz", "int8", "int4"), typesOf(oneOrder))
    ).combineAll

  // === Composition with a parameterized user SELECT and with quoted identifiers ===

  pureTest("parameterized inner SELECT: parameters run inner SELECT, filters, keyset, then fetch limit"):
    val paramSelect = sql"SELECT * FROM messages WHERE tenant = $int8".apply(99L)
    val applied = Pagination.buildSql(
      resolved(nameFilter, Position.Keyset(anchor)),
      paramSelect,
      Some(messageKeyset)
    )
    List(
      expect.same(
        Right(
          """SELECT * FROM (SELECT * FROM messages WHERE tenant = $1) AS usersql WHERE usersql."name" = $2 AND ((usersql."enqueued_at" < $3) OR (usersql."enqueued_at" IS NOT DISTINCT FROM $4 AND (usersql."id" > $5))) ORDER BY usersql."enqueued_at" DESC, usersql."id" ASC LIMIT $6"""
        ),
        applied.map(_.fragment.sql)
      ),
      expect.same(
        Right(List("int8", "text", "timestamptz", "timestamptz", "int8", "int4")),
        applied.map(_.fragment.encoder.types.map(_.name).toList)
      ),
      expect.same(Right(List(Some("99"), Some("alice"))), applied.map(bindings(_).take(2)))
    ).combineAll

  pureTest("filter identifiers are quoted like every other column: reserved words and embedded quotes"):
    val query = ResolvedQuery[QuoteField](
      Set[FilterBy[QuoteField]](
        FilterBy.ExactMatch(QuoteField.Reserved, 1),
        FilterBy.ExactMatch(QuoteField.Weird, "x")
      ),
      Vector(QuoteField.Reserved.ascending),
      10.items,
      Position.Offset.unsafe(0),
      Direction.Forward
    )
    expect.same(
      Right(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE usersql."a""b" = $1 AND usersql."order" = $2 ORDER BY usersql."order" ASC OFFSET $3 LIMIT $4"""
      ),
      Pagination.buildSql(query, select, None).map(_.fragment.sql)
    )

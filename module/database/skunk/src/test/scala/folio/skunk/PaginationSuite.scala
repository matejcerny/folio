/*
 * Copyright (c) 2026 Matej Cerny
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package folio.skunk

import java.time.OffsetDateTime

import cats.syntax.foldable.*
import skunk.AppliedFragment
import skunk.Void
import skunk.codec.all.int8
import skunk.implicits.*

import folio.*
import weaver.SimpleIOSuite

/** Pure SQL-shape tests for [[Pagination.buildSql]]. No effects, no database — they pin the template text and the
  * bound-argument types for the direction/Absent-aware keyset algorithm and the offset branch.
  */
object PaginationSuite extends SimpleIOSuite:

  enum MessageField derives FieldSchema.SnakeCase:
    case Id, EnqueuedAt, LastReadAt

  final case class Message(id: Long, enqueuedAt: OffsetDateTime, lastReadAt: Option[OffsetDateTime])

  // EnqueuedAt is registered as required (non-absentable); LastReadAt is absentable (T => Option[V]).
  given KeysetField[MessageField, Message] =
    KeysetField
      .uniqueBy(MessageField.Id, (message: Message) => message.id)
      .withField(MessageField.EnqueuedAt, (message: Message) => message.enqueuedAt)
      .withField(MessageField.LastReadAt, (message: Message) => message.lastReadAt)

  // Distinct enum + hand-written schema to exercise identifier quoting/escaping: an ordinary name, a reserved word,
  // and a name with an embedded double quote. No KeysetField in scope, so these only ever take the offset branch.
  enum QuoteField:
    case Plain, Reserved, Weird

  given FieldSchema[QuoteField] = FieldSchema.fromMapping:
    case QuoteField.Plain    => "plain_col"
    case QuoteField.Reserved => "order"
    case QuoteField.Weird    => "a\"b"

  private val select: AppliedFragment = sql"SELECT * FROM messages".apply(Void)
  private val instant: OffsetDateTime = OffsetDateTime.parse("2024-01-01T00:00:00Z")

  private def resolved(
      ordering: Vector[OrderBy[MessageField]],
      position: Position,
      direction: Direction = Direction.Forward,
      limit: Limit = 10.items
  ): ResolvedQuery[MessageField] =
    ResolvedQuery(Set.empty, ordering, limit, position, direction)

  private val messageKeyset: KeysetField[MessageField, Message] = summon

  private def sqlOf(query: ResolvedQuery[MessageField]): String =
    Pagination.buildSql(query, select, Some(messageKeyset)) match
      case Right(applied) => applied.fragment.sql
      case Left(error)    => s"buildSql failed: $error"

  private def typesOf(query: ResolvedQuery[MessageField]): List[String] =
    Pagination.buildSql(query, select, Some(messageKeyset)) match
      case Right(applied) => applied.fragment.encoder.types.map(_.name)
      case Left(error)    => List(s"buildSql failed: $error")

  // === Single non-absentable cursor field (order by id): all four strict-step rows ===

  pureTest("ASC forward: strict `>`, ORDER BY ASC NULLS LAST"):
    val query = resolved(Vector(MessageField.Id.ascending), Position.Keyset(List(KeysetValue.LongV(5))))
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."id" > $1) ORDER BY usersql."id" ASC NULLS LAST LIMIT $2""",
        sqlOf(query)
      ),
      expect.same(List("int8", "int4"), typesOf(query))
    ).combineAll

  pureTest("DESC forward: strict `<`, ORDER BY DESC NULLS LAST"):
    val query = resolved(Vector(MessageField.Id.descending), Position.Keyset(List(KeysetValue.LongV(5))))
    expect.same(
      """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."id" < $1) ORDER BY usersql."id" DESC NULLS LAST LIMIT $2""",
      sqlOf(query)
    )

  pureTest("ASC backward: strict `<`, ORDER BY DESC NULLS FIRST"):
    val query =
      resolved(Vector(MessageField.Id.ascending), Position.Keyset(List(KeysetValue.LongV(5))), Direction.Backward)
    expect.same(
      """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."id" < $1) ORDER BY usersql."id" DESC NULLS FIRST LIMIT $2""",
      sqlOf(query)
    )

  pureTest("DESC backward: strict `>`, ORDER BY ASC NULLS FIRST"):
    val query =
      resolved(Vector(MessageField.Id.descending), Position.Keyset(List(KeysetValue.LongV(5))), Direction.Backward)
    expect.same(
      """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."id" > $1) ORDER BY usersql."id" ASC NULLS FIRST LIMIT $2""",
      sqlOf(query)
    )

  // === Absentable cursor field (order by last_read_at), present anchor: all four strict-step rows ===

  pureTest("absentable ASC forward present: `> v OR IS NULL`, ORDER BY ASC NULLS LAST, id ASC"):
    val query = resolved(
      Vector(MessageField.LastReadAt.ascending),
      Position.Keyset(List(KeysetValue.StringV("t"), KeysetValue.LongV(5)))
    )
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."last_read_at" > $1 OR usersql."last_read_at" IS NULL) OR (usersql."last_read_at" IS NOT DISTINCT FROM $2 AND (usersql."id" > $3)) ORDER BY usersql."last_read_at" ASC NULLS LAST, usersql."id" ASC LIMIT $4""",
        sqlOf(query)
      ),
      expect.same(List("text", "text", "int8", "int4"), typesOf(query))
    ).combineAll

  pureTest("absentable DESC forward present: `< v OR IS NULL`, ORDER BY DESC NULLS LAST, id ASC"):
    val query = resolved(
      Vector(MessageField.LastReadAt.descending),
      Position.Keyset(List(KeysetValue.StringV("t"), KeysetValue.LongV(5)))
    )
    expect.same(
      """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."last_read_at" < $1 OR usersql."last_read_at" IS NULL) OR (usersql."last_read_at" IS NOT DISTINCT FROM $2 AND (usersql."id" > $3)) ORDER BY usersql."last_read_at" DESC NULLS LAST, usersql."id" ASC LIMIT $4""",
      sqlOf(query)
    )

  pureTest("absentable ASC backward present: `< v`, ORDER BY DESC NULLS FIRST, id DESC"):
    val query = resolved(
      Vector(MessageField.LastReadAt.ascending),
      Position.Keyset(List(KeysetValue.StringV("t"), KeysetValue.LongV(5))),
      Direction.Backward
    )
    expect.same(
      """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."last_read_at" < $1) OR (usersql."last_read_at" IS NOT DISTINCT FROM $2 AND (usersql."id" < $3)) ORDER BY usersql."last_read_at" DESC NULLS FIRST, usersql."id" DESC LIMIT $4""",
      sqlOf(query)
    )

  pureTest("absentable DESC backward present: `> v`, ORDER BY ASC NULLS FIRST, id DESC"):
    val query = resolved(
      Vector(MessageField.LastReadAt.descending),
      Position.Keyset(List(KeysetValue.StringV("t"), KeysetValue.LongV(5))),
      Direction.Backward
    )
    expect.same(
      """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."last_read_at" > $1) OR (usersql."last_read_at" IS NOT DISTINCT FROM $2 AND (usersql."id" < $3)) ORDER BY usersql."last_read_at" ASC NULLS FIRST, usersql."id" DESC LIMIT $4""",
      sqlOf(query)
    )

  // === Absentable cursor field, Absent anchor: FALSE forward / IS NOT NULL backward; eq rung becomes IS NOT DISTINCT FROM NULL ===

  pureTest("absentable ASC forward absent: strict collapses to FALSE, no bound param for the absent slot"):
    val query = resolved(
      Vector(MessageField.LastReadAt.ascending),
      Position.Keyset(List(KeysetValue.Absent, KeysetValue.LongV(5)))
    )
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (FALSE) OR (usersql."last_read_at" IS NOT DISTINCT FROM NULL AND (usersql."id" > $1)) ORDER BY usersql."last_read_at" ASC NULLS LAST, usersql."id" ASC LIMIT $2""",
        sqlOf(query)
      ),
      expect.same(List("int8", "int4"), typesOf(query))
    ).combineAll

  pureTest("absentable DESC forward absent: strict collapses to FALSE, ORDER BY DESC NULLS LAST"):
    val query = resolved(
      Vector(MessageField.LastReadAt.descending),
      Position.Keyset(List(KeysetValue.Absent, KeysetValue.LongV(5)))
    )
    expect.same(
      """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (FALSE) OR (usersql."last_read_at" IS NOT DISTINCT FROM NULL AND (usersql."id" > $1)) ORDER BY usersql."last_read_at" DESC NULLS LAST, usersql."id" ASC LIMIT $2""",
      sqlOf(query)
    )

  pureTest("absentable ASC backward absent: strict becomes IS NOT NULL"):
    val query = resolved(
      Vector(MessageField.LastReadAt.ascending),
      Position.Keyset(List(KeysetValue.Absent, KeysetValue.LongV(5))),
      Direction.Backward
    )
    expect.same(
      """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."last_read_at" IS NOT NULL) OR (usersql."last_read_at" IS NOT DISTINCT FROM NULL AND (usersql."id" < $1)) ORDER BY usersql."last_read_at" DESC NULLS FIRST, usersql."id" DESC LIMIT $2""",
      sqlOf(query)
    )

  pureTest("absentable DESC backward absent: strict becomes IS NOT NULL, ORDER BY ASC NULLS FIRST"):
    val query = resolved(
      Vector(MessageField.LastReadAt.descending),
      Position.Keyset(List(KeysetValue.Absent, KeysetValue.LongV(5))),
      Direction.Backward
    )
    expect.same(
      """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."last_read_at" IS NOT NULL) OR (usersql."last_read_at" IS NOT DISTINCT FROM NULL AND (usersql."id" < $1)) ORDER BY usersql."last_read_at" ASC NULLS FIRST, usersql."id" DESC LIMIT $2""",
      sqlOf(query)
    )

  // === Equality-rung chaining across multiple order fields ===

  pureTest("multi order field: equality rungs chain, id appended last"):
    val query = resolved(
      Vector(MessageField.EnqueuedAt.descending, MessageField.LastReadAt.ascending),
      Position.Keyset(List(KeysetValue.TimestampV(instant), KeysetValue.StringV("x"), KeysetValue.LongV(5)))
    )
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."enqueued_at" < $1) OR (usersql."enqueued_at" IS NOT DISTINCT FROM $2 AND (usersql."last_read_at" > $3 OR usersql."last_read_at" IS NULL)) OR (usersql."enqueued_at" IS NOT DISTINCT FROM $4 AND usersql."last_read_at" IS NOT DISTINCT FROM $5 AND (usersql."id" > $6)) ORDER BY usersql."enqueued_at" DESC NULLS LAST, usersql."last_read_at" ASC NULLS LAST, usersql."id" ASC LIMIT $7""",
        sqlOf(query)
      ),
      expect.same(List("timestamptz", "timestamptz", "text", "timestamptz", "text", "int8", "int4"), typesOf(query))
    ).combineAll

  // === Appended id appears in ORDER BY when id is not an order field ===

  pureTest("appended id tiebreaker present in ORDER BY (id not an order field)"):
    val query = resolved(
      Vector(MessageField.EnqueuedAt.descending),
      Position.Keyset(List(KeysetValue.TimestampV(instant), KeysetValue.LongV(5)))
    )
    expect.same(
      """SELECT * FROM (SELECT * FROM messages) AS usersql WHERE (usersql."enqueued_at" < $1) OR (usersql."enqueued_at" IS NOT DISTINCT FROM $2 AND (usersql."id" > $3)) ORDER BY usersql."enqueued_at" DESC NULLS LAST, usersql."id" ASC LIMIT $4""",
      sqlOf(query)
    )

  pureTest("first page (empty anchor): no WHERE, ORDER BY still includes appended id"):
    val query = resolved(Vector(MessageField.EnqueuedAt.descending), Position.Keyset.First)
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql ORDER BY usersql."enqueued_at" DESC NULLS LAST, usersql."id" ASC LIMIT $1""",
        sqlOf(query)
      ),
      expect.same(List("int4"), typesOf(query))
    ).combineAll

  // === Offset branch ===

  pureTest("offset branch: OFFSET $n LIMIT $m, no keyset predicate, id tiebreaker appended to ORDER BY"):
    val query = resolved(Vector(MessageField.EnqueuedAt.descending), Position.Offset.unsafe(40))
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql ORDER BY usersql."enqueued_at" DESC NULLS LAST, usersql."id" ASC OFFSET $1 LIMIT $2""",
        sqlOf(query)
      ),
      expect.same(List("int8", "int4"), typesOf(query))
    ).combineAll

  pureTest("offset branch with no order fields (KeysetField present): id tiebreaker still yields a total order"):
    val query = resolved(Vector.empty, Position.Offset.unsafe(0))
    List(
      expect.same(
        """SELECT * FROM (SELECT * FROM messages) AS usersql ORDER BY usersql."id" ASC OFFSET $1 LIMIT $2""",
        sqlOf(query)
      ),
      expect.same(List("int8", "int4"), typesOf(query))
    ).combineAll

  pureTest("offset branch with no order fields and no KeysetField: genuine no-ORDER BY form preserved"):
    val query = resolved(Vector.empty, Position.Offset.unsafe(0))
    expect.same(
      Right("""SELECT * FROM (SELECT * FROM messages) AS usersql OFFSET $1 LIMIT $2"""),
      Pagination.buildSql(query, select, None).map(_.fragment.sql)
    )

  // === Keyset value -> codec mapping ===

  pureTest("KeysetValue maps to the hard-coded Skunk codec; Absent binds no parameter"):
    def bindTypesFor(value: KeysetValue): List[String] =
      typesOf(resolved(Vector(MessageField.Id.ascending), Position.Keyset(List(value))))
    List(
      expect.same(List("int4", "int4"), bindTypesFor(KeysetValue.IntV(7))),
      expect.same(List("int8", "int4"), bindTypesFor(KeysetValue.LongV(7))),
      expect.same(List("text", "int4"), bindTypesFor(KeysetValue.StringV("x"))),
      expect.same(List("timestamptz", "int4"), bindTypesFor(KeysetValue.TimestampV(instant))),
      expect.same(List("int4"), bindTypesFor(KeysetValue.Absent))
    ).combineAll

  // === Identifier quoting / escaping ===

  pureTest("identifiers are double-quoted; embedded quotes doubled; reserved words safe"):
    val query = ResolvedQuery[QuoteField](
      Set.empty,
      Vector(QuoteField.Plain.ascending, QuoteField.Reserved.ascending, QuoteField.Weird.ascending),
      10.items,
      Position.Offset.unsafe(0),
      Direction.Forward
    )
    expect.same(
      Right(
        """SELECT * FROM (SELECT * FROM messages) AS usersql ORDER BY usersql."plain_col" ASC NULLS LAST, usersql."order" ASC NULLS LAST, usersql."a""b" ASC NULLS LAST OFFSET $1 LIMIT $2"""
      ),
      Pagination.buildSql(query, select, None).map(_.fragment.sql)
    )

  // === Composition with a parameterized user SELECT ===

  pureTest("parameterized user SELECT: its parameters precede folio's, placeholders renumber"):
    val paramSelect = sql"SELECT * FROM messages WHERE tenant = $int8".apply(99L)
    val applied = Pagination.buildSql(
      resolved(Vector(MessageField.Id.ascending), Position.Keyset(List(KeysetValue.LongV(5)))),
      paramSelect,
      Some(messageKeyset)
    )
    List(
      expect.same(
        Right(
          """SELECT * FROM (SELECT * FROM messages WHERE tenant = $1) AS usersql WHERE (usersql."id" > $2) ORDER BY usersql."id" ASC NULLS LAST LIMIT $3"""
        ),
        applied.map(_.fragment.sql)
      ),
      expect.same(Right(List("int8", "int8", "int4")), applied.map(_.fragment.encoder.types.map(_.name).toList))
    ).combineAll

  // === Contract: a keyset position requires the KeysetField metadata ===

  pureTest("Position.Keyset paired with keyset = None returns Left, never truncated SQL"):
    val query = resolved(Vector(MessageField.Id.ascending), Position.Keyset(List(KeysetValue.LongV(5))))
    expect.same(
      Left(
        FolioError.InvalidQuery(
          "Position.Keyset requires Some(keysetField); pass the KeysetField used to resolve the query"
        )
      ),
      Pagination.buildSql(query, select, None).map(_.fragment.sql)
    )

  pureTest("keyset anchor with too few values (missing id tiebreaker) returns Left, never truncated SQL"):
    // order by EnqueuedAt -> cursor fields [EnqueuedAt, id]; a single-value anchor would drop the id tiebreaker rung.
    val query = resolved(
      Vector(MessageField.EnqueuedAt.ascending),
      Position.Keyset(List(KeysetValue.TimestampV(instant)))
    )
    expect.same(
      Left(FolioError.InvalidQuery("Keyset anchor arity mismatch: 2 cursor field(s) but 1 anchor value(s)")),
      Pagination.buildSql(query, select, Some(messageKeyset)).map(_.fragment.sql)
    )

  pureTest("keyset anchor with extra values returns Left, never silently ignored"):
    // order by id -> cursor fields [id]; a second value has no rung and would be dropped by zip.
    val query = resolved(
      Vector(MessageField.Id.ascending),
      Position.Keyset(List(KeysetValue.LongV(5), KeysetValue.LongV(6)))
    )
    expect.same(
      Left(FolioError.InvalidQuery("Keyset anchor arity mismatch: 1 cursor field(s) but 2 anchor value(s)")),
      Pagination.buildSql(query, select, Some(messageKeyset)).map(_.fragment.sql)
    )

  // === Contract: duplicate / contradictory order fields are rejected before rendering ===

  pureTest("duplicate order field (identical order) returns Left InvalidQuery"):
    val query = resolved(
      Vector(MessageField.Id.ascending, MessageField.Id.ascending),
      Position.Keyset(List(KeysetValue.LongV(5)))
    )
    expect.same(
      Left(FolioError.InvalidQuery("duplicate order field: id")),
      Pagination.buildSql(query, select, Some(messageKeyset)).map(_.fragment.sql)
    )

  pureTest("contradictory order field (asc then desc) returns Left InvalidQuery"):
    val query = resolved(
      Vector(MessageField.Id.ascending, MessageField.Id.descending),
      Position.Offset.First
    )
    expect.same(
      Left(FolioError.InvalidQuery("duplicate order field: id")),
      Pagination.buildSql(query, select, Some(messageKeyset)).map(_.fragment.sql)
    )

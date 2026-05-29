package folio

import scala.collection.immutable.ListSet

import weaver.SimpleIOSuite

object CursorAdvanceSuite extends SimpleIOSuite:

  private val limit = 10.items

  // --- offsetOnly defensive Keyset passthrough ---

  pureTest("offsetOnly.next passes Keyset through unchanged (defensive branch)"):
    val advance = CursorAdvance.offsetOnly[TestField, Row]
    val keyset = Position.Keyset(List(KeysetValue.LongV(7L)))

    expect.same(keyset, advance.next(keyset, ListSet.empty, Seq.empty, limit))

  pureTest("offsetOnly.previous passes Keyset through unchanged (defensive branch)"):
    val advance = CursorAdvance.offsetOnly[TestField, Row]
    val keyset = Position.Keyset(List(KeysetValue.LongV(7L)))

    expect.same(keyset, advance.previous(keyset, ListSet.empty, Seq.empty, limit))

  // --- keysetAware None-extractor fallback ---
  // When a sort field has no registered extractor, encodeRow falls back to the id codec/extractor.
  // In normal flow Position.fromQuery would pick Offset for this configuration, but the trait
  // defensively handles a forged Keyset position.

  pureTest("keysetAware.next falls back to id when sort field has no registered extractor"):
    val keysetField = KeysetField(TestField.Id, (row: Row) => row.id)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(99L, "alice", "2024-01-01", "2024-01-01")
    val sortBys = ListSet(TestField.Name.ascending)

    expect.same(
      Position.Keyset(List(KeysetValue.LongV(99L), KeysetValue.LongV(99L))),
      advance.next(Position.Keyset(Nil), sortBys, Seq(row), limit)
    )

  pureTest("keysetAware.previous falls back to id when sort field has no registered extractor"):
    val keysetField = KeysetField(TestField.Id, (row: Row) => row.id)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(7L, "bob", "2024-01-02", "2024-01-02")
    val sortBys = ListSet(TestField.Name.ascending)

    expect.same(
      Position.Keyset(List(KeysetValue.LongV(7L), KeysetValue.LongV(7L))),
      advance.previous(Position.Keyset(Nil), sortBys, Seq(row), limit)
    )

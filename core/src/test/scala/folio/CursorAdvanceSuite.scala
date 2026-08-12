package folio

import weaver.SimpleIOSuite

object CursorAdvanceSuite extends SimpleIOSuite:

  private val limit = 10.items

  // --- offsetOnly defensive Keyset passthrough ---

  pureTest("offsetOnly.next passes Keyset through unchanged (defensive branch)"):
    val advance = CursorAdvance.offsetOnly[TestField, Row]
    val keyset = Position.Keyset(List(FieldValue.LongV(7L).present))

    expect.same(keyset, advance.next(keyset, Vector.empty, Seq.empty, limit))

  pureTest("offsetOnly.previous passes Keyset through unchanged (defensive branch)"):
    val advance = CursorAdvance.offsetOnly[TestField, Row]
    val keyset = Position.Keyset(List(FieldValue.LongV(7L).present))

    expect.same(keyset, advance.previous(keyset, Vector.empty, Seq.empty, limit))

  // --- keysetAware None-extractor fallback ---
  // With no registered extractor for an order field, encodeRow falls back to the id extractor. Position.fromQuery would
  // pick Offset here; this covers a forged Keyset position.

  pureTest("keysetAware.next falls back to id when order field has no registered extractor"):
    val keysetField = KeysetField.uniqueBy(TestField.Id, (row: Row) => row.id)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(99L, "alice", "2024-01-01", "2024-01-01", None)
    val ordering = Vector(TestField.Name.ascending)

    expect.same(
      Position.Keyset(List(FieldValue.LongV(99L).present, FieldValue.LongV(99L).present)),
      advance.next(Position.Keyset(Nil), ordering, Seq(row), limit)
    )

  pureTest("keysetAware.previous falls back to id when order field has no registered extractor"):
    val keysetField = KeysetField.uniqueBy(TestField.Id, (row: Row) => row.id)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(7L, "bob", "2024-01-02", "2024-01-02", None)
    val ordering = Vector(TestField.Name.ascending)

    expect.same(
      Position.Keyset(List(FieldValue.LongV(7L).present, FieldValue.LongV(7L).present)),
      advance.previous(Position.Keyset(Nil), ordering, Seq(row), limit)
    )

  // --- absentable-field extractor produces AnchorValue.Absent on None ---

  pureTest("keysetAware.next emits Absent when the boundary row's absentable field is None"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.LastSeen, (row: Row) => row.lastSeen)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(8L, "alice", "2024-01-09", "2024-01-09", None)
    val ordering = Vector(TestField.LastSeen.ascending)

    expect.same(
      Position.Keyset(List(AnchorValue.Absent, FieldValue.LongV(8L).present)),
      advance.next(Position.Keyset(Nil), ordering, Seq(row), limit)
    )

  pureTest("keysetAware.previous emits Absent when the boundary row's absentable field is None"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.LastSeen, (row: Row) => row.lastSeen)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(5L, "alice", "2024-01-06", "2024-01-06", None)
    val ordering = Vector(TestField.LastSeen.ascending)

    expect.same(
      Position.Keyset(List(AnchorValue.Absent, FieldValue.LongV(5L).present)),
      advance.previous(Position.Keyset(Nil), ordering, Seq(row), limit)
    )

  pureTest("keysetAware.next emits the inner codec value when the absentable field has Some"):
    val keysetField = KeysetField
      .uniqueBy(TestField.Id, (row: Row) => row.id)
      .withField(TestField.LastSeen, (row: Row) => row.lastSeen)
    val advance = CursorAdvance.keysetAware[TestField, Row](keysetField)
    val row = Row(2L, "alice", "2024-01-01", "2024-01-01", Some("2024-02-03"))
    val ordering = Vector(TestField.LastSeen.ascending)

    expect.same(
      Position.Keyset(List(FieldValue.StringV("2024-02-03").present, FieldValue.LongV(2L).present)),
      advance.next(Position.Keyset(Nil), ordering, Seq(row), limit)
    )

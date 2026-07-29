package folio

import java.time.OffsetDateTime

import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object AnchorValueSuite extends SimpleIOSuite:

  private val timestamp = OffsetDateTime.parse("2024-01-15T10:30:00Z")

  pureTest("Absent is a singleton case object value"):
    val value: AnchorValue = AnchorValue.Absent
    expect.same(AnchorValue.Absent, value)

  pureTest("Absent is not equal to any Present value"):
    List(
      expect(AnchorValue.Absent != AnchorValue.Present(FieldValue.IntV(0))),
      expect(AnchorValue.Absent != AnchorValue.Present(FieldValue.LongV(0L))),
      expect(AnchorValue.Absent != AnchorValue.Present(FieldValue.StringV(""))),
      expect(AnchorValue.Absent != AnchorValue.Present(FieldValue.StringV("absent"))),
      expect(AnchorValue.Absent != AnchorValue.Present(FieldValue.TimestampV(timestamp)))
    ).combineAll

  pureTest("Present wraps its FieldValue and compares by it"):
    List(
      expect.same(AnchorValue.Present(FieldValue.LongV(7L)), AnchorValue.Present(FieldValue.LongV(7L))),
      expect(AnchorValue.Present(FieldValue.LongV(7L)) != AnchorValue.Present(FieldValue.LongV(8L))),
      expect(AnchorValue.Present(FieldValue.LongV(7L)) != AnchorValue.Present(FieldValue.IntV(7)))
    ).combineAll

  pureTest("Present and Absent match exhaustively"):
    def describe(value: AnchorValue): String =
      value match
        case AnchorValue.Present(fieldValue) => s"present:${fieldValue}"
        case AnchorValue.Absent              => "absent"
    List(
      expect.same("present:StringV(alpha)", describe(AnchorValue.Present(FieldValue.StringV("alpha")))),
      expect.same("absent", describe(AnchorValue.Absent))
    ).combineAll

  pureTest("the present extension wraps a FieldValue as Present"):
    List(
      expect.same(AnchorValue.Present(FieldValue.IntV(3)), FieldValue.IntV(3).present),
      expect.same(AnchorValue.Present(FieldValue.TimestampV(timestamp)), FieldValue.TimestampV(timestamp).present)
    ).combineAll

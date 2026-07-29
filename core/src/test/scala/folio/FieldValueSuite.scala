package folio

import java.time.OffsetDateTime

import scala.compiletime.testing.typeCheckErrors

import cats.syntax.foldable.*
import weaver.SimpleIOSuite

object FieldValueSuite extends SimpleIOSuite:

  private val timestamp = OffsetDateTime.parse("2024-01-15T10:30:00Z")

  private def describe(value: FieldValue): String =
    value match
      case _: FieldValue.IntV       => "int"
      case _: FieldValue.LongV      => "long"
      case _: FieldValue.StringV    => "string"
      case _: FieldValue.TimestampV => "timestamp"

  pureTest("every variant matches exhaustively as itself"):
    List(
      expect.same("int", describe(FieldValue.IntV(0))),
      expect.same("long", describe(FieldValue.LongV(0L))),
      expect.same("string", describe(FieldValue.StringV(""))),
      expect.same("timestamp", describe(FieldValue.TimestampV(timestamp)))
    ).combineAll

  pureTest("variants carrying comparable payloads are not equal across variants"):
    List(
      expect(FieldValue.IntV(0) != FieldValue.LongV(0L)),
      expect(FieldValue.LongV(0L) != FieldValue.StringV("0")),
      expect(FieldValue.StringV("") != FieldValue.StringV("absent")),
      expect(FieldValue.TimestampV(timestamp) != FieldValue.StringV(timestamp.toString))
    ).combineAll

  pureTest("Absent is not a FieldValue variant (compile error)"):
    // Pins the FieldValue / AnchorValue split: the missing-value case belongs to AnchorValue only.
    val errors = typeCheckErrors("folio.FieldValue.Absent")
    expect(errors.nonEmpty)

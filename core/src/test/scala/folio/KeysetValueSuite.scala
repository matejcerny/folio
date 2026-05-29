package folio

import weaver.SimpleIOSuite

object KeysetValueSuite extends SimpleIOSuite:

  pureTest("Absent is a singleton case object value"):
    val value: KeysetValue = KeysetValue.Absent
    expect.same(KeysetValue.Absent, value)

  pureTest("Absent pattern-matches as Absent and is distinct from atomic cases"):
    val value: KeysetValue = KeysetValue.Absent
    val matched = value match
      case KeysetValue.Absent        => "absent"
      case _: KeysetValue.IntV       => "int"
      case _: KeysetValue.LongV      => "long"
      case _: KeysetValue.StringV    => "string"
      case _: KeysetValue.TimestampV => "timestamp"
    expect.same("absent", matched)

  pureTest("Absent is not equal to any atomic KeysetValue"):
    List(
      expect(KeysetValue.Absent != KeysetValue.IntV(0)),
      expect(KeysetValue.Absent != KeysetValue.LongV(0L)),
      expect(KeysetValue.Absent != KeysetValue.StringV("")),
      expect(KeysetValue.Absent != KeysetValue.StringV("absent"))
    ).reduce(_ and _)

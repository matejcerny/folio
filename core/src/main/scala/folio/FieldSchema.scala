package folio

import scala.compiletime.{ erasedValue, summonInline }
import scala.deriving.Mirror

/** Typeclass that binds a user-defined field type to a string identifier used as the column/attribute name in queries
  * and cursor encoding.
  */
trait FieldSchema[FIELD]:
  def name(field: FIELD): String
  def fromName(name: String): Either[String, FIELD]

object FieldSchema:
  /** Build a [[FieldSchema]] from just the forward `FIELD => String` mapping. The reverse `fromName` is derived by
    * enumerating the enum cases at compile time via [[scala.deriving.Mirror.SumOf]] and matching against the supplied
    * mapping.
    *
    * Only works for enums whose cases are all singletons (no parameters). For richer cases, implement [[FieldSchema]]
    * directly.
    */
  inline def fromMapping[FIELD](toName: FIELD => String)(using m: Mirror.SumOf[FIELD]): FieldSchema[FIELD] =
    build(toName, enumerateCases[m.MirroredElemTypes, FIELD])

  private def build[FIELD](toName: FIELD => String, cases: List[FIELD]): FieldSchema[FIELD] =
    new FieldSchema[FIELD]:
      def name(field: FIELD): String = toName(field)
      def fromName(input: String): Either[String, FIELD] =
        cases.find(candidate => toName(candidate) == input).toRight(s"Unknown field: $input")

  private inline def enumerateCases[ElemTypes <: Tuple, FIELD]: List[FIELD] =
    inline erasedValue[ElemTypes] match
      case _: EmptyTuple     => Nil
      case _: (head *: tail) => summonInline[ValueOf[head]].value.asInstanceOf[FIELD] :: enumerateCases[tail, FIELD]

extension [FIELD](field: FIELD)(using fieldSchema: FieldSchema[FIELD]) def name: String = fieldSchema.name(field)

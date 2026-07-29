package folio

import scala.compiletime.{ constValueTuple, erasedValue, summonInline }
import scala.deriving.Mirror

/** Binds a user-defined field type to a string identifier used as the column/attribute name in queries and cursor
  * encoding.
  */
trait FieldSchema[FIELD]:
  def name(field: FIELD): String
  def fromName(name: String): Either[String, FIELD]

object FieldSchema:
  class Schema[FIELD](fieldsToNames: Map[FIELD, String], namesToFields: Map[String, FIELD]) extends FieldSchema[FIELD]:
    def name(field: FIELD): String = fieldsToNames(field)
    def fromName(input: String): Either[String, FIELD] = namesToFields.get(input).toRight(s"Unknown field: $input")

  /** Build a [[FieldSchema]] from just the forward `FIELD => String` mapping. The reverse `fromName` is derived by
    * enumerating the enum cases at compile time via [[scala.deriving.Mirror.SumOf]] and matching against the supplied
    * mapping.
    *
    * Only works for enums whose cases are all singletons (no parameters). For richer cases, implement [[FieldSchema]]
    * directly.
    */
  inline def fromMapping[FIELD](toName: FIELD => String)(using m: Mirror.SumOf[FIELD]): FieldSchema[FIELD] =
    val cases = enumerateCases[m.MirroredElemTypes, FIELD]
    val forwardMap = cases.map(c => c -> toName(c)).toMap
    val reverseMap = forwardMap.map((f, n) => n -> f)

    new Schema(forwardMap, reverseMap)

  private inline def enumerateCases[ElemTypes <: Tuple, FIELD]: List[FIELD] =
    inline erasedValue[ElemTypes] match
      case _: EmptyTuple     => Nil
      case _: (head *: tail) => summonInline[ValueOf[head]].value.asInstanceOf[FIELD] :: enumerateCases[tail, FIELD]

  private inline def deriveMaps[FIELD](transform: String => String)(using
      m: Mirror.SumOf[FIELD]
  ): (Map[FIELD, String], Map[String, FIELD]) =
    val cases = enumerateCases[m.MirroredElemTypes, FIELD]
    val labels = constValueTuple[m.MirroredElemLabels].toList.asInstanceOf[List[String]]
    val fieldsToNames = cases.zip(labels).map((field, label) => field -> transform(label)).toMap
    val namesToFields = fieldsToNames.map((field, name) => name -> field)

    (fieldsToNames, namesToFields)

  /** Derives a FieldSchema by automatically converting the enum case names into snake case strings. */
  class SnakeCase[FIELD](fieldsToNames: Map[FIELD, String], namesToFields: Map[String, FIELD])
      extends Schema[FIELD](fieldsToNames, namesToFields)

  object SnakeCase:
    inline def derived[FIELD](using m: Mirror.SumOf[FIELD]): SnakeCase[FIELD] =
      val (fieldsToNames, namesToFields) = deriveMaps[FIELD](camelToSnake)
      new SnakeCase(fieldsToNames, namesToFields)

    private def camelToSnake(name: String): String =
      name
        .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
        .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
        .toLowerCase

extension [FIELD](field: FIELD)(using fieldSchema: FieldSchema[FIELD]) def name: String = fieldSchema.name(field)

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

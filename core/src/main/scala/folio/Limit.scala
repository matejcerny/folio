package folio

opaque type Limit = Int

object Limit:
  def apply(n: Int): Limit = n
  val Default: Limit = 10

  extension (limit: Limit) def value: Int = limit

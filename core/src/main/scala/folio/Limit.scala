package folio

opaque type Limit = Int

object Limit:
  def apply(n: Int): Limit = n
  val Default: Limit = 10

  extension (limit: Limit)
    def value: Int = limit
    private[folio] def fetchLimit: Limit = Limit(limit + 1)
    private[folio] def hasMore(items: Seq[?]): Boolean = items.lengthCompare(limit) > 0

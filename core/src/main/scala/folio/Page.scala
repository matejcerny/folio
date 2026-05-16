package folio

case class Page[T](
    limit: Limit,
    previousCursor: Option[Cursor],
    nextCursor: Option[Cursor],
    data: Seq[T]
)

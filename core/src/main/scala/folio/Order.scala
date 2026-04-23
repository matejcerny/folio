package folio

enum Order:
  case Ascending
  case Descending

object Order:
  val Default: Order = Ascending

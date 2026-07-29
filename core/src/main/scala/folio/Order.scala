package folio

enum Order:
  case Ascending
  case Descending

  def flip: Order = this match
    case Ascending  => Descending
    case Descending => Ascending

object Order:
  val Default: Order = Ascending

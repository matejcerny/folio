package folio

/** One slot of a keyset cursor anchor: either a concrete [[FieldValue]] or [[Absent]], the anchor for a row whose
  * order-column value was missing. Only order fields registered as absentable may carry [[Absent]].
  */
enum AnchorValue:
  case Present(value: FieldValue)
  case Absent

extension (fieldValue: FieldValue) private[folio] def present: AnchorValue = AnchorValue.Present(fieldValue)

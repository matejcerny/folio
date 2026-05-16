package folio

import weaver.SimpleIOSuite

object OrderSuite extends SimpleIOSuite:

  pureTest("Order.Default is Ascending"):
    expect.same(Order.Default, Order.Ascending)

  pureTest("Ascending and Descending are distinct"):
    expect(clue(Order.Ascending) != clue(Order.Descending))

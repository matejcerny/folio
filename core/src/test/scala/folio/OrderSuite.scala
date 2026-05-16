package folio

import weaver.SimpleIOSuite

object OrderSuite extends SimpleIOSuite:

  pureTest("Order.Default is Ascending"):
    expect.same(Order.Default, Order.Ascending)

  pureTest("Ascending and Descending are distinct"):
    expect(clue(Order.Ascending) != clue(Order.Descending))

  pureTest("Order.flip flips the order"):
    expect.same(Order.Ascending.flip, Order.Descending)
    expect.same(Order.Descending.flip, Order.Ascending)

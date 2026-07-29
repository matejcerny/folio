package folio

import weaver.SimpleIOSuite

object OrderSuite extends SimpleIOSuite:

  pureTest("Order.Default is Ascending"):
    expect.same(Order.Ascending, Order.Default)

  pureTest("Ascending and Descending are distinct"):
    expect(clue(Order.Ascending) != clue(Order.Descending))

  pureTest("Order.flip flips the order"):
    expect.same(Order.Descending, Order.Ascending.flip)
    expect.same(Order.Ascending, Order.Descending.flip)

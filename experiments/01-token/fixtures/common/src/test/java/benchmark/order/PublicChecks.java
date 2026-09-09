package benchmark.order;
public final class PublicChecks {
    public static void main(String[] args) {
        OrderCalculator calculator = new OrderCalculator();
        equal(3_000, calculator.shippingFee(49_999));
        equal(0, calculator.shippingFee(50_001));
        equal(43_000, calculator.total(40_000));
        equal(50_000, calculator.total(50_000));
    }
    public static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
}

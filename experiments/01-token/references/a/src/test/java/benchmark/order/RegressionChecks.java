package benchmark.order;
public final class RegressionChecks {
    public static void main(String[] args) {
        PublicChecks.equal(0, new OrderCalculator().shippingFee(50_000));
    }
}

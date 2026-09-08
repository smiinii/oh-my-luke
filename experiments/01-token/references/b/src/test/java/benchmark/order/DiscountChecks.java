package benchmark.order;
public final class DiscountChecks {
    public static void main(String[] args) {
        PublicChecks.equal(38_000, new OrderCalculator().total(40_000, 5_000));
        PublicChecks.equal(45_000, new OrderCalculator().total(50_000, 5_000));
    }
}

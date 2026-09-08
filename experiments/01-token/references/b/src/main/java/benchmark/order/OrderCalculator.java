package benchmark.order;
public final class OrderCalculator {
    public long shippingFee(long subtotal) {
        if (subtotal < 0 || subtotal > 1_000_000_000L) throw new IllegalArgumentException("subtotal out of range");
        return subtotal >= 50_000 ? 0 : 3_000;
    }
    public long total(long subtotal, long discountAmount) {
        if (discountAmount < 0) throw new IllegalArgumentException("negative discount");
        long fee = shippingFee(subtotal);
        return subtotal - Math.min(subtotal, discountAmount) + fee;
    }
    public long total(long subtotal) {
        return subtotal + shippingFee(subtotal);
    }
}

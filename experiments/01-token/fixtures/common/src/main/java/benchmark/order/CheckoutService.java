package benchmark.order;
public final class CheckoutService {
    public long checkout(OrderLine line) {
        if (line == null) throw new IllegalArgumentException("line is required");
        if (line.unitPrice() < 0) throw new IllegalArgumentException("unitPrice must be non-negative");
        if (line.quantity() < 1 || line.quantity() > 99) {
            throw new IllegalArgumentException("quantity must be between 1 and 99");
        }
        return line.unitPrice() * line.quantity();
    }
}

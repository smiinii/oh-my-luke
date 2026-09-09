package benchmark.order;
public final class OrderLineValidator {
    private OrderLineValidator() {}
    public static void validate(OrderLine line) {
        if (line == null) throw new IllegalArgumentException("line is required");
        if (line.unitPrice() < 0) throw new IllegalArgumentException("unitPrice must be non-negative");
        if (line.quantity() < 1 || line.quantity() > 99) {
            throw new IllegalArgumentException("quantity must be between 1 and 99");
        }
    }
}

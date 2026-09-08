package benchmark.order;
public final class CheckoutService {
    public long checkout(OrderLine line) {
        OrderLineValidator.validate(line);
        return line.unitPrice() * line.quantity();
    }
}

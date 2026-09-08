package benchmark.order;
public final class QuoteService {
    public long quote(OrderLine line) {
        OrderLineValidator.validate(line);
        return line.unitPrice() * line.quantity();
    }
}

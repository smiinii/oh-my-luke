package benchmark.order;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Emits observations only. Expected values and final verdict live outside this JVM. */
public final class Judge {
    @FunctionalInterface private interface Observation { Object read() throws Exception; }

    private static void observe(Observation operation) {
        try {
            System.out.println("value:" + operation.read());
        } catch (Exception failure) {
            Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            System.out.println("error:" + cause.getClass().getName() + ":"
                    + Base64.getEncoder().encodeToString(message.getBytes(StandardCharsets.UTF_8)));
        }
    }

    public static void main(String[] args) throws Exception {
        OrderCalculator calculator = new OrderCalculator();
        switch (args[0]) {
            case "a" -> {
                for (long subtotal : new long[] {0, 1, 49_999, 50_000, 50_001, 1_000_000_000L}) {
                    observe(() -> calculator.shippingFee(subtotal));
                    observe(() -> calculator.total(subtotal));
                }
            }
            case "b" -> {
                var method = OrderCalculator.class.getMethod("total", long.class, long.class);
                for (long subtotal : new long[] {0, 1, 10_000, 40_000, 49_999, 50_000, 50_001, 1_000_000_000L}) {
                    observe(() -> calculator.total(subtotal));
                    for (long discount : new long[] {0, 1, 5_000, 20_000, subtotal, subtotal + 1, Long.MAX_VALUE}) {
                        observe(() -> method.invoke(calculator, subtotal, discount));
                    }
                }
                // B specifies exception type, not message: emit that type only.
                for (long[] values : new long[][] {{-1, 0}, {10_000, -1}}) {
                    String type = "none";
                    try { method.invoke(calculator, values[0], values[1]); }
                    catch (InvocationTargetException error) { type = error.getCause().getClass().getName(); }
                    System.out.println("exception-type:" + type);
                }
            }
            case "c" -> {
                var method = Class.forName("benchmark.order.OrderLineValidator").getMethod("validate", OrderLine.class);
                observe(() -> Modifier.isStatic(method.getModifiers()));
                for (long price : new long[] {0, 1, 1_000_000_000L}) {
                    for (int quantity : new int[] {1, 2, 99}) {
                        OrderLine line = new OrderLine(price, quantity);
                        observe(() -> new CheckoutService().checkout(line));
                        observe(() -> new QuoteService().quote(line));
                    }
                }
                for (OrderLine line : new OrderLine[] {null, new OrderLine(-1, 0), new OrderLine(-1, 100),
                        new OrderLine(0, 0), new OrderLine(0, 100)}) {
                    observe(() -> new CheckoutService().checkout(line));
                    observe(() -> new QuoteService().quote(line));
                }
            }
            case "pilot" -> {
                Object label = Class.forName("benchmark.order.QuantityLabel").getConstructor().newInstance();
                var method = label.getClass().getMethod("label", int.class);
                for (int quantity = 0; quantity < 100; quantity++) {
                    int value = quantity;
                    observe(() -> method.invoke(label, value));
                }
            }
            default -> throw new IllegalArgumentException("unknown task");
        }
    }
}

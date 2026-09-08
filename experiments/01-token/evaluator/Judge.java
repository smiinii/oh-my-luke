package benchmark.order;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Coordinator-owned checks, not copied into an agent workspace. */
public final class Judge {
    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }

    private static long discounted(OrderCalculator calculator, long subtotal, long discount) throws Exception {
        Method method = OrderCalculator.class.getMethod("total", long.class, long.class);
        try {
            return (long) method.invoke(calculator, subtotal, discount);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof IllegalArgumentException cause) throw cause;
            throw error;
        }
    }

    private static void negativeDiscount(OrderCalculator calculator, long subtotal, long discount) throws Exception {
        try {
            discounted(calculator, subtotal, discount);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("negative input was accepted");
    }

    private static void rejected(OrderLine line, String message) {
        for (boolean checkout : new boolean[] {true, false}) {
            try {
                if (checkout) new CheckoutService().checkout(line);
                else new QuoteService().quote(line);
            } catch (IllegalArgumentException expected) {
                if (!message.equals(expected.getMessage())) throw new AssertionError("changed exception message");
                continue;
            }
            throw new AssertionError("invalid line accepted");
        }
    }

    public static void main(String[] args) throws Exception {
        OrderCalculator calculator = new OrderCalculator();
        switch (args[0]) {
            case "a" -> {
                for (long subtotal : new long[] {0, 1, 49_999, 50_000, 50_001, 1_000_000_000L}) {
                    long fee = subtotal >= 50_000 ? 0 : 3_000;
                    equal(fee, calculator.shippingFee(subtotal));
                    equal(subtotal + fee, calculator.total(subtotal));
                }
            }
            case "b" -> {
                for (long subtotal : new long[] {0, 1, 10_000, 40_000, 49_999, 50_000, 50_001, 1_000_000_000L}) {
                    long fee = subtotal >= 50_000 ? 0 : 3_000;
                    equal(subtotal + fee, calculator.total(subtotal));
                    for (long discount : new long[] {0, 1, 5_000, 20_000, subtotal, subtotal + 1, Long.MAX_VALUE}) {
                        equal(subtotal - Math.min(subtotal, discount) + fee, discounted(calculator, subtotal, discount));
                    }
                }
                negativeDiscount(calculator, -1, 0);
                negativeDiscount(calculator, 10_000, -1);
            }
            case "c" -> {
                Class<?> validator = Class.forName("benchmark.order.OrderLineValidator");
                validator.getMethod("validate", OrderLine.class);
                for (long price : new long[] {0, 1, 1_000_000_000L}) {
                    for (int quantity : new int[] {1, 2, 99}) {
                        OrderLine line = new OrderLine(price, quantity);
                        equal(price * quantity, new CheckoutService().checkout(line));
                        equal(price * quantity, new QuoteService().quote(line));
                    }
                }
                rejected(null, "line is required");
                rejected(new OrderLine(-1, 0), "unitPrice must be non-negative");
                rejected(new OrderLine(-1, 100), "unitPrice must be non-negative");
                rejected(new OrderLine(0, 0), "quantity must be between 1 and 99");
                rejected(new OrderLine(0, 100), "quantity must be between 1 and 99");
            }
            case "pilot" -> {
                Object label = Class.forName("benchmark.order.QuantityLabel").getConstructor().newInstance();
                Method method = label.getClass().getMethod("label", int.class);
                for (int quantity = 0; quantity < 100; quantity++) {
                    String expected = quantity == 1 ? "item" : "items";
                    if (!expected.equals(method.invoke(label, quantity))) throw new AssertionError("wrong label");
                }
            }
            default -> throw new IllegalArgumentException("unknown task");
        }
        System.out.println("JUDGE_PASS:" + args[1]);
    }
}

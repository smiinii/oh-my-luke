"""Trusted expected results; never copied into the candidate JVM or its filesystem."""
import base64


def expected_observations(task):
    lines = []
    def value(number):
        lines.append("value:" + str(number))
    if task == "a":
        for subtotal in (0, 1, 49999, 50000, 50001, 1_000_000_000):
            fee = 0 if subtotal >= 50000 else 3000
            value(fee)
            value(subtotal + fee)
    elif task == "b":
        for subtotal in (0, 1, 10000, 40000, 49999, 50000, 50001, 1_000_000_000):
            fee = 0 if subtotal >= 50000 else 3000
            value(subtotal + fee)
            for discount in (0, 1, 5000, 20000, subtotal, subtotal + 1, 2**63-1):
                value(subtotal - min(subtotal, discount) + fee)
        lines += ["exception-type:java.lang.IllegalArgumentException"] * 2
    elif task == "c":
        value("true")
        for price in (0, 1, 1_000_000_000):
            for quantity in (1, 2, 99):
                value(price * quantity)
                value(price * quantity)
        for message in ("line is required", "unitPrice must be non-negative", "unitPrice must be non-negative",
                        "quantity must be between 1 and 99", "quantity must be between 1 and 99"):
            lines += ["error:java.lang.IllegalArgumentException:" + base64.b64encode(message.encode()).decode()] * 2
    elif task == "pilot":
        for quantity in range(100):
            value("item" if quantity == 1 else "items")
    else:
        raise ValueError("Unknown task")
    return lines

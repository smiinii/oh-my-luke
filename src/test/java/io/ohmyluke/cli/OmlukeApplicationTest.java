package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OmlukeApplicationTest {
    @Test
    void exposesProductName() {
        assertEquals("Oh My Luke", OmlukeApplication.productName());
    }
}

package io.ohmyluke.cli;

/** Entry point for the Oh My Luke command-line application. */
public final class OmlukeApplication {
    private static final String PRODUCT_NAME = "Oh My Luke";

    private OmlukeApplication() {
    }

    public static void main(String[] args) {
        System.out.println(PRODUCT_NAME);
    }

    static String productName() {
        return PRODUCT_NAME;
    }
}

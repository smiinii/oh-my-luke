package io.ohmyluke.tool;

final class UnsafeFileRequestException extends RuntimeException {
    private final String reasonCode;

    UnsafeFileRequestException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    String reasonCode() {
        return reasonCode;
    }
}

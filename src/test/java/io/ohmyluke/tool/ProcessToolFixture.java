package io.ohmyluke.tool;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProcessToolFixture {
    private ProcessToolFixture() {}

    public static void main(String[] arguments) throws Exception {
        switch (arguments[0]) {
            case "write" -> Files.writeString(Path.of("generated-by-process.txt"), "generated");
            case "env" -> System.out.print(System.getenv(arguments[1]));
            case "secret" -> System.out.print("TOKEN=ghp_abcdefghijklmnopqrstuvwxyz1234567890");
            case "large" -> System.out.print("x".repeat(Integer.parseInt(arguments[1])));
            case "sleep" -> Thread.sleep(Long.parseLong(arguments[1]));
            default -> throw new IllegalArgumentException("unknown fixture mode");
        }
    }
}

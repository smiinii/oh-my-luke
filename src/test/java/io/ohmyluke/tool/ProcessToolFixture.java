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
            case "opaque-secrets" -> System.out.print(
                    "AKIAIOSFODNN7EXAMPLE Authorization: Bearer opaque-secret");
            case "auth-variants" -> System.out.print(
                    "Authorization: Basic dXNlcjpwYXNz\n"
                            + "Proxy-Authorization: Digest opaque-credential\n"
                            + "Cookie: session=opaque-request-credential\n"
                            + "Set-Cookie: session=opaque-cookie\n"
                            + "Cookie=session=opaque-equals-cookie\n"
                            + "Set-Cookie=session=opaque-equals-set-cookie\n"
                            + "fortuneCookie=chocolate\n"
                            + "acceptCookie=false\n");
            case "large" -> System.out.print("x".repeat(Integer.parseInt(arguments[1])));
            case "sleep" -> Thread.sleep(Long.parseLong(arguments[1]));
            case "spawn" -> {
                Process child = new ProcessBuilder(arguments[1], "-version").start();
                if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    throw new IllegalStateException("child process escaped the fixture timeout");
                }
            }
            case "spawn-detached" -> {
                Process child = new ProcessBuilder(
                                arguments[1],
                                "-cp",
                                System.getProperty("java.class.path"),
                                ProcessToolFixture.class.getName(),
                                "sleep",
                                "30000")
                        .start();
                System.out.print(child.pid());
            }
            default -> throw new IllegalArgumentException("unknown fixture mode");
        }
    }
}

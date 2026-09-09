package io.ohmyluke.ai.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Deliberately hostile local process; never invokes a real AI CLI. */
public final class CatalogServerFixture {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("silent")) { Thread.sleep(60_000); return; }
        if (mode.equals("flood")) { System.out.print("x".repeat(140_000)); System.out.flush(); Thread.sleep(60_000); return; }
        if (mode.equals("stderr")) { System.err.print("private-value".repeat(10_000)); System.err.flush(); Thread.sleep(60_000); return; }
        if (mode.equals("child")) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), CatalogServerFixture.class.getName(), "silent").inheritIO().start();
            Files.writeString(Path.of(args[1]), Long.toString(child.pid()));
            Thread.sleep(60_000); return;
        }
        var json = new ObjectMapper();
        var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        String line;
        while ((line = input.readLine()) != null) {
            var request = json.readTree(line);
            String method = request.path("method").asText();
            if (method.equals("initialized")) { continue; }
            if (!java.util.Set.of("initialize", "account/read", "model/list").contains(method)) { System.exit(9); }
            int id = request.path("id").intValue();
            if (mode.equals("wrong-id")) { System.out.println("{\"id\":999,\"result\":{}}"); }
            else if (mode.equals("duplicate")) { System.out.println("{\"id\":" + id + ",\"id\":" + id + ",\"result\":{}}"); }
            else if (mode.equals("error")) { System.out.println(json.writeValueAsString(Map.of("id", id, "error", Map.of("message", "private-value")))); }
            else if (mode.equals("request")) { System.out.println("{\"id\":99,\"method\":\"command/exec\",\"params\":{}}"); }
            else if (mode.equals("notifications")) { for (int i = 0; i < 150; i++) { System.out.println("{\"method\":\"notice\"}"); } }
            else {
                Object result = switch (method) {
                    case "initialize" -> Map.of("userAgent", "fixture");
                    case "account/read" -> Map.of("account", Map.of("type", "chatgpt", "email", "private@example.test"));
                    default -> json.readTree("{\"data\":[{\"id\":\"wire\",\"model\":\"actual-model\",\"displayName\":\"Model\",\"hidden\":false,\"isDefault\":true}],\"nextCursor\":null}");
                };
                if (mode.equals("account-changed") && method.equals("model/list")) { System.out.println("{\"method\":\"account/updated\",\"params\":{}}"); }
                System.out.println(json.writeValueAsString(Map.of("id", id, "result", result)));
            }
            System.out.flush();
        }
    }
}

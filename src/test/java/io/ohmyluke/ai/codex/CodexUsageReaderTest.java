package io.ohmyluke.ai.codex;

import static org.junit.jupiter.api.Assertions.*;
import io.ohmyluke.ai.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexUsageReaderTest {
    @TempDir Path project;
    @Test void storedRawUsageMatchesMetadataWithoutReturningTheAnswerOrSessionSecrets() throws Exception {
        project=project.toRealPath();
        var usage = AiTokenUsage.measured(100,40,20,5,"codex-exec-jsonl");
        try (var record = new CodexInvocationStore(project,4096).lock("invocation")) {
            record.save(CodexStoredInvocation.current("a".repeat(64),"codex-cli:v1:sha256:"+"b".repeat(64),
                    AiRuntimeResult.success("PRIVATE_RESPONSE",usage,"thread")));
        }
        var evidence = new CodexUsageReader(project).read("invocation");
        assertNotNull(evidence); assertEquals(usage,evidence.tokens());
        assertFalse(evidence.toString().contains("PRIVATE_RESPONSE"));
        assertNull(new CodexUsageReader(project).read("another-run"));
        Path file = project.resolve(".oml/runtime/codex/invocations/"+CodexHashing.safeFileId("invocation")+".json");
        String original = Files.readString(file);
        Files.writeString(file,original.replace("\"inputTokens\" : 100","\"inputTokens\" : 100, \"inputTokens\" : 200"));
        assertNull(new CodexUsageReader(project).read("invocation"));
        Files.writeString(file,original.replace("\"usage\" : 120","\"usage\" : 121"));
        assertNull(new CodexUsageReader(project).read("invocation"));
        Files.delete(file); Files.createSymbolicLink(file,project.resolve("missing"));
        assertNull(new CodexUsageReader(project).read("invocation"));
    }
    @Test void singleOfficialUsageEventIsCountedButMissingRepeatedAndOverflowingEventsAreNotGuessed() {
        var parser = new CodexCliJsonParser();
        String valid = "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"cached_input_tokens\":40,\"output_tokens\":20,\"reasoning_output_tokens\":5}}";
        assertEquals(120,parser.parse(valid).tokenUsage().recordedTotal());
        assertFalse(parser.parse(valid+"\n"+valid).tokenUsage().available());
        assertFalse(parser.parse(valid.replace(",\"reasoning_output_tokens\":5","")).tokenUsage().available());
        assertFalse(parser.parse(valid.replace("100",Long.toString(Long.MAX_VALUE))).tokenUsage().available());
        assertThrows(IllegalArgumentException.class,()->parser.parse(valid+" {}"));
        assertThrows(IllegalArgumentException.class,()->parser.parse(valid.replace("\"input_tokens\":100","\"input_tokens\":100,\"input_tokens\":200")));
    }
}

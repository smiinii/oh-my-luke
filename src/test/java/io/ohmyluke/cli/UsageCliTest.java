package io.ohmyluke.cli;

import static org.junit.jupiter.api.Assertions.*;
import io.ohmyluke.ai.*;
import io.ohmyluke.usage.UsageReport;
import io.ohmyluke.preset.*;
import io.ohmyluke.tool.UnavailableProcessSandbox;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageCliTest {
    @TempDir Path project;
    @Test void tableJsonAndCsvAgreeAndExportDoesNotWriteOrChangeSettings() throws Exception {
        var records=fixture();
        Map<Path,byte[]> before=snapshot();
        String table=execute(records,"usage","--details");
        assertTrue(table.contains("110"));assertTrue(table.contains("입력 100 (캐시 40) · 출력 10 (추론 3) · 합계 110"));
        String json=execute(records,"usage","--format","json");
        var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals("110",node.at("/questions/0/recordedTotal").asText());
        assertEquals("100",node.at("/questions/0/attempts/0/input").asText());
        assertEquals("unverified",node.path("childUsageCoverage").asText());
        assertFalse(node.path("subscriptionDebit").booleanValue());
        String csv=execute(records,"usage","--format","csv");
        assertTrue(csv.contains("\"100\",\"40\",\"10\",\"3\",\"110\",\"true\""));
        assertTrue(csv.contains("\"'=1+1, \"\"quoted\"\" next\""));
        String detail=execute(records,"usage","--format","csv","--details");
        assertTrue(detail.contains("runWarnings"));assertTrue(detail.contains("\"provider-reported-only\",\"unverified\""));
        assertTrue(detail.contains("\"100\",\"40\",\"10\",\"3\",\"110\""));
        var after=snapshot();assertEquals(before.keySet(),after.keySet());
        before.forEach((path,bytes)->assertArrayEquals(bytes,after.get(path)));
    }
    @Test void invalidOptionsDoNotQueryOrCreateHistory() {
        var reports=new UsageReport(project,id->{throw new AssertionError("no provider call");});
        var output=new ByteArrayOutputStream();var cli=new UsageCli(reports,new PrintStream(output));
        assertEquals(2,cli.execute(new String[]{"usage","--format","yaml"}));
        assertEquals(2,cli.execute(new String[]{"usage","--details","--details"}));
        assertFalse(Files.exists(project.resolve(".oml")));
    }
    private String execute(UsageReport reports,String... args) {
        var output=new ByteArrayOutputStream();
        assertEquals(0,new UsageCli(reports,new PrintStream(output,true,StandardCharsets.UTF_8)).execute(args));
        return output.toString(StandardCharsets.UTF_8);
    }
    private Map<Path,byte[]> snapshot() throws IOException {
        var result=new HashMap<Path,byte[]>();
        try(var files=Files.walk(project)){for(Path file:files.filter(Files::isRegularFile).toList()){result.put(file,Files.readAllBytes(file));}}
        return result;
    }
    private UsageReport fixture() throws Exception {
        Files.writeString(project.resolve("hello.txt"),"old");
        var tokens=AiTokenUsage.measured(100,40,10,3,"codex-exec-jsonl");
        var evidence=new HashMap<String,UsageReport.Evidence>();
        AiRuntime ai=new AiRuntime(){
            public String fingerprint(){return "test:usage";}
            public AiRuntimeResult invoke(AiRequest request){
                evidence.put(request.invocationId(),new UsageReport.Evidence(tokens,"one","SUCCESS"));
                return AiRuntimeResult.success(PresetJson.encode(Map.of("path","hello.txt","content","ready")),tokens);
            }
        };
        var service=new PresetRunService(project,t->ai,
                request->io.ohmyluke.policy.ToolPermissionDecision.allow("test.allow","allowed",null),
                new UnavailableProcessSandbox("test"),Clock.systemUTC());
        var task=new TaskSpec(1,"=1+1, \"quoted\"\nnext","hello.txt",ExecutionMode.DIRECT,1,0,60_000,1,
                new ValidationSpec(List.of("ready"),List.of(),null),null,null);
        service.start("q1",task);service.resume("q1");
        return new UsageReport(project,evidence::get);
    }
}

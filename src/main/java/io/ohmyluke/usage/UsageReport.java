package io.ohmyluke.usage;

import io.ohmyluke.ai.*;
import io.ohmyluke.graph.*;
import io.ohmyluke.preset.*;
import io.ohmyluke.runtime.*;
import io.ohmyluke.state.*;
import io.ohmyluke.tool.SecretRedactor;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** One submitted task is one question. Counts are observed provider reports, never subscription debits. */
public final class UsageReport {
    public record Evidence(AiTokenUsage tokens, String sessionId, String response) {}
    @FunctionalInterface public interface Source { Evidence read(String invocationId); }
    public record Attempt(String invocationId, int step, String node, String requestedModel, String response,
                          String input, String cachedInput, String output, String reasoningOutput, String total, String problem) {}
    public record Question(String runId, String question, String status, List<Attempt> attempts,
                           String input, String cachedInput, String output, String reasoningOutput, String recordedTotal,
                           boolean completeReportedUsage, List<String> warnings) {}
    private final Path root;
    private final Source source;
    public UsageReport(Path root, Source source) {
        try { this.root = root.toRealPath(); } catch (Exception e) { throw new IllegalArgumentException("작업 폴더를 확인하세요."); }
        this.source = Objects.requireNonNull(source);
    }
    public List<String> list() {
        Path directory = root.resolve(".oml/runs");
        try {
            if (!UsageFiles.check(root, directory, 0)) { return List.of(); }
            var ids = new ArrayList<String>();
            try (var entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    if (ids.size() >= 500) { throw new IllegalArgumentException("기록이 500개를 넘습니다. usage <실행ID>로 조회하세요."); }
                    validateId(entry.getFileName().toString());
                    if (!UsageFiles.check(root, entry, 0) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) { throw new java.io.IOException(); }
                    ids.add(entry.getFileName().toString());
                }
            }
            ids.sort(String::compareTo); return List.copyOf(ids);
        } catch (java.io.IOException e) { throw new IllegalArgumentException("프로젝트 기록 경로가 안전하지 않거나 읽을 수 없습니다."); }
    }
    public Question read(String runId) {
        validateId(runId);
        Path directory = root.resolve(".oml/runs/" + runId);
        try {
            // Lock an existing run only: no directory, settings, record or lock file is created by this query.
            if (!UsageFiles.check(root, directory.resolve("run.lock"), 1024)) { throw new java.io.IOException(); }
            try (var channel = FileChannel.open(directory.resolve("run.lock"), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lease = channel.tryLock()) {
                if (lease == null) { throw new java.io.IOException(); }
                for (String file : List.of("state.json", "state.json.bak", "events.jsonl")) {
                    UsageFiles.check(root, directory.resolve(file), 16 * 1024 * 1024);
                }
                var runs = new ManagedRunService(new GraphRunner(new GraphValidator()), new CheckpointStore(root,new CheckpointCodec()),
                        new EventLogStore(root,new RunEventCodec()),new HandoffStore(root),new RunLockManager(root));
                boolean logPresent=Files.isRegularFile(directory.resolve("events.jsonl"),LinkOption.NOFOLLOW_LINKS)
                        && Files.size(directory.resolve("events.jsonl"))>0;
                return aggregate(runs.inspect(runId),logPresent);
            }
        } catch (Exception failure) { throw new IllegalArgumentException("기록 조회 불가: " + runId + " (실행 중·손상·경로·형식 제한을 확인하세요.)"); }
    }
    private Question aggregate(RunInspection inspection,boolean logPresent) {
        var values = inspection.state().values();
        var models = new HashMap<String,String>();
        String goal;
        boolean workflow = values.containsKey("workflow.spec");
        if (workflow) {
            WorkflowSpec spec = PresetJson.decode(values.get("workflow.spec"),WorkflowSpec.class); goal = spec.goal();
            for (var step : spec.steps()) {
                if (step.type() == WorkflowStep.Type.EDIT) { models.put(step.id()+".writer", model(step.task())); }
            }
        } else {
            TaskSpec spec = PresetJson.decode(values.get("preset.task"),TaskSpec.class); goal = spec.goal(); models.put("writer",model(spec));
        }
        var calls = new LinkedHashMap<String,TransitionEvent>();
        int expectedStep = 1;
        for (var event : inspection.state().events()) {
            if (event.step() != expectedStep++) { throw new IllegalArgumentException("invalid history"); }
            if (models.containsKey(event.node().value())) {
                String id = AiInvocationId.forNode(inspection.runId(),new NodeId("writer"),event.step()-1);
                if (calls.putIfAbsent(id,event) != null) { throw new IllegalArgumentException("duplicate invocation"); }
            }
        }
        if (expectedStep-1 != inspection.state().executedSteps()) { throw new IllegalArgumentException("incomplete history"); }
        var evidence = new HashMap<String,Evidence>();
        var sessions = new HashMap<String,Integer>();
        calls.keySet().forEach(id -> {
            Evidence found = source.read(id); evidence.put(id,found);
            if (found != null && !found.sessionId().isBlank()) { sessions.merge(found.sessionId(),1,Integer::sum); }
        });
        var warnings = new ArrayList<String>();
        if(!logPresent) { warnings.add("실행 로그 누락: 중단·재실행 이력 확인 불가"); }
        long previousSequence=0;
        for (var event:inspection.events()) {
            if (!event.runId().equals(inspection.runId()) || event.sequence()<=previousSequence) { throw new IllegalArgumentException("mixed event history"); }
            previousSequence=event.sequence();
        }
        if (inspection.recoveredFromBackup() || inspection.ignoredIncompleteEventTail()) { warnings.add("복구·불완전 로그: 관찰되지 않은 호출 사용량이 있을 수 있음"); }
        if (inspection.phase() == CheckpointPhase.NODE_STARTED) { warnings.add("중단된 노드: 미기록 사용량 가능"); }
        Map<Integer,Long> starts = inspection.events().stream().filter(e -> e.type()==RunEventType.NODE_STARTED)
                .collect(java.util.stream.Collectors.groupingBy(RunEvent::executedSteps,java.util.stream.Collectors.counting()));
        if (starts.values().stream().anyMatch(count -> count > 1)) { warnings.add("노드 재실행 이력: 저장 결과 재사용 밖의 추가 소비는 확인 불가"); }
        if (inspection.events().stream().anyMatch(e->e.type()==RunEventType.NODE_STARTED && e.executedSteps()>=inspection.state().executedSteps())) {
            warnings.add("완료 전이가 없는 시작 이력: 추가 소비 확인 불가");
        }
        var attempts = new ArrayList<Attempt>();
        BigInteger[] sums = {BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO}; int measured = 0;
        for (var call : calls.entrySet()) {
            var event = call.getValue(); Evidence found = evidence.get(call.getKey());
            String problem = null;
            if (found == null || !found.tokens().available()) { problem = "저장 사용량 누락·조회 불가"; }
            else if (sessions.getOrDefault(found.sessionId(),0)>1) { problem = "동일 세션 중복: 포크·중복 여부 미확인"; }
            else if (BigInteger.valueOf(found.tokens().inputTokens()).add(BigInteger.valueOf(found.tokens().outputTokens()))
                    .compareTo(BigInteger.valueOf(event.metrics().usage())) != 0) { problem = "실행 이력과 저장 사용량 불일치"; }
            String[] counts = new String[4]; String total = null;
            if (problem == null) {
                long[] raw = {found.tokens().inputTokens(),found.tokens().cachedInputTokens(),found.tokens().outputTokens(),found.tokens().reasoningOutputTokens()};
                for (int i=0;i<4;i++) { counts[i]=Long.toString(raw[i]); sums[i]=sums[i].add(BigInteger.valueOf(raw[i])); }
                total=BigInteger.valueOf(raw[0]).add(BigInteger.valueOf(raw[2])).toString(); measured++;
            }
            attempts.add(new Attempt(call.getKey(),event.step(),event.node().value(),models.get(event.node().value()),
                    found==null ? "확인 불가" : found.response(),counts[0],counts[1],counts[2],counts[3],total,problem));
        }
        boolean complete = measured==calls.size() && warnings.isEmpty();
        if (complete && sums[0].add(sums[2]).compareTo(BigInteger.valueOf(inspection.policyState().usage())) != 0) {
            warnings.add("정책 누계와 호출 합계 불일치"); complete=false;
        }
        boolean known = measured>0 || (calls.isEmpty() && complete);
        return new Question(inspection.runId(),clean(goal),status(inspection,workflow,attempts),List.copyOf(attempts),
                known?sums[0].toString():null,known?sums[1].toString():null,known?sums[2].toString():null,
                known?sums[3].toString():null,known?sums[0].add(sums[2]).toString():null,complete,List.copyOf(warnings));
    }
    private static String status(RunInspection inspection, boolean workflow, List<Attempt> attempts) {
        if (inspection.state().status()==RunStatus.CANCELLED) { return "취소"; }
        if (inspection.approval()!=null && inspection.approval().decision()==ApprovalDecision.PENDING) { return "승인 대기"; }
        String state = inspection.state().values().getOrDefault(workflow?"workflow.status":"preset.status","RUNNING");
        if (inspection.state().status()==RunStatus.COMPLETED && inspection.state().currentNode().value().equals("succeeded")) { return "통과"; }
        if (inspection.state().status()==RunStatus.STEP_LIMIT_REACHED
                || !Set.of("CONTINUE","SUCCESS").contains(inspection.policyState().lastDecision().outcome().name())) { return "중단"; }
        if (!state.equals("RUNNING") && !state.equals("SUCCEEDED")) { return "실패·중단"; }
        return !attempts.isEmpty() && attempts.getLast().response().equals("SUCCESS") ? "완료 · 검증 전" : "진행 중";
    }
    private static String model(TaskSpec task) { return task.model()==null ? "실행기 설정 상속" : task.model(); }
    private static void validateId(String id) { if (id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")) { throw new IllegalArgumentException("올바른 실행 ID를 지정하세요."); } }
    public static String clean(String value) {
        return new SecretRedactor().redact(value,false).codePoints().collect(StringBuilder::new,
                (text,ch) -> text.appendCodePoint(Character.isISOControl(ch)||Character.getType(ch)==Character.FORMAT?' ':ch),StringBuilder::append).toString();
    }
}

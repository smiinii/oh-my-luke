package io.ohmyluke.cli;

import io.ohmyluke.usage.UsageReport;
import io.ohmyluke.preset.PresetJson;
import java.io.PrintStream;
import java.util.*;

/** All three views use one checked snapshot; no provider call and no export file is written implicitly. */
final class UsageCli {
    private final UsageReport reports;
    private final PrintStream out;
    UsageCli(UsageReport reports, PrintStream out) { this.reports=reports; this.out=out; }
    int execute(String[] args) {
        String run=null,format="table"; boolean details=false,seenFormat=false;
        for (int i=1;i<args.length;i++) {
            if (args[i].equals("--format") && !seenFormat && i+1<args.length) { format=args[++i];seenFormat=true; }
            else if (args[i].equals("--details") && !details) { details=true; }
            else if (!args[i].startsWith("-") && run==null) { run=args[i]; }
            else { return usage(); }
        }
        if (!Set.of("table","json","csv").contains(format)) { return usage(); }
        List<String> ids=run==null?reports.list():List.of(run);
        List<UsageReport.Question> questions=ids.stream().map(reports::read).toList();
        switch (format) {
            case "json" -> out.println(PresetJson.encode(new Export(1,"codex-exec-jsonl","provider-reported-only",
                    "unverified",false,questions)));
            case "csv" -> csv(questions,details);
            default -> table(questions,details);
        }
        return 0;
    }
    private int usage() { out.println("사용법: omluke usage [실행ID] [--format table|json|csv] [--details]"); return 2; }
    record Export(int schemaVersion,String source,String scope,String childUsageCoverage,boolean subscriptionDebit, List<UsageReport.Question> questions) {}
    private void table(List<UsageReport.Question> questions,boolean details) {
        out.println("OH MY LUKE · 이 프로젝트의 질문별 사용량");
        out.println("작업표 한 건 = 질문 한 건 · Codex가 보고하고 저장된 토큰만 집계");
        out.println("실행 ID | 질문 | 결과 | AI 시도 | 기록 토큰 | 대조");
        for (var q:questions) {
            out.println(q.runId()+" | "+shortText(q.question(),48)+" | "+q.status()+" | "+q.attempts().size()+" | "+value(q.recordedTotal())
                    +" | "+(q.completeReportedUsage()?"기록 일치":"부분·확인 불가"));
            if(details) {
                for(var a:q.attempts()) {
                    out.println("  단계 "+a.step()+" · "+a.node()+" · 요청 모델 "+a.requestedModel()+" · 응답 "+a.response());
                    out.println("    입력 "+value(a.input())+" (캐시 "+value(a.cachedInput())+") · 출력 "+value(a.output())
                            +" (추론 "+value(a.reasoningOutput())+") · 합계 "+value(a.total()));
                    if(a.problem()!=null) { out.println("    "+a.problem()); }
                }
            }
            q.warnings().forEach(w->out.println("  주의: "+w));
        }
        if(questions.isEmpty()) { out.println("저장된 질문이 없습니다."); }
        out.println("합계 = 입력 + 출력. 캐시·추론은 하위 항목이며 다시 더하지 않습니다.");
        out.println("부분 값은 전체 소비량이 아닙니다. 구독 차감률·자식 에이전트 포함 여부·외부 앱 사용량은 확인하지 않습니다.");
    }
    private void csv(List<UsageReport.Question> questions,boolean details) {
        if(details) {
            row("runId","invocationId","step","node","requestedModel","response","input","cachedInput","output","reasoningOutput","total","problem","completeReportedUsage","runWarnings","scope","childUsageCoverage");
            for(var q:questions) { for(var a:q.attempts()) { row(q.runId(),a.invocationId(),Integer.toString(a.step()),a.node(),a.requestedModel(),a.response(),
                    a.input(),a.cachedInput(),a.output(),a.reasoningOutput(),a.total(),a.problem(),Boolean.toString(q.completeReportedUsage()),
                    String.join("; ",q.warnings()),"provider-reported-only","unverified"); } }
        } else {
            row("runId","question","status","attempts","input","cachedInput","output","reasoningOutput","recordedTotal","completeReportedUsage","warnings","scope","childUsageCoverage");
            for(var q:questions) { row(q.runId(),q.question(),q.status(),Integer.toString(q.attempts().size()),q.input(),q.cachedInput(),q.output(),q.reasoningOutput(),
                    q.recordedTotal(),Boolean.toString(q.completeReportedUsage()),String.join("; ",q.warnings()),"provider-reported-only","unverified"); }
        }
    }
    private void row(String... values) { out.println(Arrays.stream(values).map(UsageCli::cell).collect(java.util.stream.Collectors.joining(","))); }
    private static String cell(String value) {
        if(value==null) { return ""; }
        String safe=UsageReport.clean(value);
        if(!safe.stripLeading().isEmpty() && "=+-@".indexOf(safe.stripLeading().charAt(0))>=0) { safe="'"+safe; }
        return "\""+safe.replace("\"","\"\"")+"\"";
    }
    private static String value(String value) { return value==null?"확인 불가":value; }
    private static String shortText(String text,int limit) { return text.codePointCount(0,text.length())<=limit?text:text.substring(0,text.offsetByCodePoints(0,limit))+"…"; }
}

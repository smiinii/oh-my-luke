package io.ohmyluke.preset;

import java.util.Objects;

/** Operator-authored step; only fields relevant to its type are accepted. */
public record WorkflowStep(String id, Type type, String onSuccess, String onFailure,
                           String file, ValidationSpec validation, TaskSpec task,
                           String prompt, Boolean approvalBeforeApply) {
    public enum Type { CHECK, EDIT, APPROVAL }

    public WorkflowStep {
        id = identifier(id);
        if (id.equals("succeeded") || id.equals("stopped")) { throw new IllegalArgumentException("reserved step id"); }
        Objects.requireNonNull(type, "type");
        approvalBeforeApply = Boolean.TRUE.equals(approvalBeforeApply);
        onSuccess = identifier(onSuccess);
        onFailure = identifier(onFailure);
        switch (type) {
            case CHECK -> {
                file = TaskSpec.relativeFile(file);
                Objects.requireNonNull(validation, "validation");
                if (task != null || prompt != null || approvalBeforeApply) { throw invalidFields(); }
            }
            case EDIT -> {
                Objects.requireNonNull(task, "task");
                if (file != null || validation != null || prompt != null) { throw invalidFields(); }
            }
            case APPROVAL -> {
                prompt = TaskSpec.text(prompt, 1_024, "approval prompt");
                if (file != null || validation != null || task != null || approvalBeforeApply
                        || !onFailure.equals("stopped")) { throw invalidFields(); }
            }
        }
        if (onFailure.equals("succeeded") || (type == Type.APPROVAL && onSuccess.equals("succeeded"))) {
            throw new IllegalArgumentException("success requires an objective successful check or edit");
        }
    }

    public static WorkflowStep check(String id, String file, ValidationSpec validation, String yes, String no) {
        return new WorkflowStep(id, Type.CHECK, yes, no, file, validation, null, null, false);
    }

    public static WorkflowStep edit(String id, TaskSpec task, boolean approval, String yes, String no) {
        return new WorkflowStep(id, Type.EDIT, yes, no, null, null, task, null, approval);
    }

    public static WorkflowStep approval(String id, String prompt, String next) {
        return new WorkflowStep(id, Type.APPROVAL, next, "stopped", null, null, null, prompt, false);
    }

    static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_-]{0,47}")) {
            throw new IllegalArgumentException("workflow id must be 1..48 ASCII letters, digits, underscore or hyphen");
        }
        return value;
    }

    private static IllegalArgumentException invalidFields() { return new IllegalArgumentException("fields do not match workflow step type"); }
}

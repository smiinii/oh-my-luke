package io.ohmyluke.policy;

/** Stable Korean UX copy for remembered approvals and project autonomy. */
public final class PermissionMessages {
    private PermissionMessages() {}

    public static String prompt(ToolPermissionRequest request) {
        return "OML이 " + request.target() + " 작업 권한을 요청합니다.\n\n"
                + "[이번만 허용]\n"
                + "[현재 작업 동안 허용]\n"
                + "[이 프로젝트에서 계속 허용]\n"
                + "[거부]";
    }

    public static String remembered(ToolPermissionRequest request, ApprovalScope scope) {
        return switch (scope) {
            case ONCE -> "이번 작업을 한 번 허용했습니다.";
            case RUN -> "현재 작업 동안 같은 대상의 작업을 묻지 않고 실행합니다.";
            case PROJECT -> "이 프로젝트에서 같은 대상의 작업을 앞으로 묻지 않고 실행합니다. "
                    + "다시 승인이 필요하도록 변경하려면 `omluke permissions reset`을 실행해 주세요.";
        };
    }

    public static String autonomousEnabled() {
        return "이 프로젝트에서 자율 실행 모드를 활성화했습니다. "
                + "앞으로 허용 가능한 작업은 묻지 않고 실행합니다. "
                + "다시 승인이 필요하도록 변경하려면 `omluke permissions reset`을 실행해 주세요.";
    }

    public static String reused(ToolPermissionRequest request) {
        return "이전에 저장한 프로젝트 권한에 따라 " + request.target() + " 작업을 승인 없이 실행했습니다.";
    }
}

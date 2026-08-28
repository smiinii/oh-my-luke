package io.ohmyluke.tool;

import io.ohmyluke.policy.ToolCapability;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit process input with bounded time, output, environment, and declared effect. */
public record ProcessToolRequest(
        String operationId,
        Path executable,
        List<String> arguments,
        Path workingDirectory,
        Map<String, String> environment,
        Duration timeout,
        int maxOutputBytes,
        ToolCapability capability,
        String permissionTarget) {
    private static final Set<ToolCapability> PROCESS_CAPABILITIES = Set.of(
            ToolCapability.LOCAL_PROCESS,
            ToolCapability.DEPENDENCY_INSTALL,
            ToolCapability.NETWORK_ACCESS,
            ToolCapability.EXTERNAL_WRITE,
            ToolCapability.DOCKER_ACCESS,
            ToolCapability.OUTSIDE_PROJECT_ACCESS,
            ToolCapability.SECRET_USE,
            ToolCapability.POLICY_MUTATION,
            ToolCapability.SANDBOX_BYPASS,
            ToolCapability.SECRET_DISCLOSURE,
            ToolCapability.PROTECTED_SYSTEM_DAMAGE);
    private static final Pattern SENSITIVE_ENVIRONMENT = Pattern.compile(
            ".*(TOKEN|SECRET|PASSWORD|PASSWD|API_KEY|APIKEY|AUTH|COOKIE|CREDENTIAL|PRIVATE_KEY).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_ARGUMENT_NAME = Pattern.compile(
            "(?i)^--?(token|secret|password|passwd|api[_-]?key|authorization|cookie|credential|private[_-]?key)(?:=.*)?$");
    private static final Pattern CREDENTIAL_PARAMETER = Pattern.compile(
            "(?i)(?:^|[?&;,\\s])(?:api[_-]?key|token|secret|password|passwd|authorization|auth|cookie|credential)\\s*=");
    private static final List<Pattern> SECRET_VALUES = List.of(
            Pattern.compile("gh[pousr]_[A-Za-z0-9_]{20,}"),
            Pattern.compile("sk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));

    public ProcessToolRequest {
        operationId = requireText(operationId, "operationId");
        Objects.requireNonNull(executable, "executable");
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("executable must be an absolute path");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            validateNul(argument, "argument");
            if (SENSITIVE_ARGUMENT_NAME.matcher(argument).matches()
                    || containsSecretValue(argument)
                    || containsCredentialSyntax(argument)) {
                throw new IllegalArgumentException("raw credentials are not accepted in process arguments");
            }
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        environment.forEach(ProcessToolRequest::validateEnvironment);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("timeout must be between zero and one hour");
        }
        if (maxOutputBytes <= 0 || maxOutputBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("maxOutputBytes must be between 1 and 16777216");
        }
        Objects.requireNonNull(capability, "capability");
        if (!PROCESS_CAPABILITIES.contains(capability)) {
            throw new IllegalArgumentException("unsupported process capability: " + capability);
        }
        permissionTarget = requireText(permissionTarget, "permissionTarget");
        if (containsSecretValue(permissionTarget) || containsCredentialSyntax(permissionTarget)) {
            throw new IllegalArgumentException("permissionTarget must not contain a raw credential");
        }
    }

    public ProcessToolRequest withCapability(ToolCapability newCapability, String newTarget) {
        return new ProcessToolRequest(
                operationId,
                executable,
                arguments,
                workingDirectory,
                environment,
                timeout,
                maxOutputBytes,
                newCapability,
                newTarget);
    }

    public ProcessToolRequest withEnvironment(Map<String, String> newEnvironment) {
        return new ProcessToolRequest(
                operationId,
                executable,
                arguments,
                workingDirectory,
                newEnvironment,
                timeout,
                maxOutputBytes,
                capability,
                permissionTarget);
    }

    public ProcessToolRequest withOutputLimit(int newLimit) {
        return new ProcessToolRequest(
                operationId,
                executable,
                arguments,
                workingDirectory,
                environment,
                timeout,
                newLimit,
                capability,
                permissionTarget);
    }

    public ProcessToolRequest withTimeout(Duration newTimeout) {
        return new ProcessToolRequest(
                operationId,
                executable,
                arguments,
                workingDirectory,
                environment,
                newTimeout,
                maxOutputBytes,
                capability,
                permissionTarget);
    }

    boolean networkRequested() {
        return capability == ToolCapability.NETWORK_ACCESS
                || capability == ToolCapability.DEPENDENCY_INSTALL
                || capability == ToolCapability.EXTERNAL_WRITE;
    }

    private static void validateEnvironment(String name, String value) {
        name = requireText(name, "environment name");
        value = Objects.requireNonNull(value, "environment value");
        validateNul(value, "environment value");
        boolean secretValue = false;
        for (Pattern pattern : SECRET_VALUES) {
            if (pattern.matcher(value).find()) {
                secretValue = true;
                break;
            }
        }
        if (SENSITIVE_ENVIRONMENT.matcher(name).matches() || secretValue) {
            throw new IllegalArgumentException("raw secret environment values are not accepted: " + name);
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("HOME") || upper.equals("PATH") || upper.equals("TMPDIR") || upper.equals("JAVA_TOOL_OPTIONS")) {
            throw new IllegalArgumentException("OML owns the process environment entry: " + name);
        }
    }

    private static boolean containsSecretValue(String value) {
        for (Pattern pattern : SECRET_VALUES) {
            if (pattern.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCredentialSyntax(String value) {
        if (CREDENTIAL_PARAMETER.matcher(value).find()) {
            return true;
        }
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            return false;
        }
        int start = scheme + 3;
        int end = value.length();
        for (char delimiter : new char[] {'/', '?', '#'}) {
            int index = value.indexOf(delimiter, start);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        return value.substring(start, end).contains("@");
    }

    private static void validateNul(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must not contain NUL");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no NUL");
        }
        return value;
    }
}

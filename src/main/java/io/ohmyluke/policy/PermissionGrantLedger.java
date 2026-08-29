package io.ohmyluke.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Operator-owned grant collection that atomically consumes one-time approvals. */
public final class PermissionGrantLedger {
    private final List<ToolPermissionGrant> grants;

    public PermissionGrantLedger(List<ToolPermissionGrant> grants) {
        this.grants = new ArrayList<>(List.copyOf(Objects.requireNonNull(grants, "grants")));
        this.grants.forEach(grant -> Objects.requireNonNull(grant, "grant"));
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (ToolPermissionGrant grant : this.grants) {
            if (!ids.add(grant.grantId())) {
                throw new IllegalArgumentException("duplicate grantId: " + grant.grantId());
            }
        }
    }

    public synchronized Optional<ToolPermissionGrant> consumeMatching(
            ToolPermissionRequest request,
            long nowEpochMilli) {
        Objects.requireNonNull(request, "request");
        for (int index = 0; index < grants.size(); index++) {
            ToolPermissionGrant grant = grants.get(index);
            if (!grant.matches(request, nowEpochMilli)) {
                continue;
            }
            if (grant.scope() == ApprovalScope.ONCE) {
                grants.remove(index);
            }
            return Optional.of(grant);
        }
        return Optional.empty();
    }

    public synchronized List<ToolPermissionGrant> snapshot() {
        return List.copyOf(grants);
    }

    public synchronized void add(ToolPermissionGrant grant) {
        Objects.requireNonNull(grant, "grant");
        if (grants.stream().anyMatch(existing -> existing.grantId().equals(grant.grantId()))) {
            throw new IllegalArgumentException("duplicate grantId: " + grant.grantId());
        }
        grants.add(grant);
    }

    public synchronized boolean pruneExpired(long nowEpochMilli) {
        return grants.removeIf(grant -> grant.expiresAtEpochMilli() <= nowEpochMilli);
    }

    public synchronized void clear() {
        grants.clear();
    }
}

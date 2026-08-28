package io.ohmyluke.state;

import io.ohmyluke.policy.ApprovalScope;
import io.ohmyluke.policy.PermissionChoice;
import io.ohmyluke.policy.PermissionGrantLedger;
import io.ohmyluke.policy.ToolPermission;
import io.ohmyluke.policy.ToolPermissionDecision;
import io.ohmyluke.policy.ToolPermissionEvaluator;
import io.ohmyluke.policy.ToolPermissionGrant;
import io.ohmyluke.policy.ToolPermissionPolicy;
import io.ohmyluke.policy.ToolPermissionRequest;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Persists project autonomy and remembered approvals while atomically consuming one-time grants. */
public final class ProjectPermissionManager implements ToolPermissionEvaluator {
    private final ProjectPermissionStore store;
    private final Clock clock;
    private final Supplier<String> grantIds;
    private ProjectPermissionSettings settings;

    public ProjectPermissionManager(ProjectPermissionStore store, Clock clock) {
        this(store, clock, () -> UUID.randomUUID().toString());
    }

    public ProjectPermissionManager(
            ProjectPermissionStore store,
            Clock clock,
            Supplier<String> grantIds) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.grantIds = Objects.requireNonNull(grantIds, "grantIds");
        this.settings = store.withExclusiveLock(this::loadAndPrune);
    }

    @Override
    public synchronized ToolPermissionDecision evaluate(ToolPermissionRequest request) {
        requireProject(request);
        return store.withExclusiveLock(() -> {
            settings = loadAndPrune();
            PermissionGrantLedger ledger = new PermissionGrantLedger(settings.grants());
            int before = ledger.snapshot().size();
            ToolPermissionDecision decision = new ToolPermissionPolicy(
                            ledger,
                            settings.autonomousProject(),
                            clock)
                    .evaluate(request);
            if (ledger.snapshot().size() != before) {
                settings = withGrants(settings, ledger.snapshot());
                store.save(settings);
            }
            return decision;
        });
    }

    public synchronized ToolPermissionGrant approve(
            ToolPermissionRequest request,
            PermissionChoice choice,
            long expiresAtEpochMilli) {
        requireProject(request);
        Objects.requireNonNull(choice, "choice");
        ToolPermissionDecision baseline = new ToolPermissionPolicy(
                        new PermissionGrantLedger(List.of()),
                        false,
                        clock)
                .evaluate(request);
        if (baseline.permission() == ToolPermission.DENY) {
            throw new IllegalArgumentException("a DENY invariant cannot receive an approval");
        }
        if (baseline.permission() == ToolPermission.ALLOW) {
            throw new IllegalArgumentException("an ALLOW request does not need a remembered approval");
        }
        if (choice == PermissionChoice.DENY) {
            return null;
        }
        if (expiresAtEpochMilli <= clock.millis()) {
            throw new IllegalArgumentException("approval expiry must be in the future");
        }
        String grantId = requireGrantId(grantIds.get());
        ToolPermissionGrant grant = switch (choice) {
            case ONCE -> ToolPermissionGrant.once(grantId, request, expiresAtEpochMilli);
            case RUN -> ToolPermissionGrant.forRun(grantId, request, expiresAtEpochMilli);
            case PROJECT -> ToolPermissionGrant.forProject(grantId, request, expiresAtEpochMilli);
            case DENY -> throw new IllegalStateException("DENY was already handled");
        };
        return store.withExclusiveLock(() -> {
            settings = loadAndPrune();
            PermissionGrantLedger ledger = new PermissionGrantLedger(settings.grants());
            ledger.add(grant);
            settings = withGrants(settings, ledger.snapshot());
            store.save(settings);
            return grant;
        });
    }

    public synchronized void setAutonomousProject(boolean enabled) {
        store.withExclusiveLock(() -> {
            settings = loadAndPrune();
            settings = new ProjectPermissionSettings(
                    ProjectPermissionSettings.CURRENT_SCHEMA_VERSION,
                    store.projectRoot(),
                    enabled,
                    settings.grants());
            store.save(settings);
            return null;
        });
    }

    public synchronized void reset() {
        store.withExclusiveLock(() -> {
            settings = ProjectPermissionSettings.defaults(store.projectRoot());
            store.save(settings);
            return null;
        });
    }

    public synchronized ProjectPermissionSettings settings() {
        return store.withExclusiveLock(() -> settings = loadAndPrune());
    }

    private ProjectPermissionSettings loadAndPrune() {
        ProjectPermissionSettings loaded = store.load();
        PermissionGrantLedger ledger = new PermissionGrantLedger(loaded.grants());
        if (!ledger.pruneExpired(clock.millis())) {
            return loaded;
        }
        ProjectPermissionSettings pruned = withGrants(loaded, ledger.snapshot());
        store.save(pruned);
        return pruned;
    }

    private ProjectPermissionSettings withGrants(
            ProjectPermissionSettings source,
            List<ToolPermissionGrant> grants) {
        return new ProjectPermissionSettings(
                ProjectPermissionSettings.CURRENT_SCHEMA_VERSION,
                store.projectRoot(),
                source.autonomousProject(),
                grants);
    }

    private void requireProject(ToolPermissionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.projectRoot().equals(store.projectRoot())) {
            throw new IllegalArgumentException("permission request belongs to a different project");
        }
    }

    private static String requireGrantId(String value) {
        Objects.requireNonNull(value, "grantId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("grantId supplier returned a blank value");
        }
        return value;
    }
}

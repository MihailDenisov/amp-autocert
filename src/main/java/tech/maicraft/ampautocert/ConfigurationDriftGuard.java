package tech.maicraft.ampautocert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Local-only fail-closed gate for accepted identity/provider configuration. */
final class ConfigurationDriftGuard {
    private static final String BASELINE_FIELD = "enrollment.baseline";
    private static final String CONFIG_FIELD = "automodpack.configuration";
    private static final String STATE_FIELD = "enrollment.state";

    private final AutoCertConfig config;
    private final AutoModpackConfigSource autoModpack;
    private final StateAccess store;
    private Result stickyBlock;

    ConfigurationDriftGuard(AutoCertConfig config, AutoModpackConfigSource autoModpack,
                            StateAccess store) {
        this.config = config;
        this.autoModpack = autoModpack;
        this.store = store;
    }

    synchronized Result inspect() {
        if (stickyBlock != null) return stickyBlock;
        EnrollmentStateStore.State state;
        try {
            state = store.load();
        } catch (IOException | RuntimeException unavailable) {
            return Result.blocked(State.UNREADABLE, List.of(STATE_FIELD));
        }
        if (state.baseline().isEmpty()) {
            return Result.blocked(State.BASELINE_MISSING, List.of(BASELINE_FIELD));
        }
        ConfigurationBaseline accepted = state.baseline().orElseThrow();
        if (state.phase() == EnrollmentStateStore.Phase.BLOCKED_CONFIGURATION_DRIFT) {
            List<String> persisted = parseFields(state.detail());
            return Result.blocked(State.DRIFT, persisted.isEmpty() ? List.of(CONFIG_FIELD) : persisted);
        }
        if (state.phase() != EnrollmentStateStore.Phase.ACTIVE
                && state.phase() != EnrollmentStateStore.Phase.STAGING_VALIDATED) {
            String field = switch (state.phase()) {
                case MIGRATED_PENDING_STAGING, STAGING_FAILED -> "enrollment.stagingValidation";
                case BLOCKED_PREFLIGHT -> "enrollment.preflight";
                default -> "enrollment.phase";
            };
            return Result.blocked(State.ENROLLMENT_BLOCKED, List.of(field));
        }

        ConfigurationBaseline effective;
        ConfigurationBaseline onDisk;
        try {
            effective = ConfigurationBaseline.from(config, autoModpack.effective(config));
            onDisk = ConfigurationBaseline.from(config, autoModpack.onDisk(config));
        } catch (IOException | RuntimeException unavailable) {
            return blockAndPersist(accepted, State.UNREADABLE, List.of(CONFIG_FIELD));
        }

        List<String> loadedDifferences = effective.differences(onDisk);
        if (!loadedDifferences.isEmpty()) {
            return blockAndPersist(accepted, State.DRIFT, loadedDifferences);
        }
        List<String> acceptedDifferences = accepted.differences(effective);
        if (!acceptedDifferences.isEmpty()) {
            return blockAndPersist(accepted, State.DRIFT, acceptedDifferences);
        }
        return Result.ready();
    }

    synchronized void acceptAfterReconciliation(ConfigurationBaseline candidate) throws IOException {
        ConfigurationBaseline effective;
        ConfigurationBaseline onDisk;
        try {
            effective = ConfigurationBaseline.from(config, autoModpack.effective(config));
            onDisk = ConfigurationBaseline.from(config, autoModpack.onDisk(config));
        } catch (IOException | RuntimeException unavailable) {
            throw new ReconciliationConfigurationChangedException(List.of(CONFIG_FIELD), unavailable);
        }
        ArrayList<String> differences = new ArrayList<>(effective.differences(onDisk));
        differences.addAll(candidate.differences(effective));
        List<String> changed = safeFields(differences);
        if (!changed.isEmpty()) throw new ReconciliationConfigurationChangedException(changed, null);

        store.save(EnrollmentStateStore.State.of(EnrollmentStateStore.Phase.ACTIVE, candidate,
                "reconciled after staging validation"));
        // EnrollmentStateStore.save is the atomic commit point. Do not report failure after it returns.
        stickyBlock = null;
    }

    synchronized void recordReconciliationFailure(EnrollmentStateStore.Phase phase, List<String> fields) {
        if (phase != EnrollmentStateStore.Phase.BLOCKED_CONFIGURATION_DRIFT
                && phase != EnrollmentStateStore.Phase.BLOCKED_PREFLIGHT
                && phase != EnrollmentStateStore.Phase.STAGING_FAILED) {
            throw new IllegalArgumentException("Unsupported reconciliation failure phase");
        }
        Result blocked = Result.blocked(State.ENROLLMENT_BLOCKED, fields);
        try {
            EnrollmentStateStore.State current = store.load();
            if (current.baseline().isPresent()) {
                store.save(EnrollmentStateStore.State.of(phase, current.baseline().orElseThrow(),
                        String.join(", ", blocked.changedFields())));
            } else {
                stickyBlock = blocked;
            }
        } catch (IOException | RuntimeException persistenceFailure) {
            ArrayList<String> reported = new ArrayList<>(blocked.changedFields());
            reported.add(STATE_FIELD);
            stickyBlock = Result.blocked(State.UNREADABLE, reported);
        }
    }

    private Result blockAndPersist(ConfigurationBaseline accepted, State blockedState, List<String> fields) {
        Result blocked = Result.blocked(blockedState, fields);
        try {
            store.save(EnrollmentStateStore.State.of(EnrollmentStateStore.Phase.BLOCKED_CONFIGURATION_DRIFT,
                    accepted, String.join(", ", blocked.changedFields())));
        } catch (IOException | RuntimeException persistenceFailure) {
            ArrayList<String> reported = new ArrayList<>(blocked.changedFields());
            reported.add(STATE_FIELD);
            stickyBlock = Result.blocked(State.UNREADABLE, reported);
            return stickyBlock;
        }
        return blocked;
    }

    private static List<String> parseFields(String detail) {
        if (detail == null || detail.isBlank() || detail.equals("none")) return List.of();
        return java.util.Arrays.stream(detail.split(","))
                .map(String::strip).filter(value -> value.matches("[A-Za-z][A-Za-z0-9_.]{0,79}"))
                .distinct().toList();
    }

    static List<String> safeFields(List<String> fields) {
        if (fields == null) return List.of();
        return fields.stream().filter(java.util.Objects::nonNull)
                .filter(value -> value.matches("[A-Za-z][A-Za-z0-9_.]{0,79}"))
                .distinct().toList();
    }

    enum State { READY, BASELINE_MISSING, ENROLLMENT_BLOCKED, DRIFT, UNREADABLE }

    static final class ReconciliationConfigurationChangedException extends IOException {
        private final List<String> changedFields;

        ReconciliationConfigurationChangedException(List<String> changedFields, Throwable cause) {
            super("Configuration changed during reconciliation", cause);
            this.changedFields = safeFields(changedFields);
        }

        List<String> changedFields() { return changedFields; }
    }

    interface StateAccess {
        EnrollmentStateStore.State load() throws IOException;
        void save(EnrollmentStateStore.State state) throws IOException;
    }

    record Result(State state, List<String> changedFields) {
        Result { changedFields = safeFields(changedFields); }
        static Result ready() { return new Result(State.READY, List.of()); }
        static Result blocked(State state, List<String> fields) {
            List<String> safe = safeFields(fields);
            return new Result(state, safe.isEmpty() ? List.of(CONFIG_FIELD) : safe);
        }
        boolean allowed() { return state == State.READY; }
    }
}

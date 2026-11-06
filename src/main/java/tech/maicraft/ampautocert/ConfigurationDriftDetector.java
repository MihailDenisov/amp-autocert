package tech.maicraft.ampautocert;

import java.util.List;

/** Pure semantic drift comparison. Unrelated AutoModpack fields never enter the baseline. */
final class ConfigurationDriftDetector {
    private ConfigurationDriftDetector() { }

    static Result compare(ConfigurationBaseline accepted, ConfigurationBaseline current) {
        List<String> fields = accepted.differences(current);
        return new Result(fields.isEmpty(), fields);
    }

    record Result(boolean stable, List<String> changedFields) {
        Result { changedFields = List.copyOf(changedFields); }
        String safeSummary() { return stable ? "none" : String.join(", ", changedFields); }
    }
}

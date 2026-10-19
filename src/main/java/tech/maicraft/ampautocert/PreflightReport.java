package tech.maicraft.ampautocert;

import java.util.List;

/** Immutable, secret-free result of the read-only doctor checks. */
record PreflightReport(List<Check> checks) {
    PreflightReport {
        checks = List.copyOf(checks);
        if (checks.isEmpty()) throw new IllegalArgumentException("Preflight report must contain checks");
    }

    boolean passed() {
        return checks.stream().noneMatch(check -> check.level() == Level.FAIL);
    }

    List<String> format() {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        lines.add("amp-autocert doctor: " + (passed() ? "PASS" : "BLOCKED"));
        for (Check check : checks) {
            lines.add("[" + check.level().label + "] " + check.name() + ": " + check.detail());
        }
        return List.copyOf(lines);
    }

    enum Level {
        PASS("PASS"), WARNING("WARN"), FAIL("FAIL");
        private final String label;
        Level(String label) { this.label = label; }
    }

    record Check(Level level, String name, String detail) {
        Check {
            if (level == null) throw new IllegalArgumentException("Preflight level is required");
            name = safe(name, "check name", 48);
            detail = safe(detail, "check detail", 240);
        }

        private static String safe(String value, String field, int maximum) {
            if (value == null) throw new IllegalArgumentException("Missing " + field);
            String normalized = value.strip();
            if (normalized.isEmpty() || normalized.length() > maximum
                    || normalized.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Unsafe " + field);
            }
            return normalized;
        }
    }
}

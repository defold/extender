package com.defold.extender.remote.operator;

import java.util.Locale;
import java.util.Optional;

/** Builder phase as reported by the touch API (see OperatorInstanceController). */
public enum Phase {
    COLD, WAKING, READY, DRAINING;

    /** Parses the API spelling ({@code "Ready"}, ...); empty for anything unknown. */
    static Optional<Phase> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

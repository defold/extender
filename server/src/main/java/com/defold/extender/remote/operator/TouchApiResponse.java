package com.defold.extender.remote.operator;

import java.time.Duration;
import java.util.Optional;

/** Outcome of one touch API call, already classified for the controller's decisions. */
public sealed interface TouchApiResponse {

    /** 200/202 with a parseable body. */
    record Ok(BuilderState state) implements TouchApiResponse {
    }

    /** Anything else. {@code status} is 0 when no HTTP response was received. */
    record Failure(Kind kind, int status, String detail, Optional<Duration> retryAfter) implements TouchApiResponse {

        static Failure of(Kind kind, int status, String detail) {
            return new Failure(kind, status, detail, Optional.empty());
        }
    }

    enum Kind {
        /** 401: our token isn't (yet) accepted — treated like an unreachable operator (C1 rotation). */
        UNAUTHORIZED,
        /** 404: the operator has no such builder — a configuration mismatch, retrying won't help. */
        UNKNOWN_BUILDER,
        /** Other 4xx, unexpected status or body: a bug on one side, retrying won't help. */
        REJECTED,
        /** 429/500/503 (not_leader, store_timeout): the operator is there but can't answer now. */
        RETRYABLE,
        /** No HTTP response (refused, timeout, DNS). */
        UNREACHABLE;

        /** Whether waiting and retrying can change the answer. */
        boolean transientFailure() {
            return this != UNKNOWN_BUILDER && this != REJECTED;
        }
    }
}

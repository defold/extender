package com.defold.extender.remote.operator;

import java.util.Objects;

/** What the operator says about a builder: its phase and whether it is always on. */
public record BuilderState(Phase phase, boolean alwaysOn) {

    public BuilderState {
        Objects.requireNonNull(phase, "phase");
    }

    /** Builds can be sent to it right now. */
    boolean warm() {
        return alwaysOn || phase == Phase.READY;
    }
}

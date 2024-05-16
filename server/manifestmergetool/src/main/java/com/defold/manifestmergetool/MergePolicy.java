package com.defold.manifestmergetool;

public enum MergePolicy {
    KEEP,
    MERGE,
    REPLACE;

    public static MergePolicy fromString(String s) {
        switch (s) {
        case "keep":
            return MergePolicy.KEEP;
        case "replace":
            return MergePolicy.REPLACE;
        default:
            return MergePolicy.MERGE;
        }
    }
}

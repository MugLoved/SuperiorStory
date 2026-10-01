package com.mugloved.superiorstory.api;

/** Optional author label on a dialogue choice; {@link #NONE} unless the JSON says {@code "outcome": "good"} or {@code "bad"}. */
public enum DialogueOutcome {
    NONE, GOOD, BAD;

    public static DialogueOutcome parse(String value) {
        return switch (value) {
            case "good" -> GOOD;
            case "bad" -> BAD;
            case "none" -> NONE;
            default -> throw new IllegalArgumentException("outcome must be good, bad, or none");
        };
    }
}

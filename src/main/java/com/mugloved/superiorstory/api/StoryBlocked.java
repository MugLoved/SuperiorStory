package com.mugloved.superiorstory.api;

import java.util.Map;

/**
 * Thrown by an action or line kind that cannot go ahead for a reason the player should hear (a quest on cooldown, a
 * time-of-day gate). The engine plays the choice's {@code blocked} branch with {@code vars} set, or the default
 * localized line when none is authored, and ends the conversation.
 */
public final class StoryBlocked extends RuntimeException {
    private final String reasonKey;
    private final Map<String, String> vars;

    /** @param reasonKey a lang key; {@code vars} become conversation variables ({@code blocked} and {@code cooldown} are set by Story) */
    public StoryBlocked(String reasonKey, Map<String, String> vars) {
        super(reasonKey, null, false, false);
        this.reasonKey = reasonKey;
        this.vars = Map.copyOf(vars);
    }

    public String reasonKey() { return reasonKey; }
    public Map<String, String> vars() { return vars; }
}

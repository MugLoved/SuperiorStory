package com.mugloved.superiorstory.api;

import java.util.Locale;

/** Where a player is in a quest; later stages come later. {@link #key()} is the word authors write in JSON. */
public enum StoryQuestStage {
    /** An NPC named the structure to the player. */
    OFFERED,
    /** The player accepted it. */
    ACCEPTED,
    /** The player has been within range of the structure. */
    VISITED,
    /** A quest boss died and the player contributed to the kill. */
    BOSS_DEFEATED,
    /** The player carries the quest item. Derived from the inventory, never stored. */
    COLLECTED,
    /** The player reported back. */
    TURNED_IN;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** ACCEPTED, VISITED, or BOSS_DEFEATED: stored stages of a quest in progress. */
    public boolean active() {
        return this == ACCEPTED || this == VISITED || this == BOSS_DEFEATED;
    }

    public static StoryQuestStage byKey(String key) {
        for (StoryQuestStage stage : values()) {
            if (stage.key().equals(key)) return stage;
        }
        return null;
    }
}

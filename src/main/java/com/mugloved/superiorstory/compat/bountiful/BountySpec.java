package com.mugloved.superiorstory.compat.bountiful;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The compiled {@code bounty} line value. Every field is optional: {@code decrees} (a decree ID, an array of them, or
 * {@code "*"} for every loaded decree; absent means the speaking villager's profession), {@code rep} (the reputation and
 * giver ID; absent means the speaking NPC) and {@code refresh} (ticks before an untaken offer is replaced). No Bountiful
 * types appear here, so tests can parse it without the mod.
 *
 * @param decrees decree IDs, or null for the derived default; ignored when {@code all}
 */
public record BountySpec(@Nullable List<String> decrees, boolean all, @Nullable String rep, long refresh, String where) {
    public static final long DEFAULT_REFRESH = 24_000L;
    private static final Set<String> FIELDS = Set.of("decrees", "rep", "refresh");

    public static BountySpec parse(JsonElement value, String where) {
        if (!value.isJsonObject()) throw new IllegalArgumentException("bounty must be an object");
        JsonObject object = value.getAsJsonObject();
        for (String key : object.keySet()) {
            if (!FIELDS.contains(key)) throw new IllegalArgumentException("Unknown bounty field: " + key);
        }
        List<String> decrees = null;
        boolean all = false;
        if (object.has("decrees")) {
            JsonElement element = object.get("decrees");
            if (isString(element) && element.getAsString().equals("*")) {
                all = true;
            } else if (isString(element)) {
                decrees = List.of(element.getAsString());
            } else if (element.isJsonArray() && element.getAsJsonArray().size() > 0) {
                decrees = new ArrayList<>();
                for (JsonElement entry : element.getAsJsonArray()) {
                    if (!isString(entry)) throw new IllegalArgumentException("decrees entries must be decree IDs");
                    decrees.add(entry.getAsString());
                }
            } else {
                throw new IllegalArgumentException("decrees must be a decree ID, a non-empty array of them, or \"*\"");
            }
        }
        String rep = null;
        if (object.has("rep")) {
            if (!isString(object.get("rep")) || object.get("rep").getAsString().isBlank()) throw new IllegalArgumentException("rep must be a non-blank string");
            rep = object.get("rep").getAsString();
        }
        long refresh = DEFAULT_REFRESH;
        if (object.has("refresh")) {
            JsonElement element = object.get("refresh");
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber() || element.getAsLong() < 0) {
                throw new IllegalArgumentException("refresh must be a whole number of ticks, 0 or more");
            }
            refresh = element.getAsLong();
        }
        return new BountySpec(decrees == null ? null : List.copyOf(decrees), all, rep, refresh, where);
    }

    /** {@code true} for the accept action and the two conditions. */
    public static void requireTrue(JsonElement value, String key) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean() || !value.getAsBoolean()) {
            throw new IllegalArgumentException(key + " must be true");
        }
    }

    /** The turn-in reputation: {@code true} for the default (a null result) or {@code {"rep": amount}}. */
    @Nullable
    public static Integer parseTurnIn(JsonElement value) {
        if (value.isJsonPrimitive()) {
            requireTrue(value, "bounty_turn_in");
            return null;
        }
        if (!value.isJsonObject()) throw new IllegalArgumentException("bounty_turn_in must be true or an object");
        JsonObject object = value.getAsJsonObject();
        for (String key : object.keySet()) {
            if (!key.equals("rep")) throw new IllegalArgumentException("Unknown bounty_turn_in field: " + key);
        }
        if (!object.has("rep") || !object.get("rep").isJsonPrimitive() || !object.get("rep").getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("bounty_turn_in rep must be a whole number");
        }
        return object.get("rep").getAsInt();
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }
}

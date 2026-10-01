package com.mugloved.superiorstory.dialogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds the nearest structure the player has not been sent to yet while the text of the line plays. {@code found}
 * and {@code failed} are branch names or "end"; null means the next line, or the end for failed.
 * The target is at most one of {@code pool}, {@code structure}, {@code general}, or {@code quest}; with none, the
 * search uses the derived pool of every profiled structure a quest may target.
 *
 * @param structure one exact structure, {@code ns:*}, or {@code #tag}
 * @param pools     structure pool IDs
 * @param quest     a quest whose structure the search targets
 */
public record LocateSpec(int radiusBlocks, int minSize, Mode mode, @Nullable IdSelector structure, List<ResourceLocation> pools,
                         @Nullable ResourceLocation quest, String found, String failed, boolean waypoint) {
    public static final int DEFAULT_RADIUS = 4000;
    public static final int DEFAULT_MIN_SIZE = 24;

    public enum Mode { DEFAULT, STRUCTURE, POOL, GENERAL, QUEST }

    public LocateSpec {
        pools = List.copyOf(pools);
    }

    public static LocateSpec parse(JsonElement element, String where) {
        if (!element.isJsonObject()) throw new IllegalArgumentException(where + " must be an object");
        JsonObject object = element.getAsJsonObject();
        for (String key : object.keySet()) {
            if (key.equals("only")) throw new IllegalArgumentException("only was renamed to structure");
            if (!Set.of("radius", "min_size", "structure", "pool", "general", "quest", "found", "failed", "waypoint").contains(key)) {
                throw new IllegalArgumentException("Unknown field: " + key);
            }
        }
        int targets = 0;
        for (String key : List.of("structure", "pool", "general", "quest")) if (object.has(key)) targets++;
        if (targets > 1) throw new IllegalArgumentException("Use only one of structure, pool, general, quest");
        Mode mode = Mode.DEFAULT;
        IdSelector structure = null;
        List<ResourceLocation> pools = new ArrayList<>();
        ResourceLocation quest = null;
        if (object.has("structure")) {
            mode = Mode.STRUCTURE;
            structure = IdSelector.parse(string(object, "structure"));
        } else if (object.has("pool")) {
            mode = Mode.POOL;
            JsonElement value = object.get("pool");
            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                for (JsonElement entry : array) pools.add(id(entry, "pool"));
            } else {
                pools.add(id(value, "pool"));
            }
            if (pools.isEmpty()) throw new IllegalArgumentException("pool needs at least one pool ID");
        } else if (object.has("general")) {
            if (!object.get("general").isJsonPrimitive() || !object.get("general").getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("general must be a boolean");
            }
            if (object.get("general").getAsBoolean()) mode = Mode.GENERAL;
        } else if (object.has("quest")) {
            mode = Mode.QUEST;
            quest = id(object.get("quest"), "quest");
        }
        boolean waypoint = true;
        if (object.has("waypoint")) {
            if (!object.get("waypoint").isJsonPrimitive() || !object.get("waypoint").getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("waypoint must be a boolean");
            }
            waypoint = object.get("waypoint").getAsBoolean();
        }
        return new LocateSpec(integer(object, "radius", DEFAULT_RADIUS, 64, 30000), integer(object, "min_size", DEFAULT_MIN_SIZE, 0, 512),
            mode, structure, pools, quest, object.has("found") ? string(object, "found") : null,
            object.has("failed") ? string(object, "failed") : null, waypoint);
    }

    public List<String> branches() {
        List<String> branches = new ArrayList<>();
        if (found != null) branches.add(found);
        if (failed != null) branches.add(failed);
        return branches;
    }

    private static ResourceLocation id(JsonElement element, String key) {
        ResourceLocation id = element.isJsonPrimitive() ? ResourceLocation.tryParse(element.getAsString()) : null;
        if (id == null) throw new IllegalArgumentException(key + " must be a valid ID");
        return id;
    }

    private static int integer(JsonObject object, String key, int fallback, int min, int max) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be a number");
        int number = value.getAsInt();
        if (number < min || number > max) throw new IllegalArgumentException(key + " must be " + min + "-" + max);
        return number;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }
}

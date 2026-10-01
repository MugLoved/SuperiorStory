package com.mugloved.superiorstory.dialogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A pool of quest rewards ({@code data/<ns>/superiorstory/reward_pools/<name>.json}, the path is the pool ID):
 * {@code {"rewards": [{"give": {...}, "weight": 2, "text": "...", "if": {...}}, ...]}}. An entry is an action map like a quest
 * {@code reward}; {@code weight} (default 1) is its chance of being offered, {@code text} its label on the choice row (default
 * the first action's own description, such as the item given), {@code if} / {@code unless} decide at offer time whether it can
 * be offered at all.
 */
public record RewardPool(List<Entry> entries) {
    public static final int MAX_ENTRIES = 512;
    private static final Set<String> FIELDS = Set.of("weight", "text", "if", "unless");

    public RewardPool {
        entries = List.copyOf(entries);
    }

    public record Entry(int weight, @Nullable String text, Guard guard, List<Act> actions) {
        public Entry {
            actions = List.copyOf(actions);
        }

        /** The choice row label: the authored text, else the first action's reward description, else null. */
        @Nullable
        public String label() {
            if (text != null) return text;
            for (Act act : actions) {
                String var = act.action().rewardVar();
                if (var != null) return var;
            }
            return null;
        }
    }

    public static RewardPool parse(JsonElement element) {
        if (!element.isJsonObject()) throw new IllegalArgumentException("A reward pool is an object with a rewards array");
        JsonObject root = element.getAsJsonObject();
        for (String key : root.keySet()) if (!key.equals("rewards")) throw new IllegalArgumentException("Unknown reward pool field: " + key);
        if (!root.has("rewards") || !root.get("rewards").isJsonArray()) throw new IllegalArgumentException("rewards must be an array");
        JsonArray array = root.getAsJsonArray("rewards");
        if (array.isEmpty() || array.size() > MAX_ENTRIES) throw new IllegalArgumentException("rewards needs 1-" + MAX_ENTRIES + " entries");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) entries.add(entry(array.get(i), "rewards[" + i + "]"));
        return new RewardPool(entries);
    }

    private static Entry entry(JsonElement element, String where) {
        if (!element.isJsonObject()) throw new IllegalArgumentException(where + " must be an object of actions");
        JsonObject object = element.getAsJsonObject();
        int weight = 1;
        if (object.has("weight")) {
            JsonElement value = object.get("weight");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || value.getAsInt() < 1) {
                throw new IllegalArgumentException(where + ".weight must be a whole number of 1 or more");
            }
            weight = value.getAsInt();
        }
        String text = null;
        if (object.has("text")) {
            if (!object.get("text").isJsonPrimitive() || !object.get("text").getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(where + ".text must be a string");
            }
            text = object.get("text").getAsString();
        }
        List<Act> actions = Act.parseAll(object.entrySet(), FIELDS, Set.of(), where);
        if (actions.isEmpty()) throw new IllegalArgumentException(where + " needs at least one action");
        return new Entry(weight, text, Guard.parse(object, where), actions);
    }
}

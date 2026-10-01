package com.mugloved.superiorstory.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.scene.StoryScene.Text;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Compiled interchangeable text entries; conditions are evaluated whenever the text plays. */
public record LinePool(List<Entry> entries, boolean first, boolean replace) {
    public record Entry(Text text, int weight, Guard guard) {}

    public LinePool { entries = List.copyOf(entries); }

    public static LinePool parse(JsonElement json) {
        JsonObject root = root(json, "lines", true);
        String mode = root.has("mode") ? Dialogue.string(root, "mode") : "random";
        if (!Set.of("random", "first").contains(mode)) throw new IllegalArgumentException("mode must be random or first");
        return new LinePool(entries(root, "lines"), mode.equals("first"), replace(root));
    }

    static JsonObject root(JsonElement json, String field, boolean modes) {
        if (!json.isJsonObject()) throw new IllegalArgumentException(field + " pool must be an object");
        JsonObject root = json.getAsJsonObject();
        for (String key : root.keySet()) {
            if (!key.equals(field) && !key.equals("replace") && !(modes && key.equals("mode"))) {
                throw new IllegalArgumentException("Unknown pool field: " + key);
            }
        }
        return root;
    }

    static boolean replace(JsonObject root) {
        if (!root.has("replace")) return false;
        JsonElement value = root.get("replace");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("replace must be a boolean");
        return value.getAsBoolean();
    }

    static List<Entry> entries(JsonObject root, String field) {
        if (!root.has(field) || !root.get(field).isJsonArray()) throw new IllegalArgumentException(field + " must be an array");
        var array = root.getAsJsonArray(field);
        if (array.isEmpty() || array.size() > RewardPool.MAX_ENTRIES) throw new IllegalArgumentException(field + " needs 1-" + RewardPool.MAX_ENTRIES + " entries");
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            String where = field + "[" + i + "]";
            try {
                JsonElement value = array.get(i);
                if (value.isJsonObject() && !value.getAsJsonObject().has("translate")) {
                    JsonObject entry = value.getAsJsonObject();
                    for (String key : entry.keySet()) if (!Set.of("text", "weight", "if", "unless").contains(key)) {
                        throw new IllegalArgumentException("Unknown field: " + key);
                    }
                    int weight = 1;
                    if (entry.has("weight")) {
                        JsonElement number = entry.get("weight");
                        if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("weight must be a positive integer");
                        try { weight = number.getAsBigDecimal().intValueExact(); }
                        catch (ArithmeticException exception) { throw new IllegalArgumentException("weight must be a positive integer"); }
                        if (weight < 1) throw new IllegalArgumentException("weight must be a positive integer");
                    }
                    out.add(new Entry(Dialogue.text(entry.get("text")), weight, Guard.parse(entry, where)));
                } else out.add(new Entry(Dialogue.text(value), 1, Guard.EMPTY));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(where + ": " + exception.getMessage(), exception);
            }
        }
        return out;
    }

    /** -1 means no eligible text: the caller skips the line or substitutes an empty word. */
    public static int pick(List<Entry> entries, boolean first, StoryContext context, RandomSource random, int last) {
        List<Integer> eligible = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).guard().passes(context)) {
            eligible.add(i);
        }
        if (eligible.size() > 1) eligible.remove(Integer.valueOf(last));
        if (first && !eligible.isEmpty()) return eligible.get(0);
        long total = 0;
        for (int i : eligible) total += entries.get(i).weight();
        if (total == 0) return -1;
        long roll = (long) (random.nextDouble() * total);
        for (int i : eligible) {
            roll -= entries.get(i).weight();
            if (roll < 0) return i;
        }
        throw new IllegalStateException("Pool weight selection failed");
    }
}

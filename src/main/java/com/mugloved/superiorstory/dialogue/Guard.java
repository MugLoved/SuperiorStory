package com.mugloved.superiorstory.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Compiled {@code if} / {@code unless} conditions. Every {@code if} must pass and no {@code unless} may. */
public record Guard(List<Check> ifs, List<Check> unless) {
    public static final Guard EMPTY = new Guard(List.of(), List.of());

    public record Check(String key, StoryHooks.Condition condition) {
        boolean test(StoryContext context) {
            try {
                return condition.test(context);
            } catch (RuntimeException exception) {
                SuperiorStory.LOGGER.error("Story condition {} failed", key, exception);
                return false;
            }
        }
    }

    public Guard {
        ifs = List.copyOf(ifs);
        unless = List.copyOf(unless);
    }

    public boolean isEmpty() {
        return ifs.isEmpty() && unless.isEmpty();
    }

    public boolean passes(StoryContext context) {
        return failing(context) == null && !anyUnless(context);
    }

    private boolean anyUnless(StoryContext context) {
        for (Check check : unless) if (check.test(context)) return true;
        return false;
    }

    /** The first {@code if} that fails, or null when they all pass (an {@code unless} that matches is not reported here). */
    @Nullable
    public Check failing(StoryContext context) {
        for (Check check : ifs) if (!check.test(context)) return check;
        return null;
    }

    /** Compiles {@code if} and {@code unless} from an owner object; both are objects of condition keys. */
    public static Guard parse(JsonObject owner, String where) {
        return new Guard(checks(owner, "if", where), checks(owner, "unless", where));
    }

    private static List<Check> checks(JsonObject owner, String key, String where) {
        if (!owner.has(key)) return List.of();
        if (!owner.get(key).isJsonObject()) throw new IllegalArgumentException(where + "." + key + " must be an object of condition keys");
        List<Check> checks = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : owner.getAsJsonObject(key).entrySet()) {
            Function<JsonElement, StoryHooks.Condition> compiler = StoryHooks.condition(entry.getKey());
            if (compiler == null) throw new IllegalArgumentException(where + "." + key + ": unknown condition \"" + entry.getKey() + "\"");
            try {
                checks.add(new Check(entry.getKey(), compiler.apply(entry.getValue())));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(where + "." + key + "." + entry.getKey() + ": " + exception.getMessage());
            }
        }
        return checks;
    }
}

package com.mugloved.superiorstory.dialogue;

import com.google.gson.JsonElement;
import com.mugloved.superiorstory.api.StoryHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** A compiled action bound to the key it was authored under. */
public record Act(String key, StoryHooks.Action action) {
    /**
     * Compiles every entry of {@code owner} that is not in {@code known} as an action.
     *
     * @param extra additional accepted keys handled by the caller (line kinds)
     */
    public static List<Act> parseAll(Iterable<Map.Entry<String, JsonElement>> entries, Set<String> known, Set<String> extra,
                                     String where) {
        List<Act> actions = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : entries) {
            String key = entry.getKey();
            if (known.contains(key) || extra.contains(key)) continue;
            Function<JsonElement, StoryHooks.Action> compiler = StoryHooks.action(key);
            if (compiler == null) throw new IllegalArgumentException(where + ": unknown field or action \"" + key + "\"");
            try {
                actions.add(new Act(key, compiler.apply(entry.getValue())));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(where + "." + key + ": " + exception.getMessage());
            }
        }
        return actions;
    }
}

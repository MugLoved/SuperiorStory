package com.mugloved.superiorstory.dialogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the author knows about a structure. Every field but {@code structures} is optional, and profiles for a whole mod
 * ({@code "ns:*"}) can supply defaults that a specific structure's profile overrides.
 *
 * @param bosses the first entry is the one dialogue names as {boss}; every entry counts toward "defeated the boss"
 * @param quest  false keeps the structure from ever being offered as a quest target
 */
public record StructureProfile(List<IdSelector> structures, String name, String look, String lore,
                               List<String> keywords, List<ResourceLocation> bosses, Boolean quest) {
    private static final int MAX_FIELD = 280;

    /** The combined view for one structure. */
    public record Merged(String name, String look, String lore, List<String> keywords, List<ResourceLocation> bosses, boolean quest) {
        public static final Merged EMPTY = new Merged(null, null, null, List.of(), List.of(), true);
    }

    public StructureProfile {
        structures = List.copyOf(structures);
        keywords = List.copyOf(keywords);
        bosses = List.copyOf(bosses);
    }

    public static List<StructureProfile> parseFile(JsonElement root) {
        List<StructureProfile> out = new ArrayList<>();
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) out.add(parse(element));
        } else {
            out.add(parse(root));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Profile file is empty");
        return out;
    }

    public static StructureProfile parse(JsonElement element) {
        if (!element.isJsonObject()) throw new IllegalArgumentException("Profile must be an object");
        JsonObject object = element.getAsJsonObject();
        for (String key : object.keySet()) {
            if (!Set.of("structures", "name", "look", "lore", "keywords", "boss", "quest").contains(key)) {
                throw new IllegalArgumentException("Unknown profile field: " + key);
            }
        }
        List<IdSelector> selectors = new ArrayList<>();
        for (String raw : strings(object, "structures", true)) selectors.add(IdSelector.parse(raw));
        if (selectors.isEmpty()) throw new IllegalArgumentException("structures needs at least one entry");
        List<ResourceLocation> bosses = new ArrayList<>();
        for (String raw : strings(object, "boss", false)) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null) throw new IllegalArgumentException("Invalid boss entity ID: " + raw);
            bosses.add(id);
        }
        Boolean quest = null;
        if (object.has("quest")) {
            if (!object.get("quest").isJsonPrimitive() || !object.get("quest").getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("quest must be a boolean");
            }
            quest = object.get("quest").getAsBoolean();
        }
        return new StructureProfile(selectors, text(object, "name"), text(object, "look"), text(object, "lore"),
            strings(object, "keywords", false), bosses, quest);
    }

    /** Combines every profile matching {@code structure}, least specific first so specific ones override. */
    public static Merged merge(List<StructureProfile> profiles, ResourceLocation structure, Predicate<ResourceLocation> inTag) {
        record Hit(int specificity, StructureProfile profile) {}
        List<Hit> hits = new ArrayList<>();
        for (StructureProfile profile : profiles) {
            int best = 0;
            for (IdSelector selector : profile.structures()) best = Math.max(best, selector.match(structure, inTag));
            if (best > 0) hits.add(new Hit(best, profile));
        }
        hits.sort((a, b) -> Integer.compare(a.specificity(), b.specificity()));
        String name = null, look = null, lore = null;
        Set<String> keywords = new LinkedHashSet<>();
        List<ResourceLocation> bosses = List.of();
        boolean quest = true;
        for (Hit hit : hits) {
            StructureProfile profile = hit.profile();
            if (profile.name() != null) name = profile.name();
            if (profile.look() != null) look = profile.look();
            if (profile.lore() != null) lore = profile.lore();
            keywords.addAll(profile.keywords());
            if (!profile.bosses().isEmpty()) bosses = profile.bosses();
            if (profile.quest() != null) quest = profile.quest();
        }
        return hits.isEmpty() ? Merged.EMPTY : new Merged(name, look, lore, List.copyOf(keywords), bosses, quest);
    }

    private static String text(JsonObject object, String key) {
        if (!object.has(key)) return null;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()
            || value.getAsString().length() > MAX_FIELD) {
            throw new IllegalArgumentException(key + " must be a string of 1-" + MAX_FIELD + " characters");
        }
        return value.getAsString().trim();
    }

    /** A string or an array of strings. */
    private static List<String> strings(JsonObject object, String key, boolean required) {
        if (!object.has(key)) {
            if (required) throw new IllegalArgumentException(key + " is required");
            return List.of();
        }
        JsonElement value = object.get(key);
        List<String> out = new ArrayList<>();
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            out.add(value.getAsString());
        } else if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (JsonElement entry : array) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException(key + " entries must be strings");
                }
                out.add(entry.getAsString());
            }
        } else {
            throw new IllegalArgumentException(key + " must be a string or an array of strings");
        }
        return out;
    }
}

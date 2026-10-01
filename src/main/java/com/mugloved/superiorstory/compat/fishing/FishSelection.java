package com.mugloved.superiorstory.compat.fishing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mugloved.superiorstory.dialogue.IdSelector;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The {@code fish} item source's value and its selection over a catalogue of guide fish. Pure (no Starcatcher types), so it
 * loads and tests without Starcatcher.
 *
 * @param rarities   allowed rarities; empty means any but trash
 * @param dimensions dimension selectors; empty means any
 * @param fluids     fluid selectors ({@code minecraft:water}, {@code minecraft:lava}, {@code minecraft:empty}); empty means any
 * @param kinds      distinct species per quest (1-3)
 * @param quantity   count of each species (1-16)
 * @param preferNew  prefer species the player has not caught yet
 */
public record FishSelection(Set<String> rarities, List<IdSelector> dimensions, List<IdSelector> fluids, int kinds, int quantity, boolean preferNew) {
    public static final Set<String> RARITIES = Set.of("common", "uncommon", "rare", "epic", "legendary");
    public static final int MAX_KINDS = 3;
    public static final int MAX_QUANTITY = 16;
    private static final Set<String> FIELDS = Set.of("rarity", "dimension", "fluid", "kinds", "quantity", "new");
    private static final ResourceLocation WATER = new ResourceLocation("minecraft", "water");

    /**
     * One guide fish.
     *
     * @param dimensions the dimensions its dimension restriction accepts; null when it has none (any dimension)
     * @param fluids     the fluids it bites in (water when it names none)
     * @param parts      the fact parts it has restrictions for ({@code where}, {@code when}, ...)
     * @param biomes     its biome restriction's biomes as selector strings; empty for none
     */
    public record Candidate(ResourceLocation entry, ResourceLocation item, String rarity, @Nullable Set<ResourceLocation> dimensions,
                            Set<ResourceLocation> fluids, Set<String> parts, List<String> biomes) {
        public Candidate {
            fluids = fluids.isEmpty() ? Set.of(WATER) : Set.copyOf(fluids);
            parts = Set.copyOf(parts);
            biomes = List.copyOf(biomes);
        }
    }

    public FishSelection {
        rarities = Set.copyOf(rarities);
        dimensions = List.copyOf(dimensions);
        fluids = List.copyOf(fluids);
    }

    public static FishSelection parse(JsonElement value) {
        if (!value.isJsonObject()) throw new IllegalArgumentException("fish must be an object");
        JsonObject object = value.getAsJsonObject();
        for (String key : object.keySet()) if (!FIELDS.contains(key)) throw new IllegalArgumentException("Unknown fish field: " + key);
        Set<String> rarities = new LinkedHashSet<>();
        if (object.has("rarity")) {
            for (String rarity : strings(object.get("rarity"), "rarity")) {
                if (!RARITIES.contains(rarity)) throw new IllegalArgumentException("fish rarity must be one of " + RARITIES + " (trash is never offered)");
                rarities.add(rarity);
            }
        }
        List<IdSelector> dimensions = new ArrayList<>();
        if (object.has("dimension")) {
            for (String id : strings(object.get("dimension"), "dimension")) {
                IdSelector selector = IdSelector.parse(id);
                if (selector.kind() == IdSelector.Kind.TAG) throw new IllegalArgumentException("fish dimension does not accept tags");
                dimensions.add(selector);
            }
        }
        List<IdSelector> fluids = new ArrayList<>();
        if (object.has("fluid")) for (String id : strings(object.get("fluid"), "fluid")) fluids.add(IdSelector.parse(id));
        int kinds = integer(object, "kinds", 1, MAX_KINDS);
        int quantity = integer(object, "quantity", 1, MAX_QUANTITY);
        boolean preferNew = true;
        if (object.has("new")) {
            if (!object.get("new").isJsonPrimitive() || !object.get("new").getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("fish new must be a boolean");
            preferNew = object.get("new").getAsBoolean();
        }
        return new FishSelection(rarities, dimensions, fluids, kinds, quantity, preferNew);
    }

    /** Whether a guide fish passes the filters. */
    public boolean eligible(Candidate fish) {
        if (!RARITIES.contains(fish.rarity())) return false;
        if (!rarities.isEmpty() && !rarities.contains(fish.rarity())) return false;
        if (!dimensions.isEmpty() && fish.dimensions() != null && fish.dimensions().stream().noneMatch(this::dimensionMatches)) return false;
        return fluids.isEmpty() || fish.fluids().stream().anyMatch(fluid -> fluids.stream().anyMatch(selector -> selector.match(fluid, tag -> false) > 0));
    }

    private boolean dimensionMatches(ResourceLocation dimension) {
        for (IdSelector selector : dimensions) if (selector.match(dimension, tag -> false) > 0) return true;
        return false;
    }

    /**
     * Picks {@link #kinds} species with distinct items: species the player has not caught first (when {@link #preferNew}), then
     * any eligible one. Empty when fewer eligible species exist than {@code kinds}.
     */
    public List<Candidate> pick(List<Candidate> catalogue, Set<ResourceLocation> caught, Random random) {
        List<Candidate> fresh = new ArrayList<>();
        List<Candidate> known = new ArrayList<>();
        for (Candidate fish : catalogue) {
            if (!eligible(fish)) continue;
            (preferNew && caught.contains(fish.entry()) ? known : fresh).add(fish);
        }
        Collections.shuffle(fresh, random);
        Collections.shuffle(known, random);
        fresh.addAll(known);
        List<Candidate> picked = new ArrayList<>();
        Set<ResourceLocation> items = new LinkedHashSet<>();
        for (Candidate fish : fresh) {
            if (picked.size() == kinds) break;
            if (items.add(fish.item())) picked.add(fish);
        }
        return picked.size() == kinds ? picked : List.of();
    }

    /** The fact part a restriction type belongs to. */
    public static String part(ResourceLocation type) {
        return switch (type.getPath()) {
            case "biome", "structure_restriction" -> "where";
            case "dimension" -> "dimension";
            case "elevation_restriction", "elevation_bias" -> "depth";
            case "fluid" -> "fluid";
            case "daytime_restriction", "daytime_bias", "weather_restriction", "moon_phase", "season" -> "when";
            case "bait" -> "bait";
            default -> "more";
        };
    }

    public static final List<String> PARTS = List.of("where", "dimension", "depth", "fluid", "when", "bait", "more");

    private static List<String> strings(JsonElement value, String key) {
        List<String> out = new ArrayList<>();
        for (JsonElement entry : value.isJsonArray() ? value.getAsJsonArray() : List.of(value)) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("fish " + key + " must be a string or an array of strings");
            out.add(entry.getAsString().trim());
        }
        if (out.isEmpty()) throw new IllegalArgumentException("fish " + key + " needs at least one entry");
        return out;
    }

    private static int integer(JsonObject object, String key, int min, int max) {
        if (!object.has(key)) return 1;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("fish " + key + " must be a number");
        int number = value.getAsInt();
        if (number < min || number > max) throw new IllegalArgumentException("fish " + key + " must be " + min + "-" + max);
        return number;
    }
}

package com.mugloved.superiorstory.compat.fishing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.mugloved.superiorstory.SuperiorStory;
import com.wdiscute.starcatcher.fish.FishApi;
import com.wdiscute.starcatcher.fish.FishProperties;
import com.wdiscute.starcatcher.registry.fishrestrictions.AbstractFishRestriction;
import com.wdiscute.starcatcher.registry.fishrestrictions.DimensionRestriction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The guide fish from the live Starcatcher fish registry, reduced to what the {@code fish} source filters on. Built once
 * per server registry state (server start and every datapack reload), never per pick. Story names no fish, biome, or rarity.
 */
final class FishCatalogue {
    private static volatile Object builtFor;
    private static volatile List<FishSelection.Candidate> fish = List.of();

    private FishCatalogue() {}

    static List<FishSelection.Candidate> get(MinecraftServer server) {
        Object key = server.getRecipeManager();   // replaced on every datapack reload, like the fish registry contents
        if (builtFor != key) {
            fish = build(server);
            builtFor = key;
        }
        return fish;
    }

    private static List<FishSelection.Candidate> build(MinecraftServer server) {
        List<FishSelection.Candidate> out = new ArrayList<>();
        var registry = server.registryAccess().registryOrThrow(com.wdiscute.starcatcher.Starcatcher.FISH_REGISTRY_KEY);
        for (FishProperties fp : FishApi.getFishes(server.registryAccess())) {
            if (!fp.hasGuideEntry() || fp.catchInfo().fish().isEmpty()) continue;
            ResourceLocation entry = registry.getKey(fp);
            if (entry == null) continue;
            Set<ResourceLocation> fluids = new LinkedHashSet<>();
            Set<String> parts = new LinkedHashSet<>();
            List<String> biomes = new ArrayList<>();
            boolean restrictedDimension = false;
            for (AbstractFishRestriction restriction : fp.restrictions()) {
                JsonObject json = encode(restriction);
                ResourceLocation type = json == null ? null : ResourceLocation.tryParse(json.has("type") ? json.get("type").getAsString() : "");
                if (type == null || type.getPath().equals("empty") || "hide".equals(restriction.translationOverride)) continue;
                parts.add(FishSelection.part(type));
                if (restriction instanceof DimensionRestriction) restrictedDimension = true;
                if (type.getPath().equals("fluid") && json.has("fluids")) {
                    for (JsonElement fluid : json.getAsJsonArray("fluids")) {
                        ResourceLocation id = ResourceLocation.tryParse(fluid.getAsString());
                        if (id != null) fluids.add(id);
                    }
                }
                if (type.getPath().equals("biome") && json.has("biomes")) {
                    for (JsonElement biome : json.getAsJsonArray("biomes")) if (biome.isJsonPrimitive()) biomes.add(biome.getAsString());
                }
            }
            out.add(new FishSelection.Candidate(entry, fp.catchInfo().fish().identifier(), fp.rarity().name().toLowerCase(Locale.ROOT),
                restrictedDimension ? dimensions(server, fp) : null, fluids, parts, biomes));
        }
        SuperiorStory.LOGGER.info("Fish catalogue: {} guide fish from Starcatcher", out.size());
        return List.copyOf(out);
    }

    /** The loaded dimensions whose level every dimension restriction of the fish accepts. */
    private static Set<ResourceLocation> dimensions(MinecraftServer server, FishProperties fp) {
        Set<ResourceLocation> accepted = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            boolean ok = true;
            for (AbstractFishRestriction restriction : fp.restrictions()) {
                if (restriction instanceof DimensionRestriction dimension
                    && dimension.adjustChance(0, level, fp, null, ItemStack.EMPTY, AbstractFishRestriction.Context.GUIDE_ENTRY) < 0) {
                    ok = false;
                }
            }
            if (ok) accepted.add(level.dimension().location());
        }
        return accepted;
    }

    private static JsonObject encode(AbstractFishRestriction restriction) {
        try {
            JsonElement json = AbstractFishRestriction.ABSTRACT_PROCESSOR_CODEC.encodeStart(JsonOps.INSTANCE, restriction).result().orElse(null);
            return json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}

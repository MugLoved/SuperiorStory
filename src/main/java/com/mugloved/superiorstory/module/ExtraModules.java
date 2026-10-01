package com.mugloved.superiorstory.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.dialogue.Vars;
import com.superior.lib.api.entity.BossTierApi;
import com.superior.lib.api.service.SuperiorServiceRegistry;
import com.mugloved.superiorstory.server.StoryServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.List;
import java.util.Set;

/**
 * Small modules that only need the registry: the {@code weather} and {@code moon} conditions, an NPC reputation counter
 * ({@code rep} action and condition, kept in the player's persisted data by author-chosen ID), and the {@code loot}
 * reward action that rolls any loot table.
 */
public final class ExtraModules {
    public static final int REP_MIN = -10;
    public static final int REP_MAX = 10;
    private static final String REP_KEY = "superiorstory_rep";
    private static final List<String> MOON = List.of("full_moon", "waning_gibbous", "third_quarter", "waning_crescent",
        "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous");

    private ExtraModules() {}

    public static void register() {
        StoryHooks.registerCondition("weather", value -> {
            String weather = string(value, "weather");
            if (!Set.of("clear", "rain", "thunder").contains(weather)) throw new IllegalArgumentException("weather must be clear, rain, or thunder");
            return context -> switch (weather) {
                case "clear" -> !context.level().isRaining();
                case "rain" -> context.level().isRaining();
                default -> context.level().isThundering();
            };
        });
        StoryHooks.registerCondition("moon", value -> {
            int phase = MOON.indexOf(string(value, "moon"));
            if (phase < 0) throw new IllegalArgumentException("moon must be one of " + MOON);
            return context -> context.level().getMoonPhase() == phase;
        });
        StoryHooks.registerCondition("boss_tier", value -> {
            JsonObject object = object(value, "boss_tier");
            for (String key : object.keySet()) if (!Set.of("min", "max").contains(key)) throw new IllegalArgumentException("Unknown boss_tier field: " + key);
            int min = object.has("min") ? number(object, "min") : 1;
            int max = object.has("max") ? number(object, "max") : 10;
            if (min > max) throw new IllegalArgumentException("boss_tier min is above max");
            return context -> {
                String boss = context.vars().get("boss");
                ResourceLocation id = boss != null && boss.startsWith(Vars.ENTITY) ? ResourceLocation.tryParse(boss.substring(Vars.ENTITY.length())) : null;
                BossTierApi tiers = id == null ? null : SuperiorServiceRegistry.getOptional(BossTierApi.class).orElse(null);
                int tier = tiers == null ? 0 : tiers.find(id).map(BossTierApi.BossTierEntry::tier).orElse(0);
                return tier >= min && tier <= max;
            };
        });
        StoryHooks.registerCondition("rep", value -> {
            RepSpec spec = RepSpec.parse(value, true);
            return context -> {
                String id = spec.id != null ? spec.id : speakerId(context);
                if (id == null) return false;
                int rep = reputation(context.player(), id);
                return rep >= spec.min && rep <= spec.max;
            };
        });
        StoryHooks.registerAction("rep", value -> {
            RepSpec spec = RepSpec.parse(value, false);
            return context -> {
                String id = spec.id != null ? spec.id : speakerId(context);
                if (id == null) {
                    SuperiorStory.LOGGER.warn("rep without an id needs a speaking NPC");
                    return;
                }
                addReputation(context.player(), id, spec.amount);
            };
        });
        StoryHooks.registerAction("loot", value -> {
            ResourceLocation id = ResourceLocation.tryParse(string(value, "loot"));
            if (id == null) throw new IllegalArgumentException("loot is not a valid loot table ID");
            return context -> roll(context.player(), id);
        });
    }

    /** {@code "rep": 2} (an amount, or for the condition a minimum) or {@code {"id": "wren", "amount": 2}}; without an id it is the speaking NPC's. */
    private record RepSpec(String id, int amount, int min, int max) {
        static RepSpec parse(JsonElement value, boolean condition) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                int number = number(value, "rep");
                if (!condition && number == 0) throw new IllegalArgumentException("rep amount must not be 0");
                if (condition) range(number, "min");
                return new RepSpec(null, number, number, REP_MAX);
            }
            JsonObject object = object(value, "rep");
            for (String key : object.keySet()) if (!Set.of("id", "amount", "min", "max").contains(key)) throw new IllegalArgumentException("Unknown rep field: " + key);
            if (condition && object.has("amount") || !condition && (object.has("min") || object.has("max"))) {
                throw new IllegalArgumentException(condition ? "rep condition uses min and max" : "rep action uses amount");
            }
            String id = object.has("id") ? string(object.get("id"), "id") : null;
            int amount = object.has("amount") ? number(object, "amount") : 1;
            if (!condition && amount == 0) throw new IllegalArgumentException("rep amount must not be 0");
            int min = object.has("min") ? number(object, "min") : REP_MIN;
            int max = object.has("max") ? number(object, "max") : REP_MAX;
            if (condition) { range(min, "min"); range(max, "max"); }
            if (min > max) throw new IllegalArgumentException("rep min is above max");
            return new RepSpec(id, amount, min, max);
        }

        private static void range(int value, String field) {
            if (value < REP_MIN || value > REP_MAX) throw new IllegalArgumentException("rep." + field + " must be " + REP_MIN + " to " + REP_MAX);
        }
    }

    /** The speaking NPC's name in lower case, the reputation ID when a scene names none; null with no speaking entity. */
    public static String speakerId(StoryContext context) {
        if (context.speakerEntity() == null) return null;
        String name = context.speakerEntity().getDisplayName().getString().trim().toLowerCase(java.util.Locale.ROOT);
        return name.isEmpty() ? null : name;
    }

    /** A player's reputation with the named NPC or group; 0 until they have earned some. */
    public static int reputation(ServerPlayer player, String id) {
        return reputation(StoryServer.persisted(player), id);
    }

    /** Adds to a player's reputation with the named NPC or group; the one writer of the stored reputation. */
    public static void addReputation(ServerPlayer player, String id, int amount) {
        addReputation(StoryServer.persisted(player), id, amount);
    }

    /** The persisted-tag boundary, shared by reads and writes; reads never migrate older saved values. */
    public static int reputation(CompoundTag data, String id) {
        return clampReputation(reps(data).getInt(id));
    }

    public static void addReputation(CompoundTag data, String id, int amount) {
        reps(data).putInt(id, clampReputation((long) reputation(data, id) + amount));
    }

    public static int clampReputation(long value) {
        return (int) Math.max(REP_MIN, Math.min(REP_MAX, value));
    }

    private static CompoundTag reps(CompoundTag data) {
        if (!data.contains(REP_KEY, 10)) data.put(REP_KEY, new CompoundTag());
        return data.getCompound(REP_KEY);
    }

    private static void roll(ServerPlayer player, ResourceLocation id) {
        ServerLevel level = player.serverLevel();
        LootTable table = level.getServer().getLootData().getLootTable(id);
        if (table == LootTable.EMPTY) {
            SuperiorStory.LOGGER.warn("loot references unknown or empty loot table {}", id);
            return;
        }
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, player.position())
            .withParameter(LootContextParams.THIS_ENTITY, player).create(LootContextParamSets.GIFT);
        for (ItemStack stack : table.getRandomItems(params)) {
            if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false);
        }
    }

    private static JsonObject object(JsonElement value, String key) {
        if (!value.isJsonObject()) throw new IllegalArgumentException(key + " must be an object");
        return value.getAsJsonObject();
    }

    private static int number(JsonObject object, String key) {
        return number(object.get(key), key);
    }

    private static int number(JsonElement value, String key) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be a whole number");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException exception) { throw new IllegalArgumentException(key + " must be a whole number"); }
    }

    private static String string(JsonElement value, String key) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be a string");
        return value.getAsString();
    }
}

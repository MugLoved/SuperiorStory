package com.mugloved.superiorstory.compat.bountiful;

import com.google.gson.JsonElement;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryBlocked;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.dialogue.Vars;
import com.mugloved.superiorstory.module.ExtraModules;
import com.mugloved.superiorstory.server.StorySceneLoader;
import com.mugloved.superiorstory.server.StoryServer;
import io.ejekta.bountiful.bounty.BountyData;
import io.ejekta.bountiful.bounty.BountyDataEntry;
import io.ejekta.bountiful.bounty.BountyInfo;
import io.ejekta.bountiful.bounty.BountyRarity;
import io.ejekta.bountiful.bounty.types.IBountyObjective;
import io.ejekta.bountiful.content.BountifulContent;
import io.ejekta.bountiful.content.BountyCreator;
import io.ejekta.bountiful.data.Decree;
import io.ejekta.bountiful.data.Pool;
import io.ejekta.bountiful.data.PoolEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC bounty givers over Bountiful, loaded only when Bountiful is present. Bountiful keeps generation, tracking, expiry, and
 * payout; Story owns the conversation, the pending-offer store, the giver link on the accepted stack, and the tier gate.
 * Module keys: the {@code bounty} line (offers, or routes to the state's template), the {@code bounty_accept} and
 * {@code bounty_turn_in} actions, and the {@code bounty_ready} and {@code bounty_active} conditions.
 */
public final class BountifulBridge {
    public static final int BOUNTIFUL_REP_SCALE = 3;

    public static int bountifulRep(int storyRep) {
        return ExtraModules.clampReputation(storyRep) * BOUNTIFUL_REP_SCALE;
    }
    /** Root key on the bounty stack naming its giver; Bountiful's data lives in a sub-tag, so this survives its writes. */
    static final String GIVER_TAG = "superiorstory_giver";
    /** Player persisted data: giver ID to {stack, time} of the offer waiting to be accepted. */
    private static final String STORE_KEY = "superiorstory_bounty";
    /** Conversation variable the line sets so the actions and conditions resolve the same giver. */
    private static final String GIVER_VAR = "bounty_giver";
    /** Objectives and rewards listed as variables; the dialogue packet carries at most {@link Vars#MAX_VARS} variables. */
    private static final int MAX_LISTED = 3;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private BountifulBridge() {}

    public static void register() {
        StoryHooks.registerLineKind("bounty", new BountyLine());
        StoryHooks.registerAction("bounty_accept", value -> {
            BountySpec.requireTrue(value, "bounty_accept");
            return BountifulBridge::accept;
        });
        StoryHooks.registerAction("bounty_turn_in", value -> {
            Integer amount = BountySpec.parseTurnIn(value);
            return context -> turnIn(context, amount);
        });
        StoryHooks.registerCondition("bounty_ready", value -> {
            BountySpec.requireTrue(value, "bounty_ready");
            return context -> held(context.player(), giver(context), true) != null;
        });
        StoryHooks.registerCondition("bounty_active", value -> {
            BountySpec.requireTrue(value, "bounty_active");
            return context -> held(context.player(), giver(context), false) != null;
        });
    }

    // ---------------------------------------------------------------- line

    private static final class BountyLine implements StoryHooks.LineKind {
        @Override
        public Object compile(JsonElement value, String where) {
            return BountySpec.parse(value, where);
        }

        @Override
        public Job start(StoryContext context, Object spec) {
            Result result = resolve(context, (BountySpec) spec);
            return new Job() {
                @Override public boolean poll(StoryContext ignored) { return true; }
                @Override public void cancel() {}
                @Override public Result result() { return result; }
            };
        }
    }

    /** Routes by state (turn in, active, none), else by the offer's first objective type; the offer is generated or reused. */
    private static StoryHooks.LineKind.Result resolve(StoryContext context, BountySpec spec) {
        ServerPlayer player = context.player();
        String giver = spec.rep() != null ? spec.rep() : ExtraModules.speakerId(context);
        if (giver == null) {
            warnOnce(spec.where(), "bounty line without a speaking NPC needs a rep ID");
            return route("none", Map.of());
        }
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put(GIVER_VAR, giver);
        ItemStack stack = held(player, giver, true);
        if (stack != null) return route("turn_in", describe(vars, player, stack));
        stack = held(player, giver, false);
        if (stack != null) return route("active", describe(vars, player, stack));

        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        CompoundTag store = store(player);
        ItemStack offer = ItemStack.EMPTY;
        if (store.contains(giver, 10)) {
            CompoundTag pending = store.getCompound(giver);
            long age = now - pending.getLong("time");
            if (age >= 0 && age < spec.refresh()) offer = ItemStack.of(pending.getCompound("stack"));
        }
        if (offer.isEmpty()) {
            offer = generate(context, spec, giver, level, now);
            if (offer.isEmpty()) {
                store.remove(giver);
                return route("none", vars);
            }
            CompoundTag pending = new CompoundTag();
            pending.put("stack", offer.save(new CompoundTag()));
            pending.putLong("time", now);
            store.put(giver, pending);
        }
        BountyData data = BountyData.Companion.get(offer);
        return route(objectiveRoute(data), describe(vars, player, offer));
    }

    private static StoryHooks.LineKind.Result route(String route, Map<String, String> vars) {
        String branch = "bounty_" + route;
        ResourceLocation dialogue = new ResourceLocation(SuperiorStory.MODID, "bounty/" + route);
        if (StorySceneLoader.dialogue(dialogue) == null) dialogue = new ResourceLocation(SuperiorStory.MODID, "bounty/generic");
        return new StoryHooks.LineKind.Result(true, vars, branch, null, dialogue);
    }

    /** {@code bountiful:entity} becomes {@code bountiful_entity}: the template name for an objective type. */
    private static String objectiveRoute(BountyData data) {
        ResourceLocation type = data.getObjectives().get(0).getLogicId();
        return type.getNamespace() + "_" + type.getPath().replace('/', '_');
    }

    // ---------------------------------------------------------------- offer generation

    private static ItemStack generate(StoryContext context, BountySpec spec, String giver, ServerLevel level, long now) {
        Set<Decree> decrees = decrees(context, spec);
        if (decrees.isEmpty()) return ItemStack.EMPTY;
        int rep = bountifulRep(ExtraModules.reputation(context.player(), giver));
        gate(decrees);
        Entity speaker = context.speakerEntity();
        BlockPos pos = speaker != null ? speaker.blockPosition() : context.player().blockPosition();
        ItemStack stack = BountyCreator.Companion.createBountyItem(level, pos, decrees, rep, now);
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        BountyData data = BountyData.Companion.get(stack);
        if (data.getRewards().isEmpty() || data.getObjectives().isEmpty()) return ItemStack.EMPTY;   // no reward this reputation may be offered
        stack.getOrCreateTag().putString(GIVER_TAG, giver);
        return stack;
    }

    /**
     * The tier cap: Bountiful's only hard reputation gate is a reward entry's {@code repRequired}, so raise every reward entry
     * of the selected decrees to at least its own rarity's reputation tier, so the rep passed to Bountiful caps the offered rarity. Idempotent, and applied to the live entries at offer
     * time so a Bountiful reload cannot race it.
     */
    private static void gate(Set<Decree> decrees) {
        for (Decree decree : decrees) {
            for (Pool pool : decree.getRewardPools()) {
                for (PoolEntry entry : pool.getItems()) {
                    entry.setRepRequired(Math.max(entry.getRepRequired(), entry.getRarity().getRepTier()));
                }
            }
        }
    }

    private static Set<Decree> decrees(StoryContext context, BountySpec spec) {
        BountifulContent content = BountifulContent.INSTANCE;
        if (spec.all()) return new LinkedHashSet<>(content.getDecrees());
        Set<String> ids = new LinkedHashSet<>();
        if (spec.decrees() != null) {
            ids.addAll(spec.decrees());
        } else {
            ResourceLocation profession = com.mugloved.superiorstory.module.CoreModules.professionOf(context.speakerEntity());
            if (profession != null) ids.add(profession.getPath());
        }
        Set<Decree> found = ids.isEmpty() ? Set.of() : content.getDecrees(ids);
        if (found.isEmpty()) warnOnce(spec.where(), "bounty has no loaded decree for " + (ids.isEmpty() ? "a non-villager speaker" : ids) + "; list decrees or use \"*\"");
        return found;
    }

    private static void warnOnce(String where, String message) {
        if (WARNED.add(where + message)) SuperiorStory.LOGGER.warn("{}: {}", where, message);
    }

    // ---------------------------------------------------------------- variables

    private static Map<String, String> describe(Map<String, String> vars, ServerPlayer player, ItemStack stack) {
        BountyData data = BountyData.Companion.get(stack);
        BountyInfo info = BountyInfo.Companion.get(stack);
        vars.put("bounty_rarity", Vars.LANG + "superiorstory.bounty.rarity." + info.getRarity().name().toLowerCase(java.util.Locale.ROOT));
        long left = info.timeLeft(player.serverLevel());
        vars.put("bounty_time", left <= 1 ? Vars.LANG + "superiorstory.bounty.no_limit" : Vars.TIME + left * 50L);
        vars.put("bounty_objectives", list(vars, "obj", player, data.getObjectives(), true));
        vars.put("bounty_rewards", list(vars, "rew", player, data.getRewards(), false));
        return vars;
    }

    /** Sets the per-entry variables and returns the joined summaries. */
    private static String list(Map<String, String> vars, String kind, ServerPlayer player, List<BountyDataEntry> entries, boolean objective) {
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            BountyDataEntry entry = entries.get(i);
            String summary = clip(entry.textSummary(player, objective).getString());
            summaries.add(summary);
            if (i >= MAX_LISTED) continue;
            String key = "bounty_" + kind + "_" + (i + 1);
            vars.put(key, summary);
            boolean registry = isRegistry(entry);
            vars.put(key + "_amount", registry ? String.valueOf(entry.getAmount()) : "");   // other types carry the amount in their own summary
            vars.put(key + "_name", registry ? name(entry) : clip(summary.replaceAll("\\s*\\(\\d+/\\d+\\)\\s*$", "")));
            if (objective) vars.put(key + "_have", String.valueOf((long) ((IBountyObjective) entry.getLogic()).getProgress(entry, player).getCurrent()));
        }
        return clip(String.join(", ", summaries));
    }

    /** An item or entity entry names a registry entry so the client shows the icon or localized name; any other type its own description. */
    private static String name(BountyDataEntry entry) {
        return (entry.getLogicId().toString().equals("bountiful:item") ? Vars.ITEM : Vars.ENTITY) + entry.getContent();
    }

    /** A plain item or entity entry, whose name the client can resolve and hover; anything else is described by its own summary. */
    private static boolean isRegistry(BountyDataEntry entry) {
        String type = entry.getLogicId().toString();
        return !entry.isMystery() && (type.equals("bountiful:item") || type.equals("bountiful:entity")) && ResourceLocation.tryParse(entry.getContent()) != null;
    }

    private static String clip(String text) {
        return text.length() > Vars.MAX_VALUE ? text.substring(0, Vars.MAX_VALUE) : text;
    }

    // ---------------------------------------------------------------- actions and conditions

    private static String giver(StoryContext context) {
        String giver = context.vars().get(GIVER_VAR);
        return giver != null ? giver : ExtraModules.speakerId(context);
    }

    private static CompoundTag store(ServerPlayer player) {
        CompoundTag data = StoryServer.persisted(player);
        if (!data.contains(STORE_KEY, 10)) data.put(STORE_KEY, new CompoundTag());
        return data.getCompound(STORE_KEY);
    }

    /** The first unexpired bounty from this giver in the inventory; {@code complete} also requires every objective finished. */
    @Nullable
    private static ItemStack held(ServerPlayer player, @Nullable String giver, boolean complete) {
        if (giver == null) return null;
        for (ItemStack stack : bountiesFrom(player, giver)) {
            if (BountyInfo.Companion.get(stack).timeLeft(player.serverLevel()) <= 0) continue;
            if (!complete || finished(player, stack)) return stack;
        }
        return null;
    }

    private static List<ItemStack> bountiesFrom(ServerPlayer player, String giver) {
        List<ItemStack> found = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() == BountifulContent.INSTANCE.getBOUNTY_ITEM() && stack.hasTag() && giver.equals(stack.getTag().getString(GIVER_TAG))) {
                found.add(stack);
            }
        }
        return found;
    }

    private static boolean finished(ServerPlayer player, ItemStack stack) {
        for (BountyDataEntry entry : BountyData.Companion.get(stack).getObjectives()) {
            if (!((IBountyObjective) entry.getLogic()).getProgress(entry, player).isComplete()) return false;
        }
        return true;
    }

    private static void accept(StoryContext context) {
        ServerPlayer player = context.player();
        String giver = giver(context);
        if (giver == null) return;
        if (held(player, giver, false) != null) throw new StoryBlocked("superiorstory.bounty.blocked.active", Map.of());
        CompoundTag store = store(player);
        ItemStack offer = store.contains(giver, 10) ? ItemStack.of(store.getCompound(giver).getCompound("stack")) : ItemStack.EMPTY;
        if (offer.isEmpty()) throw new StoryBlocked("superiorstory.bounty.blocked.gone", Map.of());
        BountyInfo info = BountyInfo.Companion.get(offer);
        info.setTimeStarted(player.serverLevel().getGameTime());   // the clock starts when the bounty is taken, not when it was offered
        BountyInfo.Companion.set(offer, info);
        store.remove(giver);
        if (!player.getInventory().add(offer)) player.drop(offer, false);
    }

    private static void turnIn(StoryContext context, @Nullable Integer amount) {
        ServerPlayer player = context.player();
        String giver = giver(context);
        if (giver == null) return;
        List<ItemStack> mine = bountiesFrom(player, giver);
        if (mine.isEmpty()) throw new StoryBlocked("superiorstory.bounty.blocked.none", Map.of());
        for (ItemStack stack : mine) {
            if (!finished(player, stack)) continue;
            BountyInfo info = BountyInfo.Companion.get(stack);
            if (info.timeLeft(player.serverLevel()) <= 0) throw new StoryBlocked("superiorstory.bounty.blocked.expired", Map.of());
            BountyRarity rarity = info.getRarity();
            if (!BountyData.Companion.get(stack).tryCashIn(player, stack)) throw new StoryBlocked("superiorstory.bounty.blocked.unfinished", Map.of());
            int rep = amount != null ? amount : rarity.ordinal() + 1;
            if (rep != 0) ExtraModules.addReputation(player, giver, rep);
            return;
        }
        throw new StoryBlocked("superiorstory.bounty.blocked.unfinished", Map.of());
    }
}

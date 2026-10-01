package com.mugloved.superiorstory.server;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryBlocked;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.api.StoryQuestEvent;
import com.mugloved.superiorstory.api.StoryQuestStage;
import com.mugloved.superiorstory.dialogue.Act;
import com.mugloved.superiorstory.dialogue.ItemSpec;
import com.mugloved.superiorstory.dialogue.QuestDef;
import com.mugloved.superiorstory.dialogue.StructureProfile;
import com.mugloved.superiorstory.dialogue.Vars;
import com.superior.lib.api.entity.AllianceQueryApi;
import com.superior.lib.api.entity.BossTierApi;
import com.superior.lib.api.service.SuperiorServiceRegistry;
import com.superior.lib.api.structure.KnownStructureStartsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quests, kept in the player's persisted data (per player, per world, surviving death). Each quest a player is sent on or
 * takes is one instance; an instance may name a quest file ({@link QuestDef}) or be an implicit structure quest (reach the
 * site, kill a profile boss, report back). A quest is never tied to a giver: any speaker whose dialogue offers the turn-in
 * takes it. Quests are done once per player unless the quest file is {@code repeatable}; allies (through Superior Lib)
 * near a player share accept and turn-in, and kill credit goes to everyone who helped kill the boss.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID)
public final class StoryQuests {
    private static final String KEY = "superiorstory_quest_instances";
    private static final int MAX_ENTRIES = 200;
    private static final int VISIT_RANGE = 48;
    private static final int FALLBACK_RADIUS = 160;
    private static final int BOSS_PAD = 32;
    private static final int SHARE_RADIUS = 16;
    private static final int CHECK_TICKS = 40;
    private static final int MAX_CREDIT = 16;

    /** Who hurt each tracked boss while it lives (players and the owners of their summons and pets). */
    private static final Map<UUID, Set<UUID>> CREDIT = new ConcurrentHashMap<>();

    /**
     * @param structure the found place (a structure, a biome, or another location kind's ID); {@value #NO_PLACE} for a fetch quest
     * @param kind      the location kind that found it; empty for a fetch quest
     */
    public record Instance(@Nullable ResourceLocation def, ResourceLocation structure, BlockPos position, String dimension,
                           StoryQuestStage stage, String giver, @Nullable BlockPos acceptPos, long turnedAt, boolean credit,
                           int pending, String kind) {}

    static final String NO_PLACE = "superiorstory:none";

    public enum State { AVAILABLE, ACTIVE, DONE, COOLDOWN, GATED }

    /** Whether a quest can be offered to a player now, and if not, why. */
    public record Availability(State state, String reasonKey, long cooldownMillis) {
        static final Availability OPEN = new Availability(State.AVAILABLE, "", 0);
    }

    /** Payload of the {@code quest} trigger. */
    public record QuestPayload(ResourceLocation questId, StoryQuestStage stage) {}

    private StoryQuests() {}

    // ---------------------------------------------------------------- storage

    static ListTag list(ServerPlayer player) {
        CompoundTag data = StoryServer.persisted(player);
        if (!data.contains(KEY, Tag.TAG_LIST)) data.put(KEY, new ListTag());
        return data.getList(KEY, Tag.TAG_COMPOUND);
    }

    static Instance read(CompoundTag tag) {
        StoryQuestStage[] stages = StoryQuestStage.values();
        int stage = Math.max(0, Math.min(stages.length - 1, tag.getByte("stage")));
        ResourceLocation def = tag.getString("def").isEmpty() ? null : ResourceLocation.tryParse(tag.getString("def"));
        BlockPos accept = tag.contains("ax") ? new BlockPos(tag.getInt("ax"), tag.getInt("ay"), tag.getInt("az")) : null;
        String id = tag.getString("id");
        return new Instance(def, new ResourceLocation(id.isEmpty() ? NO_PLACE : id), new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
            tag.getString("dim"), stages[stage], tag.getString("giver"), accept, tag.getLong("turned"), tag.getBoolean("credit"),
            QuestObjectives.pending(tag, def == null ? null : QuestDefinitions.get(def)), tag.contains("loc") ? tag.getString("loc") : "structure");
    }

    /** The stored tag of this player's active instance of the quest, or null. */
    @Nullable
    static CompoundTag activeTag(ServerPlayer player, ResourceLocation def) {
        ListTag list = list(player);
        for (int i = list.size() - 1; i >= 0; i--) {
            CompoundTag tag = list.getCompound(i);
            Instance instance = read(tag);
            if (def.equals(instance.def()) && instance.stage().active()) return tag;
        }
        return null;
    }

    public static List<Instance> all(ServerPlayer player) {
        List<Instance> entries = new ArrayList<>();
        for (Tag tag : list(player)) entries.add(read((CompoundTag) tag));
        return entries;
    }

    /** The newest instance, or null when the player was never sent anywhere. */
    @Nullable
    public static Instance latest(ServerPlayer player) {
        ListTag list = list(player);
        return list.isEmpty() ? null : read(list.getCompound(list.size() - 1));
    }

    /** The newest instance of this quest, or of this structure when the quest is implicit. */
    @Nullable
    public static Instance find(ServerPlayer player, @Nullable ResourceLocation def, ResourceLocation structure) {
        ListTag list = list(player);
        for (int i = list.size() - 1; i >= 0; i--) {
            Instance instance = read(list.getCompound(i));
            if (def != null ? def.equals(instance.def()) : instance.def() == null && instance.structure().equals(structure)) return instance;
        }
        return null;
    }

    /** Whether this structure (same chunk) was already offered to the player. */
    public static boolean sent(ServerPlayer player, ResourceLocation structure, BlockPos position) {
        for (Tag tag : list(player)) if (QuestObjectives.claims((CompoundTag) tag, structure, position)) return true;
        for (Instance entry : all(player)) {
            if (entry.structure().equals(structure) && entry.position().getX() >> 4 == position.getX() >> 4
                && entry.position().getZ() >> 4 == position.getZ() >> 4) {
                return true;
            }
        }
        return false;
    }

    public static void forget(ServerPlayer player) {
        StoryServer.persisted(player).remove(KEY);
    }

    private static String dimensionOf(ServerPlayer player) {
        return player.serverLevel().dimension().location().toString();
    }

    private static int indexOfActive(ListTag list, @Nullable ResourceLocation def) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Instance instance = read(list.getCompound(i));
            if (instance.stage().active() && (def == null || def.equals(instance.def()))) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- availability

    /** Whether the quest can be offered to the player now: never taken, or repeatable and off cooldown, and its own conditions pass. */
    public static Availability availability(StoryContext context, QuestDef def) {
        ServerPlayer player = context.player();
        ListTag list = list(player);
        for (int i = list.size() - 1; i >= 0; i--) {
            Instance instance = read(list.getCompound(i));
            if (!def.id().equals(instance.def())) continue;
            if (instance.stage().active()) return new Availability(State.ACTIVE, "superiorstory.blocked.active", 0);
            if (instance.stage() == StoryQuestStage.TURNED_IN) {
                if (!def.repeatable()) return new Availability(State.DONE, "superiorstory.blocked.done", 0);
                long left = instance.turnedAt() + def.cooldownMillis() - System.currentTimeMillis();
                if (left > 0) return new Availability(State.COOLDOWN, "superiorstory.blocked.cooldown", left);
                break;
            }
        }
        var failing = def.guard().failing(context);
        if (failing != null) {
            String reason = failing.condition().reasonKey(context);
            return new Availability(State.GATED, reason == null ? "superiorstory.blocked.generic" : reason, 0);
        }
        if (!def.guard().passes(context)) return new Availability(State.GATED, "superiorstory.blocked.generic", 0);
        return Availability.OPEN;
    }

    /** Throws {@link StoryBlocked} with the reason unless the quest can be offered now. */
    public static void requireAvailable(StoryContext context, QuestDef def) {
        Availability availability = availability(context, def);
        if (availability.state() != State.AVAILABLE) throw blocked(availability);
    }

    /** Throws {@link StoryBlocked} unless the quest can be offered now or the player is already on it. */
    public static void requireAvailableOrActive(StoryContext context, QuestDef def) {
        Availability availability = availability(context, def);
        if (availability.state() != State.AVAILABLE && availability.state() != State.ACTIVE) throw blocked(availability);
    }

    private static StoryBlocked blocked(Availability availability) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("blocked", Vars.LANG + availability.reasonKey());
        if (availability.state() == State.COOLDOWN) vars.put("cooldown", Vars.TIME + availability.cooldownMillis());
        return new StoryBlocked(availability.reasonKey(), vars);
    }

    // ---------------------------------------------------------------- items and variables

    /** Picks the items for a new instance; throws {@link StoryBlocked} with the source's reason when nothing is eligible. */
    public static List<ItemSpec> pick(StoryContext context, QuestDef def) {
        if (def.item() == null) return List.of();
        List<ItemSpec> picks = def.item().pick(context);
        if (picks.isEmpty()) {
            String key = def.item().blockedKey();
            throw new StoryBlocked(key, Map.of("blocked", Vars.LANG + key));
        }
        return picks;
    }

    static void writeItems(CompoundTag tag, List<ItemSpec> items) {
        ListTag list = new ListTag();
        for (ItemSpec spec : items) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", spec.item().id().toString());
            entry.putInt("n", spec.quantity());
            entry.putBoolean("c", spec.consume());
            list.add(entry);
        }
        tag.put("items", list);
    }

    /** The items this instance asks for: its stored picks, else the quest's fixed items. */
    static List<ItemSpec> items(@Nullable CompoundTag tag, QuestDef def) {
        if (tag != null && tag.contains("items", Tag.TAG_LIST)) {
            List<ItemSpec> items = new ArrayList<>();
            for (Tag entry : tag.getList("items", Tag.TAG_COMPOUND)) {
                CompoundTag item = (CompoundTag) entry;
                items.add(new ItemSpec(com.mugloved.superiorstory.dialogue.IdSelector.parse(item.getString("id")), Math.max(1, item.getInt("n")), item.getBoolean("c")));
            }
            return items;
        }
        List<ItemSpec> fixed = def.item() == null ? null : def.item().fixed();
        return fixed == null ? List.of() : fixed;
    }

    /** The items the player's newest instance of the quest asks for (the quest's fixed items when there is none). */
    public static List<ItemSpec> items(ServerPlayer player, QuestDef def) {
        return items(newestTag(player, def.id()), def);
    }

    @Nullable
    private static CompoundTag newestTag(ServerPlayer player, ResourceLocation def) {
        CompoundTag active = activeTag(player, def);
        if (active != null) return active;
        ListTag list = list(player);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (def.toString().equals(list.getCompound(i).getString("def"))) return list.getCompound(i);
        }
        return null;
    }

    private static boolean hasAll(ServerPlayer player, List<ItemSpec> items) {
        if (items.isEmpty()) return false;
        for (ItemSpec spec : items) if (!spec.has(player)) return false;
        return true;
    }

    /** Whether the player carries every item the quest asks for; a quest with no item never does. */
    public static boolean carries(ServerPlayer player, QuestDef def) {
        return def.item() != null && hasAll(player, items(player, def));
    }

    /** Variables a quest adds to a conversation: {@code item}, {@code reward}, {@code boss}, and the item source's facts. */
    public static Map<String, String> questVars(ServerPlayer player, QuestDef def) {
        ServerLevel level = player.serverLevel();
        Map<String, String> vars = new LinkedHashMap<>();
        ResourceLocation exact = def.location() == null ? null : def.location().exactStructure();
        if (exact != null) vars.put("structure", StructureFacts.displayName(StructureProfiles.resolve(level, exact), exact));
        List<ItemSpec> items = items(player, def);
        if (!items.isEmpty()) {
            vars.put("item", Vars.ITEM + items.get(0).item().id());
            def.item().facts(level, items, vars);
        }
        for (Act act : def.reward()) {
            String reward = act.action().rewardVar();
            if (reward != null) {
                vars.put("reward", reward);
                break;
            }
        }
        ResourceLocation entity = QuestDefinitions.entityOf(def);
        if (entity != null) {
            vars.put("boss", Vars.ENTITY + entity);
            String danger = StructureFacts.danger(entity);
            if (danger != null) vars.put("danger", danger);
        }
        return vars;
    }

    /** Variables for a player who returns to a giver: the structure facts and, for a quest file, its item and reward. */
    static Map<String, String> vars(ServerPlayer player, Instance instance) {
        Map<String, String> vars = new LinkedHashMap<>(instance.kind().equals("structure") || instance.kind().equals("pool")
            ? StructureFacts.vars(player, instance.structure(), instance.position()) : Map.of());
        QuestDef def = instance.def() == null ? null : QuestDefinitions.get(instance.def());
        if (def != null) vars.putAll(questVars(player, def));
        return vars;
    }

    // ---------------------------------------------------------------- transitions

    /**
     * A place was located for this player (or a fetch quest is offered, with no place); {@code quest} is the quest that asked,
     * or null for an implicit structure quest. {@code picks} are the items this instance asks for.
     */
    public static void offer(ServerPlayer player, @Nullable QuestDef quest, @Nullable String kind, @Nullable ResourceLocation place,
                             @Nullable BlockPos position, List<ItemSpec> picks) {
        ListTag list = list(player);
        ResourceLocation def = quest == null ? null : quest.id();
        if (def != null) {
            for (int i = list.size() - 1; i >= 0; i--) {   // an older offer or a finished repeat is superseded by this one
                Instance old = read(list.getCompound(i));
                if (def.equals(old.def()) && (old.stage() == StoryQuestStage.OFFERED
                    || (old.stage() == StoryQuestStage.TURNED_IN && quest != null && quest.repeatable()))) {
                    list.remove(i);
                }
            }
        }
        while (list.size() >= MAX_ENTRIES) list.remove(0);
        CompoundTag tag = new CompoundTag();
        tag.putString("def", def == null ? "" : def.toString());
        tag.putString("loc", kind == null ? "" : kind);
        tag.putString("id", place == null ? "" : place.toString());
        if (position != null) {
            tag.putInt("x", position.getX());
            tag.putInt("y", position.getY());
            tag.putInt("z", position.getZ());
        }
        tag.putString("dim", dimensionOf(player));
        tag.putByte("stage", (byte) StoryQuestStage.OFFERED.ordinal());
        if (!picks.isEmpty()) writeItems(tag, picks);
        list.add(tag);
        post(player, read(tag));
    }

    /**
     * Dialogue action {@code "accept"}: the newest offered structure (of the named quest, if given) becomes the player's
     * quest, taking the speaker's position and name as the place to return to. Allies nearby get it too.
     */
    public static void accept(StoryContext context, @Nullable ResourceLocation def) {
        ServerPlayer player = context.player();
        ListTag list = list(player);
        int index = -1;
        for (int i = list.size() - 1; i >= 0; i--) {
            Instance instance = read(list.getCompound(i));
            if (instance.stage() == StoryQuestStage.OFFERED && (def == null || def.equals(instance.def()))) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            SuperiorStory.LOGGER.warn("accept found no offered quest for {}", def == null ? "the newest offer" : def);
            return;
        }
        Instance offered = read(list.getCompound(index));
        QuestDef quest = def != null ? QuestDefinitions.get(def) : offered.def() == null ? null : QuestDefinitions.get(offered.def());
        if (quest != null) requireAvailable(context, quest);
        CompoundTag tag = list.getCompound(index);
        BlockPos at = context.speakerBlock() != null ? context.speakerBlock()
            : context.speakerEntity() != null ? context.speakerEntity().blockPosition() : player.blockPosition();
        tag.putInt("ax", at.getX());
        tag.putInt("ay", at.getY());
        tag.putInt("az", at.getZ());
        tag.putString("giver", giverName(context));
        set(player, list, index, StoryQuestStage.ACCEPTED);
        share(player, tag, quest);
    }

    private static String giverName(StoryContext context) {
        String speaker = context.vars().get("speaker");
        if (speaker != null && !speaker.isBlank()) return speaker;
        if (context.speakerEntity() != null) return context.speakerEntity().getDisplayName().getString();
        return "";
    }

    /** Allies within reach who could be offered the quest get their own accepted instance. */
    private static void share(ServerPlayer player, CompoundTag sourceTag, @Nullable QuestDef def) {
        Instance source = read(sourceTag);
        for (ServerPlayer ally : nearbyAllies(player)) {
            if (def != null ? availability(StoryContext.of(ally), def).state() != State.AVAILABLE
                : find(ally, null, source.structure()) != null) {
                continue;
            }
            ListTag list = list(ally);
            while (list.size() >= MAX_ENTRIES) list.remove(0);
            CompoundTag tag = new CompoundTag();
            tag.putString("def", def == null ? "" : def.id().toString());
            tag.putString("loc", source.kind());
            if (sourceTag.contains("items")) tag.put("items", sourceTag.get("items").copy());
            tag.putString("id", sourceTag.getString("id"));
            tag.putInt("x", source.position().getX());
            tag.putInt("y", source.position().getY());
            tag.putInt("z", source.position().getZ());
            tag.putString("dim", source.dimension());
            tag.putString("giver", source.giver());
            if (source.acceptPos() != null) {
                tag.putInt("ax", source.acceptPos().getX());
                tag.putInt("ay", source.acceptPos().getY());
                tag.putInt("az", source.acceptPos().getZ());
            }
            tag.putByte("stage", (byte) StoryQuestStage.ACCEPTED.ordinal());
            if (def != null) {   // a repeat supersedes the ally's finished copy
                for (int i = list.size() - 1; i >= 0; i--) {
                    Instance old = read(list.getCompound(i));
                    if (def.id().equals(old.def()) && (old.stage() == StoryQuestStage.OFFERED || old.stage() == StoryQuestStage.TURNED_IN)) list.remove(i);
                }
            }
            if (def != null && !def.extra().isEmpty()) QuestObjectives.copy(sourceTag, tag);
            list.add(tag);
            post(ally, read(tag));
            if (def != null) QuestObjectives.markers(ally, tag, def);
            ally.displayClientMessage(Component.translatable("superiorstory.quest.shared", player.getDisplayName()), true);   // after the stage line, so the ally sees who shared it
        }
    }

    /** Allied players in the same dimension within the share radius; empty when no alliance provider is installed. */
    private static List<ServerPlayer> nearbyAllies(ServerPlayer player) {
        AllianceQueryApi alliances = SuperiorServiceRegistry.getOptional(AllianceQueryApi.class).orElse(null);
        if (alliances == null) return List.of();
        List<ServerPlayer> allies = new ArrayList<>();
        for (ServerPlayer other : player.serverLevel().players()) {
            if (other != player && other.isAlive() && other.distanceToSqr(player) <= (double) SHARE_RADIUS * SHARE_RADIUS
                && alliances.areAllied(player, other)) {
                allies.add(other);
            }
        }
        return allies;
    }

    /**
     * Dialogue action {@code "turn_in"} and {@code hand_over} with a quest: reports back. With a quest file the player must
     * carry its item (taken unless {@code consume} is false) or, with no item, have finished the job; the quest's reward is
     * paid, and each ally nearby whose own quest is ready completes it too and is rewarded.
     */
    public static void turnIn(StoryContext context, @Nullable ResourceLocation defId) {
        ServerPlayer player = context.player();
        ListTag list = list(player);
        QuestDef def = defId == null ? null : QuestDefinitions.get(defId);
        if (defId != null && def == null) {
            SuperiorStory.LOGGER.warn("turn_in references unknown quest {}", defId);
            return;
        }
        int index = indexOfActive(list, defId);
        if (def == null && index >= 0) {
            ResourceLocation open = read(list.getCompound(index)).def();
            def = open == null ? null : QuestDefinitions.get(open);
        }
        if (def != null) {
            context.vars().putAll(questVars(player, def));   // {item}, {reward}, {boss} for the lines that follow
            requireAvailableOrActive(context, def);
            Instance existing = index < 0 ? null : read(list.getCompound(index));
            if ((existing == null ? def.extra().size() : existing.pending()) > 0) {
                throw new StoryBlocked("superiorstory.blocked.not_ready", Map.of("blocked", Vars.LANG + "superiorstory.blocked.not_ready"));
            }
            List<ItemSpec> items = items(index < 0 ? null : list.getCompound(index), def);
            if (def.item() != null) {
                if (!hasAll(player, items)) throw new StoryBlocked("superiorstory.blocked.missing_items", Map.of("blocked", Vars.LANG + "superiorstory.blocked.missing_items"));
            } else if (!readyWithoutItem(existing, def)) {
                throw new StoryBlocked("superiorstory.blocked.not_ready", Map.of("blocked", Vars.LANG + "superiorstory.blocked.not_ready"));
            }
            for (ItemSpec spec : items) if (spec.consume()) spec.take(player);
            if (index < 0) index = addDirect(player, list, def, items);
        } else if (index < 0) {
            return;
        }
        Instance turned = complete(player, list, index, def, context);
        completeAllies(player, turned, def);
    }

    /**
     * Dialogue action {@code "deliver"}: the NPC speaking is the one a delivery objective of the player's quest (the named one, or
     * any) spawned for them, so that objective is done. The NPC despawns a moment later.
     */
    public static void deliver(StoryContext context, @Nullable ResourceLocation defId) {
        ServerPlayer player = context.player();
        Entity speaker = context.speakerEntity();
        if (speaker == null) {
            SuperiorStory.LOGGER.warn("deliver needs an NPC speaker");
            return;
        }
        ListTag list = list(player);
        for (int i = list.size() - 1; i >= 0; i--) {
            CompoundTag tag = list.getCompound(i);
            Instance instance = read(tag);
            QuestDef def = instance.def() == null ? null : QuestDefinitions.get(instance.def());
            if (def == null || !instance.stage().active() || (defId != null && !defId.equals(def.id()))) continue;
            if (QuestObjectives.deliver(player, tag, def, speaker.getUUID())) return;
        }
        SuperiorStory.LOGGER.warn("deliver found no delivery objective waiting on {}", speaker.getDisplayName().getString());
    }

    /** Whether a turn-in of the quest (or of the player's open quest when none is named) would succeed now; the "!" marker reads this. */
    public static boolean readyToTurnIn(StoryContext context, @Nullable ResourceLocation defId) {
        ServerPlayer player = context.player();
        ListTag list = list(player);
        int index = indexOfActive(list, defId);
        if (index < 0) return false;
        Instance instance = read(list.getCompound(index));
        QuestDef def = instance.def() == null ? null : QuestDefinitions.get(instance.def());
        if (def == null || instance.pending() > 0) return false;
        return def.item() != null ? hasAll(player, items(list.getCompound(index), def)) : readyWithoutItem(instance, def);
    }

    /** Whether the speaking NPC is the one a delivery objective of the player's quest (the named one, or any) is waiting on; read only. */
    public static boolean waitingOnDelivery(StoryContext context, @Nullable ResourceLocation defId) {
        Entity speaker = context.speakerEntity();
        if (speaker == null) return false;
        ListTag list = list(context.player());
        for (int i = list.size() - 1; i >= 0; i--) {
            CompoundTag tag = list.getCompound(i);
            Instance instance = read(tag);
            QuestDef def = instance.def() == null ? null : QuestDefinitions.get(instance.def());
            if (def != null && instance.stage().active() && (defId == null || defId.equals(def.id()))
                && QuestObjectives.delivers(tag, def, speaker.getUUID())) return true;
        }
        return false;
    }

    /** A quest handed over without being accepted first: record it as an instance so its state is tracked. */
    private static int addDirect(ServerPlayer player, ListTag list, QuestDef def, List<ItemSpec> items) {
        while (list.size() >= MAX_ENTRIES) list.remove(0);
        for (int i = list.size() - 1; i >= 0; i--) {
            Instance old = read(list.getCompound(i));
            if (def.id().equals(old.def()) && (old.stage() == StoryQuestStage.OFFERED || old.stage() == StoryQuestStage.TURNED_IN)) list.remove(i);
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("def", def.id().toString());
        ResourceLocation exact = def.location() == null ? null : def.location().exactStructure();
        tag.putString("loc", def.location() == null ? "" : def.location().kind());
        tag.putString("id", exact != null ? exact.toString() : "superiorstory:unknown");
        if (!items.isEmpty()) writeItems(tag, items);
        tag.putString("dim", dimensionOf(player));
        tag.putByte("stage", (byte) StoryQuestStage.ACCEPTED.ordinal());
        list.add(tag);
        return list.size() - 1;
    }

    static boolean readyWithoutItem(@Nullable Instance instance, QuestDef def) {
        if (instance == null) return false;
        StoryQuestStage needed = QuestDefinitions.entityOf(def) != null ? StoryQuestStage.BOSS_DEFEATED : StoryQuestStage.VISITED;
        return instance.stage().active() && instance.pending() == 0 && instance.stage().ordinal() >= needed.ordinal();
    }

    private static Instance complete(ServerPlayer player, ListTag list, int index, @Nullable QuestDef def, StoryContext context) {
        CompoundTag tag = list.getCompound(index);
        QuestObjectives.clear(player, tag);
        tag.putLong("turned", System.currentTimeMillis());
        set(player, list, index, StoryQuestStage.TURNED_IN);
        if (def != null) payReward(def, context);
        return read(tag);
    }

    private static final int COINS_PER_TIER_SQUARED = 25;

    /** The coins a quest pays by default for its hardest boss: 25 times the tier squared (tier 1 pays 25, tier 10 pays 2500). */
    public static int derivedCoins(int tier) {
        return COINS_PER_TIER_SQUARED * tier * tier;
    }

    /** The highest Superior Lib tier among the quest's bosses, or 0 when none is classified. */
    private static int hardestTier(QuestDef def) {
        BossTierApi tiers = SuperiorServiceRegistry.getOptional(BossTierApi.class).orElse(null);
        if (tiers == null) return 0;
        List<ResourceLocation> bosses = new ArrayList<>();
        bosses.add(QuestDefinitions.entityOf(def));
        for (QuestDef.Objective objective : def.extra()) bosses.add(QuestDefinitions.bossOf(objective));
        int best = 0;
        for (ResourceLocation boss : bosses) if (boss != null) best = Math.max(best, tiers.find(boss).map(BossTierApi.BossTierEntry::tier).orElse(0));
        return best;
    }

    /** A quest with no coins, item, or loot reward of its own pays coins by the tier of its hardest boss, when Superior Shop is present. */
    private static void payDerivedReward(QuestDef def, StoryContext paying) {
        for (Act act : def.reward()) if (act.key().equals("coins") || act.key().equals("give") || act.key().equals("loot")) return;
        int tier = hardestTier(def);
        var coins = StoryHooks.action("coins");
        if (tier > 0 && coins != null) coins.apply(new com.google.gson.JsonPrimitive(derivedCoins(tier))).run(paying);
    }

    private static void payReward(QuestDef def, StoryContext context) {
        StoryContext paying = context.withSource(def.id().toString());
        payDerivedReward(def, paying);
        for (Act act : def.reward()) {
            try {
                act.action().run(paying);
            } catch (RuntimeException exception) {
                SuperiorStory.LOGGER.error("Quest {} reward {} failed", def.id(), act.key(), exception);
            }
        }
    }

    private static void completeAllies(ServerPlayer player, Instance turned, @Nullable QuestDef def) {
        for (ServerPlayer ally : nearbyAllies(player)) {
            ListTag list = list(ally);
            int index = -1;
            for (int i = list.size() - 1; i >= 0; i--) {
                Instance instance = read(list.getCompound(i));
                boolean same = def != null ? def.id().equals(instance.def())
                    : instance.def() == null && instance.structure().equals(turned.structure());
                if (same && instance.stage().active()) {
                    index = i;
                    break;
                }
            }
            if (index < 0) continue;
            Instance instance = read(list.getCompound(index));
            boolean ready;
            boolean takeItems = false;
            List<ItemSpec> items = def == null ? List.of() : items(list.getCompound(index), def);
            if (def != null && def.item() != null) {
                ready = instance.pending() == 0 && (instance.credit() || hasAll(ally, items));
                takeItems = !instance.credit();
            } else if (def != null) {
                ready = readyWithoutItem(instance, def);
            } else {
                ready = instance.pending() == 0 && instance.stage().ordinal() >= (hasBoss(ally, instance) ? StoryQuestStage.BOSS_DEFEATED : StoryQuestStage.VISITED).ordinal();
            }
            if (!ready) continue;
            if (takeItems) for (ItemSpec spec : items) if (spec.consume()) spec.take(ally);
            complete(ally, list, index, def, StoryContext.of(ally));
        }
    }

    private static boolean hasBoss(ServerPlayer player, Instance instance) {
        return !StructureProfiles.resolve(player.serverLevel(), instance.structure()).bosses().isEmpty();
    }

    /** Dialogue condition {@code "quest_stage"}: none, active, or a stage key, judged on the newest instance. */
    public static boolean stageIs(ServerPlayer player, String value) {
        Instance latest = latest(player);
        return switch (value) {
            case "none" -> latest == null;
            case "active" -> latest != null && (latest.stage().active());
            default -> latest != null && stageMatches(player, latest, value);
        };
    }

    /** Dialogue condition {@code "quest"}: one quest's stage. {@code available} means it can be offered now. */
    public static boolean questStageIs(StoryContext context, ResourceLocation questId, String stage) {
        ServerPlayer player = context.player();
        if (stage.equals("available")) {
            QuestDef def = QuestDefinitions.get(questId);
            return def != null && availability(context, def).state() == State.AVAILABLE;
        }
        ListTag list = list(player);
        for (int i = list.size() - 1; i >= 0; i--) {
            Instance instance = read(list.getCompound(i));
            if (!questId.equals(instance.def())) continue;
            return stage.equals("active") ? instance.stage().active() : stageMatches(player, instance, stage);
        }
        return stage.equals("none");
    }

    private static boolean stageMatches(ServerPlayer player, Instance instance, String key) {
        if (key.equals("collected")) {
            QuestDef def = instance.def() == null ? null : QuestDefinitions.get(instance.def());
            return instance.stage().active() && def != null && carries(player, def);
        }
        return instance.stage().key().equals(key);
    }

    private static void set(ServerPlayer player, ListTag list, int index, StoryQuestStage stage) {
        CompoundTag tag = list.getCompound(index);
        tag.putByte("stage", (byte) stage.ordinal());
        post(player, read(tag));
    }

    private static void post(ServerPlayer player, Instance instance) {
        feedback(player, instance);
        MinecraftForge.EVENT_BUS.post(new StoryQuestEvent(player, instance.def(), instance.kind(), instance.structure(), instance.position(), instance.stage()));
        if (instance.def() != null) StoryHooks.fire("quest", player, new QuestPayload(instance.def(), instance.stage()));
    }

    /** The short action-bar line every stage change shows. */
    private static void feedback(ServerPlayer player, Instance instance) {
        if (instance.stage() == StoryQuestStage.OFFERED) return;
        QuestDef def = instance.def() == null ? null : QuestDefinitions.get(instance.def());
        String structure = placeName(player, instance, def);
        Component message = switch (instance.stage()) {
            case ACCEPTED -> Component.translatable("superiorstory.quest.accepted", structure);
            case VISITED -> Component.translatable("superiorstory.quest.visited", structure);
            case BOSS_DEFEATED -> {
                ResourceLocation boss = bossFor(player, instance, def);
                Component name = boss == null ? Component.literal(structure) : BuiltInRegistries.ENTITY_TYPE.get(boss).getDescription();
                yield instance.giver().isEmpty() ? Component.translatable("superiorstory.quest.boss_defeated", name)
                    : Component.translatable("superiorstory.quest.boss_defeated_return", name, instance.giver());
            }
            case COLLECTED -> Component.translatable("superiorstory.quest.collected", firstItemName(player, def, structure));
            case TURNED_IN -> Component.translatable("superiorstory.quest.turned_in", structure);
            default -> null;
        };
        if (message != null) player.displayClientMessage(message, true);
    }

    /** The name of the instance's place; a fetch quest is named by its first item. */
    static String placeName(ServerPlayer player, Instance instance, @Nullable QuestDef def) {
        ServerLevel level = player.serverLevel();
        if (def == null) return StructureFacts.displayName(StructureProfiles.resolve(level, instance.structure()), instance.structure());
        if (def.location() != null) return def.location().displayName(level, instance.structure());
        return firstItemName(player, def, "").getString();
    }

    private static Component firstItemName(ServerPlayer player, @Nullable QuestDef def, String fallback) {
        List<ItemSpec> items = def == null ? List.of() : items(player, def);
        return items.isEmpty() ? Component.literal(fallback) : items.get(0).stack().getHoverName();
    }

    @Nullable
    private static ResourceLocation bossFor(ServerPlayer player, Instance instance, @Nullable QuestDef def) {
        if (def != null) return QuestDefinitions.entityOf(def);
        List<ResourceLocation> bosses = StructureProfiles.resolve(player.serverLevel(), instance.structure()).bosses();
        return bosses.isEmpty() ? null : bosses.get(0);
    }

    // ---------------------------------------------------------------- automatic progress

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % CHECK_TICKS != 0) return;
        QuestObjectives.sweep(event.getServer());
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            ListTag list = list(player);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                Instance instance = read(tag);
                QuestDef def = instance.def() == null ? null : QuestDefinitions.get(instance.def());
                if (instance.stage() == StoryQuestStage.ACCEPTED && arrived(player, instance, def)) {
                    set(player, list, i, StoryQuestStage.VISITED);
                    instance = read(tag);
                }
                if (instance.stage().active() && def != null && !def.extra().isEmpty()) QuestObjectives.tick(player, tag, instance, def);
                if (instance.stage().active() && instance.stage() != StoryQuestStage.OFFERED && def != null && def.location() == null && def.item() != null) {
                    QuestObjectives.derived(player, tag, def, items(tag, def));
                }
                if (instance.stage().active() && def != null && def.item() != null) {
                    boolean carrying = hasAll(player, items(tag, def));
                    if (carrying && !tag.getBoolean("fired")) {
                        tag.putBoolean("fired", true);
                        post(player, new Instance(instance.def(), instance.structure(), instance.position(), instance.dimension(),
                            StoryQuestStage.COLLECTED, instance.giver(), instance.acceptPos(), instance.turnedAt(), instance.credit(), instance.pending(), instance.kind()));
                    } else if (!carrying && tag.getBoolean("fired")) {
                        tag.putBoolean("fired", false);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !tracks(BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()))) return;
        ServerPlayer attacker = playerFor(event.getSource());
        if (attacker == null) return;
        Set<UUID> credit = CREDIT.computeIfAbsent(victim.getUUID(), id -> ConcurrentHashMap.newKeySet());
        if (credit.size() < MAX_CREDIT) credit.add(attacker.getUUID());
    }

    /** The player behind a damage source: the attacker, or the owner of the summon, pet, or companion that hit. */
    @Nullable
    private static ServerPlayer playerFor(DamageSource source) {
        Entity cause = source.getEntity();
        if (cause instanceof ServerPlayer player) return player;
        if (cause instanceof LivingEntity living) {
            AllianceQueryApi alliances = SuperiorServiceRegistry.getOptional(AllianceQueryApi.class).orElse(null);
            if (alliances != null) return alliances.resolveOwner(living);
        }
        return null;
    }

    /** Whether some profile boss or quest names this entity, so its damage is worth remembering. */
    private static boolean tracks(ResourceLocation type) {
        return StructureProfiles.allBosses().contains(type) || QuestDefinitions.entityIds().contains(type);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && !CREDIT.isEmpty()) CREDIT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide() || dead.getServer() == null) return;
        Set<UUID> credit = CREDIT.remove(dead.getUUID());
        if (credit == null || credit.isEmpty()) return;
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType());
        ServerLevel level = (ServerLevel) dead.level();
        for (UUID uuid : credit) {
            ServerPlayer player = dead.getServer().getPlayerList().getPlayer(uuid);
            if (player == null || player.level() != level) continue;
            ListTag list = list(player);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                Instance instance = read(tag);
                if (!instance.stage().active() || !instance.dimension().equals(dimensionOf(player))) continue;
                QuestDef def = instance.def() == null ? null : QuestDefinitions.get(instance.def());
                if ((instance.stage() == StoryQuestStage.ACCEPTED || instance.stage() == StoryQuestStage.VISITED)
                    && isBoss(level, instance, type) && credits(level, instance, def, dead.position())) {
                    tag.putBoolean("credit", true);
                    set(player, list, i, StoryQuestStage.BOSS_DEFEATED);
                }
                if (def != null && !def.extra().isEmpty()) QuestObjectives.bossDied(player, level, tag, def, type, dead.position());
            }
        }
    }

    private static boolean isBoss(ServerLevel level, Instance instance, ResourceLocation type) {
        QuestDef def = instance.def() == null ? null : QuestDefinitions.get(instance.def());
        if (def != null) return type.equals(QuestDefinitions.entityOf(def));
        StructureProfile.Merged profile = StructureProfiles.resolve(level, instance.structure());
        return profile.bosses().contains(type);
    }

    /** Whether a kill at {@code at} counts for the instance's place: its location decides; an implicit quest uses its structure. */
    private static boolean credits(ServerLevel level, Instance instance, @Nullable QuestDef def, net.minecraft.world.phys.Vec3 at) {
        if (def == null) return insideStructure(level, instance.structure(), instance.position(), at);
        return def.location() != null && def.location().credits(level, instance.structure(), instance.position(), at);
    }

    /** Whether the player reached the instance's place (never for a fetch quest). */
    private static boolean arrived(ServerPlayer player, Instance instance, @Nullable QuestDef def) {
        if (!instance.dimension().equals(dimensionOf(player))) return false;
        if (def == null) return near(player, instance, VISIT_RANGE);
        return def.location() != null && def.location().arrived(player, instance.structure(), instance.position());
    }

    static boolean insideStructure(ServerLevel level, ResourceLocation structure, BlockPos position, net.minecraft.world.phys.Vec3 at) {
        BoundingBox box = boundsOf(level, structure, position);
        BlockPos pos = BlockPos.containing(at);
        if (box != null) return box.inflatedBy(BOSS_PAD).isInside(pos);
        return Math.hypot(at.x - position.getX(), at.z - position.getZ()) <= FALLBACK_RADIUS;
    }

    /** The structure's bounding box from the known-structure index, else from the live structure start; null when neither is known. */
    @Nullable
    static BoundingBox boundsOf(ServerLevel level, ResourceLocation structureId, BlockPos position) {
        try {
            KnownStructureStartsApi index = SuperiorServiceRegistry.getOptional(KnownStructureStartsApi.class).orElse(null);
            if (index != null) {
                Optional<KnownStructureStartsApi.KnownStart> known = index.nearest(level, position, id -> id.equals(structureId), 128, start -> false);
                if (known.isPresent() && known.get().boundingBox().isInside(position)) return known.get().boundingBox();
            }
            Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(structureId);
            if (structure == null) return null;
            StructureStart start = level.structureManager().getStructureAt(position, structure);
            return start.isValid() ? start.getBoundingBox() : null;
        } catch (RuntimeException exception) {
            SuperiorStory.LOGGER.warn("Could not read bounds of {}: {}", structureId, exception.toString());
            return null;
        }
    }

    private static boolean near(ServerPlayer player, Instance instance, int range) {
        return instance.dimension().equals(dimensionOf(player))
            && Math.hypot(player.getX() - instance.position().getX(), player.getZ() - instance.position().getZ()) <= range;
    }
}

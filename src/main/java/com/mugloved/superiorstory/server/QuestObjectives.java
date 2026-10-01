package com.mugloved.superiorstory.server;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.dialogue.ItemSpec;
import com.mugloved.superiorstory.dialogue.LocateSpec;
import com.mugloved.superiorstory.dialogue.QuestDef;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The extra objectives of a quest file ({@code also}): kill a boss in a structure, or reach a structure where an NPC appears
 * beside the player, glowing gold, to be talked to ({@code deliver}). Progress lives in the quest instance's own tag under
 * {@value #KEY}, one entry per objective in the order the file lists them: the structure found for it, whether it is done, and
 * the NPC standing for it. Each objective gets its own structure, found through the known-structure index and Superior
 * Locator, and its own map marker; the quest cannot be turned in until every one is done.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID)
public final class QuestObjectives {
    static final String KEY = "objs";
    private static final String MARKS = "marks";
    private static final String TEMP_TAG = "superiorstory_temp_npc";
    private static final String TEAM = "superiorstory_npc";
    private static final int INSIDE_PAD = 8;
    private static final int FALLBACK_RANGE = 48;
    private static final double LEAVE_RANGE_SQR = 96.0 * 96.0;
    private static final long RETRY_MS = 60_000L;
    private static final long DESPAWN_MS = 4_000L;

    /** Searches in flight and when a failed one may run again, keyed by player, quest, and objective. */
    private static final Map<String, StoryHooks.LineKind.Job> JOBS = new ConcurrentHashMap<>();
    private static final Map<String, Long> RETRY = new ConcurrentHashMap<>();
    /** NPCs this run spawned; any other entity with the temp tag is a leftover from a crash and is removed on load. */
    private static final Set<UUID> LIVE = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> DESPAWN = new ConcurrentHashMap<>();

    private QuestObjectives() {}

    // ---------------------------------------------------------------- state

    /** Objectives still to do; every objective when the instance has no progress yet. */
    static int pending(CompoundTag tag, @Nullable QuestDef def) {
        if (def == null || def.extra().isEmpty()) return 0;
        ListTag objs = tag.getList(KEY, Tag.TAG_COMPOUND);
        int pending = 0;
        for (int i = 0; i < def.extra().size(); i++) {
            if (i >= objs.size() || !objs.getCompound(i).getBoolean("done")) pending++;
        }
        return pending;
    }

    private static ListTag ensure(CompoundTag tag, QuestDef def) {
        if (!tag.contains(KEY, Tag.TAG_LIST)) tag.put(KEY, new ListTag());
        ListTag objs = tag.getList(KEY, Tag.TAG_COMPOUND);
        while (objs.size() < def.extra().size()) objs.add(new CompoundTag());
        return objs;
    }

    /** Whether an objective of this instance already claimed a structure in the chunk of {@code position}. */
    static boolean claims(CompoundTag tag, ResourceLocation structure, BlockPos position) {
        for (Tag entry : tag.getList(KEY, Tag.TAG_COMPOUND)) {
            CompoundTag objective = (CompoundTag) entry;
            if (objective.contains("id") && objective.getString("id").equals(structure.toString())
                && objective.getInt("x") >> 4 == position.getX() >> 4 && objective.getInt("z") >> 4 == position.getZ() >> 4) {
                return true;
            }
        }
        return false;
    }

    /** An ally shares the structures already found, not the progress. */
    static void copy(CompoundTag from, CompoundTag to) {
        ListTag copy = new ListTag();
        for (Tag entry : from.getList(KEY, Tag.TAG_COMPOUND)) {
            CompoundTag source = (CompoundTag) entry;
            CompoundTag objective = new CompoundTag();
            if (source.contains("id")) {
                objective.putString("id", source.getString("id"));
                objective.putInt("x", source.getInt("x"));
                objective.putInt("y", source.getInt("y"));
                objective.putInt("z", source.getInt("z"));
            }
            copy.add(objective);
        }
        to.put(KEY, copy);
    }

    // ---------------------------------------------------------------- progress each check

    /** One check of an active quest with extra objectives: find missing structures, spawn and mind delivery NPCs. */
    static void tick(ServerPlayer player, CompoundTag tag, StoryQuests.Instance instance, QuestDef def) {
        ListTag objs = ensure(tag, def);
        ServerLevel level = player.serverLevel();
        for (int i = 0; i < def.extra().size(); i++) {
            CompoundTag objective = objs.getCompound(i);
            if (objective.getBoolean("done")) continue;
            if (!objective.contains("id")) {
                resolve(player, def, i);
                continue;
            }
            if (!instance.dimension().equals(level.dimension().location().toString()) || !def.extra().get(i).deliver()) continue;
            String npc = objective.getString("npc");
            if (npc.isEmpty()) {
                if (reached(player, def.extra().get(i).location(), new ResourceLocation(objective.getString("id")), position(objective))) {
                    spawn(player, def.extra().get(i), objective);
                }
            } else {
                Entity entity = level.getEntity(UUID.fromString(npc));
                if (entity == null || !entity.isAlive() || entity.distanceToSqr(player) > LEAVE_RANGE_SQR) leave(player, objective, entity);
            }
        }
    }

    private static BlockPos position(CompoundTag objective) {
        return new BlockPos(objective.getInt("x"), objective.getInt("y"), objective.getInt("z"));
    }

    /** A structure objective counts from inside its box; other location kinds decide arrival themselves. */
    private static boolean reached(ServerPlayer player, com.mugloved.superiorstory.api.StoryHooks.Location location, ResourceLocation found, BlockPos position) {
        return location instanceof QuestLocations.Structures ? inside(player.serverLevel(), found, position, player.position())
            : location.arrived(player, found, position);
    }

    private static boolean inside(ServerLevel level, ResourceLocation structure, BlockPos position, Vec3 at) {
        BoundingBox box = StoryQuests.boundsOf(level, structure, position);
        if (box != null) return box.inflatedBy(INSIDE_PAD).isInside(BlockPos.containing(at));
        return Math.hypot(at.x - position.getX(), at.z - position.getZ()) <= FALLBACK_RANGE;
    }

    // ---------------------------------------------------------------- finding structures

    private static void resolve(ServerPlayer player, QuestDef def, int index) {
        StoryHooks.Location location = def.extra().get(index).location();
        search(player, player.getUUID() + "|" + def.id() + "|" + index, location,
            (found, at) -> found(player, def, index, found, at), "objective " + (index + 1) + " of quest " + def.id());
    }

    /** Runs (or resumes) one location search keyed by {@code key}; a failed search waits a minute before trying again. */
    private static void search(ServerPlayer player, String key, StoryHooks.Location location,
                               java.util.function.BiConsumer<ResourceLocation, BlockPos> sink, String what) {
        long now = System.currentTimeMillis();
        StoryHooks.LineKind.Job job = JOBS.get(key);
        if (job == null) {
            if (RETRY.getOrDefault(key, 0L) > now) return;
            if (!location.possibleIn(player.serverLevel())) {
                RETRY.put(key, now + RETRY_MS);
                return;
            }
            job = location.search(player, LocateSpec.DEFAULT_RADIUS, false, sink);
            JOBS.put(key, job);
        }
        if (job.poll(StoryContext.of(player))) {
            JOBS.remove(key);
            if (!job.result().success()) {
                RETRY.put(key, now + RETRY_MS);
                SuperiorStory.LOGGER.warn("No place found yet for {}; trying again in a minute", what);
            }
        }
    }

    private static void found(ServerPlayer player, QuestDef def, int index, ResourceLocation structure, BlockPos at) {
        CompoundTag tag = StoryQuests.activeTag(player, def.id());
        if (tag == null) return;
        CompoundTag objective = ensure(tag, def).getCompound(index);
        objective.putString("id", structure.toString());
        objective.putInt("x", at.getX());
        objective.putInt("y", at.getY());
        objective.putInt("z", at.getZ());
        StoryWaypoints.send(player, at, placeName(player, def.extra().get(index).location(), structure), true, false);
    }

    private static String placeName(ServerPlayer player, StoryHooks.Location location, ResourceLocation found) {
        return location.displayName(player.serverLevel(), found);
    }

    private static String structureName(ServerPlayer player, QuestDef def, int index, ResourceLocation found) {
        return index < def.extra().size() ? placeName(player, def.extra().get(index).location(), found)
            : StructureFacts.displayName(StructureProfiles.resolve(player.serverLevel(), found), found);
    }

    // ---------------------------------------------------------------- derived markers (an item source's own places)

    /**
     * One check of a quest with no location whose item source derives places (a fish's biome): each derived location is
     * searched once and marked on the map until the quest ends. Markers never block the turn-in.
     */
    static void derived(ServerPlayer player, CompoundTag tag, QuestDef def, List<ItemSpec> items) {
        if (items.isEmpty()) return;
        List<StoryHooks.Location> locations = def.item().markers(player.serverLevel(), items);
        if (locations.isEmpty()) return;
        if (!tag.contains(MARKS, Tag.TAG_LIST)) tag.put(MARKS, new ListTag());
        ListTag marks = tag.getList(MARKS, Tag.TAG_COMPOUND);
        while (marks.size() < locations.size()) marks.add(new CompoundTag());
        for (int i = 0; i < locations.size(); i++) {
            if (marks.getCompound(i).contains("id")) continue;
            int index = i;
            StoryHooks.Location location = locations.get(i);
            search(player, player.getUUID() + "|" + def.id() + "|m" + i, location, (found, at) -> {
                CompoundTag live = StoryQuests.activeTag(player, def.id());
                if (live == null || !live.contains(MARKS, Tag.TAG_LIST)) return;
                CompoundTag mark = live.getList(MARKS, Tag.TAG_COMPOUND).getCompound(index);
                mark.putString("id", found.toString());
                mark.putInt("x", at.getX());
                mark.putInt("y", at.getY());
                mark.putInt("z", at.getZ());
                StoryWaypoints.send(player, at, location.displayName(player.serverLevel(), found), true, false);
            }, "a marker of quest " + def.id());
        }
    }

    // ---------------------------------------------------------------- delivery NPC

    private static void spawn(ServerPlayer player, QuestDef.Objective objective, CompoundTag state) {
        ServerLevel level = player.serverLevel();
        QuestDef.Npc npc = objective.npc();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(npc.type()).orElse(null);
        Entity created = type == null ? null : type.create(level);
        if (!(created instanceof Mob mob)) {
            SuperiorStory.LOGGER.warn("Deliver NPC {} is not a mob entity type", npc.type());
            state.putBoolean("done", true);   // an authoring error must not trap the quest
            return;
        }
        Vec3 look = player.getLookAngle();
        double length = Math.hypot(look.x, look.z);
        double x = player.getX(), z = player.getZ();
        if (length > 1.0e-3) {
            double nudgedX = x + look.x / length * 1.5, nudgedZ = z + look.z / length * 1.5;
            if (level.noCollision(mob, mob.getBoundingBox().move(nudgedX - mob.getX(), player.getY() - mob.getY(), nudgedZ - mob.getZ()))) {
                x = nudgedX;
                z = nudgedZ;
            }
        }
        mob.moveTo(x, player.getY(), z, player.getYRot() + 180f, 0f);
        mob.setNoAi(true);
        mob.setInvulnerable(true);
        mob.setPersistenceRequired();
        mob.addTag(npc.tag());
        mob.addTag(TEMP_TAG);
        String name = npc.name().isBlank() ? type.getDescription().getString() : npc.name();
        mob.setCustomName(Component.literal(name));
        mob.setCustomNameVisible(true);
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM);
            team.setColor(ChatFormatting.GOLD);
        }
        scoreboard.addPlayerToTeam(mob.getStringUUID(), team);
        LIVE.add(mob.getUUID());
        level.addFreshEntity(mob);
        BlockPos at = mob.blockPosition();
        state.putString("npc", mob.getStringUUID());
        state.putInt("mx", at.getX());
        state.putInt("my", at.getY());
        state.putInt("mz", at.getZ());
        BlockPos structure = position(state);
        StoryWaypoints.send(player, structure, "", false, false);
        StoryWaypoints.send(player, at, name, true, false);
    }

    /** The NPC is gone or the player walked off: forget it so it appears again on the next visit. */
    private static void leave(ServerPlayer player, CompoundTag state, @Nullable Entity entity) {
        if (entity != null) discard(entity);
        BlockPos at = new BlockPos(state.getInt("mx"), state.getInt("my"), state.getInt("mz"));
        state.remove("npc");
        StoryWaypoints.send(player, at, "", false, false);
        BlockPos structure = position(state);
        StoryWaypoints.send(player, structure, StructureFacts.displayName(StructureProfiles.resolve(player.serverLevel(), new ResourceLocation(state.getString("id"))), new ResourceLocation(state.getString("id"))), true, false);
    }

    private static void discard(Entity entity) {
        LIVE.remove(entity.getUUID());
        DESPAWN.remove(entity.getUUID());
        if (entity.level() instanceof ServerLevel level) {
            Scoreboard scoreboard = level.getScoreboard();
            PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
            if (team != null) scoreboard.removePlayerFromTeam(entity.getStringUUID(), team);
        }
        entity.discard();
    }

    /** Removes every NPC whose despawn time has passed; runs from the quest check. */
    static void sweep(MinecraftServer server) {
        long now = System.currentTimeMillis();
        DESPAWN.entrySet().removeIf(entry -> {
            if (entry.getValue() > now) return false;
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(entry.getKey());
                if (entity != null) {
                    discard(entity);
                    break;
                }
            }
            LIVE.remove(entry.getKey());
            return true;
        });
        JOBS.entrySet().removeIf(entry -> {
            UUID player = UUID.fromString(entry.getKey().substring(0, 36));
            if (server.getPlayerList().getPlayer(player) != null) return false;
            entry.getValue().cancel();
            return true;
        });
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity().getTags().contains(TEMP_TAG) && !LIVE.contains(event.getEntity().getUUID())) {
            event.setCanceled(true);   // left behind by a crash or an unloaded chunk
        }
    }

    // ---------------------------------------------------------------- completing objectives

    /** A quest boss died at {@code at}: completes each boss objective it satisfies. */
    static void bossDied(ServerPlayer player, ServerLevel level, CompoundTag tag, QuestDef def, ResourceLocation type, Vec3 at) {
        ListTag objs = ensure(tag, def);
        for (int i = 0; i < def.extra().size(); i++) {
            CompoundTag objective = objs.getCompound(i);
            QuestDef.Objective wanted = def.extra().get(i);
            if (wanted.deliver() || objective.getBoolean("done") || !objective.contains("id") || !type.equals(QuestDefinitions.bossOf(wanted))) continue;
            if (!wanted.location().credits(level, new ResourceLocation(objective.getString("id")), position(objective), at)) continue;
            complete(player, tag, def, i);
        }
    }

    /** The dialogue action {@code deliver}: the speaking NPC belongs to a delivery objective, which is now done. */
    static boolean deliver(ServerPlayer player, CompoundTag tag, QuestDef def, UUID speaker) {
        int index = deliveryIndex(ensure(tag, def), def, speaker);
        if (index < 0) return false;
        complete(player, tag, def, index);
        DESPAWN.put(speaker, System.currentTimeMillis() + DESPAWN_MS);
        return true;
    }

    /** Read only: whether an unfinished delivery objective of this instance waits on the NPC. */
    static boolean delivers(CompoundTag tag, QuestDef def, UUID speaker) {
        return deliveryIndex(tag.getList(KEY, Tag.TAG_COMPOUND), def, speaker) >= 0;
    }

    private static int deliveryIndex(ListTag objs, QuestDef def, UUID speaker) {
        for (int i = 0; i < def.extra().size() && i < objs.size(); i++) {
            CompoundTag objective = objs.getCompound(i);
            if (def.extra().get(i).deliver() && !objective.getBoolean("done") && objective.getString("npc").equals(speaker.toString())) return i;
        }
        return -1;
    }

    private static void complete(ServerPlayer player, CompoundTag tag, QuestDef def, int index) {
        CompoundTag objective = ensure(tag, def).getCompound(index);
        objective.putBoolean("done", true);
        BlockPos marker = objective.contains("npc") && objective.contains("mx")
            ? new BlockPos(objective.getInt("mx"), objective.getInt("my"), objective.getInt("mz")) : position(objective);
        StoryWaypoints.send(player, marker, "", false, false);
        ResourceLocation structure = new ResourceLocation(objective.getString("id"));
        player.displayClientMessage(Component.translatable("superiorstory.quest.objective_done", structureName(player, def, index, structure)), true);
        StoryQuests.Instance instance = StoryQuests.read(tag);
        if (instance.pending() > 0 || instance.acceptPos() == null || instance.giver().isEmpty()) return;
        boolean ready = def.item() != null ? StoryQuests.carries(player, def) : StoryQuests.readyWithoutItem(instance, def);
        if (ready) StoryWaypoints.send(player, instance.acceptPos(), instance.giver(), true, true);
    }

    // ---------------------------------------------------------------- markers and cleanup

    /** Puts the markers of every found, unfinished objective on this player's map (an ally who shares the quest). */
    static void markers(ServerPlayer player, CompoundTag tag, QuestDef def) {
        ListTag objs = tag.getList(KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < objs.size(); i++) {
            CompoundTag objective = objs.getCompound(i);
            if (!objective.contains("id") || objective.getBoolean("done")) continue;
            ResourceLocation structure = new ResourceLocation(objective.getString("id"));
            StoryWaypoints.send(player, position(objective), structureName(player, def, i, structure), true, false);
        }
    }

    /** The quest ended: take its remaining markers away and remove any NPC still standing. */
    static void clear(ServerPlayer player, CompoundTag tag) {
        for (Tag entry : tag.getList(MARKS, Tag.TAG_COMPOUND)) {
            CompoundTag mark = (CompoundTag) entry;
            if (mark.contains("id")) StoryWaypoints.send(player, position(mark), "", false, false);
        }
        tag.remove(MARKS);
        for (Tag entry : tag.getList(KEY, Tag.TAG_COMPOUND)) {
            CompoundTag objective = (CompoundTag) entry;
            if (objective.getBoolean("done") || !objective.contains("id")) continue;
            String npc = objective.getString("npc");
            if (!npc.isEmpty()) {
                Entity entity = player.serverLevel().getEntity(UUID.fromString(npc));
                if (entity != null) discard(entity);
                StoryWaypoints.send(player, new BlockPos(objective.getInt("mx"), objective.getInt("my"), objective.getInt("mz")), "", false, false);
            } else {
                StoryWaypoints.send(player, position(objective), "", false, false);
            }
        }
    }
}

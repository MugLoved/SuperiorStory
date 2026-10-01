package com.mugloved.superiorstory.server;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.DialogueChoiceEvent;
import com.mugloved.superiorstory.api.DialogueEndEvent;
import com.mugloved.superiorstory.api.DialogueOutcome;
import com.mugloved.superiorstory.api.StoryBlocked;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.dialogue.Act;
import com.mugloved.superiorstory.dialogue.Dialogue;
import com.mugloved.superiorstory.dialogue.ItemSpec;
import com.mugloved.superiorstory.dialogue.QuestDef;
import com.mugloved.superiorstory.module.CoreModules;
import com.mugloved.superiorstory.module.EntitySpeaker;
import com.mugloved.superiorstory.network.DialoguePackets;
import com.mugloved.superiorstory.network.StoryNetwork;
import com.mugloved.superiorstory.scene.StoryScene.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server side of dialogue: picks the dialogue bound to a speaker (an entity or a block) or opens one by trigger as
 * narration, holds the speaker for the conversation, resolves each choice (line and choice actions, then the Forge
 * events), runs line kinds such as structure searches, and always releases the speaker. Client and server share only the
 * one-line-at-a-time packets in {@link DialoguePackets}. The engine knows no module by name; everything a dialogue does
 * comes from {@link StoryHooks}.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID)
public final class DialogueServer {
    private static final double TALK_RANGE_SQR = 6.0 * 6.0;
    private static final double LEAVE_RANGE_SQR = 8.0 * 8.0;
    private static final long MAX_SESSION_MS = 20L * 60L * 1000L;
    private static final AtomicLong NEXT_SESSION = new AtomicLong();
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /** Where the conversation goes next; a null target ends it. */
    private record Target(Dialogue dialogue, String branch, int index) {}

    private static final class Session {
        final long id = NEXT_SESSION.incrementAndGet();
        @Nullable final StoryHooks.Speaker.Bound speaker;
        final ServerLevel level;
        final long startedAt = System.currentTimeMillis();
        final Map<String, String> vars = new LinkedHashMap<>();
        Dialogue dialogue;
        String branch = Dialogue.MAIN;
        int index;
        int serial;
        boolean opened;
        List<Dialogue.Choice> visible = List.of();
        DialogueOutcome lastOutcome = DialogueOutcome.NONE;
        DialoguePackets.Node lastNode;
        StoryHooks.LineKind.Job job;
        boolean quietWaypoint;
        Target afterJob;
        /** Where the conversation resumes once a pending reward choice is settled; null ends it. */
        Target afterReward;

        Session(Dialogue dialogue, @Nullable StoryHooks.Speaker.Bound speaker, ServerLevel level) {
            this.dialogue = dialogue;
            this.speaker = speaker;
            this.level = level;
        }

        Dialogue.Line line() {
            return dialogue.branches().get(branch).get(index);
        }

        int speakerKind() {
            return speaker == null ? DialoguePackets.NONE : speaker.entityId() >= 0 ? DialoguePackets.ENTITY : DialoguePackets.BLOCK;
        }

        int npcId() {
            return speaker == null ? -1 : speaker.entityId();
        }

        BlockPos blockPos() {
            return speaker == null || speaker.blockPos() == null ? BlockPos.ZERO : speaker.blockPos();
        }

        net.minecraft.util.RandomSource random() {
            return speaker == null ? level.random : speaker.random();
        }
    }

    private DialogueServer() {}

    // ---------------------------------------------------------------- talk / advance

    public static void onTalk(ServerPlayer player, DialoguePackets.Target target, boolean probe) {
        StoryHooks.SpeakerTarget speakerTarget = resolve(player, target);
        Dialogue dialogue = speakerTarget == null ? null : select(player, speakerTarget);
        if (dialogue != null && !probe) {
            StoryHooks.Speaker speaker = StoryHooks.speaker(dialogue.speakerKey());
            StoryHooks.Speaker.Bound bound = speaker == null ? null : speaker.bind(speakerTarget);
            if (bound != null && !inUse(bound.key())) {
                start(player, dialogue, bound);
                return;
            }
        }
        if (probe) DialoguePackets.sendToClient(player, new DialoguePackets.Talkable(target, dialogue != null));
    }

    /** The target if the player may talk to it now: alive, in range, in sight, and the player is free. */
    @Nullable
    private static StoryHooks.SpeakerTarget resolve(ServerPlayer player, DialoguePackets.Target target) {
        if (!player.isAlive() || StoryServer.isBusy(player) || SESSIONS.containsKey(player.getUUID())) return null;
        if (target.kind() == DialoguePackets.ENTITY) {
            Entity entity = player.level().getEntity(target.entityId());
            if (entity == null || !entity.isAlive() || player.distanceToSqr(entity) > TALK_RANGE_SQR || !player.hasLineOfSight(entity)) return null;
            if (!willingToTalk(entity, player)) return null;
            return new StoryHooks.SpeakerTarget(player.serverLevel(), entity, null);
        }
        if (target.kind() == DialoguePackets.BLOCK) {
            BlockPos pos = target.pos();
            Vec3 center = Vec3.atCenterOf(pos);
            if (!player.serverLevel().isLoaded(pos) || player.distanceToSqr(center) > TALK_RANGE_SQR) return null;
            var hit = player.level().clip(new ClipContext(player.getEyePosition(), center, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(pos)) return null;
            return new StoryHooks.SpeakerTarget(player.serverLevel(), null, pos);
        }
        return null;
    }

    /** A mob that is targeting the player, or is fleeing (villager panic, panic or avoid goals), does not talk. */
    private static boolean willingToTalk(Entity entity, ServerPlayer player) {
        if (!(entity instanceof net.minecraft.world.entity.Mob mob)) return true;
        if (mob.getTarget() == player) return false;
        if (mob instanceof net.minecraft.world.entity.npc.Villager villager
            && villager.getBrain().isActive(net.minecraft.world.entity.schedule.Activity.PANIC)) return false;
        return mob.goalSelector.getRunningGoals().noneMatch(g -> g.getGoal() instanceof net.minecraft.world.entity.ai.goal.PanicGoal
            || g.getGoal() instanceof net.minecraft.world.entity.ai.goal.AvoidEntityGoal);
    }

    public static void onAdvance(ServerPlayer player, long sessionId, int serial, int choice) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.id != sessionId) return;
        if (choice == DialoguePackets.LEAVE) {
            end(player, DialogueEndEvent.Reason.LEFT);
            return;
        }
        if (session.serial != serial) return;
        if (session.speaker != null && !session.speaker.alive()) {
            end(player, DialogueEndEvent.Reason.NPC_LOST);
            return;
        }
        Dialogue.Line line = session.line();
        if (line.kind() != null) {
            // Continue stays unavailable until the work settles, then moves on to where it decided
            if (choice == DialoguePackets.CONTINUE && session.job == null) leave(player, session, line, null, session.afterJob);
            return;
        }
        Dialogue.Choice picked = null;
        if (choice == DialoguePackets.CONTINUE) {
            if (!session.visible.isEmpty()) return;
        } else if (choice >= 0 && choice < session.visible.size()) {
            picked = session.visible.get(choice);
        } else {
            return;
        }
        String goTo = picked == null ? null : picked.goTo();
        boolean ends = endsAfter(session, goTo);
        leave(player, session, line, picked, ends ? null : target(session, goTo, true));
    }

    /** Runs the line's and the choice's actions, posts the choice event, and moves to {@code next} (null ends). */
    private static void leave(ServerPlayer player, Session session, Dialogue.Line line, @Nullable Dialogue.Choice picked, @Nullable Target next) {
        StoryContext context = context(player, session);
        try {
            run(context, line.actions(), session);
            if (picked != null) {
                if (picked.outcome() != DialogueOutcome.NONE) session.lastOutcome = picked.outcome();
                run(context, picked.actions(), session);
            }
        } catch (StoryBlocked blocked) {
            blocked(player, session, picked != null && picked.blocked() != null ? picked.blocked() : line.blocked(), blocked);
            return;
        }
        if (session.speaker != null) session.speaker.refresh();   // an action may have changed the speaker on purpose
        next = withRewardChoice(player, session, next);
        if (picked != null) {
            Entity speakerEntity = session.speaker == null ? null : session.speaker.entity();
            MinecraftForge.EVENT_BUS.post(new DialogueChoiceEvent(player, speakerEntity instanceof Mob mob ? mob : null,
                session.dialogue.id(), picked.id(), picked.outcome(), next == null));
            if (SESSIONS.get(player.getUUID()) != session) return;   // a listener ended the conversation
        }
        goTo(player, session, next);
    }

    /** A pending reward offer takes the conversation until it is settled, then the conversation resumes where it was going. */
    @Nullable
    private static Target withRewardChoice(ServerPlayer player, Session session, @Nullable Target next) {
        Dialogue reward = StorySceneLoader.dialogue(RewardChoices.DIALOGUE);
        if (reward == null) return next;
        if (RewardChoices.pending(player)) {
            if (session.dialogue != reward) session.afterReward = next;
            RewardChoices.fillVars(player, session.vars);
            return new Target(reward, Dialogue.MAIN, 0);
        }
        if (session.dialogue == reward) {
            Target resume = session.afterReward;
            session.afterReward = null;
            return next != null ? next : resume;
        }
        return next;
    }

    /** Opens a waiting reward offer as narration for a player who left the conversation that earned it. */
    private static void openWaitingRewards(net.minecraft.server.MinecraftServer server) {
        Dialogue reward = StorySceneLoader.dialogue(RewardChoices.DIALOGUE);
        if (reward == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!RewardChoices.pending(player) || !isFree(player)) continue;
            Session session = new Session(reward, null, player.serverLevel());
            RewardChoices.fillVars(player, session.vars);
            begin(player, session);
        }
    }

    private static void run(StoryContext context, List<Act> actions, Session session) {
        for (Act act : actions) {
            try {
                act.action().run(context);
            } catch (StoryBlocked blocked) {
                throw blocked;
            } catch (RuntimeException exception) {
                SuperiorStory.LOGGER.error("Dialogue {} action {} failed", session.dialogue.id(), act.key(), exception);
            }
        }
    }

    /** Plays the branch the author gave for a blocked step, or says why in one line and ends. */
    private static void blocked(ServerPlayer player, Session session, @Nullable String branch, StoryBlocked blocked) {
        session.vars.putAll(blocked.vars());
        if (branch != null && !branch.equals(Dialogue.END)) {
            goTo(player, session, new Target(session.dialogue, branch, 0));
            return;
        }
        goTo(player, session, new Target(session.dialogue.single(Text.translation(blocked.reasonKey())), Dialogue.MAIN, 0));
    }

    private static boolean endsAfter(Session session, @Nullable String goTo) {
        if (goTo == null) return session.index + 1 >= session.dialogue.branches().get(session.branch).size();
        return goTo.equals(Dialogue.END);
    }

    /** A null goTo continues with the next line; with no next line (or {@code nextLine} false) the conversation ends. */
    @Nullable
    private static Target target(Session session, @Nullable String goTo, boolean nextLine) {
        if (Dialogue.END.equals(goTo)) return null;
        if (goTo != null) return new Target(session.dialogue, goTo, 0);
        if (nextLine && session.index + 1 < session.dialogue.branches().get(session.branch).size()) {
            return new Target(session.dialogue, session.branch, session.index + 1);
        }
        return null;
    }

    private static void goTo(ServerPlayer player, Session session, @Nullable Target target) {
        if (target == null) {
            end(player, DialogueEndEvent.Reason.COMPLETED);
            return;
        }
        session.dialogue = target.dialogue();
        session.branch = target.branch();
        session.index = target.index();
        sendLine(player, session);
    }

    // ---------------------------------------------------------------- start

    private static void start(ServerPlayer player, Dialogue dialogue, StoryHooks.Speaker.Bound speaker) {
        Session session = new Session(dialogue, speaker, player.serverLevel());
        begin(player, session);
    }

    /** Opens a triggered dialogue as narration: no speaker, no portrait. */
    static void startNarration(ServerPlayer player, Dialogue dialogue) {
        begin(player, new Session(dialogue, null, player.serverLevel()));
    }

    private static void begin(ServerPlayer player, Session session) {
        StoryQuests.Instance latest = StoryQuests.latest(player);
        if (latest != null && latest.stage() != com.mugloved.superiorstory.api.StoryQuestStage.OFFERED) {
            session.vars.putAll(StoryQuests.vars(player, latest));   // a returning player is remembered
        }
        addSceneQuestVars(player, session);
        if (session.speaker != null) session.speaker.hold();
        SESSIONS.put(player.getUUID(), session);
        sendLine(player, session);
    }

    /** Whether the player can start a conversation right now. */
    static boolean isFree(ServerPlayer player) {
        return player.isAlive() && !StoryServer.isBusy(player) && !SESSIONS.containsKey(player.getUUID());
    }

    /**
     * A scene that accepts, hands over, turns in, delivers, or locates a quest is filled with that quest's facts from its first
     * line: {structure}, {boss}, {danger}, {item}, {reward}. The one the player is on wins, else the first the scene names.
     */
    private static void addSceneQuestVars(ServerPlayer player, Session session) {
        QuestDef chosen = null;
        for (ResourceLocation id : session.dialogue.quests()) {
            QuestDef def = QuestDefinitions.get(id);
            if (def == null) continue;
            if (chosen == null) chosen = def;
            StoryQuests.Instance instance = StoryQuests.find(player, id, null);
            if (instance != null && instance.stage().active()) {
                chosen = def;
                break;
            }
        }
        if (chosen != null) session.vars.putAll(StoryQuests.questVars(player, chosen));
    }

    private static StoryContext context(ServerPlayer player, Session session) {
        return new StoryContext(player, session.speaker == null ? null : session.speaker.entity(),
            session.speaker == null ? null : session.speaker.blockPos(), session.vars, null);
    }

    private static void sendLine(ServerPlayer player, Session session) {
        StoryContext context = context(player, session);
        session.vars.putAll(StoryHooks.derivedVars(context));
        Dialogue.Line line = session.line();
        Text picked = line.available(session.vars, context) ? line.pick(context, session.random()) : null;
        while (picked == null) {   // unavailable lines and pools with no eligible entry are skipped
            if (session.index + 1 >= session.dialogue.branches().get(session.branch).size()) {
                end(player, DialogueEndEvent.Reason.COMPLETED);
                return;
            }
            session.index++;
            line = session.line();
            picked = line.available(session.vars, context) ? line.pick(context, session.random()) : null;
        }
        StoryHooks.LineKind.Job job = null;
        if (line.kind() != null) {
            try {
                job = StoryHooks.lineKind(line.kind().key()).start(context, line.kind().spec());
            } catch (StoryBlocked blocked) {
                blocked(player, session, line.blocked(), blocked);
                return;
            }
        }
        List<Dialogue.Choice> visible = new ArrayList<>();
        for (Dialogue.Choice choice : line.choices()) {
            if (choice.visible(context)) visible.add(choice);
        }
        session.visible = visible;
        session.serial++;
        List<DialoguePackets.ChoiceView> views = new ArrayList<>(visible.size());
        for (Dialogue.Choice choice : visible) views.add(new DialoguePackets.ChoiceView(
            StoryHooks.prepareText(choice.label(), session.dialogue.id().getNamespace(), context, session.random()), shown(player, context, choice)));
        boolean open = !session.opened;
        session.opened = true;
        session.lastNode = new DialoguePackets.Node(session.id, session.serial, session.speakerKind(), session.npcId(), session.blockPos(),
            open, false, StoryHooks.prepareText(session.dialogue.speaker(), session.dialogue.id().getNamespace(), context, session.random()),
            StoryHooks.prepareText(picked, session.dialogue.id().getNamespace(), context, session.random()), line.accent(), line.pace(), views, job != null,
            Map.copyOf(session.vars));
        DialoguePackets.sendToClient(player, session.lastNode);
        if (job != null) {
            session.job = job;
            session.quietWaypoint = job.suppressesWaypoint();
            session.afterJob = null;
        }
    }

    private static List<DialoguePackets.ItemShow> shown(ServerPlayer player, StoryContext context, Dialogue.Choice choice) {
        List<DialoguePackets.ItemShow> shown = new ArrayList<>();
        for (Act act : choice.actions()) {
            for (ItemSpec spec : act.action().shows(context)) {
                if (shown.size() < 4) shown.add(new DialoguePackets.ItemShow(spec.displayId(player), spec.quantity()));
            }
        }
        return shown;
    }

    // ---------------------------------------------------------------- line kinds (structure search)

    private static void finishJob(ServerPlayer player, Session session) {
        StoryHooks.LineKind.Job job = session.job;
        session.job = null;
        StoryHooks.LineKind.Result result = job.result();
        session.vars.putAll(result.vars());
        if (result.success()) {
            Dialogue dedicated = result.structure() == null ? null : selectForStructure(player, result.structure());
            String branch = result.branch();
            boolean hasBranch = branch != null && session.dialogue.branches().containsKey(branch);
            if (dedicated == null && !hasBranch && result.dialogue() != null) dedicated = StorySceneLoader.dialogue(result.dialogue());
            if (!hasBranch && branch != null && !Dialogue.END.equals(branch)) branch = null;   // an author-less branch is a hand-off hint, not a target
            session.afterJob = dedicated != null ? new Target(dedicated, Dialogue.MAIN, 0) : target(session, branch, true);
        } else {
            session.afterJob = target(session, result.branch(), false);
        }
        DialoguePackets.sendToClient(player, session.lastNode.updated(false, session.vars));
    }

    /** The most specific structure-bound dialogue whose conditions pass, or null to use the line's generic branch. */
    @Nullable
    private static Dialogue selectForStructure(ServerPlayer player, ResourceLocation structure) {
        var inTag = StructureProfiles.tagTester(player.serverLevel(), structure);
        StoryContext context = StoryContext.of(player);
        Dialogue best = null;
        int bestMatch = 0;
        for (Dialogue candidate : StorySceneLoader.dialogues()) {
            if (candidate.structure() == null || !candidate.guard().passes(context)) continue;
            int match = candidate.structure().match(structure, inTag);
            if (match == 0) continue;
            if (best == null || match > bestMatch || (match == bestMatch && outranks(candidate, best))) {
                best = candidate;
                bestMatch = match;
            }
        }
        return best;
    }

    /** Whether the player's current locate line opted out of the map marker ({@code "waypoint": false}). */
    static boolean suppressesWaypoint(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.quietWaypoint;
    }

    // ---------------------------------------------------------------- selection

    /** The marker over a mob for this player: 0 none, 1 "?" (it can talk), 2 "!" (talking would progress a quest). */
    static int marker(ServerPlayer player, Entity entity) {
        if (!entity.isAlive() || !willingToTalk(entity, player)) return 0;
        StoryHooks.SpeakerTarget target = new StoryHooks.SpeakerTarget(player.serverLevel(), entity, null);
        Dialogue dialogue = select(player, target);
        if (dialogue == null) return 0;
        return dialogue.progresses(new StoryContext(player, entity, null, new LinkedHashMap<>(), null)) ? 2 : 1;
    }

    @Nullable
    private static Dialogue select(ServerPlayer player, StoryHooks.SpeakerTarget target) {
        StoryContext context = new StoryContext(player, target.entity(), target.block(), new LinkedHashMap<>(), null);
        Dialogue best = null;
        for (Dialogue candidate : StorySceneLoader.dialogues()) {
            if (!candidate.matches(target) || !candidate.guard().passes(context)) continue;
            if (best == null || outranks(candidate, best)) best = candidate;
        }
        return best;
    }

    /** Higher priority wins; at equal priority a conditional dialogue beats an unconditional one; then ID order. */
    static boolean outranks(Dialogue a, Dialogue b) {
        if (a.priority() != b.priority()) return a.priority() > b.priority();
        if (a.conditional() != b.conditional()) return a.conditional();
        return a.id().toString().compareTo(b.id().toString()) < 0;
    }

    // ---------------------------------------------------------------- lifecycle

    /** Closes any conversation the player is in; used when another Story scene takes over. */
    public static void abort(ServerPlayer player) {
        end(player, DialogueEndEvent.Reason.INTERRUPTED);
    }

    private static void end(ServerPlayer player, DialogueEndEvent.Reason reason) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) return;
        if (session.job != null) session.job.cancel();
        Entity entity = session.speaker == null ? null : session.speaker.entity();
        if (session.speaker != null) session.speaker.release();
        MinecraftForge.EVENT_BUS.post(new DialogueEndEvent(player, entity instanceof Mob mob ? mob : null, session.dialogue.id(), reason,
            session.lastOutcome));
        if (StoryNetwork.isPresentOn(player.connection.connection)) {
            DialoguePackets.sendToClient(player, DialoguePackets.Node.closing(session.id, session.serial, session.speakerKind(),
                session.npcId(), session.blockPos()));
        }
        StoryHooks.fire("dialogue_end", player, new CoreModules.DialogueEnded(session.dialogue.id(), session.lastOutcome, reason));
    }

    private static boolean inUse(UUID key) {
        for (Session session : SESSIONS.values()) {
            if (session.speaker != null && session.speaker.key().equals(key)) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        StoryTriggers.tick(event.getServer());
        if (event.getServer().getTickCount() % 40 == 0) openWaitingRewards(event.getServer());
        if (SESSIONS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> entry : List.copyOf(SESSIONS.entrySet())) {
            Session session = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                SESSIONS.remove(entry.getKey(), session);
                if (session.job != null) session.job.cancel();
                if (session.speaker != null) session.speaker.release();
                continue;
            }
            if (session.speaker != null && !session.speaker.alive()) {
                end(player, DialogueEndEvent.Reason.NPC_LOST);
            } else if (!player.isAlive() || player.level() != session.level || now - session.startedAt > MAX_SESSION_MS
                || (session.speaker != null && session.speaker.distanceSqr(player) > LEAVE_RANGE_SQR)) {
                end(player, DialogueEndEvent.Reason.INTERRUPTED);
            } else {
                if (session.speaker != null) session.speaker.tick(player);
                if (session.job != null && session.job.poll(context(player, session))) finishJob(player, session);
            }
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (SESSIONS.isEmpty()) return;
        LivingEntity victim = event.getEntity();
        for (Map.Entry<UUID, Session> entry : List.copyOf(SESSIONS.entrySet())) {
            Session session = entry.getValue();
            if (!entry.getKey().equals(victim.getUUID()) && (session.speaker == null || !session.speaker.key().equals(victim.getUUID()))) continue;
            ServerPlayer player = victim.getServer() == null ? null : victim.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null) end(player, DialogueEndEvent.Reason.INTERRUPTED);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            end(player, DialogueEndEvent.Reason.INTERRUPTED);
            StoryTriggers.forget(player);
            LinePools.forget(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LinePools.clearHistory();
        for (UUID playerId : List.copyOf(SESSIONS.keySet())) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(playerId);
            if (player != null) end(player, DialogueEndEvent.Reason.INTERRUPTED);
        }
    }

    /** Recovery: an NPC saved while frozen (crash, unload) gets its NoAI value back when it next loads. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        CompoundTag data = mob.getPersistentData();
        if (data.contains(EntitySpeaker.RESTORE_KEY) && !inUse(mob.getUUID())) {
            mob.setNoAi(data.getBoolean(EntitySpeaker.RESTORE_KEY));
            data.remove(EntitySpeaker.RESTORE_KEY);
        }
    }
}

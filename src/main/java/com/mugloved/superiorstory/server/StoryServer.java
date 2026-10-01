package com.mugloved.superiorstory.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.network.StoryNetwork;
import com.mugloved.superiorstory.scene.StoryScene;
import com.alexh.superiorrespawn.protection.RespawnProtectionService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Server side: selects a scene, stores the first-join flag, and bounds each session.
 * Superior Respawn owns protection for active sessions.
 *
 * The flag lives in the player's persisted data, which is saved with the world
 * (so it is per player, per world) and survives death/respawn.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID)
public final class StoryServer {
    public static final ResourceLocation HARDCODED_INTRO_ID = new ResourceLocation(SuperiorStory.MODID, "hardcoded_intro");
    private static final String SEEN_KEY = "superiorstory_intro_seen";
    /** Failsafe: protection never lasts longer than this, even if the client never reports back. */
    private static final long MAX_PROTECTION_MS = 20L * 60L * 1000L;

    private record Session(long id, long startedAt, boolean firstJoin) {}
    private static final AtomicLong NEXT_SESSION = new AtomicLong();
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private StoryServer() {}

    // ---------------------------------------------------------------- login / logout

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Only players who actually have SuperiorStory installed get the intro.
        if (!StoryNetwork.isPresentOn(player.connection.connection)) return;

        if (!hasSeen(player)) {
            StoryScene selected = StorySceneLoader.firstJoin();
            start(player, selected == null ? HARDCODED_INTRO_ID : selected.id(), selected, false, true);
        } else {
            StoryNetwork.sendScene(player, false, 0, false, HARDCODED_INTRO_ID, null);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) end(player);
    }

    public static void onProgress(ServerPlayer player, long sessionId, int stage) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || (sessionId != session.id && !(sessionId == 0
                && stage == StoryNetwork.PROGRESS_CANCELED && session.firstJoin))) return;
        if (stage == StoryNetwork.PROGRESS_SEEN) {
            if (session.firstJoin) setSeen(player, true);
        } else if (stage == StoryNetwork.PROGRESS_FINISHED || stage == StoryNetwork.PROGRESS_CANCELED) {
            end(player);
        }
    }

    /** Whether an intro or scene is running for this player; dialogue waits for it. */
    public static boolean isBusy(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    private static void start(ServerPlayer player, ResourceLocation sceneId, StoryScene scene,
                              boolean immediate, boolean firstJoin) {
        DialogueServer.abort(player);
        if (scene != null) {
            var context = com.mugloved.superiorstory.api.StoryContext.of(player);
            String namespace = sceneId.getNamespace();
            List<StoryScene.Beat> beats = new java.util.ArrayList<>();
            for (StoryScene.Beat beat : scene.beats()) {
                List<StoryScene.Text> choices = beat.choices().stream().map(text ->
                    com.mugloved.superiorstory.api.StoryHooks.prepareText(text, namespace, context, player.getRandom())).toList();
                beats.add(new StoryScene.Beat(com.mugloved.superiorstory.api.StoryHooks.prepareText(beat.text(), namespace, context, player.getRandom()), choices, beat.pace(), beat.accent()));
            }
            scene = new StoryScene(scene.id(), scene.firstJoin(), scene.end(), beats);
        }
        long sessionId = NEXT_SESSION.incrementAndGet();
        SESSIONS.put(player.getUUID(), new Session(sessionId, System.currentTimeMillis(), firstJoin));
        RespawnProtectionService.protectStoryScene(player);
        StoryNetwork.sendScene(player, true, sessionId, immediate, sceneId, scene);
    }

    private static void end(ServerPlayer player) {
        if (SESSIONS.remove(player.getUUID()) != null) {
            RespawnProtectionService.releaseStoryScene(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SESSIONS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> entry : SESSIONS.entrySet()) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null && now - entry.getValue().startedAt > MAX_PROTECTION_MS) end(player);
            else if (player == null) SESSIONS.remove(entry.getKey(), entry.getValue());
        }
    }

    // ---------------------------------------------------------------- flag storage

    public static CompoundTag persisted(Player player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(Player.PERSISTED_NBT_TAG)) data.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return data.getCompound(Player.PERSISTED_NBT_TAG);
    }

    public static boolean hasSeen(Player player) {
        return persisted(player).getBoolean(SEEN_KEY);
    }

    public static void setSeen(Player player, boolean seen) {
        if (seen) persisted(player).putBoolean(SEEN_KEY, true);
        else persisted(player).remove(SEEN_KEY);
    }

    // ---------------------------------------------------------------- commands
    // /superiorstory play <scene> [players] - plays either a datapack scene or the hardcoded intro
    // /superiorstory open <dialogue> [players] - opens a dialogue that has a trigger, as narration
    // /superiorstory replay [players]  - resets and replays the hardcoded first-join intro
    // /superiorstory reset  [players]  - intro plays again next time they join this world
    // /superiorstory forget [players]  - villagers may send them to structures they were already sent to

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal(SuperiorStory.MODID)
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("play")
                        .then(Commands.argument("scene", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
                                        Stream.concat(Stream.of(HARDCODED_INTRO_ID), StorySceneLoader.ids().stream()), builder))
                                .executes(ctx -> play(ctx, ResourceLocationArgument.getId(ctx, "scene"),
                                        List.of(ctx.getSource().getPlayerOrException())))
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(ctx -> play(ctx, ResourceLocationArgument.getId(ctx, "scene"),
                                                EntityArgument.getPlayers(ctx, "players"))))))
                .then(Commands.literal("open")
                        .then(Commands.argument("dialogue", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
                                        StorySceneLoader.dialogues().stream()
                                                .filter(dialogue -> dialogue.triggerKey() != null)
                                                .map(dialogue -> dialogue.id()), builder))
                                .executes(ctx -> open(ctx, ResourceLocationArgument.getId(ctx, "dialogue"),
                                        List.of(ctx.getSource().getPlayerOrException())))
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(ctx -> open(ctx, ResourceLocationArgument.getId(ctx, "dialogue"),
                                                EntityArgument.getPlayers(ctx, "players"))))))
                .then(Commands.literal("replay")
                        .executes(ctx -> replay(ctx, List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(ctx -> replay(ctx, EntityArgument.getPlayers(ctx, "players")))))
                .then(Commands.literal("forget")
                        .executes(ctx -> forget(ctx, List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(ctx -> forget(ctx, EntityArgument.getPlayers(ctx, "players")))))
                .then(Commands.literal("reset")
                        .executes(ctx -> reset(ctx, List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(ctx -> reset(ctx, EntityArgument.getPlayers(ctx, "players"))))));
    }

    private static int play(CommandContext<CommandSourceStack> ctx, ResourceLocation id,
                            Collection<ServerPlayer> players) {
        StoryScene scene = id.equals(HARDCODED_INTRO_ID) ? null : StorySceneLoader.get(id);
        if (scene == null && !id.equals(HARDCODED_INTRO_ID)) {
            ctx.getSource().sendFailure(Component.translatable("superiorstory.command.unknown", id));
            return 0;
        }
        int count = 0;
        for (ServerPlayer player : players) {
            if (!StoryNetwork.isPresentOn(player.connection.connection)) continue;
            start(player, id, scene, true, false);
            ctx.getSource().sendSuccess(() -> Component.translatable("superiorstory.command.play", id,
                    player.getDisplayName()), true);
            count++;
        }
        return count;
    }

    private static int open(CommandContext<CommandSourceStack> ctx, ResourceLocation id, Collection<ServerPlayer> players) {
        int count = 0;
        for (ServerPlayer player : players) {
            if (StoryNetwork.isPresentOn(player.connection.connection) && StoryTriggers.open(player, id)) count++;
        }
        if (count == 0) ctx.getSource().sendFailure(Component.translatable("superiorstory.command.open_failed", id));
        return count;
    }

    private static int replay(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) throws CommandSyntaxException {
        int n = 0;
        for (ServerPlayer p : players) {
            if (!StoryNetwork.isPresentOn(p.connection.connection)) continue;
            setSeen(p, false);
            start(p, HARDCODED_INTRO_ID, null, true, true);
            ctx.getSource().sendSuccess(() -> Component.translatable("superiorstory.command.replay", p.getDisplayName()), true);
            n++;
        }
        return n;
    }

    private static int forget(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            StoryQuests.forget(p);
            ctx.getSource().sendSuccess(() -> Component.translatable("superiorstory.command.forget", p.getDisplayName()), true);
        }
        return players.size();
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            setSeen(p, false);
            ctx.getSource().sendSuccess(() -> Component.translatable("superiorstory.command.reset", p.getDisplayName()), true);
        }
        return players.size();
    }
}

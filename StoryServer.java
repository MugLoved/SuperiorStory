package com.mugloved.superiorstory.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.network.StoryNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side: decides who sees the intro, stores the "seen" flag, and keeps players
 * safe while they read.
 *
 * The flag lives in the player's persisted data, which is saved with the world
 * (so it is per player, per world) and survives death/respawn.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID)
public final class StoryServer {
    private static final String SEEN_KEY = "superiorstory_intro_seen";
    /** Failsafe: protection never lasts longer than this, even if the client never reports back. */
    private static final long MAX_PROTECTION_MS = 20L * 60L * 1000L;

    /** Players currently in the intro -> time protection started. */
    private static final Map<UUID, Long> PROTECTED = new ConcurrentHashMap<>();

    private StoryServer() {}

    // ---------------------------------------------------------------- login / logout

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Only players who actually have SuperiorStory installed get the intro.
        if (!StoryNetwork.isPresentOn(player.connection.connection)) return;

        boolean play = !hasSeen(player);
        if (play) PROTECTED.put(player.getUUID(), System.currentTimeMillis());
        StoryNetwork.sendIntro(player, play);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PROTECTED.remove(event.getEntity().getUUID());
    }

    public static void onProgress(ServerPlayer player, int stage) {
        if (stage == StoryNetwork.PROGRESS_SEEN) {
            setSeen(player, true);
        } else if (stage == StoryNetwork.PROGRESS_FINISHED) {
            PROTECTED.remove(player.getUUID());
        }
    }

    // ---------------------------------------------------------------- protection

    private static boolean isProtected(Object entity) {
        return entity instanceof ServerPlayer p && PROTECTED.containsKey(p.getUUID());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttacked(LivingAttackEvent event) {
        if (isProtected(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTargeted(LivingChangeTargetEvent event) {
        if (isProtected(event.getNewTarget())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PROTECTED.isEmpty()) return;
        long now = System.currentTimeMillis();
        PROTECTED.entrySet().removeIf(e -> now - e.getValue() > MAX_PROTECTION_MS);
        for (UUID id : PROTECTED.keySet()) {
            ServerPlayer p = event.getServer().getPlayerList().getPlayer(id);
            if (p == null) continue;
            p.fallDistance = 0;
            p.setAirSupply(p.getMaxAirSupply());
            p.clearFire();
        }
    }

    // ---------------------------------------------------------------- flag storage

    private static CompoundTag persisted(Player player) {
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
    // /superiorstory replay [players]  - plays the intro again right now (great for testing)
    // /superiorstory reset  [players]  - intro plays again next time they join this world

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal(SuperiorStory.MODID)
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("replay")
                        .executes(ctx -> replay(ctx, List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(ctx -> replay(ctx, EntityArgument.getPlayers(ctx, "players")))))
                .then(Commands.literal("reset")
                        .executes(ctx -> reset(ctx, List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(ctx -> reset(ctx, EntityArgument.getPlayers(ctx, "players"))))));
    }

    private static int replay(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) throws CommandSyntaxException {
        int n = 0;
        for (ServerPlayer p : players) {
            if (!StoryNetwork.isPresentOn(p.connection.connection)) continue;
            setSeen(p, false);
            PROTECTED.put(p.getUUID(), System.currentTimeMillis());
            StoryNetwork.sendIntro(p, true);
            ctx.getSource().sendSuccess(() -> Component.translatable("superiorstory.command.replay", p.getDisplayName()), true);
            n++;
        }
        return n;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            setSeen(p, false);
            ctx.getSource().sendSuccess(() -> Component.translatable("superiorstory.command.reset", p.getDisplayName()), true);
        }
        return players.size();
    }
}

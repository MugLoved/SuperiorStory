package com.mugloved.superiorstory.server;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.network.DialoguePackets;
import com.mugloved.superiorstory.network.StoryNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The "?" and "!" over NPCs: once a second, for each player with Story on the client, the mobs nearby that the player can talk
 * to, and whether talking would progress a quest. The client gets the map only when it changes. Markers are per player because the
 * dialogue a mob would use, and the player's quest state, are per player.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID)
public final class StoryMarkers {
    private static final int PERIOD_TICKS = 20;
    private static final double RANGE = 24;   // the farthest the client draws a marker (the "!")
    private static final Map<UUID, Map<Integer, Byte>> SENT = new HashMap<>();

    private StoryMarkers() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % PERIOD_TICKS != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!StoryNetwork.isPresentOn(player.connection.connection)) continue;
            Map<Integer, Byte> now = new HashMap<>();
            for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(RANGE))) {
                int kind = DialogueServer.marker(player, mob);
                if (kind != 0) now.put(mob.getId(), (byte) kind);
            }
            if (now.equals(SENT.getOrDefault(player.getUUID(), Map.of()))) continue;
            SENT.put(player.getUUID(), now);
            DialoguePackets.sendToClient(player, new DialoguePackets.Markers(now));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }
}

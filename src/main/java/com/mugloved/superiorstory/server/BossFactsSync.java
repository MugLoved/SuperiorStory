package com.mugloved.superiorstory.server;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.network.DialoguePackets;
import com.mugloved.superiorstory.network.StoryNetwork;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends the boss facts (look, lore, lair names) from the structure profiles to clients, on join and after every reload, so the
 * boss hover in a conversation can show them. Profiles are server data; the client never reads the datapack.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID)
public final class BossFactsSync {
    private BossFactsSync() {}

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        Map<ResourceLocation, DialoguePackets.BossEntry> facts = new LinkedHashMap<>();
        StructureProfiles.bossFacts().forEach((boss, fact) -> facts.put(boss, new DialoguePackets.BossEntry(fact.look(), fact.lore(), fact.lairs())));
        DialoguePackets.Bosses message = new DialoguePackets.Bosses(facts);
        if (event.getPlayer() != null) {
            send(event.getPlayer(), message);
            return;
        }
        for (ServerPlayer player : event.getPlayerList().getPlayers()) send(player, message);
    }

    private static void send(ServerPlayer player, DialoguePackets.Bosses message) {
        if (StoryNetwork.isPresentOn(player.connection.connection)) DialoguePackets.sendToClient(player, message);
    }
}

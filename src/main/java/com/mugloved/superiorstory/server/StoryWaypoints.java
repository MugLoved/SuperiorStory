package com.mugloved.superiorstory.server;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryQuestEvent;
import com.mugloved.superiorstory.api.StoryQuestStage;
import com.mugloved.superiorstory.dialogue.QuestDef;
import com.mugloved.superiorstory.network.DialoguePackets;
import com.mugloved.superiorstory.network.StoryNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Drives the client's map marker from quest progress: the structure marker appears when the player accepts and goes away
 * once they reach it (or finish it some other way); when the next step is turning in, a "Return to <giver>" marker points
 * at where the quest was accepted. A structure that was only offered never gets a marker.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID)
public final class StoryWaypoints {
    private StoryWaypoints() {}

    /** Adds or removes one map marker for a player who has Story installed; {@code returning} labels it "Return to <name>". */
    static void send(ServerPlayer player, BlockPos at, String name, boolean add, boolean returning) {
        if (player.connection == null || !StoryNetwork.isPresentOn(player.connection.connection)) return;
        DialoguePackets.sendToClient(player, new DialoguePackets.Waypoint(at.getX(), at.getY(), at.getZ(), name, add, returning));
    }

    @SubscribeEvent
    public static void onQuest(StoryQuestEvent event) {
        ServerPlayer player = event.player();
        if (player.connection == null || !StoryNetwork.isPresentOn(player.connection.connection)) return;
        StoryQuestStage stage = event.stage();
        BlockPos at = event.position();
        StoryQuests.Instance instance = StoryQuests.find(player, event.questId(), event.locationId());
        boolean placed = !event.locationKind().isEmpty();   // a fetch quest has no place to mark
        if (stage == StoryQuestStage.ACCEPTED) {
            if (!placed || instance == null || DialogueServer.suppressesWaypoint(player)) return;
            QuestDef def = event.questId() == null ? null : QuestDefinitions.get(event.questId());
            String name = StoryQuests.placeName(player, instance, def);
            DialoguePackets.sendToClient(player, new DialoguePackets.Waypoint(at.getX(), at.getY(), at.getZ(), name, true, false));
            return;
        }
        if (stage == StoryQuestStage.OFFERED) return;
        if (placed) DialoguePackets.sendToClient(player, new DialoguePackets.Waypoint(at.getX(), at.getY(), at.getZ(), "", false, false));
        BlockPos back = instance == null ? null : instance.acceptPos();
        if (back == null) return;
        if (stage == StoryQuestStage.BOSS_DEFEATED || stage == StoryQuestStage.COLLECTED) {
            if (!instance.giver().isEmpty() && instance.pending() == 0) {   // with objectives left, the last one to finish sends this
                DialoguePackets.sendToClient(player, new DialoguePackets.Waypoint(back.getX(), back.getY(), back.getZ(), instance.giver(), true, true));
            }
        } else if (stage == StoryQuestStage.TURNED_IN) {
            DialoguePackets.sendToClient(player, new DialoguePackets.Waypoint(back.getX(), back.getY(), back.getZ(), "", false, true));
        }
    }
}

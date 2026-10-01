package com.mugloved.superiorstory.server;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.dialogue.Dialogue;
import com.mugloved.superiorstory.module.CoreModules;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens dialogues that have a {@code trigger}. A fired trigger picks the best matching dialogue whose conditions pass and
 * queues it for the player; it opens as narration on the next tick the player is free. At most one is pending per player (a
 * newer one replaces it) and an unopened one is dropped after five minutes.
 */
public final class StoryTriggers {
    private static final long PENDING_MS = 5L * 60L * 1000L;
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private record Pending(Dialogue dialogue, long queuedAt) {}

    private StoryTriggers() {}

    /** Called through {@code StoryHooks.fire}. */
    public static void fire(String key, ServerPlayer player, Object payload) {
        StoryContext context = StoryContext.of(player);
        Dialogue best = null;
        for (Dialogue candidate : StorySceneLoader.dialogues()) {
            if (!candidate.triggeredBy(key, payload) || !candidate.guard().passes(context)) continue;
            if (payload instanceof CoreModules.DialogueEnded ended && candidate.id().equals(ended.dialogue())) continue;   // never re-open itself
            if (best == null || DialogueServer.outranks(candidate, best)) best = candidate;
        }
        if (best != null) queue(player, best);
    }

    /** Queues a triggered dialogue by ID (the {@code open} action and {@code /superiorstory open}); false when it cannot open. */
    public static boolean open(ServerPlayer player, ResourceLocation id) {
        Dialogue dialogue = null;
        for (Dialogue candidate : StorySceneLoader.dialogues()) {
            if (candidate.id().equals(id)) dialogue = candidate;
        }
        if (dialogue == null || dialogue.triggerKey() == null) {
            SuperiorStory.LOGGER.warn("Cannot open {}: not a dialogue with a trigger", id);
            return false;
        }
        if (!dialogue.guard().passes(StoryContext.of(player))) return false;
        queue(player, dialogue);
        return true;
    }

    private static void queue(ServerPlayer player, Dialogue dialogue) {
        PENDING.put(player.getUUID(), new Pending(dialogue, System.currentTimeMillis()));
    }

    static void forget(ServerPlayer player) {
        PENDING.remove(player.getUUID());
    }

    static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> entry : PENDING.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || now - entry.getValue().queuedAt() > PENDING_MS
                || !com.mugloved.superiorstory.network.StoryNetwork.isPresentOn(player.connection.connection)) {
                PENDING.remove(entry.getKey(), entry.getValue());
            } else if (DialogueServer.isFree(player)) {
                PENDING.remove(entry.getKey(), entry.getValue());
                DialogueServer.startNarration(player, entry.getValue().dialogue());
            }
        }
    }
}

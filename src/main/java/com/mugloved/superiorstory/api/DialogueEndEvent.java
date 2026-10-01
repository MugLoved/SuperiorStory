package com.mugloved.superiorstory.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;

/**
 * Posted on {@code MinecraftForge.EVENT_BUS}, server side, exactly once when a conversation closes for any reason.
 *
 * @param lastOutcome the most recent non-{@link DialogueOutcome#NONE} outcome the player picked, or NONE
 */
public class DialogueEndEvent extends Event {
    public enum Reason {
        /** A choice or the last line closed the conversation. */
        COMPLETED,
        /** The player pressed Esc or closed the screen. */
        LEFT,
        /** Damage, distance, death, dimension change, logout, timeout, or another Story scene. */
        INTERRUPTED,
        /** The NPC died or unloaded. */
        NPC_LOST
    }

    private final ServerPlayer player;
    private final Mob npc;
    private final ResourceLocation dialogueId;
    private final Reason reason;
    private final DialogueOutcome lastOutcome;

    public DialogueEndEvent(ServerPlayer player, @Nullable Mob npc, ResourceLocation dialogueId, Reason reason,
                            DialogueOutcome lastOutcome) {
        this.player = player;
        this.npc = npc;
        this.dialogueId = dialogueId;
        this.reason = reason;
        this.lastOutcome = lastOutcome;
    }

    public ServerPlayer player() { return player; }
    /** Null when the NPC is no longer loaded. */
    @Nullable public Mob npc() { return npc; }
    public ResourceLocation dialogueId() { return dialogueId; }
    public Reason reason() { return reason; }
    public DialogueOutcome lastOutcome() { return lastOutcome; }
}

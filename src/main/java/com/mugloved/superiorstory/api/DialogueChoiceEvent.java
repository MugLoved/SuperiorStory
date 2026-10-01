package com.mugloved.superiorstory.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;

/**
 * Posted on {@code MinecraftForge.EVENT_BUS}, server side, after a player picks any dialogue choice
 * and the choice actions have run. Fires for every choice, including plain "continue" choices.
 * Other mods only subscribe; nothing needs registering.
 *
 * @param npc          the speaking mob, or null for a block speaker or narration
 * @param choiceId    the author-supplied {@code id}, or {@code <branch>/<line>/<index>} by default
 * @param outcome     the author-supplied label, {@link DialogueOutcome#NONE} when unlabeled
 * @param endsDialogue whether this choice closes the conversation
 */
public class DialogueChoiceEvent extends Event {
    private final ServerPlayer player;
    private final Mob npc;
    private final ResourceLocation dialogueId;
    private final String choiceId;
    private final DialogueOutcome outcome;
    private final boolean endsDialogue;

    public DialogueChoiceEvent(ServerPlayer player, @Nullable Mob npc, ResourceLocation dialogueId, String choiceId,
                               DialogueOutcome outcome, boolean endsDialogue) {
        this.player = player;
        this.npc = npc;
        this.dialogueId = dialogueId;
        this.choiceId = choiceId;
        this.outcome = outcome;
        this.endsDialogue = endsDialogue;
    }

    public ServerPlayer player() { return player; }
    @Nullable public Mob npc() { return npc; }
    public ResourceLocation dialogueId() { return dialogueId; }
    public String choiceId() { return choiceId; }
    public DialogueOutcome outcome() { return outcome; }
    public boolean endsDialogue() { return endsDialogue; }
}

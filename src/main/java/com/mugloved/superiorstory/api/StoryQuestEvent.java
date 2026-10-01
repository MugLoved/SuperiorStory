package com.mugloved.superiorstory.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;

/**
 * Posted on {@code MinecraftForge.EVENT_BUS}, server side, each time a player reaches a new stage of a quest
 * (see {@link StoryQuestStage}). Subscribe to reward, unlock, or track progress; nothing to register.
 * {@code questId} is the quest file's ID, or null for a structure quest with no quest file. {@code locationKind} is the
 * quest location kind that found the place ({@code structure}, {@code pool}, {@code biome}, ...), empty for a fetch quest,
 * and {@code locationId} the found place's ID.
 */
public class StoryQuestEvent extends Event {
    private final ServerPlayer player;
    private final ResourceLocation questId;
    private final String locationKind;
    private final ResourceLocation locationId;
    private final BlockPos position;
    private final StoryQuestStage stage;

    public StoryQuestEvent(ServerPlayer player, @Nullable ResourceLocation questId, String locationKind, ResourceLocation locationId,
                           BlockPos position, StoryQuestStage stage) {
        this.player = player;
        this.questId = questId;
        this.locationKind = locationKind;
        this.locationId = locationId;
        this.position = position;
        this.stage = stage;
    }

    public ServerPlayer player() { return player; }
    @Nullable public ResourceLocation questId() { return questId; }
    public String locationKind() { return locationKind; }
    public ResourceLocation locationId() { return locationId; }
    public BlockPos position() { return position; }
    public StoryQuestStage stage() { return stage; }
}

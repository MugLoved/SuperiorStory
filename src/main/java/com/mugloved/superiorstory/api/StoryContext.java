package com.mugloved.superiorstory.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What every Story module sees: the player, the level, the speaker (an entity or a block position, or neither for
 * narration), the conversation variables, and an optional source ID (the quest a reward belongs to).
 * {@link #vars()} is the live conversation variable map; modules may add to it.
 */
public final class StoryContext {
    private final ServerPlayer player;
    private final Entity entity;
    private final BlockPos block;
    private final Map<String, String> vars;
    private final String sourceId;

    public StoryContext(ServerPlayer player, @Nullable Entity entity, @Nullable BlockPos block, Map<String, String> vars,
                        @Nullable String sourceId) {
        this.player = player;
        this.entity = entity;
        this.block = block;
        this.vars = vars;
        this.sourceId = sourceId;
    }

    /** A context with no speaker and no variables, for checks that run outside a conversation. */
    public static StoryContext of(ServerPlayer player) {
        return new StoryContext(player, null, null, new LinkedHashMap<>(), null);
    }

    public StoryContext withSource(@Nullable String source) {
        return new StoryContext(player, entity, block, vars, source);
    }

    public ServerPlayer player() { return player; }
    public ServerLevel level() { return player.serverLevel(); }
    /** The speaking entity, or null for a block speaker or narration. */
    @Nullable public Entity speakerEntity() { return entity; }
    /** The speaking block position, or null for an entity speaker or narration. */
    @Nullable public BlockPos speakerBlock() { return block; }
    public Map<String, String> vars() { return vars; }
    /** For example the quest ID a reward is paid for; null outside quest rewards. */
    @Nullable public String sourceId() { return sourceId; }
}

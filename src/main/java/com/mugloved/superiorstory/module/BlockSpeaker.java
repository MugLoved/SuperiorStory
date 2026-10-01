package com.mugloved.superiorstory.module;

import com.google.gson.JsonElement;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.dialogue.IdSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The {@code block} speaker: a block picked by ID, {@code ns:*}, or {@code #tag} that can be talked to. The conversation
 * ends if the block changes. Its portrait is the block's item and its name the block's name.
 */
public final class BlockSpeaker implements StoryHooks.Speaker {
    @Override
    public Matcher compile(JsonElement value, String where) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("block must be a string");
        IdSelector selector = IdSelector.parse(value.getAsString());
        return target -> target.block() != null && matches(selector, target.level().getBlockState(target.block()));
    }

    static boolean matches(IdSelector selector, BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return selector.match(id, tag -> state.is(TagKey.create(Registries.BLOCK, tag))) > 0;
    }

    @Nullable
    @Override
    public Bound bind(StoryHooks.SpeakerTarget target) {
        return target.block() == null ? null : new Watched(target.level(), target.block());
    }

    private static final class Watched implements Bound {
        private final ServerLevel level;
        private final BlockPos pos;
        private BlockState state;

        Watched(ServerLevel level, BlockPos pos) {
            this.level = level;
            this.pos = pos.immutable();
            this.state = level.getBlockState(pos);
        }

        @Override public boolean alive() { return level.getBlockState(pos) == state; }
        @Override public double distanceSqr(ServerPlayer player) { return player.distanceToSqr(Vec3.atCenterOf(pos)); }
        @Override public void hold() {}
        @Override public void release() {}
        @Override public void tick(ServerPlayer player) {}
        @Override public void refresh() { state = level.getBlockState(pos); }
        @Override public UUID key() { return new UUID(pos.asLong(), level.dimension().location().hashCode()); }
        @Override public RandomSource random() { return level.random; }
        @Override public int entityId() { return -1; }
        @Override public BlockPos blockPos() { return pos; }
        @Nullable @Override public Entity entity() { return null; }
    }
}

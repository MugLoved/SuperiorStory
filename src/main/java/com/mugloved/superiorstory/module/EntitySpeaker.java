package com.mugloved.superiorstory.module;

import com.google.gson.JsonElement;
import com.mugloved.superiorstory.api.StoryHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The {@code npc} speaker: a mob picked with vanilla selectors ({@code "borin"} = scoreboard tag, {@code "ns:type"} =
 * entity type, {@code "#ns:tag"} = entity type tag). It freezes with vanilla NoAI and faces the player while the
 * conversation runs, and is always restored.
 */
public final class EntitySpeaker implements StoryHooks.Speaker {
    /** Present on an NPC only while frozen; holds the NoAI value to restore, so a crash cannot leave it frozen. */
    public static final String RESTORE_KEY = "superiorstory_noai_restore";
    private static final float TURN_DEGREES = 14f;

    @Override
    public Matcher compile(JsonElement value, String where) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("npc must be a string");
        String npc = value.getAsString().trim();
        if (npc.isEmpty()) throw new IllegalArgumentException("npc must not be blank");
        try {
            if (npc.startsWith("#")) new ResourceLocation(npc.substring(1));
            else if (npc.indexOf(':') >= 0) new ResourceLocation(npc);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("npc is not a valid entity type or tag: " + npc);
        }
        return target -> target.entity() instanceof Mob mob && matches(npc, mob);
    }

    private static boolean matches(String npc, Mob mob) {
        if (npc.startsWith("#")) {
            return mob.getType().is(TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(npc.substring(1))));
        }
        if (npc.indexOf(':') >= 0) {
            return EntityType.getKey(mob.getType()).equals(new ResourceLocation(npc));
        }
        return mob.getTags().contains(npc);
    }

    @Nullable
    @Override
    public Bound bind(StoryHooks.SpeakerTarget target) {
        return target.entity() instanceof Mob mob ? new Held(target.level(), mob) : null;
    }

    private static final class Held implements Bound {
        private final ServerLevel level;
        private final UUID id;
        private final int entityId;
        private final boolean previousNoAi;

        Held(ServerLevel level, Mob mob) {
            this.level = level;
            this.id = mob.getUUID();
            this.entityId = mob.getId();
            this.previousNoAi = mob.isNoAi();
        }

        @Nullable
        private Mob mob() {
            return level.getEntity(id) instanceof Mob mob && mob.isAlive() ? mob : null;
        }

        @Override public boolean alive() { return mob() != null; }

        @Override
        public double distanceSqr(ServerPlayer player) {
            Mob mob = mob();
            return mob == null ? Double.MAX_VALUE : player.distanceToSqr(mob);
        }

        @Override
        public void hold() {
            Mob mob = mob();
            if (mob == null) return;
            mob.getPersistentData().putBoolean(RESTORE_KEY, previousNoAi);
            mob.setNoAi(true);
            mob.getNavigation().stop();
        }

        @Override
        public void release() {
            Mob mob = mob();
            if (mob == null) return;
            mob.setNoAi(previousNoAi);
            mob.getPersistentData().remove(RESTORE_KEY);
        }

        /** With AI off the look controller no longer runs, so turn the NPC toward the player's eyes by hand. */
        @Override
        public void tick(ServerPlayer player) {
            Mob mob = mob();
            if (mob == null) return;
            Vec3 delta = player.getEyePosition().subtract(mob.getEyePosition());
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            float yaw = (float) (Mth.atan2(delta.z, delta.x) * (180.0 / Math.PI)) - 90.0f;
            float pitch = (float) -(Mth.atan2(delta.y, horizontal) * (180.0 / Math.PI));
            mob.setYRot(Mth.approachDegrees(mob.getYRot(), yaw, TURN_DEGREES));
            mob.setXRot(Mth.approachDegrees(mob.getXRot(), Mth.clamp(pitch, -40.0f, 40.0f), TURN_DEGREES));
            mob.setYHeadRot(mob.getYRot());
            mob.setYBodyRot(mob.getYRot());
        }

        @Override public void refresh() {}
        @Override public UUID key() { return id; }

        @Override
        public RandomSource random() {
            Mob mob = mob();
            return mob == null ? level.random : mob.getRandom();
        }

        @Override public int entityId() { return entityId; }
        @Nullable @Override public BlockPos blockPos() { return null; }
        @Nullable @Override public Entity entity() { return mob(); }
    }
}

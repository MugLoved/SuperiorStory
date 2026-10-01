package com.mugloved.superiorstory.server;

import com.mugloved.superiorstory.dialogue.Names;
import com.mugloved.superiorstory.dialogue.StructureProfile;
import com.mugloved.superiorstory.dialogue.Vars;
import com.superior.lib.api.entity.BossTierApi;
import com.superior.lib.api.service.SuperiorServiceRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dialogue variables for a located structure. Everything except the profile fields is derived, so an unprofiled
 * structure from any mod still gets a name, distance, direction, and biome.
 */
final class StructureFacts {
    private StructureFacts() {}

    static Map<String, String> vars(ServerPlayer player, ResourceLocation structureId, BlockPos position) {
        ServerLevel level = player.serverLevel();
        StructureProfile.Merged profile = StructureProfiles.resolve(level, structureId);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("structure", displayName(profile, structureId));
        vars.put("id", structureId.toString());
        double dx = position.getX() + 0.5 - player.getX();
        double dz = position.getZ() + 0.5 - player.getZ();
        vars.put("distance", Integer.toString(Names.roundDistance(Math.hypot(dx, dz))));
        vars.put("direction", Names.direction(dx, dz));
        try {
            int y = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, position.getY()));
            level.getUncachedNoiseBiome(position.getX() >> 2, y >> 2, position.getZ() >> 2).unwrapKey()
                .ifPresent(key -> vars.put("biome", Names.clean(key.location().toString())));
        } catch (RuntimeException ignored) {
            // biome is decoration only; the "{biome|...}" fallback covers it
        }
        put(vars, "look", profile.look());
        put(vars, "lore", profile.lore());
        if (!profile.keywords().isEmpty()) put(vars, "keywords", String.join(", ", profile.keywords()));
        if (!profile.bosses().isEmpty()) {
            vars.put("boss", Vars.ENTITY + profile.bosses().get(0));
            put(vars, "danger", danger(profile));
        }
        return vars;
    }

    /** The profile's authored name, or the cleaned ID: no mod ID, no digits, capitalized words. */
    static String displayName(StructureProfile.Merged profile, ResourceLocation structureId) {
        return profile.name() != null ? profile.name() : Names.clean(structureId.getPath());
    }

    private static void put(Map<String, String> vars, String key, String value) {
        if (value != null && !value.isBlank()) vars.put(key, value.length() > Vars.MAX_VALUE ? value.substring(0, Vars.MAX_VALUE) : value);
    }

    /** Wording from the highest Superior Lib boss tier among the profile bosses; absent when none is classified. */
    private static String danger(StructureProfile.Merged profile) {
        BossTierApi tiers = SuperiorServiceRegistry.getOptional(BossTierApi.class).orElse(null);
        if (tiers == null) return null;
        int best = 0;
        for (ResourceLocation boss : profile.bosses()) best = Math.max(best, tiers.find(boss).map(BossTierApi.BossTierEntry::tier).orElse(0));
        return Names.danger(best);
    }

    /** Wording from one boss's Superior Lib tier; null when it is not classified. */
    static String danger(ResourceLocation boss) {
        BossTierApi tiers = SuperiorServiceRegistry.getOptional(BossTierApi.class).orElse(null);
        return tiers == null ? null : Names.danger(tiers.find(boss).map(BossTierApi.BossTierEntry::tier).orElse(0));
    }
}

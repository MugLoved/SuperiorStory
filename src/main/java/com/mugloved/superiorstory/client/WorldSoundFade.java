package com.mugloved.superiorstory.client;

import com.mugloved.superiorstory.SuperiorStory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

import java.lang.reflect.Method;

/**
 * How loud the world is allowed to be right now (0 = silent, 1 = normal).
 *
 * Covers two kinds of sound:
 *  - everything played through Minecraft's sound system (ambience, mobs, vanilla music, other
 *    mods), except SuperiorStory's own sounds and interface sounds on Master, so the Skill
 *    Tree's buttons still click. Handled together with {@code SoundEngineMixin}.
 *  - Biome Beats, the pack's music player. It plays music outside Minecraft's sound system, so
 *    it gets its own volume control here. Optional: if Biome Beats isn't installed, this part
 *    simply does nothing.
 *
 * Never goes fully to zero for Minecraft sounds: Minecraft skips or stops sounds at exactly zero,
 * so they're kept playing inaudibly instead and simply come back up.
 */
public final class WorldSoundFade {
    private static final float SILENT = 0.0001f;
    private static float factor = 1f;

    private WorldSoundFade() {}

    public static float factorFor(SoundInstance sound) {
        if (factor >= 1f || sound == null) return 1f;
        if (sound.getSource() == SoundSource.MASTER) return 1f;
        if (SuperiorStory.MODID.equals(sound.getLocation().getNamespace())) return 1f;
        return Math.max(factor, SILENT);
    }

    /** Called every client tick with the level the world should have right now. */
    static void set(float level) {
        level = Math.max(0f, Math.min(1f, level));
        boolean changed = level != factor;
        factor = level;
        if (changed) {
            Minecraft mc = Minecraft.getInstance();
            // Refreshing any non-Master category makes Minecraft recalculate every playing sound.
            mc.getSoundManager().updateSourceVolume(SoundSource.AMBIENT, mc.options.getSoundSourceVolume(SoundSource.AMBIENT));
        }
        // Biome Beats: keep re-applying while quiet (its music may start mid-intro); restore once at the end.
        if (level < 1f || changed) BiomeBeats.apply(level);
    }

    /** Talks to Biome Beats by name, so SuperiorStory doesn't require it to be installed. */
    private static final class BiomeBeats {
        private static boolean looked;
        private static Object manager;
        private static Method setVolume;
        private static Method updateVolume;

        static void apply(float level) {
            if (!looked) find();
            if (manager == null) return;
            try {
                if (level >= 1f) {
                    updateVolume.invoke(manager);   // back to the player's own Master x Music setting
                } else {
                    Minecraft mc = Minecraft.getInstance();
                    float userMax = mc.options.getSoundSourceVolume(SoundSource.MASTER)
                            * mc.options.getSoundSourceVolume(SoundSource.MUSIC);
                    setVolume.invoke(manager, userMax * level);
                }
            } catch (Throwable t) {
                // Biome Beats not ready yet (or changed): skip this time, never crash
            }
        }

        private static void find() {
            looked = true;
            try {
                Class<?> constants = Class.forName("io.github.maki99999.biomebeats.Constants");
                Object mgr = constants.getField("MUSIC_MANAGER").get(null);
                if (mgr == null) return;
                setVolume = mgr.getClass().getMethod("setVolume", float.class);
                updateVolume = mgr.getClass().getMethod("updateVolume");
                manager = mgr;
            } catch (Throwable ignored) {
                // Biome Beats not installed
            }
        }
    }
}

package com.mugloved.superiorstory.client;

import com.mugloved.superiorstory.SuperiorStory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The intro's UI sounds. They live in assets/superiorstory/sounds and are played
 * client-side only, so they don't need to be registered with the game.
 * They play through the Master volume slider.
 */
public final class StorySounds {
    private static final SoundEvent TYPE = event("ui.type");
    private static final SoundEvent HOVER = event("ui.hover");
    private static final SoundEvent SELECT = event("ui.select");
    private static final SoundEvent APPEAR = event("ui.appear");
    static final SoundEvent HUM = event("ambient.hum");

    private StorySounds() {}

    private static SoundEvent event(String path) {
        return SoundEvent.createVariableRangeEvent(new ResourceLocation(SuperiorStory.MODID, path));
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private static float jitter(float centre, float spread) {
        return centre + (ThreadLocalRandom.current().nextFloat() * 2f - 1f) * spread;
    }

    /** One letter being typed. Deeper for emphasised lines. */
    public static void type(boolean deep) {
        play(TYPE, jitter(deep ? 0.82f : 1.0f, 0.08f), 0.5f);
    }

    public static void hover() {
        play(HOVER, jitter(1.0f, 0.03f), 0.45f);
    }

    public static void select() {
        play(SELECT, 1.0f, 0.6f);
    }

    public static void appear() {
        play(APPEAR, 1.0f, 0.5f);
    }
}

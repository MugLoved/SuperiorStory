package com.mugloved.superiorstory.client;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

/**
 * The low background hum. Loops seamlessly, sits on the Master slider (the pack's music
 * setup takes over the Music category, so the hum stays out of it), and follows
 * {@link StoryClient#humLevel()} for its fade in / fade out.
 */
final class HumSound extends AbstractTickableSoundInstance {
    /** Overall loudness at full fade (the file itself is already soft). */
    private static final float LEVEL = 0.35f;

    HumSound() {
        super(StorySounds.HUM, SoundSource.MASTER, SoundInstance.createUnseededRandom());
        this.looping = true;
        this.delay = 0;
        this.relative = true;                           // not positional: it's "in your head"
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.x = 0; this.y = 0; this.z = 0;
        this.volume = 0.0001f;
    }

    @Override
    public boolean canStartSilent() {
        return true;   // starts silent and fades in
    }

    @Override
    public void tick() {
        float level = StoryClient.humLevel();
        if (level < 0f) {
            stop();
            return;
        }
        this.volume = Math.max(0.0001f, LEVEL * level);
    }
}

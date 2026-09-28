package com.mugloved.superiorstory.mixin;

import com.mugloved.superiorstory.client.WorldSoundFade;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets SuperiorStory turn the world's sounds down (and back up) without stopping or
 * replacing any of them. Sounds keep playing silently during the intro, so music,
 * ambience loops and other mods' sounds are all still "running" when the world fades in.
 *
 * Minecraft works out a sound's loudness in two places:
 *  - when it starts            -> play() calls calculateVolume(float, SoundSource)
 *  - every tick / on refreshes -> calculateVolume(SoundInstance)
 * We scale the result in both. Method names are the game's runtime (SRG) names.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    /** The sound currently being started by play(), so the start-volume hook knows which sound it is. */
    @Unique
    private SoundInstance superiorstory$starting;

    // play(SoundInstance)
    @Inject(method = "m_120312_(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"), remap = false)
    private void superiorstory$playHead(SoundInstance sound, CallbackInfo ci) {
        superiorstory$starting = sound;
    }

    @Inject(method = "m_120312_(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("RETURN"), remap = false)
    private void superiorstory$playReturn(SoundInstance sound, CallbackInfo ci) {
        superiorstory$starting = null;
    }

    // calculateVolume(float, SoundSource): used for the starting volume
    @Inject(method = "m_235257_(FLnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"), cancellable = true, remap = false)
    private void superiorstory$startVolume(float volume, net.minecraft.sounds.SoundSource source, CallbackInfoReturnable<Float> cir) {
        if (superiorstory$starting == null) return;   // not called from play(): handled below
        float factor = WorldSoundFade.factorFor(superiorstory$starting);
        if (factor < 1f) cir.setReturnValue(cir.getReturnValueF() * factor);
    }

    // calculateVolume(SoundInstance): used every tick for moving sounds and when volumes are refreshed
    @Inject(method = "m_120327_(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true, remap = false)
    private void superiorstory$liveVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        float factor = WorldSoundFade.factorFor(sound);
        if (factor < 1f) cir.setReturnValue(cir.getReturnValueF() * factor);
    }
}

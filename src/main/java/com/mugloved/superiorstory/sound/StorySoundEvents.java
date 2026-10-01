package com.mugloved.superiorstory.sound;

import com.mugloved.superiorstory.SuperiorStory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/** Story owns sound content IDs; Superior Sounds owns their playback. */
public final class StorySoundEvents {
    public static final DeferredRegister<SoundEvent> REGISTER =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, SuperiorStory.MODID);

    static {
        for (String path : new String[] {"ui.type", "ui.hover", "ui.select", "ui.appear", "ambient.hum"}) {
            REGISTER.register(path, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(SuperiorStory.MODID, path)));
        }
    }

    private StorySoundEvents() {}
}

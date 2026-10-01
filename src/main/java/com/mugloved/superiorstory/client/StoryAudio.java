package com.mugloved.superiorstory.client;

import com.mugloved.superiorstory.SuperiorStory;
import com.superior.lib.api.service.SuperiorServiceRegistry;
import com.superior.sounds.api.audio.AudioEventOptions;
import com.superior.sounds.api.audio.AudioProviderAuthority;
import com.superior.sounds.api.audio.AudioProviderSchedule;
import com.superior.sounds.api.audio.AudioSignalEmitter;
import com.superior.sounds.api.audio.AudioSignalKey;
import com.superior.sounds.api.audio.AudioSignalProvider;
import com.superior.sounds.api.audio.AudioSignalRegistrationApi;
import com.superior.sounds.api.audio.AudioSignalUpdateContext;
import com.superior.sounds.api.audio.SituationalAudioApi;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/** Publishes Story intent; Superior Sounds selects and plays the audio. */
final class StoryAudio {
    private static final AudioSignalKey<Boolean> SCENE_ACTIVE =
        new AudioSignalKey<>(id("scene_active"), Boolean.class);
    private static final AudioSignalKey<String> SCENE_ID =
        new AudioSignalKey<>(id("scene_id"), String.class);
    private static final AudioSignalKey<Boolean> DIALOGUE_ACTIVE =
        new AudioSignalKey<>(id("dialogue_active"), Boolean.class);
    private static final ResourceLocation TYPE = id("type");
    private static final ResourceLocation HOVER = id("hover");
    private static final ResourceLocation SELECT = id("select");
    private static final ResourceLocation APPEAR = id("appear");
    private static final ResourceLocation PING = id("ping");
    private static final AudioSignalProvider PROVIDER = new AudioSignalProvider() {
        @Override public ResourceLocation id() { return StoryAudio.id("provider/intro"); }
        @Override public AudioProviderAuthority authority() { return AudioProviderAuthority.CLIENT_LOCAL; }
        @Override public AudioProviderSchedule schedule() { return AudioProviderSchedule.every(1); }
        @Override public Set<AudioSignalKey<?>> publishedKeys() { return Set.of(SCENE_ACTIVE, SCENE_ID, DIALOGUE_ACTIVE); }
        @Override public void collect(AudioSignalUpdateContext context, AudioSignalEmitter emitter) {
            emitter.set(SCENE_ACTIVE, StoryClient.isAudioActive());
            emitter.set(DIALOGUE_ACTIVE, DialogueClient.isActive());
            String sceneId = StoryClient.activeSceneId();
            if (StoryClient.isAudioActive() && sceneId != null) emitter.set(SCENE_ID, sceneId);
            else emitter.remove(SCENE_ID);
        }
    };
    private static boolean registered;

    private StoryAudio() {}

    static void register() {
        if (registered) return;
        SuperiorServiceRegistry.getRequired(AudioSignalRegistrationApi.class).registerProvider(PROVIDER);
        registered = true;
    }

    static void type(boolean deep) {
        emit(TYPE, new AudioEventOptions(null, 0.5f, deep ? 0.02f : 0.08f, 0));
    }

    static void hover() { emit(HOVER); }
    static void select() { emit(SELECT); }
    static void appear() { emit(APPEAR); }
    static void ping() { emit(PING); }

    private static void emit(ResourceLocation event) {
        SuperiorServiceRegistry.getRequired(SituationalAudioApi.class).emit(event);
    }

    private static void emit(ResourceLocation event, AudioEventOptions options) {
        SuperiorServiceRegistry.getRequired(SituationalAudioApi.class).emit(event, options);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(SuperiorStory.MODID, path);
    }
}

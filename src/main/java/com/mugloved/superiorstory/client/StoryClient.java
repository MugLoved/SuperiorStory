package com.mugloved.superiorstory.client;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.network.StoryNetwork;
import net.minecraft.Util;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.util.Map;

/**
 * Client side director. Keeps track of where the player is in the intro and makes sure
 * the world stays hidden until it's time for the reveal.
 *
 *  IDLE      nothing happening
 *  AWAITING  just joined, waiting for the server to say "intro" or "no intro" (world stays covered)
 *  PLAYING   the intro screen is running
 *  HANDOFF   player pressed the Skill Tree key; the backdrop stays behind the Skill Tree screen
 *  REVEAL    Skill Tree closed; the backdrop fades away and the world appears
 *
 * Sound follows the same states: from joining until the reveal the world is silent and
 * the hum plays; during the reveal the hum fades out while the world's sounds fade in.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID, value = Dist.CLIENT)
public final class StoryClient {
    private enum State { IDLE, AWAITING, PLAYING, HANDOFF, REVEAL }

    private static final long AWAIT_TIMEOUT = 8000;
    private static final long REVEAL_MS = 1800;
    private static final long HUM_FADE_IN = 3000;

    private static State state = State.IDLE;
    private static IntroScreen screen;
    private static long awaitingSince;
    private static long revealStart;
    private static boolean gravityHeld;
    private static boolean hadNoGravity;
    private static boolean tellServerFinished;
    private static HumSound hum;
    private static long humStart;
    private static boolean humUnpauseBroken;

    private StoryClient() {}

    // ---------------------------------------------------------------- joining / leaving

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        reset();
        if (StoryNetwork.isPresentOn(event.getConnection())) {
            state = State.AWAITING;
            awaitingSince = Util.getMillis();
            WorldSoundFade.set(0f);   // keep the world quiet from the very first sound
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    private static void reset() {
        state = State.IDLE;
        screen = null;
        gravityHeld = false;
        tellServerFinished = false;
        stopHum();
        WorldSoundFade.set(1f);
    }

    /** Server's answer on login (or from /superiorstory replay). */
    public static void onServerIntro(boolean play) {
        Minecraft mc = Minecraft.getInstance();
        if (play) {
            if (state != State.AWAITING || screen == null) screen = new IntroScreen();
            state = State.PLAYING;
            tellServerFinished = true;
            screen.begin();
            // Replay from a command: show it right away (on first join we wait for the loading screen).
            if (mc.player != null && mc.screen != screen && !(mc.screen instanceof ReceivingLevelScreen)) {
                mc.setScreen(screen);
            }
        } else if (state == State.AWAITING) {
            if (screen != null && mc.screen == screen) {
                // The backdrop was already up: fade gently into the world instead of popping.
                startReveal();
                mc.setScreen(null);
            } else {
                finish();
            }
        }
    }

    /** The intro screen appeared for the first time: start the hum. */
    static void onIntroShown() {
        if (hum != null) return;
        hum = new HumSound();
        humStart = Util.getMillis();
        Minecraft.getInstance().getSoundManager().play(hum);
    }

    private static void stopHum() {
        if (hum != null) Minecraft.getInstance().getSoundManager().stop(hum);
        hum = null;
    }

    /** Hum loudness 0..1, or -1 when it should stop. Read by {@link HumSound} every tick. */
    static float humLevel() {
        if (state == State.IDLE || hum == null) return -1f;
        long now = Util.getMillis();
        float in = Math.min(1f, (now - humStart) / (float) HUM_FADE_IN);
        float out = state == State.REVEAL ? 1f - revealProgress(now) : 1f;
        return in * in * out;
    }

    /** 0 -> 1 over the reveal, eased. Shared by the picture, the hum and the world's sounds. */
    private static float revealProgress(long now) {
        float t = Math.min(1f, Math.max(0f, (now - revealStart) / (float) REVEAL_MS));
        return t * t * (3f - 2f * t);
    }

    /** Player pressed the Skill Tree key on the last beat. */
    static void onHandOff() {
        state = State.HANDOFF;
        StoryNetwork.sendProgress(StoryNetwork.PROGRESS_SEEN);
    }

    private static void startReveal() {
        state = State.REVEAL;
        revealStart = Util.getMillis();
        releasePlayer();
    }

    private static void finish() {
        if (tellServerFinished) StoryNetwork.sendProgress(StoryNetwork.PROGRESS_FINISHED);
        releasePlayer();
        reset();
    }

    // ---------------------------------------------------------------- every frame, before rendering

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || state == State.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long now = Util.getMillis();

        switch (state) {
            case AWAITING -> {
                if (now - awaitingSince > AWAIT_TIMEOUT) {
                    onServerIntro(false);   // server never answered: don't keep the player waiting
                } else if (mc.screen == null) {
                    if (screen == null) screen = new IntroScreen();
                    mc.setScreen(screen);   // cover the world while we wait for the answer
                }
            }
            case PLAYING -> {
                // Loading screen just closed, or player came back from the pause menu.
                if (mc.screen == null) mc.setScreen(screen);
            }
            case HANDOFF -> {
                if (mc.screen == null) startReveal();   // Skill Tree closed (or never opened)
            }
            case REVEAL -> {
                if (now - revealStart >= REVEAL_MS) finish();
            }
            default -> {}
        }
    }

    // ---------------------------------------------------------------- the cover / reveal overlay

    /** Drawn above the HUD but below any open screen, so the world never peeks through. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (state == State.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof IntroScreen) return;   // it draws its own backdrop
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float alpha = state == State.REVEAL ? 1f - revealProgress(Util.getMillis()) : 1f;
        Backdrop.draw(event.getGuiGraphics(), w, h, alpha, Util.getMillis());
    }

    // ---------------------------------------------------------------- keep the player still

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        // world sound level follows the state (20 updates a second)
        WorldSoundFade.set(switch (state) {
            case IDLE -> 1f;
            case REVEAL -> revealProgress(Util.getMillis());
            default -> 0f;
        });
        keepHumPlayingWhilePaused();
        if (state != State.AWAITING && state != State.PLAYING && state != State.HANDOFF) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!gravityHeld) {
            hadNoGravity = player.isNoGravity();
            player.setNoGravity(true);
            gravityHeld = true;
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
    }

    /**
     * In singleplayer, opening a pausing screen (the Skill Tree, or the pause menu) pauses every
     * sound in the game. The world should stay paused, but the hum should carry on underneath,
     * so we un-pause just the hum. If this ever fails, the hum simply pauses with everything else.
     */
    private static void keepHumPlayingWhilePaused() {
        Minecraft mc = Minecraft.getInstance();
        if (hum == null || humUnpauseBroken || !mc.isPaused()) return;
        try {
            SoundEngine engine = ObfuscationReflectionHelper.getPrivateValue(SoundManager.class, mc.getSoundManager(), "f_120349_");
            Map<SoundInstance, ChannelAccess.ChannelHandle> channels =
                    ObfuscationReflectionHelper.getPrivateValue(SoundEngine.class, engine, "f_120226_");
            ChannelAccess.ChannelHandle handle = channels == null ? null : channels.get(hum);
            if (handle != null) handle.execute(Channel::unpause);
        } catch (Throwable t) {
            humUnpauseBroken = true;
        }
    }

    private static void releasePlayer() {
        if (!gravityHeld) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.setNoGravity(hadNoGravity);
        gravityHeld = false;
    }
}

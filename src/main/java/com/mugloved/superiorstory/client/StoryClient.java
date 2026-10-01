package com.mugloved.superiorstory.client;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.network.StoryNetwork;
import com.mugloved.superiorstory.scene.StoryScene;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
 * Publishes intro state to Superior Sounds; it owns the audio policy and playback.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID, value = Dist.CLIENT)
public final class StoryClient {
    private enum State { IDLE, AWAITING, PLAYING, HANDOFF, REVEAL }

    private static final long AWAIT_TIMEOUT = 8000;
    private static final long REVEAL_MS = 1800;
    private static State state = State.IDLE;
    private static IntroScreen screen;
    private static long awaitingSince;
    private static long revealStart;
    private static boolean gravityHeld;
    private static boolean hadNoGravity;
    private static long sessionId;
    private static ResourceLocation activeSceneId;

    private StoryClient() {}

    // ---------------------------------------------------------------- joining / leaving

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        reset();
        if (StoryNetwork.isPresentOn(event.getConnection())) {
            StoryAudio.register();
            StoryPresentation.register();
            state = State.AWAITING;
            awaitingSince = Util.getMillis();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    private static void reset() {
        releasePlayer();
        state = State.IDLE;
        screen = null;
        sessionId = 0;
        activeSceneId = null;
    }

    static boolean isAudioActive() {
        return state == State.PLAYING || state == State.HANDOFF;
    }

    static String activeSceneId() {
        return activeSceneId == null ? null : activeSceneId.toString();
    }

    /** Server's answer on login or a requested scene. Null definition selects the hardcoded intro. */
    public static void onServerScene(boolean play, long id, boolean immediate,
                                     ResourceLocation sceneId, StoryScene scene) {
        Minecraft mc = Minecraft.getInstance();
        if (play) {
            if (!immediate && state != State.AWAITING) {
                StoryNetwork.sendProgress(id, StoryNetwork.PROGRESS_CANCELED);
                return;
            }
            releasePlayer();
            screen = scene == null ? new IntroScreen() : new IntroScreen(scene);
            sessionId = id;
            activeSceneId = sceneId;
            state = State.PLAYING;
            screen.begin();
            // Replay from a command: show it right away (on first join we wait for the loading screen).
            if (mc.player != null && mc.screen != screen && !(mc.screen instanceof ReceivingLevelScreen)) {
                mc.setScreen(screen);
            }
        } else if (state == State.AWAITING) {
            if (screen != null && mc.screen == screen) {
                startReveal();
                mc.setScreen(null);
            } else {
                finish();
            }
        }
    }

    /** 0 -> 1 over the reveal, eased. */
    private static float revealProgress(long now) {
        float t = Math.min(1f, Math.max(0f, (now - revealStart) / (float) REVEAL_MS));
        return t * t * (3f - 2f * t);
    }

    /** The scene ended; the tree screen may remain open during the handoff. */
    static void onSceneEnded(boolean opened) {
        StoryNetwork.sendProgress(sessionId, StoryNetwork.PROGRESS_SEEN);
        if (opened) state = State.HANDOFF;
        else startReveal();
    }

    private static void startReveal() {
        state = State.REVEAL;
        revealStart = Util.getMillis();
        releasePlayer();
    }

    private static void finish() {
        if (sessionId != 0) StoryNetwork.sendProgress(sessionId, StoryNetwork.PROGRESS_FINISHED);
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
                    StoryNetwork.sendProgress(0, StoryNetwork.PROGRESS_CANCELED);
                    onServerScene(false, 0, false, null, null);
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

    private static void releasePlayer() {
        if (!gravityHeld) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.setNoGravity(hadNoGravity);
        gravityHeld = false;
    }
}

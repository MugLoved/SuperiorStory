package com.mugloved.superiorstory.client;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.network.DialoguePackets;
import com.mugloved.superiorstory.network.StoryNetwork;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * Client director for dialogue: the Talk key, the small "[R] Talk" prompt for NPCs and blocks the server says can be
 * talked to, and opening or closing {@link DialogueScreen} (and the Superior HUD fade) as the server's lines arrive.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID, value = Dist.CLIENT)
public final class DialogueClient {
    private static final int REPROBE_TICKS = 40;   // conditions (quests) can change while looking at the same target
    private static final int BLOCK_REACH_SQR = 5 * 5;
    private static final long NEGATIVE_MS = 4000;   // a block type nobody talks through is not asked about again for a while

    private static DialogueScreen screen;
    private static DialoguePackets.Target probed = DialoguePackets.Target.NOTHING;
    private static DialoguePackets.Target talkable = DialoguePackets.Target.NOTHING;
    private static int probeAge;
    private static long talkableSince;
    private static final Map<Block, Long> NO_SPEAKER = new HashMap<>();

    private DialogueClient() {}

    public static boolean isActive() {
        return screen != null;
    }

    // ---------------------------------------------------------------- server -> client

    public static void onTalkable(DialoguePackets.Talkable msg) {
        if (!msg.target().equals(probed)) return;
        Minecraft mc = Minecraft.getInstance();
        if (msg.target().kind() == DialoguePackets.BLOCK && mc.level != null) {
            Block block = mc.level.getBlockState(msg.target().pos()).getBlock();
            if (msg.talkable()) NO_SPEAKER.remove(block);
            else NO_SPEAKER.put(block, Util.getMillis() + NEGATIVE_MS);
        }
        talkable = msg.talkable() ? msg.target() : DialoguePackets.Target.NOTHING;
        if (msg.talkable()) talkableSince = Util.getMillis();
    }

    public static void onNode(DialoguePackets.Node msg) {
        Minecraft mc = Minecraft.getInstance();
        if (msg.end()) {
            DialogueScreen closing = screen;
            if (closing != null && closing.sessionId() == msg.sessionId()) {
                closing.serverEnded();
                if (mc.screen == closing) mc.setScreen(null);   // removed() releases the HUD
                else finish(closing);
            }
            return;
        }
        if (msg.open()) {
            if (screen != null) finish(screen);
            screen = new DialogueScreen(msg.sessionId(), msg.speakerKind(), msg.npcId(), msg.blockPos());
            StoryHud.hold(true);
            mc.setScreen(screen);
        }
        if (screen != null && screen.sessionId() == msg.sessionId()) screen.receive(msg);
    }

    public static void onBosses(DialoguePackets.Bosses msg) {
        StoryPresentation.setBossFacts(msg.facts());
    }

    public static void onWaypoint(DialoguePackets.Waypoint msg) {
        if (msg.add()) WaypointReveal.add(msg.x(), msg.y(), msg.z(), msg.name(), msg.returning());
        else WaypointReveal.remove(msg.x(), msg.z());
    }

    /** Called from the screen's {@code removed()} on every close path. */
    static void finish(DialogueScreen closed) {
        if (screen != closed) return;
        screen = null;
        StoryHud.hold(false);
    }

    // ---------------------------------------------------------------- talk key and prompt

    /** What the crosshair is on: a mob, else a block within reach that might have a dialogue. */
    private static DialoguePackets.Target target() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui || mc.player == null || mc.level == null) return DialoguePackets.Target.NOTHING;
        Entity entity = mc.crosshairPickEntity;
        if (entity instanceof Mob) return DialoguePackets.Target.entity(entity.getId());
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && mc.player.distanceToSqr(hit.getBlockPos().getCenter()) <= BLOCK_REACH_SQR) {
            Block block = mc.level.getBlockState(hit.getBlockPos()).getBlock();
            Long until = NO_SPEAKER.get(block);
            if (until != null && Util.getMillis() < until && !hit.getBlockPos().equals(talkable.pos())) return DialoguePackets.Target.NOTHING;
            return DialoguePackets.Target.block(hit.getBlockPos());
        }
        return DialoguePackets.Target.NOTHING;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !StoryNetwork.isPresentOn(mc.player.connection.getConnection())) {
            probed = talkable = DialoguePackets.Target.NOTHING;
            while (DialogueKeys.TALK.consumeClick()) { /* drain */ }
            return;
        }
        DialoguePackets.Target now = target();
        boolean any = now.kind() != DialoguePackets.NONE;
        if (!now.equals(probed)) {
            probed = now;
            talkable = DialoguePackets.Target.NOTHING;
            probeAge = 0;
            if (any) DialoguePackets.sendToServer(new DialoguePackets.Talk(now, true));
        } else if (any && ++probeAge >= REPROBE_TICKS) {
            probeAge = 0;
            DialoguePackets.sendToServer(new DialoguePackets.Talk(now, true));
        }
        while (DialogueKeys.TALK.consumeClick()) {
            if (any && now.equals(talkable)) DialoguePackets.sendToServer(new DialoguePackets.Talk(now, false));
        }
    }

    /** The prompt is small, soft, and gray so it never competes with the world. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderGui(RenderGuiEvent.Post event) {
        DialoguePackets.Target now = target();
        if (now.kind() == DialoguePackets.NONE || !now.equals(talkable)) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        float alpha = StoryChrome.easeOut(StoryChrome.clamp01((Util.getMillis() - talkableSince) / 220f));
        Component key = Component.literal("[").append(DialogueKeys.TALK.getTranslatedKeyMessage()).append("]");
        Component label = Component.literal(" ").append(Component.translatable("superiorstory.prompt.talk"));
        int keyWidth = mc.font.width(key), w = keyWidth + mc.font.width(label) + 2 * StoryChrome.PROMPT_PAD, h = StoryChrome.PROMPT_HEIGHT;
        int x0 = g.guiWidth() / 2 - w / 2, y0 = g.guiHeight() / 2 + StoryChrome.PROMPT_OFFSET + Math.round((1f - alpha) * 3f);
        StoryChrome.frame(g, x0, y0, x0 + w, y0 + h, alpha * StoryChrome.PROMPT_FRAME_ALPHA, 0f);
        int keyColor = Backdrop.argb(alpha * 0.9f, StoryChrome.PROMPT_KEY);
        int textColor = Backdrop.argb(alpha * 0.85f, StoryChrome.LABEL);
        int ty = y0 + (h - mc.font.lineHeight) / 2 + 1;
        if ((textColor >>> 24) >= 5) {
            g.drawString(mc.font, key.getVisualOrderText(), x0 + StoryChrome.PROMPT_PAD, ty, keyColor, false);
            g.drawString(mc.font, label.getVisualOrderText(), x0 + StoryChrome.PROMPT_PAD + keyWidth, ty, textColor, false);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (screen != null) finish(screen);
        probed = talkable = DialoguePackets.Target.NOTHING;
        NO_SPEAKER.clear();
    }
}

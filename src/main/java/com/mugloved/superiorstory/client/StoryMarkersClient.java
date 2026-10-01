package com.mugloved.superiorstory.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mugloved.superiorstory.SuperiorStory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.Map;

/**
 * Draws the server's NPC markers over mobs: a "?" when the player can talk to it, a "!" when talking would progress a quest. The
 * "?" shows only up close, the "!" from farther away, so a village is not covered in icons. The icons are Superior Lib textures.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID, value = Dist.CLIENT)
public final class StoryMarkersClient {
    private static final byte ASK = 1;
    private static final byte BANG = 2;
    private static final ResourceLocation ASK_TEXTURE = new ResourceLocation("superior_lib", "textures/gui/icons/question_mark.png");
    private static final ResourceLocation BANG_TEXTURE = new ResourceLocation("superior_lib", "textures/gui/icons/icon_exclamation.png");
    private static final float ASK_WIDTH = 0.28f, ASK_HEIGHT = 0.28f;    // blocks (the texture is 9x9 px)
    private static final float BANG_WIDTH = 0.136f, BANG_HEIGHT = 0.5f;  // blocks (the texture is 6x22 px)
    private static final double ASK_FULL = 4, ASK_GONE = 6, BANG_FULL = 20, BANG_GONE = 24;

    private static volatile Map<Integer, Byte> markers = Map.of();

    private StoryMarkersClient() {}

    public static void onMarkers(Map<Integer, Byte> kinds) {
        markers = Map.copyOf(kinds);
    }

    @SubscribeEvent
    public static void onRender(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity entity = event.getEntity();
        Byte kind = markers.get(entity.getId());
        Minecraft mc = Minecraft.getInstance();
        if (kind == null || mc.player == null || mc.level == null || mc.options.hideGui || DialogueClient.isActive() || entity.isInvisible()) return;
        boolean bang = kind == BANG;
        double distance = mc.player.distanceTo(entity);
        double full = bang ? BANG_FULL : ASK_FULL, gone = bang ? BANG_GONE : ASK_GONE;
        int alpha = (int) (255 * Mth.clamp((gone - distance) / (gone - full), 0, 1));
        if (alpha < 8) return;
        float width = bang ? BANG_WIDTH : ASK_WIDTH, height = bang ? BANG_HEIGHT : ASK_HEIGHT;
        float bob = Mth.sin((mc.level.getGameTime() + event.getPartialTick()) * 0.1f) * 0.04f;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(0, entity.getBbHeight() + 0.6 + bob, 0);   // above the name tag
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        VertexConsumer buffer = event.getMultiBufferSource().getBuffer(RenderType.entityTranslucent(bang ? BANG_TEXTURE : ASK_TEXTURE));
        Matrix4f matrix = pose.last().pose();
        Matrix3f normal = pose.last().normal();
        float half = width / 2;
        vertex(buffer, matrix, normal, -half, 0, 0, 1, alpha);
        vertex(buffer, matrix, normal, half, 0, 1, 1, alpha);
        vertex(buffer, matrix, normal, half, height, 1, 0, alpha);
        vertex(buffer, matrix, normal, -half, height, 0, 0, alpha);
        pose.popPose();
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, Matrix3f normal, float x, float y, float u, float v, int alpha) {
        buffer.vertex(matrix, x, y, 0).color(255, 255, 255, alpha).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(LightTexture.FULL_BRIGHT).normal(normal, 0, 0, 1).endVertex();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        markers = Map.of();
    }
}

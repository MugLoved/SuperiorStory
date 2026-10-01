package com.mugloved.superiorstory.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;

/**
 * A still head-and-shoulders view of an entity, drawn with the same entity renderer the inventory screen uses
 * (as FTB Quests Entity Visualization does): fixed pose, full-bright, clipped to its frame, nothing animated.
 * Framing comes from the entity's own eye height, so tall humanoids and low animals both center on the face.
 */
final class EntityPortrait {
    private static final float LOOK = 0.3f;   // same tilt factor as the inventory screen's follow-mouse pose

    private EntityPortrait() {}

    /** @param frozenTick the entity tick count to render at, so idle animation stays on one frame */
    static void draw(GuiGraphics g, LivingEntity entity, int x0, int y0, int x1, int y1, int frozenTick) {
        float eye = entity.getEyeHeight();
        // Vertical span shown, in blocks: about 0.9 for a villager, tighter for small animals, wider for broad mobs.
        float span = Math.max(Mth.clamp(eye * 0.55f, 0.35f, 1.6f), Math.min(entity.getBbWidth() * 1.1f, 2.5f));
        float scale = Math.min(x1 - x0, y1 - y0) / span;
        float centerWorldY = eye - span * 0.12f;   // a little below the eyes, so chin and shoulders show
        int feetY = Math.round((y0 + y1) / 2f + centerWorldY * scale);

        float bodyRot = entity.yBodyRot, yRot = entity.getYRot(), xRot = entity.getXRot();
        float headRotO = entity.yHeadRotO, headRot = entity.yHeadRot;
        int tick = entity.tickCount;
        entity.yBodyRot = 180f + LOOK * 20f;
        entity.setYRot(180f + LOOK * 40f);
        entity.setXRot(0f);
        entity.yHeadRot = entity.getYRot();
        entity.yHeadRotO = entity.getYRot();
        entity.tickCount = frozenTick;
        g.enableScissor(x0, y0, x1, y1);
        try {
            InventoryScreen.renderEntityInInventory(g, (x0 + x1) / 2, feetY, Math.max(1, Math.round(scale)),
                new Quaternionf().rotateZ((float) Math.PI), new Quaternionf(), entity);
        } finally {
            g.disableScissor();
            entity.yBodyRot = bodyRot;
            entity.setYRot(yRot);
            entity.setXRot(xRot);
            entity.yHeadRotO = headRotO;
            entity.yHeadRot = headRot;
            entity.tickCount = tick;
        }
    }
}

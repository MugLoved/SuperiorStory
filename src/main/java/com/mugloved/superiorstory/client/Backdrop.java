package com.mugloved.superiorstory.client;

import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The cold, mystical background shared by the intro screen and the world-reveal fade.
 *
 * Layers (back to front):
 *  1. vertical gradient, cold blue-grey at the top sinking into near-black
 *  2. a faint band of pale light across the upper middle, like light from somewhere unseen
 *  3. dark vignette on all four edges
 *  4. slow, drifting pixel motes (the cold cousin of the embers on the path screen)
 *
 * Everything is computed from the clock, so it needs no per-frame state and looks the
 * same whether the screen or the overlay is drawing it.
 */
public final class Backdrop {
    private static final int TOP = 0x1C2638;
    private static final int BOTTOM = 0x05070B;
    private static final int GLOW = 0x7C9CCB;
    private static final int MOTE = 0xCFE3F7;
    private static final int MOTES = 56;

    private Backdrop() {}

    public static int argb(float alpha, int rgb) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    public static void draw(GuiGraphics g, int w, int h, float alpha, long nowMs) {
        if (alpha <= 0.002f) return;

        // 1. base gradient
        g.fillGradient(0, 0, w, h, argb(alpha, TOP), argb(alpha, BOTTOM));

        // 2. pale light band centred a little above the middle
        int cy = (int) (h * 0.40f);
        int band = (int) (h * 0.34f);
        int glow = argb(alpha * 0.16f, GLOW);
        int none = argb(0f, GLOW);
        g.fillGradient(0, cy - band, w, cy, none, glow);
        g.fillGradient(0, cy, w, cy + band, glow, none);

        // 3. vignette: top/bottom directly, left/right by rotating a vertical gradient
        int dark = argb(alpha * 0.55f, 0);
        int clear = argb(0f, 0);
        int vy = (int) (h * 0.18f);
        g.fillGradient(0, 0, w, vy, dark, clear);
        g.fillGradient(0, h - vy, w, h, clear, dark);
        int vx = (int) (w * 0.20f);
        g.pose().pushPose();
        g.pose().mulPose(Axis.ZP.rotationDegrees(-90f));          // local y -> screen x
        g.fillGradient(-h, 0, 0, vx, dark, clear);                  // left edge
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(w, 0, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(90f));             // local y -> screen -x
        g.fillGradient(0, 0, h, vx, dark, clear);                   // right edge
        g.pose().popPose();

        // 4. drifting motes
        double t = nowMs / 1000.0;
        for (int i = 0; i < MOTES; i++) {
            double r1 = hash(i * 3 + 1), r2 = hash(i * 3 + 2), r3 = hash(i * 3 + 3);
            double speed = 0.012 + r2 * 0.028;                      // screen-heights per second
            double travel = (t * speed + r1 * 7.0) % 1.15;          // 0 -> 1.15, then wraps
            double y = h * (1.08 - travel);
            double x = w * r3 + Math.sin(t * (0.25 + r1 * 0.35) + i) * (6 + r2 * 10);
            // fade in at the bottom, out near the top, and twinkle gently
            double edge = Math.min(1.0, Math.min(travel / 0.15, (1.15 - travel) / 0.35));
            double twinkle = 0.55 + 0.45 * Math.sin(t * (0.8 + r2 * 1.6) + r1 * 20.0);
            float a = (float) (alpha * edge * twinkle * (0.22 + r1 * 0.38));
            if (a < 0.02f) continue;
            int size = r2 > 0.82 ? 2 : 1;
            int px = (int) x, py = (int) y;
            g.fill(px, py, px + size, py + size, argb(a, MOTE));
        }
    }

    /** Cheap deterministic pseudo-random in [0,1). */
    private static double hash(int n) {
        long x = n * 0x9E3779B97F4A7C15L;
        x ^= (x >>> 31);
        x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27);
        return (x >>> 11) * 0x1.0p-53;
    }
}

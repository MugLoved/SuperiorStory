package com.mugloved.superiorstory.client;

import net.minecraft.client.gui.GuiGraphics;

import java.util.concurrent.ThreadLocalRandom;

/** The shared look of Story screens: palette, framed panel, easing and the typing rhythm. */
final class StoryChrome {
    static final int TEXT = 0xE6EDF6;
    static final int TEXT_ACCENT = 0xA9D8FF;
    static final int PANEL = 0x0B101B;
    static final int BORDER = 0x3A4861;
    static final int BORDER_HOT = 0x9FD3FF;
    static final int BRACKET = 0x56657F;
    static final int BRACKET_HOT = 0xD2F0FF;
    static final int LABEL = 0x9AA6BA;
    static final int LABEL_HOT = 0xFFFFFF;
    static final int KEY_GOLD = 0xF2C46D;
    /** Place names inside dialogue stand out from the body text: structures amber, biomes green. */
    static final int NAME_STRUCTURE = 0xFFB454;
    static final int NAME_BIOME = 0x7FE3A4;
    /** Time values (time left, time of day) get their own pale lavender, distinct from places and boss tiers. */
    static final int NAME_TIME = 0xB9A8FF;
    /** The Talk prompt: small, soft, gray; the key one step lighter than the word. */
    static final int PROMPT_KEY = 0xC6CEDC;
    static final int PROMPT_HEIGHT = 14;
    static final int PROMPT_PAD = 6;
    static final int PROMPT_OFFSET = 18;
    static final float PROMPT_FRAME_ALPHA = 0.55f;

    /** Bounty rewards (amounts, non-item names, the joined list). */
    static final int NAME_REWARD = 0xE8C56A;
    /** Bounty objective amounts, progress, and non-registry names. */
    static final int NAME_OBJECTIVE = 0x9FD0FF;
    /** Bounty tier words, by Bountiful's rarity colors. */
    static int bountyRarityColor(String langKey) {
        return switch (langKey.substring(langKey.lastIndexOf('.') + 1)) {
            case "uncommon" -> 0x55FFFF;
            case "rare" -> 0xFFFF55;
            case "epic" -> 0xFF55FF;
            case "legendary" -> 0xFFAA00;
            default -> 0xFFFFFF;
        };
    }

    /** The color a filled-in variable is drawn in, or 0 to keep the surrounding text color. */
    static int nameColor(String variable) {
        return switch (variable) {
            case "structure" -> NAME_STRUCTURE;
            case "biome" -> NAME_BIOME;
            case "time", "cooldown" -> NAME_TIME;
            default -> variable.startsWith("bounty_rew") ? NAME_REWARD : variable.startsWith("bounty_obj") ? NAME_OBJECTIVE : 0;
        };
    }

    private StoryChrome() {}

    /** A framed panel with corner brackets; {@code hot} (0-1) brightens the border and pushes the brackets out. */
    static void frame(GuiGraphics g, int x0, int y0, int x1, int y1, float alpha, float hot) {
        float h = easeOut(clamp01(hot));
        g.fill(x0, y0, x1, y1, Backdrop.argb(alpha * (0.80f + 0.10f * h), PANEL));
        int border = Backdrop.argb(alpha, lerpColor(BORDER, BORDER_HOT, h));
        g.fill(x0, y0, x1, y0 + 1, border);
        g.fill(x0, y1 - 1, x1, y1, border);
        g.fill(x0, y0, x0 + 1, y1, border);
        g.fill(x1 - 1, y0, x1, y1, border);

        int o = 3 + Math.round(2 * h), len = 5;
        int br = Backdrop.argb(alpha, lerpColor(BRACKET, BRACKET_HOT, h));
        corner(g, x0 - o, y0 - o, 1, 1, len, br);
        corner(g, x1 + o, y0 - o, -1, 1, len, br);
        corner(g, x0 - o, y1 + o, 1, -1, len, br);
        corner(g, x1 + o, y1 + o, -1, -1, len, br);
    }

    /** A dotted underline like the one under a tooltip definition: one pixel every third pixel. */
    static void dotted(GuiGraphics g, float x, int y, int width, int argb) {
        int x0 = Math.round(x);
        for (int offset = 0; offset < width; offset += 3) g.fill(x0 + offset, y, Math.min(x0 + offset + 1, x0 + width), y + 1, argb);
    }

    static void corner(GuiGraphics g, int x, int y, int dx, int dy, int len, int color) {
        int hx0 = Math.min(x, x + dx * len), hx1 = Math.max(x, x + dx * len);
        int vy0 = Math.min(y, y + dy * len), vy1 = Math.max(y, y + dy * len);
        g.fill(hx0, Math.min(y, y + dy), hx1, Math.max(y, y + dy), color);
        g.fill(Math.min(x, x + dx), vy0, Math.max(x, x + dx), vy1, color);
    }

    /** Natural, slightly uneven rhythm with longer rests at punctuation. */
    static long charDelay(char c, char next) {
        int jitter = ThreadLocalRandom.current().nextInt(0, 19);
        return switch (c) {
            case ' ' -> 20 + jitter / 2;
            case ',', ';', ':' -> 190;
            case '.', '!', '?' -> next == '.' ? 240 : 360;
            default -> 36 + jitter;
        };
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    static float easeOut(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    static int lerpColor(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int gg = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bb = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (gg << 8) | bb;
    }
}

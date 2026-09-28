package com.mugloved.superiorstory.client;

import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The intro itself: five typed beats over the {@link Backdrop}.
 *
 * Beat flow:  lead-in -> TYPING -> HOLD (short pause) -> INPUT (prompt / choices appear) -> OUT (fade) -> next beat
 * The last beat waits for the player's Skill Tree key; that key press is passed straight
 * through to the Skill Tree mod, which then opens on its own.
 *
 * The same instance is reused if the player opens the pause menu (Esc) and comes back,
 * so they pick up exactly where they left off.
 */
public class IntroScreen extends Screen {

    // ------------------------------------------------------------------ content

    private enum Input { CONTINUE, CHOICE, SKILL_KEY }

    /** @param speed typing-time multiplier (higher = slower)  @param accent emphasised line */
    private record Beat(String key, Input input, float speed, boolean accent) {}

    private static final Beat[] BEATS = {
            new Beat("superiorstory.beat.1", Input.CONTINUE, 1.00f, false),
            new Beat("superiorstory.beat.2", Input.CONTINUE, 1.00f, false),
            new Beat("superiorstory.beat.3", Input.CHOICE, 2.10f, true),
            new Beat("superiorstory.beat.4", Input.CONTINUE, 1.00f, false),
            new Beat("superiorstory.beat.5", Input.SKILL_KEY, 1.20f, false),
    };

    private static final String SKILL_TREE_KEY = "key.puffish_skills.open";

    // ------------------------------------------------------------------ look

    private static final int TEXT = 0xE6EDF6;
    private static final int TEXT_ACCENT = 0xA9D8FF;
    private static final int PANEL = 0x0B101B;
    private static final int BORDER = 0x3A4861;
    private static final int BORDER_HOT = 0x9FD3FF;
    private static final int BRACKET = 0x56657F;
    private static final int BRACKET_HOT = 0xD2F0FF;
    private static final int LABEL = 0x9AA6BA;
    private static final int LABEL_HOT = 0xFFFFFF;
    private static final int KEY_GOLD = 0xF2C46D;

    // ------------------------------------------------------------------ timing (ms)

    private static final long LEAD_IN = 1100;
    private static final long FADE_FROM_BLACK = 900;
    private static final long HOLD_NORMAL = 280;
    private static final long HOLD_CHOICE = 950;      // the "small pause" after "Except You."
    private static final long HOLD_CHOICE_SKIPPED = 500;
    private static final long OUT = 380;
    private static final long CHAR_FADE = 90;
    private static final long INPUT_GUARD = 180;      // stops a double-click skipping a beat
    private static final long MIN_SOUND_GAP = 38;

    // ------------------------------------------------------------------ state

    private enum Stage { WAITING, LEAD_IN, TYPING, HOLD, INPUT, OUT, DONE }

    private Stage stage = Stage.WAITING;
    private long stageAt;
    private long holdFor;
    private long firstOpenedAt = -1;

    private int beat = -1;
    private String text = "";
    private final List<String> lines = new ArrayList<>();
    private int revealed;
    private long[] revealAt = new long[0];
    private long nextCharAt;
    private long lastSoundAt;
    private long inputShownAt;
    private float textScale = 2f;
    private int textTop;
    private int textBottom;

    // interactive boxes: 0 = continue prompt, 1 = first choice, 2 = second choice
    private final int[][] rects = new int[3][4];
    private final boolean[] rectLive = new boolean[3];
    private final float[] hoverAnim = new float[3];
    private int hovered = -1;
    private long lastFrameAt;

    public IntroScreen() {
        super(Component.empty());
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called once the server confirms the intro should play. */
    public void begin() {
        if (stage == Stage.WAITING) setStage(Stage.LEAD_IN);
    }

    @Override
    protected void init() {
        if (firstOpenedAt < 0) {
            firstOpenedAt = Util.getMillis();
            StoryClient.onIntroShown();   // start the background hum
        }
        if (beat >= 0) layoutText();   // window resized or returning from the pause menu
    }

    @Override
    public boolean isPauseScreen() {
        return false;   // keep the world (and chunk/LOD loading) running underneath
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;   // Esc opens the pause menu instead; see keyPressed
    }

    private void setStage(Stage s) {
        stage = s;
        stageAt = Util.getMillis();
    }

    private void startBeat(int index) {
        beat = index;
        text = Component.translatable(BEATS[index].key()).getString();
        revealed = 0;
        revealAt = new long[text.length()];
        nextCharAt = Util.getMillis();
        layoutText();
        setStage(Stage.TYPING);
    }

    private Beat current() {
        return BEATS[beat];
    }

    // ------------------------------------------------------------------ text layout

    private void layoutText() {
        textScale = width >= 360 ? 2f : 1.5f;
        int maxWidth = (int) (width * 0.80f / textScale);
        List<String> wrapped = wrap(maxWidth);
        // Balance the lines so a long sentence doesn't leave one lonely word on the last line:
        // shrink the wrap width as far as possible without adding another line.
        if (wrapped.size() > 1) {
            int lo = 1, hi = maxWidth;
            while (lo < hi) {
                int mid = (lo + hi) / 2;
                if (wrap(mid).size() <= wrapped.size()) hi = mid; else lo = mid + 1;
            }
            wrapped = wrap(lo);
        }
        lines.clear();
        lines.addAll(wrapped);
        float lineHeight = (font.lineHeight + 3) * textScale;
        float blockHeight = lines.size() * lineHeight;
        textTop = (int) (height * 0.40f - blockHeight / 2f);
        textBottom = (int) (textTop + blockHeight);
    }

    /** Word-wrap the current beat. Lines keep their trailing space so letter positions line up with the text. */
    private List<String> wrap(int wrapWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && font.width(candidate) > wrapWidth) {
                out.add(line + " ");
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        out.add(line.toString());
        return out;
    }

    // ------------------------------------------------------------------ typing

    private void advanceTyping(long now) {
        while (stage == Stage.TYPING && revealed < text.length() && now >= nextCharAt) {
            char c = text.charAt(revealed);
            char next = revealed + 1 < text.length() ? text.charAt(revealed + 1) : ' ';
            revealAt[revealed] = now;
            revealed++;
            nextCharAt = Math.max(nextCharAt, now - 60) + (long) (charDelay(c, next) * current().speed());
            if (!Character.isWhitespace(c) && now - lastSoundAt >= MIN_SOUND_GAP) {
                StorySounds.type(current().accent());
                lastSoundAt = now;
            }
        }
        if (stage == Stage.TYPING && revealed >= text.length()) {
            holdFor = current().input() == Input.CHOICE ? HOLD_CHOICE : HOLD_NORMAL;
            setStage(Stage.HOLD);
        }
    }

    /** Natural, slightly uneven rhythm with longer rests at punctuation. */
    private static long charDelay(char c, char next) {
        int jitter = ThreadLocalRandom.current().nextInt(0, 19);
        return switch (c) {
            case ' ' -> 20 + jitter / 2;
            case ',', ';', ':' -> 190;
            case '.', '!', '?' -> next == '.' ? 240 : 360;
            default -> 36 + jitter;
        };
    }

    private void completeLine() {
        long now = Util.getMillis();
        for (int i = revealed; i < text.length(); i++) revealAt[i] = now - CHAR_FADE / 2;
        revealed = text.length();
        holdFor = current().input() == Input.CHOICE ? HOLD_CHOICE_SKIPPED : HOLD_NORMAL;
        setStage(Stage.HOLD);
    }

    // ------------------------------------------------------------------ frame update

    private void update(long now) {
        long inStage = now - stageAt;
        switch (stage) {
            case LEAD_IN -> { if (inStage >= LEAD_IN) startBeat(0); }
            case TYPING -> advanceTyping(now);
            case HOLD -> {
                if (inStage >= holdFor) {
                    setStage(Stage.INPUT);
                    inputShownAt = now;
                    if (current().input() != Input.CONTINUE) StorySounds.appear();
                }
            }
            case OUT -> {
                if (inStage >= OUT) {
                    if (beat + 1 < BEATS.length) startBeat(beat + 1);
                    else setStage(Stage.DONE);
                }
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = lastFrameAt == 0 ? 0 : Math.min(100, now - lastFrameAt);
        lastFrameAt = now;

        Backdrop.draw(g, width, height, 1f, now);
        update(now);

        if (beat >= 0 && stage != Stage.DONE) {
            float beatAlpha = stage == Stage.OUT ? 1f - clamp01((now - stageAt) / (float) OUT) : 1f;
            drawText(g, now, beatAlpha);
            drawInput(g, now, beatAlpha, mouseX, mouseY, dt);
        }

        // gentle fade in from the black loading screen the first time we appear
        float black = 1f - clamp01((now - firstOpenedAt) / (float) FADE_FROM_BLACK);
        if (black > 0f) g.fill(0, 0, width, height, Backdrop.argb(black, 0));
    }

    private void drawText(GuiGraphics g, long now, float beatAlpha) {
        int color = current().accent() ? TEXT_ACCENT : TEXT;
        float lineHeight = (font.lineHeight + 3) * textScale;
        int index = 0;
        for (int li = 0; li < lines.size(); li++) {
            String line = lines.get(li);
            String visible = line.stripTrailing();
            float x0 = width / 2f - font.width(visible) * textScale / 2f;
            float y0 = textTop + li * lineHeight;
            g.pose().pushPose();
            g.pose().translate(x0, y0, 0);
            g.pose().scale(textScale, textScale, 1f);
            // fully visible part in one go, freshly typed letters individually (fade + rise)
            int shownInLine = Math.max(0, Math.min(line.length(), revealed - index));
            int solid = 0;
            while (solid < shownInLine && now - revealAt[index + solid] >= CHAR_FADE) solid++;
            if (solid > 0) text(g, line.substring(0, solid), 0, 0, Backdrop.argb(beatAlpha, color), true);
            for (int j = solid; j < shownInLine; j++) {
                float a = clamp01((now - revealAt[index + j]) / (float) CHAR_FADE);
                float rise = (1f - a) * 3f;
                text(g, String.valueOf(line.charAt(j)), font.width(line.substring(0, j)), rise,
                        Backdrop.argb(a * beatAlpha, color), true);
            }
            g.pose().popPose();
            index += line.length();
        }
    }

    private void drawInput(GuiGraphics g, long now, float beatAlpha, int mouseX, int mouseY, float dt) {
        rectLive[0] = rectLive[1] = rectLive[2] = false;
        if (stage != Stage.INPUT && stage != Stage.OUT) {
            decayHover(dt, -1);
            return;
        }
        float appear = easeOut(clamp01((now - inputShownAt) / 380f)) * beatAlpha;
        float slide = (1f - easeOut(clamp01((now - inputShownAt) / 380f))) * 6f;
        int y = Math.min(height - 28, Math.max((int) (height * 0.62f), textBottom + 34)) + (int) slide;

        switch (current().input()) {
            case CONTINUE -> {
                float pulse = 0.78f + 0.22f * (float) Math.sin(now / 420.0);
                Component label = Component.translatable("superiorstory.prompt.continue");
                placeBox(0, width / 2, y, font.width(label) + 26, 20);
                updateHover(mouseX, mouseY, dt, appear);
                drawBox(g, 0, label, appear, pulse);
            }
            case CHOICE -> {
                Component a = Component.translatable("superiorstory.choice.where");
                Component b = Component.translatable("superiorstory.choice.who");
                int w = Math.max(font.width(a), font.width(b)) + 30;
                int gap = 20;
                placeBox(1, width / 2 - gap / 2 - w / 2, y, w, 22);
                placeBox(2, width / 2 + gap / 2 + w / 2, y, w, 22);
                updateHover(mouseX, mouseY, dt, appear);
                drawBox(g, 1, a, appear, 1f);
                drawBox(g, 2, b, appear, 1f);
            }
            case SKILL_KEY -> {
                KeyMapping key = skillTreeKey();
                float pulse = 0.80f + 0.20f * (float) Math.sin(now / 480.0);
                if (key == null) {
                    // Skill Tree mod missing or key unbound: fall back to a normal continue
                    Component label = Component.translatable("superiorstory.prompt.continue");
                    placeBox(0, width / 2, y, font.width(label) + 26, 20);
                    updateHover(mouseX, mouseY, dt, appear);
                    drawBox(g, 0, label, appear, pulse);
                } else {
                    Component keyName = Component.literal("[")
                            .append(key.getTranslatedKeyMessage())
                            .append("]")
                            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(KEY_GOLD)));
                    Component label = Component.translatable("superiorstory.prompt.skilltree", keyName);
                    decayHover(dt, -1);
                    placeBox(0, width / 2, y, font.width(label) + 30, 22);
                    rectLive[0] = false;   // not clickable: this one wants the key
                    drawBoxRaw(g, rects[0], label, appear, pulse, 0.35f + 0.25f * (float) Math.sin(now / 480.0));
                }
            }
        }
    }

    private void placeBox(int id, int cx, int cy, int w, int h) {
        rects[id][0] = cx - w / 2;
        rects[id][1] = cy - h / 2;
        rects[id][2] = w;
        rects[id][3] = h;
        rectLive[id] = true;
    }

    private boolean inside(int id, double mx, double my) {
        int[] r = rects[id];
        return rectLive[id] && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private void updateHover(int mouseX, int mouseY, float dt, float appear) {
        int now = -1;
        if (appear > 0.5f && stage == Stage.INPUT) {
            for (int i = 0; i < 3; i++) if (inside(i, mouseX, mouseY)) now = i;
        }
        if (now != hovered && now >= 0) StorySounds.hover();
        hovered = now;
        decayHover(dt, hovered);
    }

    private void decayHover(float dt, int target) {
        float k = 1f - (float) Math.exp(-dt / 55f);
        for (int i = 0; i < 3; i++) hoverAnim[i] += ((i == target ? 1f : 0f) - hoverAnim[i]) * k;
        if (target < 0) hovered = -1;
    }

    private void drawBox(GuiGraphics g, int id, Component label, float alpha, float labelPulse) {
        drawBoxRaw(g, rects[id], label, alpha, labelPulse, hoverAnim[id]);
    }

    /** A framed panel with corner brackets, in the style of the path screen. Pops out on hover. */
    private void drawBoxRaw(GuiGraphics g, int[] r, Component label, float alpha, float labelPulse, float hot) {
        if (alpha <= 0.02f) return;
        float h = easeOut(clamp01(hot));
        int w = r[2], ht = r[3];
        g.pose().pushPose();
        g.pose().translate(r[0] + w / 2f, r[1] + ht / 2f, 0);
        float s = 1f + 0.08f * h;
        g.pose().scale(s, s, 1f);
        int x0 = -w / 2, y0 = -ht / 2, x1 = x0 + w, y1 = y0 + ht;

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

        int labelColor = Backdrop.argb(alpha * Math.max(labelPulse, h), lerpColor(LABEL, LABEL_HOT, h));
        text(g, label, -font.width(label) / 2f, -font.lineHeight / 2f + 1, labelColor, false);
        g.pose().popPose();
    }

    private static void corner(GuiGraphics g, int x, int y, int dx, int dy, int len, int color) {
        int hx0 = Math.min(x, x + dx * len), hx1 = Math.max(x, x + dx * len);
        int vy0 = Math.min(y, y + dy * len), vy1 = Math.max(y, y + dy * len);
        g.fill(hx0, Math.min(y, y + dy), hx1, Math.max(y, y + dy), color);
        g.fill(Math.min(x, x + dx), vy0, Math.max(x, x + dx), vy1, color);
    }

    private void text(GuiGraphics g, String s, float x, float y, int argb, boolean shadow) {
        if ((argb >>> 24) < 5) return;   // Minecraft treats near-zero alpha as fully opaque
        g.drawString(font, s, x, y, argb, shadow);
    }

    private void text(GuiGraphics g, Component c, float x, float y, int argb, boolean shadow) {
        if ((argb >>> 24) < 5) return;
        g.drawString(font, c.getVisualOrderText(), x, y, argb, shadow);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (beat < 0) return true;
        long now = Util.getMillis();
        if (button != 0) {
            KeyMapping key = skillTreeKey();
            if (stage == Stage.INPUT && current().input() == Input.SKILL_KEY && key != null && key.matchesMouse(button)) {
                handOff();
            }
            return true;
        }
        advance(mouseX, mouseY, now);
        return true;
    }

    /** Left click (or Enter/Space, with no position) moves the story on. */
    private void advance(double mouseX, double mouseY, long now) {
        switch (stage) {
            case TYPING -> completeLine();
            case INPUT -> {
                if (now - inputShownAt < INPUT_GUARD) return;
                switch (current().input()) {
                    case CONTINUE -> next();
                    case CHOICE -> {
                        // an illusion of choice: both answers lead to the same place
                        if (inside(1, mouseX, mouseY) || inside(2, mouseX, mouseY)) next();
                    }
                    case SKILL_KEY -> { if (skillTreeKey() == null) handOff(); }
                }
            }
            default -> {}
        }
    }

    private void next() {
        StorySounds.select();
        hovered = -1;
        setStage(Stage.OUT);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {   // Esc -> normal pause menu on top; we come back afterwards
            minecraft.setScreen(new PauseScreen(true));
            return true;
        }
        if (beat >= 0 && stage == Stage.INPUT && current().input() == Input.SKILL_KEY) {
            KeyMapping key = skillTreeKey();
            if (key != null && key.matches(keyCode, scanCode)) {
                handOff();
                // Returning false lets Minecraft treat this as a normal key press now that
                // our screen is gone, so the Skill Tree mod sees it and opens by itself.
                return false;
            }
        }
        if (beat >= 0 && (keyCode == 257 || keyCode == 335 || keyCode == 32)) {   // Enter / numpad Enter / Space
            advance(-1, -1, Util.getMillis());
            return true;
        }
        return false;
    }

    private void handOff() {
        setStage(Stage.DONE);
        StoryClient.onHandOff();
        minecraft.setScreen(null);
    }

    // ------------------------------------------------------------------ helpers

    /** The Skill Tree mod's "Open Skill Tree" key, or null if it isn't installed or is unbound. */
    private KeyMapping skillTreeKey() {
        if (minecraft == null) return null;
        for (KeyMapping k : minecraft.options.keyMappings) {
            if (SKILL_TREE_KEY.equals(k.getName())) return k.isUnbound() ? null : k;
        }
        return null;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    private static float easeOut(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static int lerpColor(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int gg = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bb = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (gg << 8) | bb;
    }
}

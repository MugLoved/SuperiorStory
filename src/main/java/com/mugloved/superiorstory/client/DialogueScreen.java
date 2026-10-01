package com.mugloved.superiorstory.client;

import static com.mugloved.superiorstory.client.StoryChrome.*;

import com.mugloved.superiorstory.dialogue.Duration;
import com.mugloved.superiorstory.dialogue.Names;
import com.mugloved.superiorstory.dialogue.Vars;
import com.mugloved.superiorstory.network.DialoguePackets;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The dialogue box: a still portrait of the speaker (an entity, or a block's item; none for narration) on the left, typed
 * dialogue on the right, choices below. The world stays visible and running; the server drives the conversation one line
 * at a time. Item and boss names in the text and on choice rows are hoverable and show the full tooltip.
 *
 * Line flow: WAITING -> TYPING -> HOLD -> INPUT (continue marker or choices) -> OUT (fade) -> WAITING for the next line.
 */
final class DialogueScreen extends Screen {
    private enum Stage { WAITING, TYPING, HOLD, INPUT, OUT }

    private static final int PORTRAIT = 72;
    private static final int GAP = 10;
    private static final int PAD = 10;
    private static final int LINE_HEIGHT = 11;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int ITEM_SLOT = 18;
    private static final int MARGIN_BOTTOM = 22;
    private static final long SHOW_IN = 380;
    private static final long HOLD_MS = 240;
    private static final long OUT_MS = 220;
    private static final long CHAR_FADE = 90;
    private static final long INPUT_GUARD = 180;
    private static final long MIN_SOUND_GAP = 38;

    private final long sessionId;
    private final int speakerKind;
    private final int npcId;
    private final BlockPos blockPos;
    private final long openedAt = Util.getMillis();
    private final float[] hot = new float[4];
    private final List<String> lines = new ArrayList<>();
    private Stage stage = Stage.WAITING;
    private long stageAt = openedAt;
    private DialoguePackets.Node node;
    private DialoguePackets.Node pending;
    private boolean ended;
    private boolean busy;
    private List<String> labels = List.of();
    private List<int[]> labelColors = List.of();
    private List<List<Vars.Span>> labelSpans = List.of();
    private List<List<ItemStack>> labelItems = List.of();
    private List<Vars.Span> textSpans = List.of();
    private int[] textColors = new int[0];
    private int frozenTick = -1;

    private String text = "";
    private long[] revealAt = new long[0];
    private int revealed;
    private long nextCharAt;
    private long lastSoundAt;
    private long inputShownAt;
    private int hovered = -1;
    private int chosen = -1;
    private float top = Float.NaN;
    private long lastFrameAt;

    // layout, recomputed by layout()
    private int left;
    private int textX;
    private int textW;
    private int panelH = PORTRAIT;
    private int targetTop;

    DialogueScreen(long sessionId, int speakerKind, int npcId, BlockPos blockPos) {
        super(Component.empty());
        this.sessionId = sessionId;
        this.speakerKind = speakerKind;
        this.npcId = npcId;
        this.blockPos = blockPos;
    }

    long sessionId() {
        return sessionId;
    }

    void receive(DialoguePackets.Node next) {
        if (node != null && next.sessionId() == node.sessionId() && next.serial() == node.serial()) {
            node = next;   // the server finished working on the line we are showing (busy cleared, variables final)
            busy = next.busy();
            return;
        }
        pending = next;
    }

    // ------------------------------------------------------------------ names

    /** The localized name of an entity type ID, for {boss}-style variables. */
    private static String entityName(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? Names.clean(id) : BuiltInRegistries.ENTITY_TYPE.getOptional(key)
            .map(type -> type.getDescription().getString()).orElseGet(() -> Names.clean(id));
    }

    @Nullable
    static ItemStack itemStack(String id, int count) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : BuiltInRegistries.ITEM.getOptional(key).map(item -> new ItemStack(item, count)).orElse(null);
    }

    private static String itemName(String id) {
        ItemStack stack = itemStack(id, 1);
        return stack == null ? Names.clean(id) : stack.getHoverName().getString();
    }

    private static String timeText(String millis) {
        try {
            return Duration.format(Long.parseLong(millis), unit -> I18n.get("superiorstory.unit." + unit));
        } catch (NumberFormatException exception) {
            return millis;
        }
    }

    private static String langText(String key) {
        if (I18n.exists(key)) return I18n.get(key);
        return Names.clean(key.substring(key.lastIndexOf('.') + 1));
    }

    private static String resolve(String kind, String ref) {
        return switch (kind) {
            case "entity" -> entityName(ref);
            case "item" -> itemName(ref);
            case "time" -> timeText(ref);
            case "lang" -> langText(ref);
            case "structure" -> Names.clean(ref.substring(ref.indexOf(':') + 1));
            case "danger" -> StoryPresentation.dangerText(ref);
            default -> ValueKinds.resolve(kind, ref);
        };
    }

    /** The color a filled-in value is drawn in, or 0 to keep the surrounding text color. */
    private static int spanColor(Vars.Span span) {
        return switch (span.kind()) {
            case "item" -> StoryPresentation.itemColor(span.ref());
            case "entity", "danger" -> StoryPresentation.entityColor(span.ref());
            case "structure" -> NAME_STRUCTURE;
            case "time" -> NAME_TIME;
            case "lang" -> span.ref().startsWith("superiorstory.bounty.rarity.") ? StoryChrome.bountyRarityColor(span.ref()) : nameColor(span.key());
            default -> nameColor(span.key());
        };
    }

    void serverEnded() {
        ended = true;
    }

    @Override
    protected void init() {
        if (node != null) layout();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (!ended) {
            ended = true;
            DialoguePackets.sendToServer(new DialoguePackets.Advance(sessionId, node == null ? 0 : node.serial(), DialoguePackets.LEAVE));
        }
        DialogueClient.finish(this);
    }

    private Entity npc() {
        return speakerKind != DialoguePackets.ENTITY || minecraft == null || minecraft.level == null ? null : minecraft.level.getEntity(npcId);
    }

    private ItemStack blockItem() {
        if (speakerKind != DialoguePackets.BLOCK || minecraft == null || minecraft.level == null) return ItemStack.EMPTY;
        return new ItemStack(minecraft.level.getBlockState(blockPos).getBlock());
    }

    private boolean hasPortrait() {
        return speakerKind != DialoguePackets.NONE;
    }

    private int portraitWidth() {
        return hasPortrait() ? PORTRAIT : 0;
    }

    private int choiceCount() {
        return node == null ? 0 : node.choices().size();
    }

    // ------------------------------------------------------------------ lines

    private void begin(long now) {
        node = pending;
        pending = null;
        busy = node.busy();
        Vars.Filled body = Vars.fill(node.text().component().getString(), node.vars(), DialogueScreen::resolve);
        text = body.text();
        textSpans = body.spans();
        textColors = paint(body);
        List<String> filled = new ArrayList<>(node.choices().size());
        List<int[]> filledColors = new ArrayList<>(node.choices().size());
        List<List<Vars.Span>> filledSpans = new ArrayList<>(node.choices().size());
        List<List<ItemStack>> filledItems = new ArrayList<>(node.choices().size());
        for (var choice : node.choices()) {
            Vars.Filled label = Vars.fill(choice.label().component().getString(), node.vars(), DialogueScreen::resolve);
            filled.add(label.text());
            filledColors.add(paint(label));
            filledSpans.add(label.spans());
            List<ItemStack> stacks = new ArrayList<>();
            for (var shown : choice.items()) {
                ItemStack stack = itemStack(shown.item().toString(), Math.max(1, shown.quantity()));
                if (stack != null) stacks.add(stack);
            }
            filledItems.add(stacks);
        }
        labels = filled;
        labelColors = filledColors;
        labelSpans = filledSpans;
        labelItems = filledItems;
        revealed = 0;
        revealAt = new long[text.length()];
        nextCharAt = now;
        hovered = -1;
        chosen = -1;
        layout();
        setStage(Stage.TYPING, now);
    }

    /** Per-character override colors (0 keeps the base color) for filled-in values: places, items, bosses, times. */
    private static int[] paint(Vars.Filled filled) {
        int[] colors = new int[filled.text().length()];
        for (Vars.Span span : filled.spans()) {
            int color = spanColor(span);
            if (color != 0) java.util.Arrays.fill(colors, span.start(), span.end(), color);
        }
        return colors;
    }

    /** Draws {@code line[from, to)} in runs of equal color; {@code offset} is the line's first index in {@code colors}. */
    private void drawRuns(GuiGraphics g, String line, int from, int to, int[] colors, int offset, float x, float y,
                          float alpha, int base, boolean shadow) {
        for (int i = from; i < to; ) {
            int color = colors[offset + i] != 0 ? colors[offset + i] : base;
            int j = i + 1;
            while (j < to && (colors[offset + j] != 0 ? colors[offset + j] : base) == color) j++;
            g.drawString(font, line.substring(i, j), x + font.width(line.substring(0, i)), y, Backdrop.argb(alpha, color), shadow);
            i = j;
        }
    }

    /** A dotted underline under each hoverable item or boss name in {@code line[0, shown)}, like a tooltip definition. */
    private void underline(GuiGraphics g, String line, int offset, int shown, List<Vars.Span> spans, int[] colors, float x, float y,
                           float alpha, int base) {
        for (Vars.Span span : spans) {
            if (!span.kind().equals("item") && !span.kind().equals("entity")) continue;
            int from = Math.max(0, span.start() - offset), to = Math.min(shown, span.end() - offset);
            if (to <= from) continue;
            int color = colors[span.start()] != 0 ? colors[span.start()] : base;
            dotted(g, x + font.width(line.substring(0, from)), Math.round(y) + font.lineHeight, font.width(line.substring(from, to)),
                Backdrop.argb(alpha * 0.9f, color));
        }
    }

    private void setStage(Stage next, long now) {
        stage = next;
        stageAt = now;
    }

    private void layout() {
        int boxW = Math.min(width - 32, 460);
        left = (width - boxW) / 2;
        int gap = hasPortrait() ? GAP : 0;
        textX = left + portraitWidth() + gap;
        textW = boxW - portraitWidth() - gap;
        lines.clear();
        lines.addAll(wrap(textW - 2 * PAD));
        panelH = Math.max(hasPortrait() ? PORTRAIT : 0, 24 + lines.size() * LINE_HEIGHT + 14);
        int n = choiceCount();
        int choicesH = n == 0 ? 0 : 8 + n * (ROW_HEIGHT + ROW_GAP) - ROW_GAP;
        targetTop = height - MARGIN_BOTTOM - panelH - choicesH;
        if (Float.isNaN(top)) top = targetTop + 16;
    }

    /** Word-wrap; lines keep their trailing space so letter positions line up with the text. */
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

    private void advanceTyping(long now) {
        while (stage == Stage.TYPING && revealed < text.length() && now >= nextCharAt) {
            char c = text.charAt(revealed);
            char following = revealed + 1 < text.length() ? text.charAt(revealed + 1) : ' ';
            revealAt[revealed] = now;
            revealed++;
            nextCharAt = Math.max(nextCharAt, now - 60) + (long) (charDelay(c, following) * node.pace());
            if (!Character.isWhitespace(c) && now - lastSoundAt >= MIN_SOUND_GAP) {
                StoryAudio.type(node.accent());
                lastSoundAt = now;
            }
        }
        if (stage == Stage.TYPING && revealed >= text.length()) setStage(Stage.HOLD, now);
    }

    private void completeLine(long now) {
        for (int i = revealed; i < text.length(); i++) revealAt[i] = now - CHAR_FADE / 2;
        revealed = text.length();
        setStage(Stage.HOLD, now);
    }

    private void update(long now) {
        long inStage = now - stageAt;
        switch (stage) {
            case WAITING -> { if (pending != null) begin(now); }
            case TYPING -> advanceTyping(now);
            case HOLD -> {
                if (inStage >= HOLD_MS) {
                    setStage(Stage.INPUT, now);
                    inputShownAt = now;
                    if (choiceCount() > 0) StoryAudio.appear();
                }
            }
            case OUT -> {
                if (inStage >= OUT_MS) {
                    if (pending != null) begin(now);
                    else setStage(Stage.WAITING, now);
                }
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ camera

    /** Where the player's view eases to: the speaking entity's eyes or the middle of the speaking block. */
    @Nullable
    private Vec3 focus() {
        Entity npc = npc();
        if (npc != null) return npc.getEyePosition();
        return speakerKind == DialoguePackets.BLOCK ? Vec3.atCenterOf(blockPos) : null;
    }

    /** Ease the player's view onto the speaker; the mouse is released while this screen is open. */
    @Override
    public void tick() {
        Vec3 focus = focus();
        if (focus == null || minecraft.player == null) return;
        Vec3 delta = focus.subtract(minecraft.player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(Mth.atan2(delta.y, horizontal) * (180.0 / Math.PI));
        minecraft.player.setYRot(Mth.approachDegrees(minecraft.player.getYRot(), yaw, 10f));
        minecraft.player.setXRot(Mth.approachDegrees(minecraft.player.getXRot(), Mth.clamp(pitch, -40f, 40f), 10f));
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = lastFrameAt == 0 ? 0 : Math.min(100, now - lastFrameAt);
        lastFrameAt = now;
        update(now);
        if (node == null) return;   // first line still on its way; the HUD is already fading

        float appear = easeOut(clamp01((now - openedAt) / (float) SHOW_IN));
        top += (targetTop - top) * (1f - (float) Math.exp(-dt / 90f));
        int y0 = Math.round(top) + Math.round((1f - appear) * 12f);
        float content = appear * switch (stage) {
            case WAITING -> 0f;
            case OUT -> 1f - clamp01((now - stageAt) / (float) OUT_MS);
            default -> 1f;
        };

        // a soft shade behind the box keeps it readable over bright worlds
        g.fillGradient(0, (int) (height * 0.5f), width, height, 0, Backdrop.argb(0.6f * appear, 0x05070B));

        if (hasPortrait()) drawPortrait(g, y0, appear);
        StoryChrome.frame(g, textX, y0, textX + textW, y0 + panelH, appear, 0f);
        drawName(g, y0, appear);
        drawText(g, y0, now, content);
        drawInput(g, y0, now, content, mouseX, mouseY, dt);
        if (content > 0.6f) drawHover(g, y0, mouseX, mouseY);
    }

    private void drawPortrait(GuiGraphics g, int y0, float appear) {
        int x1 = left + PORTRAIT, y1 = y0 + PORTRAIT;
        StoryChrome.frame(g, left, y0, x1, y1, appear, 0f);
        g.fillGradient(left + 1, y0 + 1, x1 - 1, y1 - 1, Backdrop.argb(0.9f * appear, 0x1C2638), Backdrop.argb(0.9f * appear, PANEL));
        if (npc() instanceof LivingEntity living) {
            if (frozenTick < 0) frozenTick = living.tickCount;
            EntityPortrait.draw(g, living, left + 1, y0 + 1, x1 - 1, y1 - 1, frozenTick);
            if (appear < 1f) {   // entities cannot take an alpha, so fade the portrait in under a veil
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                g.fill(left + 1, y0 + 1, x1 - 1, y1 - 1, Backdrop.argb(1f - appear, PANEL));
                g.pose().popPose();
            }
        } else if (speakerKind == DialoguePackets.BLOCK) {
            ItemStack stack = blockItem();
            if (!stack.isEmpty()) {
                float scale = (PORTRAIT - 12) / 16f;
                g.pose().pushPose();
                g.pose().translate(left + 6, y0 + 6, 0);
                g.pose().scale(scale, scale, 1f);
                g.renderItem(stack, 0, 0);
                g.pose().popPose();
                if (appear < 1f) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 200);
                    g.fill(left + 1, y0 + 1, x1 - 1, y1 - 1, Backdrop.argb(1f - appear, PANEL));
                    g.pose().popPose();
                }
            }
        }
    }

    private String speakerName() {
        if (node.speaker() != null) return node.speaker().component().getString();
        Entity npc = npc();
        if (npc != null) return npc.getDisplayName().getString();
        if (speakerKind == DialoguePackets.BLOCK && minecraft != null && minecraft.level != null) {
            BlockState state = minecraft.level.getBlockState(blockPos);
            return state.getBlock().getName().getString();
        }
        return "";
    }

    private void drawName(GuiGraphics g, int y0, float appear) {
        String name = speakerName();
        int color = Backdrop.argb(appear, StoryPresentation.speakerColor(npc(), TEXT_ACCENT));
        if ((color >>> 24) < 5 || name.isEmpty()) return;
        g.drawString(font, name, textX + PAD, y0 + 7, color, false);
        g.fill(textX + PAD, y0 + 18, textX + PAD + font.width(name), y0 + 19, Backdrop.argb(appear * 0.5f, BORDER_HOT));
    }

    private void drawText(GuiGraphics g, int y0, long now, float alpha) {
        if (alpha <= 0.02f) return;
        int color = node.accent() ? TEXT_ACCENT : TEXT;
        int index = 0;
        for (int li = 0; li < lines.size(); li++) {
            String line = lines.get(li);
            float x = textX + PAD;
            float y = y0 + 24 + li * LINE_HEIGHT;
            int shown = Math.max(0, Math.min(line.length(), revealed - index));
            int solid = 0;
            while (solid < shown && now - revealAt[index + solid] >= CHAR_FADE) solid++;
            if (solid > 0) drawRuns(g, line, 0, solid, textColors, index, x, y, alpha, color, true);
            underline(g, line, index, shown, textSpans, textColors, x, y, alpha, color);
            for (int j = solid; j < shown; j++) {   // freshly typed letters fade in and rise
                float a = clamp01((now - revealAt[index + j]) / (float) CHAR_FADE);
                int letter = textColors[index + j] != 0 ? textColors[index + j] : color;
                int argb = Backdrop.argb(a * alpha, letter);
                if ((argb >>> 24) >= 5) {
                    g.drawString(font, String.valueOf(line.charAt(j)), x + font.width(line.substring(0, j)), y + (1f - a) * 2f, argb, true);
                }
            }
            index += line.length();
        }
    }

    private int rowTop(int y0, int i) {
        return y0 + panelH + 8 + i * (ROW_HEIGHT + ROW_GAP);
    }

    private boolean overRow(int y0, int i, double mx, double my) {
        int ry = rowTop(y0, i);
        return mx >= textX && mx < textX + textW && my >= ry && my < ry + ROW_HEIGHT;
    }

    private void drawInput(GuiGraphics g, int y0, long now, float content, int mouseX, int mouseY, float dt) {
        int n = choiceCount();
        boolean live = stage == Stage.INPUT && now - inputShownAt > 100;
        if (live && n > 0) {
            int over = -1;
            for (int i = 0; i < n; i++) if (overRow(y0, i, mouseX, mouseY)) over = i;
            if (over >= 0 && over != hovered) {
                hovered = over;
                StoryAudio.hover();
            }
        }
        float k = 1f - (float) Math.exp(-dt / 55f);
        for (int i = 0; i < hot.length; i++) {
            float target = (live && i == hovered) || (stage == Stage.OUT && i == chosen) ? 1f : 0f;
            hot[i] += (target - hot[i]) * k;
        }
        if (stage != Stage.INPUT && stage != Stage.OUT) return;
        float shown = easeOut(clamp01((now - inputShownAt) / 300f)) * content;
        if (n == 0) {
            if (shown < 0.05f) return;
            int x = textX + textW - PAD - 7, y = y0 + panelH - 14;
            if (busy) {   // the server is still working: three pulsing dots instead of the continue marker
                for (int d = 0; d < 3; d++) {
                    float a = 0.3f + 0.7f * (float) Math.max(0.0, Math.sin(now / 240.0 - d * 0.9));
                    g.fill(x - 6 + d * 5, y + 1, x - 3 + d * 5, y + 4, Backdrop.argb(shown * a, KEY_GOLD));
                }
                return;
            }
            float pulse = 0.55f + 0.45f * (float) Math.sin(now / 320.0);
            int c = Backdrop.argb(shown * pulse, KEY_GOLD);
            g.fill(x, y, x + 7, y + 1, c);
            g.fill(x + 1, y + 1, x + 6, y + 2, c);
            g.fill(x + 2, y + 2, x + 5, y + 3, c);
            g.fill(x + 3, y + 3, x + 4, y + 4, c);
            return;
        }
        for (int i = 0; i < n; i++) {
            float h = easeOut(clamp01(hot[i]));
            int rx = textX + Math.round((1f - shown) * 6f) + Math.round(2f * h);
            int ry = rowTop(y0, i);
            StoryChrome.frame(g, rx, ry, rx + textW, ry + ROW_HEIGHT, shown, hot[i]);
            int labelBase = lerpColor(LABEL, LABEL_HOT, h);
            int keyColor = Backdrop.argb(shown, KEY_GOLD);
            if ((Backdrop.argb(shown, labelBase) >>> 24) < 5) continue;
            g.drawString(font, Integer.toString(i + 1), rx + 8, ry + 6, keyColor, false);
            String label = labels.get(i);
            drawRuns(g, label, 0, label.length(), labelColors.get(i), 0, rx + 22, ry + 6, shown, labelBase, false);
            underline(g, label, 0, label.length(), labelSpans.get(i), labelColors.get(i), rx + 22, ry + 6, shown, labelBase);
            List<ItemStack> stacks = labelItems.get(i);
            for (int s = 0; s < stacks.size(); s++) {
                int ix = rx + textW - 6 - ITEM_SLOT * (stacks.size() - s), iy = ry + 2;
                g.renderItem(stacks.get(s), ix, iy);
                g.renderItemDecorations(font, stacks.get(s), ix, iy);
            }
        }
    }

    // ------------------------------------------------------------------ hover

    /** The span under the mouse in the typed text, or null. */
    @Nullable
    private Vars.Span textSpanAt(int y0, double mx, double my) {
        int index = 0;
        for (int li = 0; li < lines.size(); li++) {
            String line = lines.get(li);
            int ly = y0 + 24 + li * LINE_HEIGHT;
            if (my >= ly && my < ly + LINE_HEIGHT) {
                int at = charAt(line, mx - (textX + PAD));
                if (at < 0 || index + at >= revealed) return null;
                return spanIn(textSpans, index + at);
            }
            index += line.length();
        }
        return null;
    }

    /** The span under the mouse in a choice label, or null. */
    @Nullable
    private Vars.Span labelSpanAt(int y0, int row, double mx, double my) {
        int ry = rowTop(y0, row);
        if (my < ry || my >= ry + ROW_HEIGHT) return null;
        int at = charAt(labels.get(row), mx - (textX + 22));
        return at < 0 ? null : spanIn(labelSpans.get(row), at);
    }

    @Nullable
    private static Vars.Span spanIn(List<Vars.Span> spans, int index) {
        for (Vars.Span span : spans) if (index >= span.start() && index < span.end()) return span;
        return null;
    }

    /** Index of the character at {@code relX} pixels into the string, or -1 when outside it. */
    private int charAt(String line, double relX) {
        if (relX < 0 || relX >= font.width(line)) return -1;
        for (int j = 0; j < line.length(); j++) {
            if (relX < font.width(line.substring(0, j + 1))) return j;
        }
        return -1;
    }

    private void drawHover(GuiGraphics g, int y0, int mouseX, int mouseY) {
        Vars.Span span = textSpanAt(y0, mouseX, mouseY);
        int n = choiceCount();
        if (span == null && stage == Stage.INPUT) {
            for (int i = 0; i < n && span == null; i++) {
                span = labelSpanAt(y0, i, mouseX, mouseY);
                if (span == null && overRow(y0, i, mouseX, mouseY)) {   // the item icon on the row
                    List<ItemStack> stacks = labelItems.get(i);
                    int rowRight = textX + textW - 6;
                    for (int s = 0; s < stacks.size(); s++) {
                        int ix = rowRight - ITEM_SLOT * (stacks.size() - s);
                        if (mouseX >= ix && mouseX < ix + 16 && mouseY >= rowTop(y0, i) + 2 && mouseY < rowTop(y0, i) + 18) {
                            g.renderTooltip(font, stacks.get(s), mouseX, mouseY);
                            return;
                        }
                    }
                }
            }
        }
        if (span == null) return;
        if (span.kind().equals("item")) {
            ItemStack stack = itemStack(span.ref(), 1);
            if (stack != null) g.renderTooltip(font, stack, mouseX, mouseY);
        } else if (span.kind().equals("entity")) {
            StoryPresentation.drawEntityHover(g, font, span.ref(), textX, textW, y0 - 10);
        }
    }

    // ------------------------------------------------------------------ input

    private void choose(int index) {
        if (stage != Stage.INPUT || Util.getMillis() - inputShownAt < INPUT_GUARD) return;
        if (busy && index == DialoguePackets.CONTINUE) return;
        StoryAudio.select();
        chosen = index;
        DialoguePackets.sendToServer(new DialoguePackets.Advance(sessionId, node.serial(), index));
        setStage(Stage.OUT, Util.getMillis());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || node == null) return true;
        if (stage == Stage.TYPING) {
            completeLine(Util.getMillis());
        } else if (stage == Stage.INPUT) {
            int n = choiceCount();
            if (n == 0) {
                choose(DialoguePackets.CONTINUE);
            } else {
                int y0 = Math.round(top);
                for (int i = 0; i < n; i++) if (overRow(y0, i, mouseX, mouseY)) choose(i);
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) return true;   // Esc closes, which leaves the conversation
        if (node == null) return false;
        int n = choiceCount();
        if (keyCode >= 49 && keyCode < 49 + n) {   // 1-4
            choose(keyCode - 49);
            return true;
        }
        if (stage == Stage.INPUT && n > 1 && (keyCode == 264 || keyCode == 265)) {   // down / up
            hovered = Math.floorMod((hovered < 0 ? (keyCode == 264 ? -1 : 0) : hovered) + (keyCode == 264 ? 1 : -1), n);
            StoryAudio.hover();
            return true;
        }
        if (keyCode == 257 || keyCode == 335 || keyCode == 32) {   // Enter / numpad Enter / Space
            if (stage == Stage.TYPING) completeLine(Util.getMillis());
            else if (stage == Stage.INPUT) {
                if (n == 0) choose(DialoguePackets.CONTINUE);
                else if (hovered >= 0) choose(hovered);
            }
            return true;
        }
        return false;
    }
}

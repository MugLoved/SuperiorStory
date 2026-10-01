package com.mugloved.superiorstory.client;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.network.DialoguePackets;
import com.superior.lib.api.entity.BossTierApi;
import com.superior.lib.api.presentation.tooltip.SuperiorTooltipApi;
import com.superior.lib.api.presentation.tooltip.SuperiorTooltipBodyBlock;
import com.superior.lib.api.presentation.tooltip.SuperiorTooltipClientPresentationApi;
import com.superior.lib.api.presentation.tooltip.SuperiorTooltipDocument;
import com.superior.lib.api.presentation.tooltip.SuperiorTooltipDocumentBuilder;
import com.superior.lib.api.presentation.tooltip.SuperiorTooltipPresentation;
import com.superior.lib.api.presentation.tooltip.SuperiorTooltipProvider;
import com.superior.lib.api.presentation.tooltip.SuperiorTooltipRegistryApi;
import com.superior.lib.api.presentation.tooltip.SuperiorTooltipRequest;
import com.superior.lib.api.presentation.tooltip.SuperiorTooltipTextElement;
import com.superior.lib.api.presentation.tooltip.TooltipItemCapabilities;
import com.superior.lib.api.presentation.tooltip.TooltipSubject;
import com.superior.lib.api.presentation.tooltip.TooltipSurface;
import com.superior.lib.api.service.SuperiorServiceRegistry;
import com.mugloved.superiorstory.dialogue.Names;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Colors and hovers for the things a conversation names: items in their rarity color, bosses in their tier color. Superior Lib
 * owns the tier colors; Superior Tooltips owns how an item or a boss definition is shown. Every Lib and Tooltips reference is
 * optional and guarded, so Story works without them.
 */
final class StoryPresentation {
    static final String BOSS_FACTS_SOURCE = "superiorstory:boss_facts";
    private static volatile Map<ResourceLocation, DialoguePackets.BossEntry> bossFacts = Map.of();
    private static boolean registered;

    private StoryPresentation() {}

    static void setBossFacts(Map<ResourceLocation, DialoguePackets.BossEntry> facts) {
        bossFacts = Map.copyOf(facts);
    }

    /** Registers the boss facts source with Superior Tooltips once; without Tooltips the hover simply has no facts. */
    static void register() {
        if (registered) return;
        registered = true;
        try {
            SuperiorTooltipRegistryApi registry = SuperiorServiceRegistry.getOptional(SuperiorTooltipRegistryApi.class).orElse(null);
            if (registry != null) registry.registerProvider(new BossFactsProvider());
        } catch (RuntimeException | LinkageError failure) {
            SuperiorStory.LOGGER.warn("Boss hover facts unavailable: {}", failure.toString());
        }
    }

    // ---------------------------------------------------------------- colors

    /** The item's rarity color, or 0 to keep the text color. */
    static int itemColor(String id) {
        ItemStack stack = DialogueScreen.itemStack(id, 1);
        if (stack == null) return 0;
        Integer color = stack.getRarity().color.getColor();
        return color == null ? 0 : color;
    }

    @Nullable
    private static BossTierApi.BossTierEntry tier(@Nullable ResourceLocation entity) {
        if (entity == null) return null;
        try {
            BossTierApi tiers = SuperiorServiceRegistry.getOptional(BossTierApi.class).orElse(null);
            return tiers == null ? null : tiers.find(entity).orElse(null);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** The boss tier color for an entity type ID, or 0 for a creature that is not a classified boss. */
    static int entityColor(String id) {
        BossTierApi.BossTierEntry entry = tier(ResourceLocation.tryParse(id));
        return entry == null ? 0 : entry.color();
    }

    /** A speaker's name color: the tier color for a boss, else {@code fallback}. */
    static int speakerColor(@Nullable Entity entity, int fallback) {
        if (entity == null) return fallback;
        BossTierApi.BossTierEntry entry = tier(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        return entry == null ? fallback : entry.color();
    }

    // ---------------------------------------------------------------- hover

    /** How a boss is spoken of by its tier ("a legend that has ended armies"); a plain "a creature" when it is not a classified boss. */
    static String dangerText(String id) {
        BossTierApi.BossTierEntry entry = tier(ResourceLocation.tryParse(id));
        String text = entry == null ? null : Names.danger(entry.tier());
        return text == null ? "a creature" : text;
    }

    private static final int HOVER_PAD = 8;
    private static final int HOVER_PREVIEW = 64;
    private static final int HOVER_MAX_WIDTH = 300;
    private static final Map<ResourceLocation, Entity> PREVIEWS = new java.util.HashMap<>();
    private static Object previewLevel;

    /**
     * Draws the hover for a boss name the player points at, in the conversation panel's own frame and palette, with its bottom
     * edge at {@code bottom} (just above the conversation panel): the live preview, tier-colored name, tier, look, lore, lairs.
     */
    static void drawEntityHover(GuiGraphics g, Font font, String id, int panelX, int panelWidth, int bottom) {
        ResourceLocation entity = ResourceLocation.tryParse(id);
        Minecraft minecraft = Minecraft.getInstance();
        if (entity == null || minecraft.level == null) return;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(entity).orElse(null);
        if (type == null) return;
        BossTierApi.BossTierEntry tier = tier(entity);
        DialoguePackets.BossEntry facts = bossFacts.get(entity);
        Entity preview = preview(minecraft, entity, type);

        int width = Math.min(panelWidth, HOVER_MAX_WIDTH);
        int textX = panelX + HOVER_PAD + (preview instanceof LivingEntity ? HOVER_PREVIEW + HOVER_PAD : 0);
        int textWidth = panelX + width - HOVER_PAD - textX;
        List<FormattedCharSequence> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (tier != null) add(font, lines, colors, Component.translatable("superiorstory.boss.tier", tier.tier()), tier.color(), textWidth);
        if (facts != null) {
            if (!facts.look().isBlank()) add(font, lines, colors, Component.literal(facts.look()).withStyle(Style.EMPTY.withItalic(true)), 0xC9D1E0, textWidth);
            if (!facts.lore().isBlank()) add(font, lines, colors, Component.literal(facts.lore()), 0x9AA6BA, textWidth);
            if (!facts.lairs().isEmpty()) {
                add(font, lines, colors, Component.translatable("superiorstory.boss.lairs", String.join(", ", facts.lairs())), StoryChrome.NAME_STRUCTURE, textWidth);
            }
        }
        int textHeight = 14 + lines.size() * (font.lineHeight + 1);
        int height = Math.max(preview instanceof LivingEntity ? HOVER_PREVIEW + 2 * HOVER_PAD : 0, textHeight + 2 * HOVER_PAD);
        int y0 = Math.max(4, bottom - height);
        int nameColor = tier == null ? StoryChrome.TEXT : tier.color();
        String name = type.getDescription().getString();

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        StoryChrome.frame(g, panelX, y0, panelX + width, y0 + height, 1f, 0.35f);
        if (preview instanceof LivingEntity living) {
            int px = panelX + HOVER_PAD, py = y0 + (height - HOVER_PREVIEW) / 2;
            g.fillGradient(px, py, px + HOVER_PREVIEW, py + HOVER_PREVIEW, Backdrop.argb(0.9f, 0x1C2638), Backdrop.argb(0.9f, StoryChrome.PANEL));
            g.fill(px, py, px + HOVER_PREVIEW, py + 1, Backdrop.argb(1f, StoryChrome.BORDER));
            g.fill(px, py + HOVER_PREVIEW - 1, px + HOVER_PREVIEW, py + HOVER_PREVIEW, Backdrop.argb(1f, StoryChrome.BORDER));
            g.fill(px, py, px + 1, py + HOVER_PREVIEW, Backdrop.argb(1f, StoryChrome.BORDER));
            g.fill(px + HOVER_PREVIEW - 1, py, px + HOVER_PREVIEW, py + HOVER_PREVIEW, Backdrop.argb(1f, StoryChrome.BORDER));
            EntityPortrait.draw(g, living, px + 1, py + 1, px + HOVER_PREVIEW - 1, py + HOVER_PREVIEW - 1, 0);
        }
        int y = y0 + HOVER_PAD;
        g.drawString(font, name, textX, y, Backdrop.argb(1f, nameColor), false);
        StoryChrome.dotted(g, textX, y + font.lineHeight, font.width(name), Backdrop.argb(0.9f, nameColor));
        y += 14;
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), textX, y, Backdrop.argb(1f, colors.get(i)), false);
            y += font.lineHeight + 1;
        }
        g.pose().popPose();
    }

    private static void add(Font font, List<FormattedCharSequence> lines, List<Integer> colors, Component text, int color, int width) {
        for (FormattedCharSequence line : font.split(text, Math.max(40, width))) {
            lines.add(line);
            colors.add(color);
        }
    }

    /** A cached, never-ticked copy of the creature for the preview; rebuilt when the world changes. */
    @Nullable
    private static Entity preview(Minecraft minecraft, ResourceLocation id, EntityType<?> type) {
        if (previewLevel != minecraft.level) {
            PREVIEWS.clear();
            previewLevel = minecraft.level;
        }
        return PREVIEWS.computeIfAbsent(id, key -> {
            try {
                return type.create(minecraft.level);
            } catch (RuntimeException failure) {
                return null;
            }
        });
    }

    /** The lines under a boss's name: its tier, what its lair looks like, its lore, and where it lives. */
    private static final class BossFactsProvider implements SuperiorTooltipProvider {
        @Override
        public String sourceId() {
            return BOSS_FACTS_SOURCE;
        }

        @Override
        public boolean supports(SuperiorTooltipRequest request) {
            if (request == null || request.subject().kind() != TooltipSubject.Kind.ENTITY) return false;
            return tier(request.subject().id()) != null || bossFacts.containsKey(request.subject().id());
        }

        @Override
        public void contribute(SuperiorTooltipRequest request, SuperiorTooltipDocumentBuilder builder) {
            ResourceLocation id = request.subject().id();
            List<Component> lines = new ArrayList<>();
            BossTierApi.BossTierEntry tier = tier(id);
            if (tier != null) {
                lines.add(Component.translatable("superiorstory.boss.tier", tier.tier()).withStyle(Style.EMPTY.withColor(tier.color())));
            }
            DialoguePackets.BossEntry facts = bossFacts.get(id);
            if (facts != null) {
                if (!facts.look().isBlank()) lines.add(Component.literal(facts.look()).withStyle(Style.EMPTY.withItalic(true).withColor(0xC9D1E0)));
                if (!facts.lore().isBlank()) lines.add(Component.literal(facts.lore()).withStyle(Style.EMPTY.withColor(0x9AA6BA)));
                if (!facts.lairs().isEmpty()) {
                    lines.add(Component.translatable("superiorstory.boss.lairs", String.join(", ", facts.lairs()))
                        .withStyle(Style.EMPTY.withColor(0xFFB454)));
                }
            }
            if (!lines.isEmpty()) {
                builder.addBodyBlock(SuperiorTooltipBodyBlock.always("boss_facts", SuperiorTooltipBodyBlock.Kind.INFO, null,
                    List.of(new SuperiorTooltipTextElement(lines)), 500));
            }
        }
    }
}

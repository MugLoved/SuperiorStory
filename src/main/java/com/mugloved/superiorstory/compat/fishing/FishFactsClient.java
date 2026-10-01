package com.mugloved.superiorstory.compat.fishing;

import com.mugloved.superiorstory.dialogue.Vars;
import com.wdiscute.starcatcher.fish.FishApi;
import com.wdiscute.starcatcher.fish.FishProperties;
import com.wdiscute.starcatcher.registry.fishrestrictions.AbstractFishRestriction;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * Client side of the fish facts: turns {@code @fishinfo:<part>:<entry>} into Starcatcher's own restriction descriptions, in the
 * guide context and the player's language, exactly as Starcatcher's guide shows them. {@code list} names every picked fish.
 */
public final class FishFactsClient {
    private FishFactsClient() {}

    public static String resolve(String ref) {
        int colon = ref.indexOf(':');
        if (colon < 0) return "";
        String part = ref.substring(0, colon);
        String rest = ref.substring(colon + 1);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return "";
        try {
            if (part.equals("list")) return list(rest);
            ResourceLocation entry = ResourceLocation.tryParse(rest);
            FishProperties fp = entry == null ? null : FishApi.getFP(mc.level, entry);
            if (fp == null) return "";
            StringBuilder out = new StringBuilder();
            for (AbstractFishRestriction restriction : fp.restrictions()) {
                if ("hide".equals(restriction.translationOverride)) continue;
                ResourceLocation type = restriction.getRegistryHolderOrThrow().getId();
                if (type.getPath().equals("empty") || !FishSelection.part(type).equals(part)) continue;
                String text = restriction.getDescription(mc.level, fp, mc.player, AbstractFishRestriction.Context.GUIDE_ENTRY).getString().trim();
                if (text.isEmpty()) continue;
                if (out.length() + text.length() + 2 > Vars.MAX_VALUE) break;   // keep whole leading parts
                if (out.length() > 0) out.append(", ");
                out.append(text);
            }
            return out.toString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    /** {@code ns:entry*2,ns:other*1} as "2 Cod, Salmon" in the client's language. */
    private static String list(String value) {
        Minecraft mc = Minecraft.getInstance();
        StringBuilder out = new StringBuilder();
        for (String token : value.split(",")) {
            int star = token.lastIndexOf('*');
            ResourceLocation entry = ResourceLocation.tryParse(star < 0 ? token : token.substring(0, star));
            FishProperties fp = entry == null ? null : FishApi.getFP(mc.level, entry);
            if (fp == null) continue;
            String name = BuiltInRegistries.ITEM.get(fp.catchInfo().fish().identifier()).getDescription().getString();
            int count = star < 0 ? 1 : Integer.parseInt(token.substring(star + 1));
            if (out.length() > 0) out.append(", ");
            out.append(count > 1 ? count + " " + name : name);
        }
        return out.toString();
    }
}

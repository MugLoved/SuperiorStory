package com.mugloved.superiorstory.compat.fishing;

import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.dialogue.IdSelector;
import com.mugloved.superiorstory.dialogue.ItemSpec;
import com.mugloved.superiorstory.dialogue.Vars;
import com.mugloved.superiorstory.server.QuestLocations;
import com.wdiscute.starcatcher.data.attachments.FishingGuideAttachment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The {@code fish} item source: picks guide fish from Starcatcher's registry by rarity, dimension, and fluid ({@code kinds}
 * distinct species, {@code quantity} of each), preferring species the player has not caught. Its facts point the client at
 * Starcatcher's own restriction descriptions ({@value Vars#FISHINFO}), so hints are in the player's language.
 */
final class FishSource implements StoryHooks.ItemSource {
    private final FishSelection selection;

    FishSource(FishSelection selection) {
        this.selection = selection;
    }

    @Override
    public List<ItemSpec> pick(StoryContext context) {
        var server = context.player().getServer();
        if (server == null) return List.of();
        Set<ResourceLocation> caught = FishingGuideAttachment.getFishesCaught(context.player()).keySet();
        List<ItemSpec> picks = new ArrayList<>();
        for (FishSelection.Candidate fish : selection.pick(FishCatalogue.get(server), caught, new Random(context.player().getRandom().nextLong()))) {
            picks.add(new ItemSpec(IdSelector.parse(fish.item().toString()), selection.quantity(), true));
        }
        return picks;
    }

    @Override
    public String blockedKey() {
        return "superiorstory.blocked.no_fish";
    }

    /** The picked fish's catalogue entries, in pick order (a pick names its item). */
    private static List<FishSelection.Candidate> entries(ServerLevel level, List<ItemSpec> picked) {
        Map<ResourceLocation, FishSelection.Candidate> byItem = new HashMap<>();
        for (FishSelection.Candidate fish : FishCatalogue.get(level.getServer())) byItem.putIfAbsent(fish.item(), fish);
        List<FishSelection.Candidate> out = new ArrayList<>();
        for (ItemSpec spec : picked) {
            FishSelection.Candidate fish = byItem.get(spec.item().id());
            if (fish != null) out.add(fish);
        }
        return out;
    }

    @Override
    public List<StoryHooks.Location> markers(ServerLevel level, List<ItemSpec> picked) {
        List<StoryHooks.Location> out = new ArrayList<>();
        for (FishSelection.Candidate fish : entries(level, picked)) {
            if (fish.biomes().isEmpty()) continue;
            List<IdSelector> selectors = new ArrayList<>();
            for (String biome : fish.biomes()) {
                try {
                    selectors.add(IdSelector.parse(biome));
                } catch (IllegalArgumentException ignored) {
                    // an entry format this version does not read gets no marker
                }
            }
            if (!selectors.isEmpty()) out.add(QuestLocations.biomes(selectors));
        }
        return out;
    }

    @Override
    public void facts(ServerLevel level, List<ItemSpec> picked, Map<String, String> out) {
        List<FishSelection.Candidate> fish = entries(level, picked);
        if (fish.isEmpty()) return;
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < fish.size(); i++) {
            FishSelection.Candidate entry = fish.get(i);
            int quantity = i < picked.size() ? picked.get(i).quantity() : 1;
            if (list.length() > 0) list.append(',');
            list.append(entry.entry()).append('*').append(quantity);
            String n = "fish_" + (i + 1);
            out.put(n, Vars.ITEM + entry.item());
            out.put(n + "_count", Integer.toString(quantity));
            out.put(n + "_rarity", Vars.LANG + "superiorstory.fish.rarity." + entry.rarity());
            for (String part : FishSelection.PARTS) {
                if (entry.parts().contains(part)) out.put(n + "_" + part, Vars.FISHINFO + part + ":" + entry.entry());
            }
            String modifier = entry.fluids().contains(new ResourceLocation("minecraft", "lava")) ? "starcatcher:survives_lava"
                : entry.fluids().contains(new ResourceLocation("minecraft", "empty")) ? "starcatcher:no_gravity" : null;
            List<ResourceLocation> gear = modifier == null ? List.of() : FishGear.items(modifier);
            if (!gear.isEmpty()) out.put(n + "_gear", Vars.ITEM + gear.get(0));
        }
        for (String key : new ArrayList<>(out.keySet())) {   // the unnumbered facts ({fish_where}, ...) are kind 1
            if (key.startsWith("fish_1_")) out.put("fish_" + key.substring("fish_1_".length()), out.get(key));
        }
        out.put("fish", Vars.FISHINFO + "list:" + list);   // every picked fish with its count
    }
}

package com.mugloved.superiorstory.compat.fishing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which items carry a Starcatcher catch modifier, read from Starcatcher's own {@code starcatcher:modifiers} item data map
 * (every pack's copy, merged in pack order). The guide names these as the gear a fish's fluid needs: {@code survives_lava}
 * for lava and {@code no_gravity} for open air.
 */
final class FishGear implements ResourceManagerReloadListener {
    private static final ResourceLocation FILE = new ResourceLocation("starcatcher", "data_maps/item/modifiers.json");
    private static volatile Map<String, List<ResourceLocation>> byModifier = Map.of();

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        Map<ResourceLocation, List<String>> items = new LinkedHashMap<>();
        for (Resource resource : manager.getResourceStack(FILE)) {
            try (Reader reader = resource.openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.has("replace") && root.get("replace").getAsBoolean()) items.clear();
                if (!root.has("values")) continue;
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("values").entrySet()) {
                    ResourceLocation item = ResourceLocation.tryParse(entry.getKey());
                    if (item == null) continue;
                    JsonElement value = entry.getValue();
                    if (value.isJsonObject() && value.getAsJsonObject().has("value")) value = value.getAsJsonObject().get("value");
                    List<String> types = new ArrayList<>();
                    if (value.isJsonArray()) {
                        for (JsonElement modifier : value.getAsJsonArray()) {
                            if (modifier.isJsonObject() && modifier.getAsJsonObject().has("type")) types.add(modifier.getAsJsonObject().get("type").getAsString());
                        }
                    }
                    items.put(item, types);
                }
            } catch (Exception ignored) {
                // a malformed pack file is Starcatcher's to report
            }
        }
        Map<String, List<ResourceLocation>> out = new LinkedHashMap<>();
        items.forEach((item, types) -> types.forEach(type -> out.computeIfAbsent(type, key -> new ArrayList<>()).add(item)));
        out.values().forEach(list -> list.sort((a, b) -> Boolean.compare(!a.getNamespace().equals("starcatcher"), !b.getNamespace().equals("starcatcher"))));
        byModifier = out;
    }

    /** Up to two items whose modifiers include {@code modifier} (Starcatcher's own items first). */
    static List<ResourceLocation> items(String modifier) {
        List<ResourceLocation> items = byModifier.getOrDefault(modifier, List.of());
        return items.size() > 2 ? items.subList(0, 2) : items;
    }
}

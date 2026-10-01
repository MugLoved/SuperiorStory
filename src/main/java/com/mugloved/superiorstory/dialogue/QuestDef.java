package com.mugloved.superiorstory.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One mini quest: an optional location, an entity, and items. The file path is the quest ID. Every field has a default:
 * with no {@code location} the quest is a fetch quest (no search, no marker, no visit); {@code entity} is the location's
 * structure profile's first boss when the location is one exact structure; {@code chance} is 1; a quest is done once per
 * player unless {@code repeatable}.
 *
 * @param location      where the quest sends the player (a {@link StoryHooks.Location} module); null for a fetch quest
 * @param entity        null means the structure profile's first boss
 * @param item          the item source; its picks are stored per quest instance; null for a kill-only or visit quest
 * @param cooldownMillis real-time wait before a repeatable quest is offered again; 0 for none
 * @param guard         when the quest can be offered ({@code if}/{@code unless})
 * @param reward        actions run when the quest is turned in
 * @param extra         further objectives beside the main one ({@code also}); every one must be done before the turn-in
 */
public record QuestDef(ResourceLocation id, @Nullable StoryHooks.Location location, @Nullable ResourceLocation entity,
                       @Nullable StoryHooks.ItemSource item, float chance, boolean repeatable, long cooldownMillis, Guard guard,
                       List<Act> reward, List<Objective> extra) {
    private static final Set<String> FIELDS = Set.of("location", "entity", "item", "chance", "repeatable", "cooldown", "if", "unless", "reward", "also", "mods");
    private static final Set<String> OBJECTIVE_FIELDS = Set.of("location", "entity", "deliver");
    private static final Set<String> NPC_FIELDS = Set.of("type", "tag", "name");

    /**
     * An extra objective: kill {@code entity} at the location, or (with {@code npc}) reach the location, where the NPC is
     * spawned beside the player and glows gold until it has been talked to.
     */
    public record Objective(StoryHooks.Location location, @Nullable ResourceLocation entity, @Nullable Npc npc) {
        public boolean deliver() {
            return npc != null;
        }
    }

    /** The NPC of a deliver objective; a scene binds to it with {@code "npc": "<tag>"}. */
    public record Npc(ResourceLocation type, String tag, String name) {}

    /** The plain item form: one exact item (with quantity and consume) that every instance asks for. */
    public record FixedItems(List<ItemSpec> specs) implements StoryHooks.ItemSource {
        public FixedItems {
            specs = List.copyOf(specs);
        }

        @Override
        public List<ItemSpec> pick(StoryContext context) {
            return specs;
        }

        @Override
        public List<ItemSpec> fixed() {
            return specs;
        }
    }

    public QuestDef {
        reward = List.copyOf(reward);
        extra = List.copyOf(extra);
    }

    /** The one fixed item every instance asks for (the item a boss drops), or null for no item or a picking source. */
    @Nullable
    public ItemSpec fixedItem() {
        List<ItemSpec> fixed = item == null ? null : item.fixed();
        return fixed == null || fixed.isEmpty() ? null : fixed.get(0);
    }

    public static QuestDef parse(ResourceLocation id, JsonElement element) {
        if (!element.isJsonObject()) throw new IllegalArgumentException("Quest must be an object");
        JsonObject root = element.getAsJsonObject();
        for (String key : root.keySet()) {
            if (key.equals("structure")) throw new IllegalArgumentException("structure was replaced by location: {\"structure\": ...}");
            if (!FIELDS.contains(key)) throw new IllegalArgumentException("Unknown quest field: " + key);
        }
        if (root.has("mods") && !root.get("mods").isJsonArray()) throw new IllegalArgumentException("mods must be an array of mod IDs");
        StoryHooks.Location location = root.has("location") ? location(root.get("location")) : null;
        ResourceLocation entity = null;
        if (root.has("entity")) {
            entity = ResourceLocation.tryParse(string(root, "entity"));
            if (entity == null) throw new IllegalArgumentException("entity is not a valid entity ID");
        }
        StoryHooks.ItemSource item = root.has("item") ? item(root.get("item")) : null;
        if (entity == null && item != null && location != null && isStructural(location) && location.exactStructure() == null) {
            throw new IllegalArgumentException("entity is required when the location is a structure pool, tag, or namespace selector and the quest has an item");
        }
        float chance = 1f;
        if (root.has("chance")) {
            if (!root.get("chance").isJsonPrimitive() || !root.get("chance").getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("chance must be a number");
            }
            chance = root.get("chance").getAsFloat();
            if (!(chance > 0f && chance <= 1f)) throw new IllegalArgumentException("chance must be above 0 and at most 1");
        }
        boolean repeatable = false;
        if (root.has("repeatable")) {
            if (!root.get("repeatable").isJsonPrimitive() || !root.get("repeatable").getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("repeatable must be a boolean");
            }
            repeatable = root.get("repeatable").getAsBoolean();
        }
        long cooldown = 0;
        if (root.has("cooldown")) {
            if (!repeatable) throw new IllegalArgumentException("cooldown requires repeatable");
            cooldown = Duration.parse(string(root, "cooldown"));
        }
        List<Act> reward = new ArrayList<>();
        if (root.has("reward")) {
            if (!root.get("reward").isJsonObject()) throw new IllegalArgumentException("reward must be an object of action keys");
            reward.addAll(Act.parseAll(root.getAsJsonObject("reward").entrySet(), Set.of(), Set.of(), "reward"));
        }
        List<Objective> extra = new ArrayList<>();
        if (root.has("also")) {
            if (!root.get("also").isJsonArray()) throw new IllegalArgumentException("also must be an array of objectives");
            for (JsonElement entry : root.getAsJsonArray("also")) extra.add(objective(entry));
        }
        return new QuestDef(id, location, entity, item, chance, repeatable, cooldown, Guard.parse(root, "quest"), reward, extra);
    }

    private static boolean isStructural(StoryHooks.Location location) {
        return location.kind().equals("structure") || location.kind().equals("pool");
    }

    /** {@code {"<kind>": value}}: exactly one registered location kind. */
    public static StoryHooks.Location location(JsonElement element) {
        if (!element.isJsonObject() || element.getAsJsonObject().size() != 1) {
            throw new IllegalArgumentException("location is an object with one kind: " + StoryHooks.locationKeys());
        }
        var entry = element.getAsJsonObject().entrySet().iterator().next();
        var compiler = StoryHooks.location(entry.getKey());
        if (compiler == null) throw new IllegalArgumentException("Unknown location kind: " + entry.getKey());
        return compiler.apply(entry.getValue());
    }

    /** An item spec (one exact item), or {@code {"<source>": value}} naming a registered item source. */
    private static StoryHooks.ItemSource item(JsonElement element) {
        if (element.isJsonObject() && element.getAsJsonObject().size() == 1) {
            var entry = element.getAsJsonObject().entrySet().iterator().next();
            var compiler = StoryHooks.itemSource(entry.getKey());
            if (compiler != null) return compiler.apply(entry.getValue());
        }
        ItemSpec spec = ItemSpec.parse(element);
        if (spec.item().kind() != IdSelector.Kind.EXACT) throw new IllegalArgumentException("A quest item must be one exact item ID");
        return new FixedItems(List.of(spec));
    }

    private static Objective objective(JsonElement element) {
        if (!element.isJsonObject()) throw new IllegalArgumentException("also entries must be objects");
        JsonObject object = element.getAsJsonObject();
        for (String key : object.keySet()) {
            if (key.equals("structure")) throw new IllegalArgumentException("structure was replaced by location: {\"structure\": ...}");
            if (!OBJECTIVE_FIELDS.contains(key)) throw new IllegalArgumentException("Unknown objective field: " + key);
        }
        if (!object.has("location")) throw new IllegalArgumentException("an objective needs a location");
        StoryHooks.Location location = location(object.get("location"));
        if (object.has("deliver")) {
            if (object.has("entity")) throw new IllegalArgumentException("a deliver objective has no entity");
            if (!object.get("deliver").isJsonObject()) throw new IllegalArgumentException("deliver must be an object");
            JsonObject npc = object.getAsJsonObject("deliver");
            for (String key : npc.keySet()) if (!NPC_FIELDS.contains(key)) throw new IllegalArgumentException("Unknown deliver field: " + key);
            ResourceLocation type = ResourceLocation.tryParse(npc.has("type") ? string(npc, "type") : "minecraft:villager");
            if (type == null) throw new IllegalArgumentException("deliver.type is not a valid entity ID");
            String tag = string(npc, "tag").trim();
            if (tag.isEmpty()) throw new IllegalArgumentException("deliver.tag must not be blank");
            return new Objective(location, null, new Npc(type, tag, npc.has("name") ? string(npc, "name") : ""));
        }
        ResourceLocation entity = null;
        if (object.has("entity")) {
            entity = ResourceLocation.tryParse(string(object, "entity"));
            if (entity == null) throw new IllegalArgumentException("entity is not a valid entity ID");
        } else if (location.exactStructure() == null) {
            throw new IllegalArgumentException("entity is required when the location is not one exact structure");
        }
        return new Objective(location, entity, null);
    }

    /** Location values: a selector string or an array of them. */
    public static List<IdSelector> selectors(JsonElement element, String key) {
        List<IdSelector> out = new ArrayList<>();
        if (element.isJsonArray()) for (JsonElement entry : element.getAsJsonArray()) out.add(IdSelector.parse(stringValue(entry, key)));
        else out.add(IdSelector.parse(stringValue(element, key)));
        if (out.isEmpty()) throw new IllegalArgumentException(key + " needs at least one entry");
        return out;
    }

    /** An ID or an array of IDs. */
    public static List<ResourceLocation> ids(JsonElement element, String key) {
        List<ResourceLocation> out = new ArrayList<>();
        if (element.isJsonArray()) for (JsonElement entry : element.getAsJsonArray()) out.add(poolId(entry));
        else out.add(poolId(element));
        if (out.isEmpty()) throw new IllegalArgumentException(key + " needs at least one ID");
        return out;
    }

    private static String stringValue(JsonElement element, String key) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be a string");
        return element.getAsString();
    }

    private static ResourceLocation poolId(JsonElement element) {
        ResourceLocation id = element.isJsonPrimitive() ? ResourceLocation.tryParse(element.getAsString()) : null;
        if (id == null) throw new IllegalArgumentException("Invalid ID");
        return id;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }
}

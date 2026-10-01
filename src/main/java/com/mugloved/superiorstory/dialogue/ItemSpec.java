package com.mugloved.superiorstory.dialogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * An item requirement used by every item field: {@code "ns:item"} or {@code "#ns:tag"}, or
 * {@code {"item": ..., "quantity": 1, "consume": true}}. {@code quantity} defaults to 1; {@code consume} defaults to
 * true and only matters where items leave the player.
 */
public record ItemSpec(IdSelector item, int quantity, boolean consume) {
    public static final int MAX_QUANTITY = 6400;

    public ItemSpec {
        if (quantity < 1 || quantity > MAX_QUANTITY) throw new IllegalArgumentException("quantity must be 1-" + MAX_QUANTITY);
    }

    public static ItemSpec of(String id, int quantity) {
        return new ItemSpec(IdSelector.parse(id), quantity, true);
    }

    public static ItemSpec parse(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return new ItemSpec(IdSelector.parse(element.getAsString()), 1, true);
        }
        if (!element.isJsonObject()) throw new IllegalArgumentException("An item is \"ns:item\", \"#ns:tag\", or an object with item");
        JsonObject object = element.getAsJsonObject();
        for (String key : object.keySet()) {
            if (!Set.of("item", "quantity", "consume").contains(key)) throw new IllegalArgumentException("Unknown item field: " + key);
        }
        if (!object.has("item") || !object.get("item").isJsonPrimitive()) throw new IllegalArgumentException("item must be a string");
        int quantity = 1;
        if (object.has("quantity")) {
            if (!object.get("quantity").isJsonPrimitive() || !object.get("quantity").getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("quantity must be a number");
            }
            quantity = object.get("quantity").getAsInt();
        }
        boolean consume = true;
        if (object.has("consume")) {
            if (!object.get("consume").isJsonPrimitive() || !object.get("consume").getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("consume must be a boolean");
            }
            consume = object.get("consume").getAsBoolean();
        }
        return new ItemSpec(IdSelector.parse(object.get("item").getAsString()), quantity, consume);
    }

    /** One spec or an array of specs; all are required. */
    public static List<ItemSpec> parseAll(JsonElement element) {
        List<ItemSpec> specs = new ArrayList<>();
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.isEmpty()) throw new IllegalArgumentException("An item list needs at least one entry");
            for (JsonElement entry : array) specs.add(parse(entry));
        } else {
            specs.add(parse(element));
        }
        return specs;
    }

    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return switch (item.kind()) {
            case EXACT -> BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(item.id());
            case TAG -> stack.is(TagKey.create(Registries.ITEM, item.id()));
            case NAMESPACE -> BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals(item.id().getNamespace());
        };
    }

    public int count(ServerPlayer player) {
        int total = 0;
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) if (matches(stack)) total += stack.getCount();
        for (ItemStack stack : inventory.offhand) if (matches(stack)) total += stack.getCount();
        return total;
    }

    public boolean has(ServerPlayer player) {
        return count(player) >= quantity;
    }

    /** Removes {@link #quantity()} matching items; call only after {@link #has}. */
    public void take(ServerPlayer player) {
        int left = quantity;
        Inventory inventory = player.getInventory();
        for (var stacks : List.of(inventory.items, inventory.offhand)) {
            for (ItemStack stack : stacks) {
                if (left <= 0) return;
                if (!matches(stack)) continue;
                int removed = Math.min(left, stack.getCount());
                stack.shrink(removed);
                left -= removed;
            }
        }
        player.getInventory().setChanged();
    }

    /** The item ID to show for this spec: the first matching stack the player carries, else the exact ID or a tag member. */
    public ResourceLocation displayId(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) if (matches(stack)) return BuiltInRegistries.ITEM.getKey(stack.getItem());
        for (ItemStack stack : inventory.offhand) if (matches(stack)) return BuiltInRegistries.ITEM.getKey(stack.getItem());
        return item.id();
    }

    /** A stack of the exact item, or an empty stack when this spec is not an exact ID or the item does not exist. */
    public ItemStack stack() {
        if (item.kind() != IdSelector.Kind.EXACT) return ItemStack.EMPTY;
        Item found = BuiltInRegistries.ITEM.getOptional(item.id()).orElse(null);
        return found == null ? ItemStack.EMPTY : new ItemStack(found, quantity);
    }
}

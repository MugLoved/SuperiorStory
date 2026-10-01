package com.mugloved.superiorstory.dialogue;

import net.minecraft.resources.ResourceLocation;

import java.util.function.Predicate;

/**
 * Picks registry entries by ID: {@code ns:path} exactly, {@code ns:*} for a whole mod, or {@code #ns:tag} for a tag.
 * Works for any registry (structures, entity types, items, biomes, dimensions). More specific selectors win when
 * several match.
 */
public record IdSelector(String raw, ResourceLocation id, Kind kind) {
    public enum Kind { NAMESPACE, TAG, EXACT }

    public static IdSelector parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        try {
            if (value.startsWith("#")) return new IdSelector(value, new ResourceLocation(value.substring(1)), Kind.TAG);
            if (value.endsWith(":*")) return new IdSelector(value, new ResourceLocation(value.substring(0, value.length() - 2), "any"), Kind.NAMESPACE);
            return new IdSelector(value, new ResourceLocation(value), Kind.EXACT);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid selector: " + raw);
        }
    }

    /** 3 exact, 2 tag, 1 namespace; 0 when it does not match. */
    public int match(ResourceLocation entry, Predicate<ResourceLocation> inTag) {
        return switch (kind) {
            case EXACT -> id.equals(entry) ? 3 : 0;
            case TAG -> inTag.test(id) ? 2 : 0;
            case NAMESPACE -> id.getNamespace().equals(entry.getNamespace()) ? 1 : 0;
        };
    }
}

package com.mugloved.superiorstory.dialogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The value of {@code unlock}, {@code lock}, and {@code unlocked}: a string (a free-form key), an object with any of
 * {@code category} (the Shop's automatic key {@code category:<id>}), {@code entry} ({@code entry:<id>}), {@code key},
 * {@code keys}, and {@code notify} (default true), or an array of those. Parsed once at load. Superior Lib stores the keys;
 * consumers (Shop gates, Lib recipe locks) decide what a key opens.
 *
 * @param keys   the unlock keys, in order
 * @param tell   whether unlocking tells the player
 */
public record UnlockSpec(List<String> keys, boolean tell) {
    private static final Set<String> FIELDS = Set.of("category", "entry", "key", "keys", "notify");

    public UnlockSpec {
        keys = List.copyOf(keys);
    }

    public static UnlockSpec parse(JsonElement value, String kind) {
        Set<String> keys = new LinkedHashSet<>();
        boolean[] notify = {true};
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) add(element, kind, keys, notify);
        } else {
            add(value, kind, keys, notify);
        }
        if (keys.isEmpty()) throw new IllegalArgumentException(kind + " needs at least one key");
        return new UnlockSpec(new ArrayList<>(keys), notify[0]);
    }

    private static void add(JsonElement element, String kind, Set<String> keys, boolean[] notify) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            keys.add(text(element, kind));
            return;
        }
        if (!element.isJsonObject()) throw new IllegalArgumentException(kind + " is a key, an object, or an array of them");
        JsonObject object = element.getAsJsonObject();
        for (String field : object.keySet()) if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown " + kind + " field: " + field);
        if (object.has("category")) keys.add("category:" + text(object.get("category"), kind + " category"));
        if (object.has("entry")) keys.add("entry:" + text(object.get("entry"), kind + " entry"));
        if (object.has("key")) keys.add(text(object.get("key"), kind + " key"));
        if (object.has("keys")) {
            if (!object.get("keys").isJsonArray()) throw new IllegalArgumentException(kind + " keys must be an array of strings");
            JsonArray array = object.getAsJsonArray("keys");
            for (JsonElement key : array) keys.add(text(key, kind + " keys"));
        }
        if (object.has("notify")) {
            JsonElement flag = object.get("notify");
            if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(kind + " notify must be true or false");
            if (!flag.getAsBoolean()) notify[0] = false;
        }
        if (!object.has("category") && !object.has("entry") && !object.has("key") && !object.has("keys")) {
            throw new IllegalArgumentException(kind + " object needs category, entry, key, or keys");
        }
    }

    private static String text(JsonElement element, String where) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString() || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(where + " must be a non-blank string");
        }
        return element.getAsString().trim();
    }
}

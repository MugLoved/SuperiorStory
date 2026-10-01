package com.mugloved.superiorstory.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/** One validated scene definition; the resource filename supplies its ID. */
public record StoryScene(ResourceLocation id, boolean firstJoin, End end, List<Beat> beats) {
    private static final int MAX_BEATS = 32;
    public static final int MAX_TEXT = 512;

    public enum End { REVEAL, SKILL_TREE }

    public record Text(String value, boolean translate, Map<String, List<Text>> words) {
        private static final java.util.regex.Pattern WORD = java.util.regex.Pattern.compile("\\{~([^{}]*)}");
        private static final int MAX_WORDS = MAX_TEXT / 4;

        public Text(String value, boolean translate) {
            this(value, translate, Map.of());
            if (value.isBlank()) throw new IllegalArgumentException("Authored text must not be blank");
        }

        public Text {
            if (value == null || (!value.isEmpty() && value.isBlank()) || value.length() > MAX_TEXT) {
                throw new IllegalArgumentException("Scene text must contain 1–" + MAX_TEXT + " characters");
            }
            Map<String, List<Text>> copy = new LinkedHashMap<>();
            int count = 0;
            for (var entry : words.entrySet()) {
                count += entry.getValue().size();
                if (entry.getKey().isBlank() || entry.getKey().length() > MAX_TEXT || entry.getValue().isEmpty() || count > MAX_WORDS) throw new IllegalArgumentException("Invalid resolved word entries");
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            words = Map.copyOf(copy);
        }

        public static Text literal(String value) { return new Text(value, false); }
        public static Text translation(String key) { return new Text(key, true); }
        /** No eligible word contributes no text; authored empty strings remain invalid. */
        public static Text empty() { return new Text("", false, Map.of()); }
        public Component component() {
            Component base = translate ? Component.translatable(value) : Component.literal(value);
            if (words.isEmpty()) return base;
            var matcher = WORD.matcher(base.getString());
            Map<String, Integer> occurrences = new java.util.HashMap<>();
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                List<Text> picks = words.get(matcher.group(1));
                int index = occurrences.merge(matcher.group(1), 1, Integer::sum) - 1;
                String replacement = picks == null || index >= picks.size() ? "" : picks.get(index).component().getString();
                matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            return Component.literal(out.toString());
        }

        public void write(FriendlyByteBuf buf) {
            write(buf, 0, new int[]{MAX_TEXT * 4});
        }

        private void write(FriendlyByteBuf buf, int depth, int[] budget) {
            if (depth > 4 || --budget[0] < 0) throw new IllegalArgumentException("Resolved words exceed text payload limits");
            buf.writeBoolean(translate);
            buf.writeUtf(value, MAX_TEXT);
            buf.writeVarInt(words.size());
            for (var entry : words.entrySet()) {
                buf.writeUtf(entry.getKey(), MAX_TEXT);
                buf.writeVarInt(entry.getValue().size());
                for (Text text : entry.getValue()) text.write(buf, depth + 1, budget);
            }
        }

        public static Text read(FriendlyByteBuf buf) {
            return read(buf, 0, new int[]{MAX_TEXT * 4});
        }

        private static Text read(FriendlyByteBuf buf, int depth, int[] budget) {
            if (depth > 4 || --budget[0] < 0) throw new IllegalArgumentException("Resolved words exceed text payload limits");
            boolean translate = buf.readBoolean();
            String value = buf.readUtf(MAX_TEXT);
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_WORDS) throw new IllegalArgumentException("Invalid resolved word count");
            Map<String, List<Text>> words = new LinkedHashMap<>();
            int total = 0;
            for (int i = 0; i < count; i++) {
                String key = buf.readUtf(MAX_TEXT);
                int size = buf.readVarInt();
                total += size;
                if (size < 1 || size > MAX_WORDS || total > MAX_WORDS || words.containsKey(key)) throw new IllegalArgumentException("Invalid resolved word entries");
                List<Text> picks = new ArrayList<>();
                for (int j = 0; j < size; j++) picks.add(read(buf, depth + 1, budget));
                words.put(key, picks);
            }
            return new Text(value, translate, words);
        }
    }

    public record Beat(Text text, List<Text> choices, float pace, boolean accent) {
        public Beat {
            if (text == null || choices == null || (choices.size() != 0 && choices.size() != 2)
                || !Float.isFinite(pace) || pace < 0.1f || pace > 5f) {
                throw new IllegalArgumentException("Beat requires text, zero or two choices, and pace 0.1–5");
            }
            choices = List.copyOf(choices);
        }
    }

    public StoryScene {
        if (id == null || end == null || beats == null || beats.isEmpty() || beats.size() > MAX_BEATS) {
            throw new IllegalArgumentException("Scene requires an ID, end action, and 1–" + MAX_BEATS + " beats");
        }
        beats = List.copyOf(beats);
        if (end == End.SKILL_TREE && !beats.get(beats.size() - 1).choices().isEmpty()) {
            throw new IllegalArgumentException("A skill_tree ending cannot follow choices on the final beat");
        }
    }

    public static StoryScene parse(ResourceLocation id, JsonElement element) {
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("Scene root must be an object");
        JsonObject root = element.getAsJsonObject();
        rejectUnknown(root, Set.of("trigger", "end", "beats"));
        String trigger = string(root, "trigger", "manual");
        if (!trigger.equals("manual") && !trigger.equals("first_join")) {
            throw new IllegalArgumentException("trigger must be manual or first_join");
        }
        boolean firstJoin = trigger.equals("first_join");
        End end = switch (string(root, "end", "reveal")) {
            case "reveal" -> End.REVEAL;
            case "skill_tree" -> End.SKILL_TREE;
            default -> throw new IllegalArgumentException("end must be reveal or skill_tree");
        };
        if (!root.has("beats") || !root.get("beats").isJsonArray()) {
            throw new IllegalArgumentException("beats must be an array");
        }
        JsonArray authoredBeats = root.getAsJsonArray("beats");
        if (authoredBeats.isEmpty() || authoredBeats.size() > MAX_BEATS) {
            throw new IllegalArgumentException("beats must contain 1–" + MAX_BEATS + " entries");
        }
        List<Beat> beats = new ArrayList<>(authoredBeats.size());
        for (int i = 0; i < authoredBeats.size(); i++) {
            JsonElement authored = authoredBeats.get(i);
            if (authored.isJsonPrimitive() && authored.getAsJsonPrimitive().isString()) {
                beats.add(new Beat(readText(authored), List.of(), 1f, false));
                continue;
            }
            if (!authored.isJsonObject()) throw new IllegalArgumentException("beats[" + i + "] must be text or an object");
            JsonObject object = authored.getAsJsonObject();
            if (object.has("translate")) {
                beats.add(new Beat(readText(authored), List.of(), 1f, false));
                continue;
            }
            rejectUnknown(object, Set.of("text", "choices", "pace", "accent"));
            if (!object.has("text")) throw new IllegalArgumentException("beats[" + i + "] needs text");
            List<Text> choices = List.of();
            if (object.has("choices")) {
                if (!object.get("choices").isJsonArray() || object.getAsJsonArray("choices").size() != 2) {
                    throw new IllegalArgumentException("beats[" + i + "].choices needs two labels");
                }
                choices = List.of(readText(object.getAsJsonArray("choices").get(0)),
                    readText(object.getAsJsonArray("choices").get(1)));
            }
            beats.add(new Beat(readText(object.get("text")), choices, number(object, "pace", 1f),
                bool(object, "accent", false)));
        }
        return new StoryScene(id, firstJoin, end, beats);
    }

    /** Only presentation data crosses the wire; trigger and completion remain server owned. */
    public void writeClient(FriendlyByteBuf buf) {
        buf.writeVarInt(end.ordinal());
        buf.writeVarInt(beats.size());
        for (Beat beat : beats) {
            beat.text().write(buf);
            buf.writeVarInt(beat.choices().size());
            for (Text choice : beat.choices()) choice.write(buf);
            buf.writeFloat(beat.pace());
            buf.writeBoolean(beat.accent());
        }
    }

    public static StoryScene readClient(ResourceLocation id, FriendlyByteBuf buf) {
        int endIndex = buf.readVarInt();
        if (endIndex < 0 || endIndex >= End.values().length) throw new IllegalArgumentException("Invalid scene ending");
        int count = buf.readVarInt();
        if (count < 1 || count > MAX_BEATS) throw new IllegalArgumentException("Invalid scene beat count");
        List<Beat> beats = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Text text = Text.read(buf);
            int choices = buf.readVarInt();
            if (choices != 0 && choices != 2) throw new IllegalArgumentException("Invalid scene choice count");
            List<Text> labels = new ArrayList<>(choices);
            for (int j = 0; j < choices; j++) labels.add(Text.read(buf));
            beats.add(new Beat(text, labels, buf.readFloat(), buf.readBoolean()));
        }
        return new StoryScene(id, false, End.values()[endIndex], beats);
    }

    private static Text readText(JsonElement element) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return Text.literal(element.getAsString());
        }
        if (element != null && element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            rejectUnknown(object, Set.of("translate"));
            return Text.translation(string(object, "translate", null));
        }
        throw new IllegalArgumentException("Text must be a string or {\"translate\":\"key\"}");
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static float number(JsonObject object, String key, float fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        return value.getAsFloat();
    }

    private static void rejectUnknown(JsonObject object, Set<String> allowed) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown scene field: " + key);
        }
    }
}

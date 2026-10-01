package com.mugloved.superiorstory.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.dialogue.LinePool;
import com.mugloved.superiorstory.dialogue.RewardPool;
import com.mugloved.superiorstory.dialogue.WordPool;
import com.mugloved.superiorstory.scene.StoryScene.Text;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** Installed with scenes so pool references never depend on reload-listener order. */
public final class LinePools {
    private static final Pattern WORD = Pattern.compile("\\{~([^{}]*)}");
    public static final int MAX_WORD_DEPTH = 4;
    private static volatile Catalogue catalogue = new Catalogue(Map.of(), Map.of());
    private static final Map<UUID, Map<ResourceLocation, Integer>> lastLines = new HashMap<>();
    private static final Map<UUID, Map<ResourceLocation, Integer>> lastWords = new HashMap<>();

    public record Catalogue(Map<ResourceLocation, LinePool> lines, Map<ResourceLocation, WordPool> words) {
        public Catalogue { lines = Map.copyOf(lines); words = Map.copyOf(words); }
    }

    private LinePools() {}

    public static void register() {
        StoryHooks.registerTextSource("pool", (value, namespace, where) -> {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(where + " must be a pool ID");
            ResourceLocation id = id(value.getAsString(), namespace, where);
            return new StoryHooks.TextSource() {
                @Override public void validate() {
                    if (get(id) == null) throw new IllegalArgumentException(where + ": unknown line pool " + id);
                }
                @Override public Text pick(StoryContext context, RandomSource random) { return LinePools.pick(id, context, random); }
            };
        });
        StoryHooks.registerTextTransform("word_pools", new StoryHooks.TextTransform() {
            @Override public Text expand(Text text, String namespace, StoryContext context, RandomSource random) { return LinePools.expand(text, namespace, context, random); }
            @Override public void validate(Text text, String namespace, String where) { validateWords(text, namespace, catalogue.words(), where); }
        });
    }

    public static Catalogue load(ResourceManager manager) {
        return compile(read(manager, "line_pools"), read(manager, "word_pools"),
            (file, error) -> SuperiorStory.LOGGER.error("Invalid story pool {}: {}", file, error));
    }

    private static Map<ResourceLocation, List<JsonElement>> read(ResourceManager manager, String kind) {
        String prefix = "superiorstory/" + kind + "/";
        Map<ResourceLocation, List<JsonElement>> out = new LinkedHashMap<>();
        manager.listResourceStacks("superiorstory/" + kind, file -> file.getPath().endsWith(".json")).forEach((file, stack) -> {
            ResourceLocation id = new ResourceLocation(file.getNamespace(), file.getPath().substring(prefix.length(), file.getPath().length() - 5));
            List<JsonElement> layers = new ArrayList<>();
            for (var resource : stack) {
                try (var reader = resource.openAsReader()) { layers.add(JsonParser.parseReader(reader)); }
                catch (Exception exception) {
                    SuperiorStory.LOGGER.error("Invalid story pool {} in pack {}: {}", file, resource.sourcePackId(), exception.getMessage());
                    layers.add(com.google.gson.JsonNull.INSTANCE);
                }
            }
            out.put(id, layers);
        });
        return out;
    }

    /** Low-to-high pack order. Reject invalid pools while keeping valid sibling IDs active. */
    public static Catalogue compile(Map<ResourceLocation, List<JsonElement>> lineFiles, Map<ResourceLocation, List<JsonElement>> wordFiles,
                                    BiConsumer<String, String> errors) {
        Map<ResourceLocation, LinePool> lines = new LinkedHashMap<>();
        Map<ResourceLocation, WordPool> words = new LinkedHashMap<>();
        wordFiles.forEach((id, layers) -> {
            try {
                WordPool pool = null;
                for (JsonElement layer : layers) pool = merge(pool, WordPool.parse(layer));
                if (pool != null) words.put(id, pool);
            } catch (RuntimeException exception) { errors.accept(file(id, "word_pools"), exception.getMessage()); }
        });
        Map<ResourceLocation, Integer> depths = new HashMap<>();
        Set<ResourceLocation> invalid = new LinkedHashSet<>();
        for (ResourceLocation id : words.keySet()) {
            try { depth(id, words, depths, new LinkedHashSet<>()); }
            catch (RuntimeException exception) { invalid.add(id); errors.accept(file(id, "word_pools"), exception.getMessage()); }
        }
        invalid.forEach(words::remove);
        invalid.clear();
        for (ResourceLocation id : words.keySet()) {
            try {
                for (int i = 0; i < words.get(id).entries().size(); i++) validateWords(words.get(id).entries().get(i).text(), id.getNamespace(), words, "words[" + i + "].text");
            } catch (RuntimeException exception) { invalid.add(id); errors.accept(file(id, "word_pools"), exception.getMessage()); }
        }
        invalid.forEach(words::remove);
        lineFiles.forEach((id, layers) -> {
            try {
                LinePool pool = null;
                for (JsonElement layer : layers) pool = merge(pool, LinePool.parse(layer));
                if (pool != null) {
                    for (int i = 0; i < pool.entries().size(); i++) validateWords(pool.entries().get(i).text(), id.getNamespace(), words, "lines[" + i + "].text");
                    lines.put(id, pool);
                }
            } catch (RuntimeException exception) { errors.accept(file(id, "line_pools"), exception.getMessage()); }
        });
        return new Catalogue(lines, words);
    }

    private static String file(ResourceLocation id, String kind) { return "data/" + id.getNamespace() + "/superiorstory/" + kind + "/" + id.getPath() + ".json"; }

    private static List<LinePool.Entry> append(List<LinePool.Entry> lower, List<LinePool.Entry> upper) {
        List<LinePool.Entry> entries = new ArrayList<>(lower);
        entries.addAll(upper);
        if (entries.size() > RewardPool.MAX_ENTRIES) throw new IllegalArgumentException("Merged pool exceeds " + RewardPool.MAX_ENTRIES + " entries");
        return entries;
    }

    public static LinePool merge(@Nullable LinePool lower, LinePool upper) {
        return lower == null || upper.replace() ? upper : new LinePool(append(lower.entries(), upper.entries()), upper.first(), false);
    }

    public static WordPool merge(@Nullable WordPool lower, WordPool upper) {
        return lower == null || upper.replace() ? upper : new WordPool(append(lower.entries(), upper.entries()), false);
    }

    private static int depth(ResourceLocation id, Map<ResourceLocation, WordPool> words, Map<ResourceLocation, Integer> depths, Set<ResourceLocation> path) {
        if (depths.containsKey(id)) return depths.get(id);
        if (!path.add(id)) throw new IllegalArgumentException("words.text: nested-word cycle " + path + " -> " + id);
        if (path.size() > MAX_WORD_DEPTH) throw new IllegalArgumentException("words.text: nesting exceeds " + MAX_WORD_DEPTH + " pools");
        WordPool pool = words.get(id);
        if (pool == null) throw new IllegalArgumentException("words.text: unknown word pool " + id);
        int depth = 1;
        for (int i = 0; i < pool.entries().size(); i++) {
            for (ResourceLocation child : references(pool.entries().get(i).text(), id.getNamespace(), "words[" + i + "].text")) depth = Math.max(depth, 1 + depth(child, words, depths, path));
        }
        path.remove(id);
        if (depth > MAX_WORD_DEPTH) throw new IllegalArgumentException("words.text: nesting exceeds " + MAX_WORD_DEPTH + " pools");
        depths.put(id, depth);
        return depth;
    }

    private static List<ResourceLocation> references(Text text, String namespace, String where) {
        List<ResourceLocation> refs = new ArrayList<>();
        var matcher = WORD.matcher(text.component().getString());
        while (matcher.find()) refs.add(id(matcher.group(1), namespace, where));
        return refs;
    }

    private static void validateWords(Text text, String namespace, Map<ResourceLocation, WordPool> words, String where) {
        for (ResourceLocation id : references(text, namespace, where)) if (!words.containsKey(id)) throw new IllegalArgumentException(where + ": unknown word pool " + id);
        if (expandedLength(text.component().getString(), namespace, words, new HashMap<>(), where) > com.mugloved.superiorstory.scene.StoryScene.MAX_TEXT) {
            throw new IllegalArgumentException(where + ": expanded text exceeds " + com.mugloved.superiorstory.scene.StoryScene.MAX_TEXT + " characters");
        }
    }

    private static long expandedLength(String text, String namespace, Map<ResourceLocation, WordPool> words, Map<ResourceLocation, Long> lengths, String where) {
        long length = text.length();
        var matcher = WORD.matcher(text);
        while (matcher.find()) {
            ResourceLocation id = id(matcher.group(1), namespace, where);
            Long maximum = lengths.get(id);
            if (maximum == null) {
                WordPool pool = words.get(id);
                if (pool == null) throw new IllegalArgumentException(where + ": unknown word pool " + id);
                maximum = 0L;
                for (var entry : pool.entries()) maximum = Math.max(maximum, Math.max(1, expandedLength(entry.text().component().getString(), id.getNamespace(), words, lengths, where)));
                lengths.put(id, maximum);
            }
            length += maximum - matcher.group().length();
            if (length > com.mugloved.superiorstory.scene.StoryScene.MAX_TEXT) return length;
        }
        return length;
    }

    private static ResourceLocation id(String value, String namespace, String where) {
        ResourceLocation id = ResourceLocation.tryParse(value.contains(":") ? value : namespace + ":" + value);
        if (id == null || value.isBlank()) throw new IllegalArgumentException(where + ": invalid pool ID " + value);
        return id;
    }

    public static void install(Catalogue next) { catalogue = next; clearHistory(); }
    public static void forget(UUID player) { lastLines.remove(player); lastWords.remove(player); }
    public static void clearHistory() { lastLines.clear(); lastWords.clear(); }
    @Nullable public static LinePool get(ResourceLocation id) { return catalogue.lines().get(id); }
    @Nullable public static WordPool word(ResourceLocation id) { return catalogue.words().get(id); }

    @Nullable
    public static Text pick(ResourceLocation id, StoryContext context, RandomSource random) {
        return pick(id, context.player().getUUID(), context, random);
    }

    @Nullable
    public static Text pick(ResourceLocation id, UUID player, StoryContext context, RandomSource random) {
        LinePool pool = get(id);
        if (pool == null) return null;
        int index = pick(id, pool.entries(), pool.first(), player, context, random, lastLines);
        return index < 0 ? null : expand(pool.entries().get(index).text(), id.getNamespace(), player, context, random);
    }

    private static int pick(ResourceLocation id, List<LinePool.Entry> entries, boolean first, UUID player, StoryContext context, RandomSource random,
                            Map<UUID, Map<ResourceLocation, Integer>> history) {
        Map<ResourceLocation, Integer> previous = history.computeIfAbsent(player, key -> new HashMap<>());
        int index = LinePool.pick(entries, first, context, random, previous.getOrDefault(id, -1));
        if (index >= 0) previous.put(id, index);
        return index;
    }

    public static Text expand(Text text, String namespace, StoryContext context, RandomSource random) {
        if (!text.words().isEmpty()) return text;
        String value = text.component().getString();
        if (!WORD.matcher(value).find()) return text;
        return expand(text, namespace, context.player().getUUID(), context, random);
    }

    public static Text expand(Text text, String namespace, UUID player, StoryContext context, RandomSource random) {
        if (!text.words().isEmpty()) return text;
        Text expanded = expand(text, namespace, player, context, random, 0);
        return expanded.component().getString().isBlank() ? Text.translation("superiorstory.pool.empty") : expanded;
    }

    private static Text expand(Text text, String namespace, UUID player, StoryContext context, RandomSource random, int depth) {
        var matcher = WORD.matcher(text.component().getString());
        Map<String, List<Text>> resolved = new LinkedHashMap<>();
        while (matcher.find()) {
            if (depth >= MAX_WORD_DEPTH) throw new IllegalStateException("Word pool nesting exceeds " + MAX_WORD_DEPTH);
            ResourceLocation id = id(matcher.group(1), namespace, "text");
            WordPool pool = word(id);
            Text value = Text.empty();
            if (pool != null) {
                int index = pick(id, pool.entries(), false, player, context, random, lastWords);
                if (index >= 0) value = expand(pool.entries().get(index).text(), id.getNamespace(), player, context, random, depth + 1);
            }
            resolved.computeIfAbsent(matcher.group(1), key -> new ArrayList<>()).add(value);
        }
        return resolved.isEmpty() ? text : new Text(text.value(), text.translate(), resolved);
    }
}

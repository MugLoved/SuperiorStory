package com.mugloved.superiorstory.api;

import com.google.gson.JsonElement;
import com.mugloved.superiorstory.dialogue.ItemSpec;
import com.mugloved.superiorstory.scene.StoryScene.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The one Story registry. Every piece of behavior is a module registered under a JSON key with a compiler that runs
 * once when datapacks load ({@code JsonElement} in, a compiled object out, {@link IllegalArgumentException} for bad
 * values). Register during mod construction so typos are rejected at load. Modules plug in wherever their kind is
 * accepted; nothing else needs editing.
 *
 * <ul>
 *   <li>{@link Condition}: inside {@code if}/{@code unless} on a dialogue, line, choice, quest, or trigger.</li>
 *   <li>{@link Action}: a key on a choice, a line (run when the player continues past it), a trigger, or a quest reward.</li>
 *   <li>{@link LineKind}: a key that turns a line into work the server does while the text plays ({@code locate}).</li>
 *   <li>{@link Speaker}: a dialogue root key that binds who or what is talking ({@code npc}, {@code block}).</li>
 *   <li>{@link Trigger}: a dialogue {@code trigger} that opens it without a speaker.</li>
 *   <li>{@link VarSource}: derived variables available to every line ({@code time}, {@code dimension}).</li>
 *   <li>{@link Location}: a quest {@code location} kind ({@code structure}, {@code pool}, {@code biome}).</li>
 *   <li>{@link ItemSource}: a quest {@code item} source that picks the items per quest instance ({@code fish}).</li>
 * </ul>
 */
public final class StoryHooks {
    /** Keys with a fixed meaning on a choice or line; modules may not reuse them. */
    private static final Set<String> RESERVED = Set.of("text", "translate", "goto", "outcome", "id", "if", "unless",
        "blocked", "accent", "pace", "choices", "needs");
    /** Fixed dialogue root keys. */
    private static final Set<String> ROOT_RESERVED = Set.of("structure", "trigger", "speaker", "priority", "if", "unless",
        "beats", "branches", "mods");

    private static final Map<String, Function<JsonElement, Condition>> CONDITIONS = new ConcurrentHashMap<>();
    private static final Map<String, Function<JsonElement, Action>> ACTIONS = new ConcurrentHashMap<>();
    private static final Map<String, LineKind> LINE_KINDS = new ConcurrentHashMap<>();
    private static final Map<String, Speaker> SPEAKERS = new ConcurrentHashMap<>();
    private static final Map<String, Trigger> TRIGGERS = new ConcurrentHashMap<>();
    private static final Map<String, VarSource> VAR_SOURCES = new ConcurrentHashMap<>();
    private static final Map<String, TextSourceCompiler> TEXT_SOURCES = new ConcurrentHashMap<>();
    private static final Map<String, TextTransform> TEXT_TRANSFORMS = new ConcurrentHashMap<>();
    private static final Map<String, Function<JsonElement, Location>> LOCATIONS = new ConcurrentHashMap<>();
    private static final Map<String, Function<JsonElement, ItemSource>> ITEM_SOURCES = new ConcurrentHashMap<>();
    private static volatile TriggerSink sink = (key, player, payload) -> { };

    private StoryHooks() {}

    // ---------------------------------------------------------------- module kinds

    /** A compiled condition. Runs on every selection, so keep it cheap. */
    public interface Condition {
        boolean test(StoryContext context);

        /** Lang key explaining a failure to the player when a quest is blocked by this condition; null means "Not now." */
        @Nullable
        default String reasonKey(StoryContext context) { return null; }
    }

    /** A compiled action. Throw {@link StoryBlocked} when it cannot go ahead for a reason the player should hear. */
    public interface Action {
        void run(StoryContext context);

        /** A choice carrying this action is shown only when this is true (for example the player holds the items). */
        default boolean visible(StoryContext context) { return true; }

        /** Items to show on the choice row (icon, quantity, hover). */
        default List<ItemSpec> shows(StoryContext context) { return List.of(); }

        /** As a quest reward, the conversation variable value describing it (for example {@code @item:ns:id}); null for none. */
        @Nullable
        default String rewardVar() { return null; }

        /** The quest this action belongs to (accept, hand over, turn in, deliver), so the scene can be filled with that quest's facts; null for none. */
        @Nullable
        default ResourceLocation quest() { return null; }

        /** Whether running this now would move one of the player's quests forward (a ready turn-in, a delivery waiting here); drives the "!" over the speaker. */
        default boolean progresses(StoryContext context) { return false; }
    }

    /** A line key that runs work on the server while the line's text plays. */
    public interface LineKind {
        Object compile(JsonElement value, String where);

        /** Branch names the compiled spec can jump to, for load-time reachability checks. */
        default List<String> branches(Object spec) { return List.of(); }

        /** The quest this compiled spec works on, so the scene can be filled with that quest's facts; null for none. */
        @Nullable
        default ResourceLocation quest(Object spec) { return null; }

        Job start(StoryContext context, Object spec);

        interface Job {
            /** Polled each server tick; true once finished (successfully or not). */
            boolean poll(StoryContext context);

            void cancel();

            /** Valid once {@link #poll} returned true. */
            Result result();

            /** True when the line asked for no map marker. */
            default boolean suppressesWaypoint() { return false; }
        }

        /**
         * @param success   false continues at {@code branch} or ends the conversation when there is none; true continues
         *                  at {@code branch} or the next line
         * @param vars      variables to add to the conversation
         * @param branch    where to go next, or null
         * @param structure when set the engine looks for a dedicated structure dialogue and continues there
         * @param dialogue  when set and the current dialogue has no branch named {@code branch}, the engine continues in this
         *                  dialogue's main branch (the speaker is unchanged); null for none
         */
        record Result(boolean success, Map<String, String> vars, @Nullable String branch, @Nullable ResourceLocation structure,
                      @Nullable ResourceLocation dialogue) {
            public Result {
                vars = Map.copyOf(vars);
            }

            public Result(boolean success, Map<String, String> vars, @Nullable String branch, @Nullable ResourceLocation structure) {
                this(success, vars, branch, structure, null);
            }
        }
    }

    /** Who or what can be talked to. */
    public interface Speaker {
        Matcher compile(JsonElement value, String where);

        /** A live speaker for the target, or null when this kind cannot speak for it. */
        @Nullable
        Bound bind(SpeakerTarget target);

        interface Matcher {
            boolean matches(SpeakerTarget target);
        }

        /** The live speaker for one conversation. */
        interface Bound {
            boolean alive();
            double distanceSqr(ServerPlayer player);
            /** Freeze the speaker for the conversation. Must be undone by {@link #release()} on every exit path. */
            void hold();
            void release();
            /** Called every server tick while the conversation runs. */
            void tick(ServerPlayer player);
            /** The speaker was legitimately changed by an action; do not treat that as losing it. */
            void refresh();
            UUID key();
            RandomSource random();
            /** Entity ID, or -1 for a block speaker. */
            int entityId();
            @Nullable BlockPos blockPos();
            @Nullable Entity entity();
        }
    }

    public record SpeakerTarget(ServerLevel level, @Nullable Entity entity, @Nullable BlockPos block) {}

    /** Opens dialogues by an event rather than by talking. */
    public interface Trigger {
        /** {@code value} is the whole trigger object, so a module may read sibling keys such as {@code stage}. */
        Object compile(JsonElement value, String where);

        boolean matches(Object spec, Object payload);
    }

    /** Derived variables, recomputed each line. */
    public interface VarSource {
        void vars(StoryContext context, Map<String, String> out);
    }

    /** An alternative to a line's fixed text, compiled once and resolved each time it plays. */
    public interface TextSource {
        @Nullable Text pick(StoryContext context, RandomSource random);
        default void validate() {}
    }

    public interface TextSourceCompiler {
        TextSource compile(JsonElement value, String namespace, String where);
    }

    /** Text expansion before presentation; ordinary variables remain for the client's localized fill. */
    public interface TextTransform {
        Text expand(Text text, String namespace, StoryContext context, RandomSource random);
        default void validate(Text text, String namespace, String where) {}
    }

    /**
     * A compiled quest location. A search finds one concrete place (an ID such as the structure or biome found, and a
     * position); every other question is asked about that found place.
     */
    public interface Location {
        /** The location kind's JSON key. */
        String kind();

        /** Whether this location can exist in the level's dimension, so a search that cannot succeed is not started. */
        boolean possibleIn(ServerLevel level);

        /** Starts a search near the player; {@code sink} receives the found place before the job reports success. */
        LineKind.Job search(ServerPlayer player, int radiusBlocks, boolean waypoint, java.util.function.BiConsumer<ResourceLocation, BlockPos> sink);

        /** Whether the player has arrived at the found place (the quest's visit stage). */
        boolean arrived(ServerPlayer player, ResourceLocation found, BlockPos position);

        /** Whether a kill at {@code at} counts for this location. */
        boolean credits(ServerLevel level, ResourceLocation found, BlockPos position, net.minecraft.world.phys.Vec3 at);

        /** The display name of the found place (plain text or a {@code Vars} prefixed value). */
        String displayName(ServerLevel level, ResourceLocation found);

        /** The one exact structure this location names, whose profile supplies the default quest entity; null for none. */
        @Nullable
        default ResourceLocation exactStructure() { return null; }
    }

    /**
     * A compiled quest item source. The items are picked once per quest instance (when it is offered) and stored in it; every
     * reader of the quest's items reads the instance's picks.
     */
    public interface ItemSource {
        /** Picks the items for a new instance: exact item specs with their quantities; empty when nothing is eligible. */
        List<ItemSpec> pick(StoryContext context);

        /** The fixed items of a source that never varies (the plain item form); null for a source that picks. */
        @Nullable
        default List<ItemSpec> fixed() { return null; }

        /** Lang key the blocked quest explains itself with when {@link #pick} found nothing. */
        default String blockedKey() { return "superiorstory.blocked.no_items"; }

        /** Derived map locations for the picked items (one per item that has one); an explicit quest location replaces them. */
        default List<Location> markers(ServerLevel level, List<ItemSpec> picked) { return List.of(); }

        /** Facts for the picked items, added to the scene variables. */
        default void facts(ServerLevel level, List<ItemSpec> picked, Map<String, String> out) {}
    }

    /** Receives fired triggers; set by the dialogue engine. */
    @FunctionalInterface
    public interface TriggerSink {
        void fire(String triggerKey, ServerPlayer player, Object payload);
    }

    // ---------------------------------------------------------------- registration

    public static void registerCondition(String key, Function<JsonElement, Condition> compiler) {
        if (RESERVED.contains(key) || CONDITIONS.putIfAbsent(key, compiler) != null) {
            throw new IllegalArgumentException("Story condition key unavailable: " + key);
        }
    }

    public static void registerAction(String key, Function<JsonElement, Action> compiler) {
        if (RESERVED.contains(key) || LINE_KINDS.containsKey(key) || TEXT_SOURCES.containsKey(key) || ACTIONS.putIfAbsent(key, compiler) != null) {
            throw new IllegalArgumentException("Story action key unavailable: " + key);
        }
    }

    public static void registerLineKind(String key, LineKind kind) {
        if (RESERVED.contains(key) || ACTIONS.containsKey(key) || TEXT_SOURCES.containsKey(key) || LINE_KINDS.putIfAbsent(key, kind) != null) {
            throw new IllegalArgumentException("Story line kind key unavailable: " + key);
        }
    }

    public static void registerSpeaker(String key, Speaker speaker) {
        if (ROOT_RESERVED.contains(key) || SPEAKERS.putIfAbsent(key, speaker) != null) {
            throw new IllegalArgumentException("Story speaker key unavailable: " + key);
        }
    }

    public static void registerTrigger(String key, Trigger trigger) {
        if (TRIGGERS.putIfAbsent(key, trigger) != null) {
            throw new IllegalArgumentException("Story trigger key already registered: " + key);
        }
    }

    public static void registerVarSource(String id, VarSource source) {
        if (VAR_SOURCES.putIfAbsent(id, source) != null) {
            throw new IllegalArgumentException("Story variable source already registered: " + id);
        }
    }

    public static void registerTextSource(String key, TextSourceCompiler compiler) {
        if (RESERVED.contains(key) || ACTIONS.containsKey(key) || LINE_KINDS.containsKey(key) || TEXT_SOURCES.putIfAbsent(key, compiler) != null) {
            throw new IllegalArgumentException("Story text source key unavailable: " + key);
        }
    }

    public static void registerLocation(String key, Function<JsonElement, Location> compiler) {
        if (LOCATIONS.putIfAbsent(key, compiler) != null) throw new IllegalArgumentException("Story location key already registered: " + key);
    }

    public static void registerItemSource(String key, Function<JsonElement, ItemSource> compiler) {
        if (Set.of("item", "quantity", "consume").contains(key) || ITEM_SOURCES.putIfAbsent(key, compiler) != null) {
            throw new IllegalArgumentException("Story item source key unavailable: " + key);
        }
    }

    public static void registerTextTransform(String id, TextTransform transform) {
        if (TEXT_TRANSFORMS.putIfAbsent(id, transform) != null) throw new IllegalArgumentException("Story text transform already registered: " + id);
    }

    // ---------------------------------------------------------------- lookup

    @Nullable public static Function<JsonElement, Condition> condition(String key) { return CONDITIONS.get(key); }
    @Nullable public static Function<JsonElement, Action> action(String key) { return ACTIONS.get(key); }
    @Nullable public static LineKind lineKind(String key) { return LINE_KINDS.get(key); }
    @Nullable public static Speaker speaker(String key) { return SPEAKERS.get(key); }
    @Nullable public static Trigger trigger(String key) { return TRIGGERS.get(key); }
    public static Set<String> speakerKeys() { return SPEAKERS.keySet(); }
    public static Set<String> triggerKeys() { return TRIGGERS.keySet(); }
    public static Set<String> textSourceKeys() { return TEXT_SOURCES.keySet(); }
    @Nullable public static TextSourceCompiler textSource(String key) { return TEXT_SOURCES.get(key); }
    @Nullable public static Function<JsonElement, Location> location(String key) { return LOCATIONS.get(key); }
    @Nullable public static Function<JsonElement, ItemSource> itemSource(String key) { return ITEM_SOURCES.get(key); }
    public static Set<String> locationKeys() { return LOCATIONS.keySet(); }

    public static Text prepareText(Text text, String namespace, StoryContext context, RandomSource random) {
        if (text == null) return null;
        for (TextTransform transform : TEXT_TRANSFORMS.values()) text = transform.expand(text, namespace, context, random);
        return text;
    }

    public static void validateText(Text text, String namespace, String where) {
        if (text != null) for (TextTransform transform : TEXT_TRANSFORMS.values()) transform.validate(text, namespace, where);
    }

    /** Every variable source's output for one context. */
    public static Map<String, String> derivedVars(StoryContext context) {
        Map<String, String> out = new LinkedHashMap<>();
        for (VarSource source : VAR_SOURCES.values()) source.vars(context, out);
        return out;
    }

    // ---------------------------------------------------------------- triggers

    /** Set once by the dialogue engine. */
    public static void setTriggerSink(TriggerSink next) {
        sink = next;
    }

    /** Other mods and Story modules call this to fire a trigger; matching dialogues open when the player is free. */
    public static void fire(String triggerKey, ServerPlayer player, Object payload) {
        sink.fire(triggerKey, player, payload);
    }
}

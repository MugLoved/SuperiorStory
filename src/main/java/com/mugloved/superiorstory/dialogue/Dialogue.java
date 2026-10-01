package com.mugloved.superiorstory.dialogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mugloved.superiorstory.api.DialogueOutcome;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.scene.StoryScene.Text;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One authored conversation. A file is a dialogue when it names who talks (a registered speaker key such as
 * {@code npc} or {@code block}), a {@code structure} (a dedicated continuation used when a located structure matches),
 * or a {@code trigger} (opens by event, as narration); everything else is optional: {@code beats} is the main branch,
 * {@code branches} holds named branches that choices {@code goto}. Choices continue to the next line by default;
 * {@code "goto": "end"} closes the conversation.
 */
public record Dialogue(ResourceLocation id, Binding binding, Text speaker, int priority, Guard guard,
                       Map<String, List<Line>> branches) {
    public static final String MAIN = "main";
    public static final String END = "end";
    public static final int MAX_CHOICES = 4;
    private static final int MAX_VARIANTS = 8;
    private static final int MAX_LINES = 256;
    private static final int MAX_BRANCHES = 64;
    private static final Set<String> CHOICE_FIELDS = Set.of("text", "goto", "outcome", "id", "if", "unless", "blocked");
    private static final Set<String> LINE_FIELDS = Set.of("text", "pool", "accent", "pace", "choices", "needs", "if", "unless", "blocked");
    private static final Set<String> ROOT_FIELDS = Set.of("structure", "trigger", "speaker", "priority", "if", "unless", "beats", "branches", "mods");
    private static final Set<String> SCENE_TRIGGERS = Set.of("manual", "first_join");
    private static final Pattern BRANCH_NAME = Pattern.compile("[a-z0-9_./-]{1,48}");

    /** What opens the dialogue: a speaker to talk to, a located structure, or a fired trigger. */
    public record Binding(Type type, @Nullable String key, Object spec) {
        public enum Type { SPEAKER, STRUCTURE, TRIGGER }
    }

    /** A line kind's compiled spec, for example the compiled {@code locate}. */
    public record Kind(String key, Object spec, List<String> branches) {
        public Kind {
            branches = List.copyOf(branches);
        }
    }

    /**
     * @param texts   one or more variants; a random one is used each time the line plays
     * @param needs   variable names that must be set for the line to play; otherwise it is skipped
     * @param actions run when the player continues past the line
     * @param blocked branch that plays when one of the line's actions or its kind is blocked
     */
    public record Line(List<Text> texts, boolean accent, float pace, List<Choice> choices, @Nullable Kind kind,
                       List<String> needs, Guard guard, List<Act> actions, @Nullable String blocked, @Nullable StoryHooks.TextSource source) {
        public Line {
            if (texts == null || (source == null ? texts.isEmpty() : !texts.isEmpty()) || texts.size() > MAX_VARIANTS || choices == null || choices.size() > MAX_CHOICES
                || !Float.isFinite(pace) || pace < 0.1f || pace > 5f) {
                throw new IllegalArgumentException("Line requires 1-" + MAX_VARIANTS + " texts, at most " + MAX_CHOICES + " choices, and pace 0.1-5");
            }
            if (kind != null && !choices.isEmpty()) throw new IllegalArgumentException("A " + kind.key() + " line cannot have choices");
            texts = List.copyOf(texts);
            choices = List.copyOf(choices);
            needs = List.copyOf(needs);
            actions = List.copyOf(actions);
        }

        public static Line plain(Text text) {
            return new Line(List.of(text), false, 1f, List.of(), null, List.of(), Guard.EMPTY, List.of(), null, null);
        }

        public boolean available(Map<String, String> vars, StoryContext context) {
            for (String need : needs) {
                String value = vars.get(need);
                if (value == null || value.isBlank()) return false;
            }
            return guard.passes(context);
        }

        @Nullable
        public Text pick(StoryContext context, RandomSource random) {
            if (source != null) return source.pick(context, random);
            return texts.get(texts.size() == 1 ? 0 : random.nextInt(texts.size()));
        }
    }

    /** {@code goTo} is null to continue with the next line, {@link #END}, or a branch name. */
    public record Choice(Text label, String goTo, DialogueOutcome outcome, String id, List<Act> actions, Guard guard,
                         @Nullable String blocked) {
        public Choice {
            actions = List.copyOf(actions);
        }

        /** Shown when its conditions pass and every action that contributes visibility agrees. */
        public boolean visible(StoryContext context) {
            if (!guard.passes(context)) return false;
            for (Act act : actions) {
                if (!act.action().visible(context)) return false;
            }
            return true;
        }
    }

    public Dialogue {
        branches = Map.copyOf(branches);
    }

    /** Every quest this dialogue accepts, hands over, turns in, delivers, or locates, in the order it first names them. */
    public java.util.Set<ResourceLocation> quests() {
        java.util.Set<ResourceLocation> quests = new java.util.LinkedHashSet<>();
        for (List<Line> lines : branches.values()) {
            for (Line line : lines) {
                if (line.kind() != null) {
                    StoryHooks.LineKind kind = StoryHooks.lineKind(line.kind().key());
                    ResourceLocation quest = kind == null ? null : kind.quest(line.kind().spec());
                    if (quest != null) quests.add(quest);
                }
                for (Act act : line.actions()) if (act.action().quest() != null) quests.add(act.action().quest());
                for (Choice choice : line.choices()) {
                    for (Act act : choice.actions()) if (act.action().quest() != null) quests.add(act.action().quest());
                }
            }
        }
        return quests;
    }

    /** Whether an action anywhere in this dialogue would move one of the player's quests forward right now (the "!" marker). Branch conditions are not evaluated. */
    public boolean progresses(StoryContext context) {
        for (List<Line> lines : branches.values()) {
            for (Line line : lines) {
                for (Act act : line.actions()) if (act.action().progresses(context)) return true;
                for (Choice choice : line.choices()) {
                    for (Act act : choice.actions()) if (act.action().progresses(context)) return true;
                }
            }
        }
        return false;
    }

    public boolean conditional() {
        return !guard.isEmpty();
    }

    /** Called after pools are installed for this reload; an invalid reference rejects only this dialogue. */
    public void validateTexts() {
        StoryHooks.validateText(speaker, id.getNamespace(), "speaker");
        branches.forEach((branch, lines) -> {
            for (int i = 0; i < lines.size(); i++) {
                Line line = lines.get(i);
                String where = branch + "[" + i + "]";
                if (line.source() != null) line.source().validate();
                for (Text text : line.texts()) StoryHooks.validateText(text, id.getNamespace(), where + ".text");
                for (int j = 0; j < line.choices().size(); j++) StoryHooks.validateText(line.choices().get(j).label(), id.getNamespace(), where + ".choices[" + j + "].text");
            }
        });
    }

    @Nullable
    public String speakerKey() {
        return binding.type() == Binding.Type.SPEAKER ? binding.key() : null;
    }

    @Nullable
    public IdSelector structure() {
        return binding.type() == Binding.Type.STRUCTURE ? (IdSelector) binding.spec() : null;
    }

    @Nullable
    public String triggerKey() {
        return binding.type() == Binding.Type.TRIGGER ? binding.key() : null;
    }

    public boolean matches(StoryHooks.SpeakerTarget target) {
        return binding.type() == Binding.Type.SPEAKER && ((StoryHooks.Speaker.Matcher) binding.spec()).matches(target);
    }

    public boolean triggeredBy(String key, Object payload) {
        return binding.type() == Binding.Type.TRIGGER && key.equals(binding.key())
            && StoryHooks.trigger(key).matches(binding.spec(), payload);
    }

    /** A one-line dialogue with the same binding, id and speaker name, used to say why something is blocked. */
    public Dialogue single(Text text) {
        return new Dialogue(id, binding, speaker, priority, Guard.EMPTY, Map.of(MAIN, List.of(Line.plain(text))));
    }

    /** Branch names no choice can ever reach, for load-time warnings. */
    public Set<String> unreachableBranches() {
        Set<String> seen = new LinkedHashSet<>(List.of(MAIN));
        List<String> open = new ArrayList<>(List.of(MAIN));
        while (!open.isEmpty()) {
            for (Line line : branches.get(open.remove(open.size() - 1))) {
                for (String target : targets(line)) {
                    if (target != null && !target.equals(END) && seen.add(target)) open.add(target);
                }
            }
        }
        Set<String> unreachable = new LinkedHashSet<>(branches.keySet());
        unreachable.removeAll(seen);
        return unreachable;
    }

    private static List<String> targets(Line line) {
        List<String> targets = new ArrayList<>();
        for (Choice choice : line.choices()) {
            targets.add(choice.goTo());
            targets.add(choice.blocked());
        }
        if (line.kind() != null) targets.addAll(line.kind().branches());
        targets.add(line.blocked());
        return targets;
    }

    // ---------------------------------------------------------------- parsing

    public static boolean isDialogue(JsonElement element) {
        if (element == null || !element.isJsonObject()) return false;
        JsonObject root = element.getAsJsonObject();
        for (String key : StoryHooks.speakerKeys()) if (root.has(key)) return true;
        if (root.has("structure")) return true;
        if (!root.has("trigger") || root.has("end")) return false;   // scenes have an end and a plain-word trigger
        JsonElement trigger = root.get("trigger");
        return !(trigger.isJsonPrimitive() && SCENE_TRIGGERS.contains(trigger.getAsString()));
    }

    /** Whether every mod named by the file's optional {@code "mods"} array is loaded; a file for an absent mod is skipped without an error. */
    public static boolean modsLoaded(JsonElement element, java.util.function.Predicate<String> loaded) {
        if (element == null || !element.isJsonObject() || !element.getAsJsonObject().has("mods")) return true;
        JsonElement mods = element.getAsJsonObject().get("mods");
        if (!mods.isJsonArray()) return true;   // parse reports the bad shape
        for (JsonElement mod : mods.getAsJsonArray()) {
            if (mod.isJsonPrimitive() && !loaded.test(mod.getAsString())) return false;
        }
        return true;
    }

    public static Dialogue parse(ResourceLocation id, JsonElement element) {
        if (!isDialogue(element)) throw new IllegalArgumentException("Dialogue root must be an object with a speaker, a structure, or a trigger");
        JsonObject root = element.getAsJsonObject();
        Set<String> allowed = new LinkedHashSet<>(ROOT_FIELDS);
        allowed.addAll(StoryHooks.speakerKeys());
        rejectUnknown(root, allowed, "dialogue");

        Binding binding = null;
        for (String key : StoryHooks.speakerKeys()) {
            if (!root.has(key)) continue;
            if (binding != null) throw new IllegalArgumentException("A dialogue has one of " + describeBindings());
            try {
                binding = new Binding(Binding.Type.SPEAKER, key, StoryHooks.speaker(key).compile(root.get(key), key));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(key + ": " + exception.getMessage());
            }
        }
        if (root.has("structure")) {
            if (binding != null) throw new IllegalArgumentException("A dialogue has one of " + describeBindings());
            binding = new Binding(Binding.Type.STRUCTURE, null, IdSelector.parse(string(root, "structure")));
        }
        if (root.has("trigger")) {
            if (binding != null) throw new IllegalArgumentException("A dialogue has one of " + describeBindings());
            binding = trigger(root.get("trigger"));
        }

        Text speaker = root.has("speaker") ? text(root.get("speaker")) : null;
        int priority = 0;
        if (root.has("priority")) {
            if (!root.get("priority").isJsonPrimitive() || !root.get("priority").getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("priority must be a number");
            }
            priority = root.get("priority").getAsInt();
        }
        Guard guard = Guard.parse(root, "dialogue");

        Map<String, JsonArray> authored = new LinkedHashMap<>();
        authored.put(MAIN, array(root, "beats"));
        if (root.has("branches")) {
            if (!root.get("branches").isJsonObject()) throw new IllegalArgumentException("branches must be an object");
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("branches").entrySet()) {
                String name = entry.getKey();
                if (name.equals(MAIN) || name.equals(END) || !BRANCH_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("Invalid branch name: " + name);
                }
                if (!entry.getValue().isJsonArray()) throw new IllegalArgumentException("branches." + name + " must be an array");
                authored.put(name, entry.getValue().getAsJsonArray());
            }
        }
        if (authored.size() > MAX_BRANCHES) throw new IllegalArgumentException("At most " + MAX_BRANCHES + " branches");

        Map<String, List<Line>> branches = new LinkedHashMap<>();
        Set<String> choiceIds = new LinkedHashSet<>();
        int total = 0;
        for (Map.Entry<String, JsonArray> branch : authored.entrySet()) {
            JsonArray lines = branch.getValue();
            if (lines.isEmpty()) throw new IllegalArgumentException(branch.getKey() + " needs at least one line");
            total += lines.size();
            List<Line> parsed = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                parsed.add(line(lines.get(i), branch.getKey(), i, choiceIds, id.getNamespace()));
            }
            branches.put(branch.getKey(), parsed);
        }
        if (total > MAX_LINES) throw new IllegalArgumentException("At most " + MAX_LINES + " lines per dialogue");
        for (List<Line> lines : branches.values()) {
            for (Line line : lines) {
                for (String target : targets(line)) {
                    if (target != null && !target.equals(END) && !branches.containsKey(target)) {
                        throw new IllegalArgumentException("Unknown branch: " + target);
                    }
                }
            }
        }
        return new Dialogue(id, binding, speaker, priority, guard, branches);
    }

    private static String describeBindings() {
        List<String> all = new ArrayList<>(StoryHooks.speakerKeys());
        all.add("structure");
        all.add("trigger");
        return String.join(", ", all);
    }

    private static Binding trigger(JsonElement authored) {
        JsonObject object;
        if (authored.isJsonPrimitive() && authored.getAsJsonPrimitive().isString()) {
            object = new JsonObject();
            object.addProperty(authored.getAsString(), true);
        } else if (authored.isJsonObject()) {
            object = authored.getAsJsonObject();
        } else {
            throw new IllegalArgumentException("trigger must be a trigger name or an object");
        }
        String key = null;
        for (String candidate : object.keySet()) {
            if (StoryHooks.trigger(candidate) == null) continue;
            if (key != null) throw new IllegalArgumentException("trigger names more than one trigger: " + key + ", " + candidate);
            key = candidate;
        }
        if (key == null) throw new IllegalArgumentException("Unknown trigger; expected one of " + StoryHooks.triggerKeys());
        try {
            return new Binding(Binding.Type.TRIGGER, key, StoryHooks.trigger(key).compile(object, "trigger"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("trigger." + key + ": " + exception.getMessage());
        }
    }

    private static Line line(JsonElement authored, String branch, int index, Set<String> choiceIds, String namespace) {
        String where = branch + "[" + index + "]";
        if (authored.isJsonPrimitive() || authored.isJsonArray() || (authored.isJsonObject() && authored.getAsJsonObject().has("translate"))) {
            return new Line(texts(authored), false, 1f, List.of(), null, List.of(), Guard.EMPTY, List.of(), null, null);
        }
        if (!authored.isJsonObject()) throw new IllegalArgumentException(where + " must be text or an object");
        JsonObject object = authored.getAsJsonObject();
        StoryHooks.TextSource source = null;
        Set<String> sourceKeys = new LinkedHashSet<>();
        for (String key : StoryHooks.textSourceKeys()) if (object.has(key)) {
            if (source != null || object.has("text")) throw new IllegalArgumentException(where + " needs exactly one text source (text or pool)");
            source = StoryHooks.textSource(key).compile(object.get(key), namespace, where + "." + key);
            sourceKeys.add(key);
        }
        if (!object.has("text") && source == null) throw new IllegalArgumentException(where + " needs text or pool");

        Kind kind = null;
        Set<String> kindKeys = new LinkedHashSet<>(sourceKeys);
        for (String key : object.keySet()) {
            StoryHooks.LineKind module = StoryHooks.lineKind(key);
            if (module == null) continue;
            if (kind != null) throw new IllegalArgumentException(where + " has more than one line kind: " + kind.key() + ", " + key);
            kindKeys.add(key);
            try {
                Object spec = module.compile(object.get(key), where + "." + key);
                kind = new Kind(key, spec, module.branches(spec));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(where + "." + key + ": " + exception.getMessage());
            }
        }
        List<Act> actions = Act.parseAll(object.entrySet(), LINE_FIELDS, kindKeys, where);

        List<Choice> choices = new ArrayList<>();
        if (object.has("choices")) {
            JsonArray authoredChoices = array(object, "choices");
            if (authoredChoices.isEmpty() || authoredChoices.size() > MAX_CHOICES) {
                throw new IllegalArgumentException(where + ".choices needs 1-" + MAX_CHOICES + " entries");
            }
            for (int i = 0; i < authoredChoices.size(); i++) {
                Choice choice = choice(authoredChoices.get(i), where + ".choices[" + i + "]", branch + "/" + index + "/" + i);
                if (!choiceIds.add(choice.id())) throw new IllegalArgumentException("Duplicate choice id: " + choice.id());
                choices.add(choice);
            }
        }
        float pace = 1f;
        if (object.has("pace")) {
            if (!object.get("pace").isJsonPrimitive() || !object.get("pace").getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("pace must be a number");
            }
            pace = object.get("pace").getAsFloat();
        }
        boolean accent = false;
        if (object.has("accent")) {
            if (!object.get("accent").isJsonPrimitive() || !object.get("accent").getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("accent must be a boolean");
            }
            accent = object.get("accent").getAsBoolean();
        }
        List<String> needs = new ArrayList<>();
        if (object.has("needs")) {
            JsonElement value = object.get("needs");
            if (value.isJsonPrimitive()) needs.add(value.getAsString());
            else if (value.isJsonArray()) for (JsonElement entry : value.getAsJsonArray()) needs.add(entry.getAsString());
            else throw new IllegalArgumentException(where + ".needs must be a variable name or an array of them");
        }
        String blocked = object.has("blocked") ? string(object, "blocked") : null;
        return new Line(source == null ? texts(object.get("text")) : List.of(), accent, pace, choices, kind, needs, Guard.parse(object, where), actions, blocked, source);
    }

    /** One text, or an array of variants. */
    private static List<Text> texts(JsonElement element) {
        if (element != null && element.isJsonArray()) {
            List<Text> variants = new ArrayList<>();
            for (JsonElement entry : element.getAsJsonArray()) variants.add(text(entry));
            return variants;
        }
        return List.of(text(element));
    }

    private static Choice choice(JsonElement authored, String where, String defaultId) {
        if (authored.isJsonPrimitive() || (authored.isJsonObject() && authored.getAsJsonObject().has("translate"))) {
            return new Choice(text(authored), null, DialogueOutcome.NONE, defaultId, List.of(), Guard.EMPTY, null);
        }
        if (!authored.isJsonObject()) throw new IllegalArgumentException(where + " must be text or an object");
        JsonObject object = authored.getAsJsonObject();
        if (!object.has("text")) throw new IllegalArgumentException(where + " needs text");
        String goTo = object.has("goto") ? string(object, "goto") : null;
        DialogueOutcome outcome = object.has("outcome") ? DialogueOutcome.parse(string(object, "outcome")) : DialogueOutcome.NONE;
        String id = object.has("id") ? string(object, "id") : defaultId;
        if (id.isBlank() || id.length() > 128) throw new IllegalArgumentException(where + ".id must be 1-128 characters");
        List<Act> actions = Act.parseAll(object.entrySet(), CHOICE_FIELDS, Set.of(), where);
        String blocked = object.has("blocked") ? string(object, "blocked") : null;
        return new Choice(text(object.get("text")), goTo, outcome, id, actions, Guard.parse(object, where), blocked);
    }

    public static Text text(JsonElement element) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return Text.literal(element.getAsString());
        }
        if (element != null && element.isJsonObject() && element.getAsJsonObject().size() == 1
            && element.getAsJsonObject().has("translate")) {
            return Text.translation(string(element.getAsJsonObject(), "translate"));
        }
        throw new IllegalArgumentException("Text must be a string or {\"translate\":\"key\"}");
    }

    public static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }

    private static JsonArray array(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
        return object.getAsJsonArray(key);
    }

    private static void rejectUnknown(JsonObject object, Set<String> allowed, String where) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown " + where + " field: " + key);
        }
    }
}

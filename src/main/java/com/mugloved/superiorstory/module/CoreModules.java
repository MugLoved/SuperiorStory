package com.mugloved.superiorstory.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.DialogueOutcome;
import com.mugloved.superiorstory.api.DialogueEndEvent;
import com.mugloved.superiorstory.api.StoryBlocked;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.api.StoryQuestStage;
import com.mugloved.superiorstory.dialogue.IdSelector;
import com.mugloved.superiorstory.dialogue.ItemSpec;
import com.mugloved.superiorstory.dialogue.QuestDef;
import com.mugloved.superiorstory.dialogue.UnlockSpec;
import com.superior.lib.api.unlock.PlayerUnlockApi;
import com.mugloved.superiorstory.dialogue.Vars;
import com.mugloved.superiorstory.server.LocateLine;
import com.mugloved.superiorstory.server.QuestDefinitions;
import com.mugloved.superiorstory.server.StoryQuests;
import com.mugloved.superiorstory.server.StoryTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Story's built-in modules, registered through {@link StoryHooks} exactly like a third-party module would be. Each one is
 * a self-contained compiler plus a small runtime; nothing here is known to the dialogue engine.
 */
public final class CoreModules {
    private static final Set<String> STAGES = Set.of("none", "active", "available", "offered", "accepted", "visited",
        "boss_defeated", "collected", "turned_in");

    private CoreModules() {}

    public static void register() {
        com.mugloved.superiorstory.server.LinePools.register();
        StoryHooks.setTriggerSink(StoryTriggers::fire);
        // conditions
        StoryHooks.registerCondition("quest_stage", value -> {
            String stage = string(value, "quest_stage");
            if (!STAGES.contains(stage) || stage.equals("available")) throw new IllegalArgumentException("Unknown quest stage: " + stage);
            return context -> StoryQuests.stageIs(context.player(), stage);
        });
        StoryHooks.registerCondition("quest", CoreModules::questCondition);
        StoryHooks.registerCondition("time", CoreModules::timeCondition);
        StoryHooks.registerCondition("dimension", value -> {
            List<IdSelector> selectors = selectors(value, "dimension", false);
            return context -> {
                ResourceLocation dimension = context.level().dimension().location();
                for (IdSelector selector : selectors) if (selector.match(dimension, tag -> false) > 0) return true;
                return false;
            };
        });
        StoryHooks.registerCondition("biome", value -> {
            List<IdSelector> selectors = selectors(value, "biome", true);
            return context -> {
                Holder<Biome> biome = context.level().getBiome(context.player().blockPosition());
                for (IdSelector selector : selectors) {
                    ResourceLocation key = biome.unwrapKey().map(k -> k.location()).orElse(null);
                    if (key != null && selector.match(key, tag -> biome.is(TagKey.create(Registries.BIOME, tag))) > 0) return true;
                }
                return false;
            };
        });
        StoryHooks.registerCondition("profession", value -> {
            List<ResourceLocation> wanted = new ArrayList<>();
            for (JsonElement entry : value.isJsonArray() ? value.getAsJsonArray() : List.of(value)) {
                ResourceLocation id = ResourceLocation.tryParse(string(entry, "profession"));
                if (id == null) throw new IllegalArgumentException("profession is not a valid profession ID");
                wanted.add(id);
            }
            if (wanted.isEmpty()) throw new IllegalArgumentException("profession needs at least one entry");
            return context -> {
                ResourceLocation profession = professionOf(context.speakerEntity());
                return profession != null && wanted.contains(profession);
            };
        });
        StoryHooks.registerCondition("unlocked", value -> {
            UnlockSpec spec = UnlockSpec.parse(value, "unlocked");
            return context -> {
                for (String key : spec.keys()) if (!PlayerUnlockApi.isUnlocked(context.player(), key)) return false;
                return true;
            };
        });
        StoryHooks.registerCondition("has_item", value -> {
            Items items = Items.parse(value);
            return context -> items.has(context.player());
        });

        // actions
        StoryHooks.registerAction("function", value -> {
            ResourceLocation id = ResourceLocation.tryParse(string(value, "function"));
            if (id == null) throw new IllegalArgumentException("function is not a valid function ID");
            return context -> {
                var server = context.player().getServer();
                if (server == null) return;
                server.getFunctions().get(id).ifPresentOrElse(
                    function -> server.getFunctions().execute(function,
                        context.player().createCommandSourceStack().withPermission(2).withSuppressedOutput()),
                    () -> SuperiorStory.LOGGER.warn("Dialogue function {} not found", id));
            };
        });
        StoryHooks.registerAction("accept", value -> new QuestVerb(questOrTrue(value, "accept"), StoryQuests::accept, null));
        StoryHooks.registerAction("turn_in", value -> new QuestVerb(questOrTrue(value, "turn_in"), StoryQuests::turnIn, StoryQuests::readyToTurnIn));
        StoryHooks.registerAction("deliver", value -> new QuestVerb(questOrTrue(value, "deliver"), StoryQuests::deliver, StoryQuests::waitingOnDelivery));
        StoryHooks.registerAction("hand_over", value -> new HandOver(Items.parse(value)));
        StoryHooks.registerAction("use_block", value -> new UseBlock(ItemSpec.parse(value)));
        StoryHooks.registerAction("give", value -> new Give(ItemSpec.parseAll(value)));
        StoryHooks.registerAction("unlock", value -> {
            UnlockSpec spec = UnlockSpec.parse(value, "unlock");
            return context -> {
                String source = context.sourceId() == null ? "superiorstory:dialogue" : "superiorstory:" + context.sourceId();
                for (String key : spec.keys()) PlayerUnlockApi.unlock(context.player(), key, source, spec.tell());
            };
        });
        StoryHooks.registerAction("lock", value -> {
            UnlockSpec spec = UnlockSpec.parse(value, "lock");
            return context -> {
                for (String key : spec.keys()) PlayerUnlockApi.lock(context.player(), key);
            };
        });
        StoryHooks.registerAction("open", value -> {
            ResourceLocation id = ResourceLocation.tryParse(string(value, "open"));
            if (id == null) throw new IllegalArgumentException("open is not a valid dialogue ID");
            return context -> StoryTriggers.open(context.player(), id);
        });

        // line kind, speakers, triggers, derived variables
        StoryHooks.registerLineKind("locate", new LocateLine());
        com.mugloved.superiorstory.server.QuestLocations.register();
        StoryHooks.registerSpeaker("npc", new EntitySpeaker());
        StoryHooks.registerSpeaker("block", new BlockSpeaker());
        StoryHooks.registerTrigger("command", new StoryHooks.Trigger() {
            @Override public Object compile(JsonElement value, String where) { return Boolean.TRUE; }
            @Override public boolean matches(Object spec, Object payload) { return false; }   // opened only by /superiorstory open
        });
        StoryHooks.registerTrigger("bounty_template", new StoryHooks.Trigger() {
            @Override public Object compile(JsonElement value, String where) { return Boolean.TRUE; }
            @Override public boolean matches(Object spec, Object payload) { return false; }   // marks a bounty template dialogue the bounty line hands off to
        });
        StoryHooks.registerTrigger("dialogue_end", new DialogueEndTrigger());
        StoryHooks.registerTrigger("quest", new QuestTrigger());
        StoryHooks.registerVarSource("player", (context, out) -> out.put("player", context.player().getDisplayName().getString()));
        StoryHooks.registerVarSource("time", (context, out) -> {
            if (context.level().isDay()) out.put("time", Vars.LANG + "superiorstory.time.day");
            else if (context.level().isNight()) out.put("time", Vars.LANG + "superiorstory.time.night");
        });
        StoryHooks.registerVarSource("dimension", (context, out) -> {
            ResourceLocation dimension = context.level().dimension().location();
            out.put("dimension", Vars.LANG + "superiorstory.dimension." + dimension.getNamespace() + "." + dimension.getPath());
        });
        StoryHooks.registerVarSource("speaker", (context, out) -> {
            if (context.speakerEntity() != null) out.put("speaker", context.speakerEntity().getDisplayName().getString());
            else if (context.speakerBlock() != null) {
                out.put("speaker", context.level().getBlockState(context.speakerBlock()).getBlock().getName().getString());
            }
        });
        ExtraModules.register();
        com.mugloved.superiorstory.server.RewardChoices.register();
    }

    // ---------------------------------------------------------------- conditions

    private static StoryHooks.Condition questCondition(JsonElement value) {
        ResourceLocation id;
        String stage = "active";
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : object.keySet()) if (!Set.of("id", "stage").contains(key)) throw new IllegalArgumentException("Unknown quest condition field: " + key);
            id = ResourceLocation.tryParse(string(object.get("id"), "id"));
            if (object.has("stage")) stage = string(object.get("stage"), "stage");
        } else {
            id = ResourceLocation.tryParse(string(value, "quest"));
        }
        if (id == null) throw new IllegalArgumentException("quest is not a valid quest ID");
        if (!STAGES.contains(stage)) throw new IllegalArgumentException("Unknown quest stage: " + stage);
        ResourceLocation quest = id;
        String wanted = stage;
        return context -> StoryQuests.questStageIs(context, quest, wanted);
    }

    private static StoryHooks.Condition timeCondition(JsonElement value) {
        String time = string(value, "time");
        if (!time.equals("day") && !time.equals("night")) throw new IllegalArgumentException("time must be day or night");
        boolean night = time.equals("night");
        return new StoryHooks.Condition() {
            @Override
            public boolean test(StoryContext context) {
                return night ? context.level().isNight() : context.level().isDay();
            }

            @Nullable
            @Override
            public String reasonKey(StoryContext context) {
                if (context.level().dimensionType().hasFixedTime()) return null;   // no day cycle here: "Not now."
                return night ? "superiorstory.blocked.night" : "superiorstory.blocked.day";
            }
        };
    }

    private static List<IdSelector> selectors(JsonElement value, String key, boolean allowTags) {
        List<IdSelector> out = new ArrayList<>();
        if (value.isJsonArray()) for (JsonElement entry : value.getAsJsonArray()) out.add(IdSelector.parse(string(entry, key)));
        else out.add(IdSelector.parse(string(value, key)));
        if (out.isEmpty()) throw new IllegalArgumentException(key + " needs at least one entry");
        if (!allowTags) {
            for (IdSelector selector : out) if (selector.kind() == IdSelector.Kind.TAG) throw new IllegalArgumentException(key + " does not accept tags");
        }
        return out;
    }

    /** The villager profession of an entity (shared with the Bountiful bridge), or null for a non-villager. */
    @Nullable
    public static ResourceLocation professionOf(@Nullable net.minecraft.world.entity.Entity entity) {
        return entity instanceof net.minecraft.world.entity.npc.VillagerDataHolder villager
            ? BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession()) : null;
    }

    // ---------------------------------------------------------------- shared parsing

    private static String string(JsonElement value, String key) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be a string");
        return value.getAsString();
    }

    /** {@code true} (the newest offer or open quest) or a quest ID. */
    @Nullable
    private static ResourceLocation questOrTrue(JsonElement value, String key) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            if (!value.getAsBoolean()) throw new IllegalArgumentException(key + " false does nothing; remove it");
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(string(value, key));
        if (id == null) throw new IllegalArgumentException(key + " is not a valid quest ID");
        return id;
    }

    /** Item requirements: item specs, or {@code {"quest": id}} meaning the item of that quest file. */
    private record Items(List<ItemSpec> specs, @Nullable ResourceLocation quest) {
        static Items parse(JsonElement value) {
            if (value.isJsonObject() && value.getAsJsonObject().has("quest")) {
                JsonObject object = value.getAsJsonObject();
                if (object.size() != 1) throw new IllegalArgumentException("A quest item requirement is just {\"quest\": id}");
                ResourceLocation id = ResourceLocation.tryParse(string(object.get("quest"), "quest"));
                if (id == null) throw new IllegalArgumentException("quest is not a valid quest ID");
                return new Items(List.of(), id);
            }
            return new Items(ItemSpec.parseAll(value), null);
        }

        @Nullable
        QuestDef def() {
            return quest == null ? null : QuestDefinitions.get(quest);
        }

        boolean has(ServerPlayer player) {
            if (quest == null) {
                for (ItemSpec spec : specs) if (!spec.has(player)) return false;
                return true;
            }
            QuestDef def = def();
            return def != null && StoryQuests.carries(player, def);
        }

        /** The items to show: the listed specs, or the player's picks for the quest. */
        List<ItemSpec> shown(ServerPlayer player) {
            if (quest == null) return specs;
            QuestDef def = def();
            return def == null ? List.of() : StoryQuests.items(player, def);
        }
    }

    // ---------------------------------------------------------------- actions

    /** An action on a quest ({@code true} means the newest one); it also tells the scene which quest it is about. */
    private record QuestVerb(@Nullable ResourceLocation quest, java.util.function.BiConsumer<StoryContext, ResourceLocation> run,
                             @Nullable java.util.function.BiPredicate<StoryContext, ResourceLocation> progress) implements StoryHooks.Action {
        @Override
        public void run(StoryContext context) {
            run.accept(context, quest);
        }

        @Override
        public boolean progresses(StoryContext context) {
            return progress != null && progress.test(context, quest);
        }
    }

    /** Takes the items from the player, or turns a quest in. Shown only while the player carries what it needs. */
    private record HandOver(Items items) implements StoryHooks.Action {
        @Override
        public void run(StoryContext context) {
            ServerPlayer player = context.player();
            if (items.quest() != null) {
                StoryQuests.turnIn(context, items.quest());
                return;
            }
            for (ItemSpec spec : items.specs()) {
                if (!spec.has(player)) throw new StoryBlocked("superiorstory.blocked.missing_items", Map.of("blocked", Vars.LANG + "superiorstory.blocked.missing_items"));
            }
            for (ItemSpec spec : items.specs()) if (spec.consume()) spec.take(player);
        }

        @Nullable
        @Override
        public ResourceLocation quest() {
            return items.quest();
        }

        @Override
        public boolean progresses(StoryContext context) {
            return items.quest() != null && StoryQuests.readyToTurnIn(context, items.quest());
        }

        @Override
        public boolean visible(StoryContext context) {
            if (items.quest() != null && items.def() == null) {
                SuperiorStory.LOGGER.warn("hand_over references unknown quest {}", items.quest());
                return false;
            }
            return items.has(context.player());
        }

        @Override
        public List<ItemSpec> shows(StoryContext context) {
            return items.shown(context.player());
        }
    }

    /**
     * Makes the player use an item on the speaking block exactly as a right-click would: the matching stack moves to the
     * main hand, the block's own interaction runs (including Forge's right-click events), and the previous main-hand item is
     * restored. The block decides what it consumes.
     */
    private record UseBlock(ItemSpec spec) implements StoryHooks.Action {
        @Override
        public void run(StoryContext context) {
            BlockPos pos = context.speakerBlock();
            ServerPlayer player = context.player();
            if (pos == null) {
                SuperiorStory.LOGGER.warn("use_block needs a block speaker");
                return;
            }
            if (!spec.has(player)) throw new StoryBlocked("superiorstory.blocked.missing_items", Map.of("blocked", Vars.LANG + "superiorstory.blocked.missing_items"));
            Inventory inventory = player.getInventory();
            int selected = inventory.selected;
            int source = -1;
            for (int i = 0; i < inventory.items.size(); i++) {
                if (spec.matches(inventory.items.get(i))) {
                    source = i;
                    break;
                }
            }
            ServerLevel level = player.serverLevel();
            Vec3 center = Vec3.atCenterOf(pos);
            Direction face = Direction.getNearest(player.getEyePosition().x - center.x, player.getEyePosition().y - center.y,
                player.getEyePosition().z - center.z);
            BlockHitResult hit = new BlockHitResult(center.relative(face, 0.5), face, pos, false);
            if (source < 0) {   // the item is only in the off hand
                player.gameMode.useItemOn(player, level, inventory.offhand.get(0), InteractionHand.OFF_HAND, hit);
            } else {
                ItemStack previous = inventory.items.get(selected);
                ItemStack carried = inventory.items.get(source);
                inventory.items.set(selected, carried);
                if (source != selected) inventory.items.set(source, previous);
                player.gameMode.useItemOn(player, level, inventory.items.get(selected), InteractionHand.MAIN_HAND, hit);
                ItemStack result = inventory.items.get(selected);
                inventory.items.set(selected, source != selected ? previous : result);
                if (source != selected) inventory.items.set(source, result);
            }
            player.containerMenu.broadcastChanges();
        }

        @Override
        public boolean visible(StoryContext context) {
            return spec.has(context.player());
        }

        @Override
        public List<ItemSpec> shows(StoryContext context) {
            return List.of(spec);
        }
    }

    /** Gives items to the player; whatever does not fit is dropped at their feet. */
    private record Give(List<ItemSpec> specs) implements StoryHooks.Action {
        Give {
            for (ItemSpec spec : specs) {
                if (spec.item().kind() != IdSelector.Kind.EXACT) throw new IllegalArgumentException("give needs exact item IDs");
            }
            specs = List.copyOf(specs);
        }

        @Override
        public void run(StoryContext context) {
            ServerPlayer player = context.player();
            for (ItemSpec spec : specs) {
                ItemStack stack = spec.stack();
                if (stack.isEmpty()) {
                    SuperiorStory.LOGGER.warn("give references unknown item {}", spec.item().id());
                    continue;
                }
                int left = spec.quantity();
                while (left > 0) {
                    ItemStack part = stack.copyWithCount(Math.min(left, stack.getMaxStackSize()));
                    left -= part.getCount();
                    if (!player.getInventory().add(part) && !part.isEmpty()) player.drop(part, false);
                }
            }
        }

        @Override
        public String rewardVar() {
            return Vars.ITEM + specs.get(0).item().id();
        }
    }

    // ---------------------------------------------------------------- triggers

    /** {@code {"dialogue_end": "ns:id", "outcome": "good", "reason": "completed"}}. */
    private record DialogueEndSpec(ResourceLocation dialogue, @Nullable DialogueOutcome outcome, DialogueEndEvent.Reason reason) {}

    /** Payload fired when a conversation ends. */
    public record DialogueEnded(ResourceLocation dialogue, DialogueOutcome outcome, DialogueEndEvent.Reason reason) {}

    private static final class DialogueEndTrigger implements StoryHooks.Trigger {
        @Override
        public Object compile(JsonElement value, String where) {
            JsonObject object = value.getAsJsonObject();
            for (String key : object.keySet()) if (!Set.of("dialogue_end", "outcome", "reason").contains(key)) throw new IllegalArgumentException("Unknown field: " + key);
            ResourceLocation id = ResourceLocation.tryParse(string(object.get("dialogue_end"), "dialogue_end"));
            if (id == null) throw new IllegalArgumentException("dialogue_end is not a valid dialogue ID");
            DialogueOutcome outcome = object.has("outcome") ? DialogueOutcome.parse(string(object.get("outcome"), "outcome")) : null;
            DialogueEndEvent.Reason reason = DialogueEndEvent.Reason.COMPLETED;
            if (object.has("reason")) {
                try {
                    reason = DialogueEndEvent.Reason.valueOf(string(object.get("reason"), "reason").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("reason must be one of completed, left, interrupted, npc_lost");
                }
            }
            return new DialogueEndSpec(id, outcome, reason);
        }

        @Override
        public boolean matches(Object spec, Object payload) {
            DialogueEndSpec wanted = (DialogueEndSpec) spec;
            return payload instanceof DialogueEnded ended && ended.dialogue().equals(wanted.dialogue()) && ended.reason() == wanted.reason()
                && (wanted.outcome() == null || wanted.outcome() == ended.outcome());
        }
    }

    private record QuestSpec(ResourceLocation quest, StoryQuestStage stage) {}

    /** {@code {"quest": "ns:q", "stage": "collected"}}; the default stage is {@code collected}. */
    private static final class QuestTrigger implements StoryHooks.Trigger {
        @Override
        public Object compile(JsonElement value, String where) {
            JsonObject object = value.getAsJsonObject();
            for (String key : object.keySet()) if (!Set.of("quest", "stage").contains(key)) throw new IllegalArgumentException("Unknown field: " + key);
            ResourceLocation id = ResourceLocation.tryParse(string(object.get("quest"), "quest"));
            if (id == null) throw new IllegalArgumentException("quest is not a valid quest ID");
            StoryQuestStage stage = object.has("stage") ? StoryQuestStage.byKey(string(object.get("stage"), "stage")) : StoryQuestStage.COLLECTED;
            if (stage == null) throw new IllegalArgumentException("Unknown quest stage");
            return new QuestSpec(id, stage);
        }

        @Override
        public boolean matches(Object spec, Object payload) {
            QuestSpec wanted = (QuestSpec) spec;
            return payload instanceof StoryQuests.QuestPayload event && event.questId().equals(wanted.quest()) && event.stage() == wanted.stage();
        }
    }
}

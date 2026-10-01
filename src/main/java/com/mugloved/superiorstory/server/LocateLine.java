package com.mugloved.superiorstory.server;

import com.google.gson.JsonElement;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryBlocked;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.dialogue.IdSelector;
import com.mugloved.superiorstory.dialogue.ItemSpec;
import com.mugloved.superiorstory.dialogue.LocateSpec;
import com.mugloved.superiorstory.dialogue.QuestDef;
import com.mugloved.superiorstory.dialogue.StructureProfile;
import com.superior.lib.api.runtime.SuperiorActionContext;
import com.superior.lib.api.service.SuperiorServiceRegistry;
import com.superior.lib.api.structure.KnownStructureStartsApi;
import com.superior.locator.api.LocatorClientOptions;
import com.superior.locator.api.LocatorMatchMode;
import com.superior.locator.api.LocatorProgressSnapshot;
import com.superior.locator.api.LocatorQuery;
import com.superior.locator.api.LocatorResult;
import com.superior.locator.api.LocatorResultMode;
import com.superior.locator.api.LocatorSearchApi;
import com.superior.locator.api.LocatorSearchRequest;
import com.superior.locator.api.LocatorStructureDimensions;
import com.superior.locator.runtime.LocatorSessionAttributes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The {@code locate} line kind: finds the nearest structure the player has not been sent to yet while the line's text
 * plays. The candidate set is narrowed first (a pool, a structure, a quest's target) and filtered to structures that can
 * generate in the player's dimension, so a search that cannot succeed fails immediately. The known-structure index is asked
 * before anything is searched; only a pool or structure search falls back to a Superior Locator search, and {@code general}
 * never does.
 */
public final class LocateLine implements StoryHooks.LineKind {
    private static final int MAX_ATTEMPTS = 24;
    private static final long TIMEOUT_MS = 45_000L;

    @Override
    public Object compile(JsonElement value, String where) {
        return LocateSpec.parse(value, where);
    }

    @Override
    public List<String> branches(Object spec) {
        return ((LocateSpec) spec).branches();
    }

    @Override
    public ResourceLocation quest(Object spec) {
        return ((LocateSpec) spec).quest();
    }

    @Override
    public Job start(StoryContext context, Object spec) {
        LocateSpec locate = (LocateSpec) spec;
        ServerPlayer player = context.player();
        if (locate.mode() == LocateSpec.Mode.QUEST) {
            QuestDef quest = QuestDefinitions.get(locate.quest());
            if (quest == null) {
                SuperiorStory.LOGGER.warn("locate references unknown quest {}", locate.quest());
                return Finished.failed(locate);
            }
            StoryQuests.requireAvailableOrActive(context, quest);   // explains itself when the quest is blocked
            List<ItemSpec> picks = StoryQuests.pick(context, quest);   // explains itself when nothing is eligible
            if (quest.location() == null) {   // a fetch quest: offered at once, no search and no marker
                StoryQuests.offer(player, quest, null, null, null, picks);
                return new Finished(new Result(true, StoryQuests.questVars(player, quest), locate.found(), null), !locate.waypoint());
            }
            if (!quest.location().possibleIn(player.serverLevel())) return Finished.failed(locate);
            return new QuestSearch(player, locate, quest, picks);
        }
        return new Search(player, locate, null);
    }

    /** A quest's location search; the hit becomes the quest offer. */
    private static final class QuestSearch implements Job {
        private final LocateSpec spec;
        private final QuestDef quest;
        private final List<ItemSpec> picks;
        private final Job job;
        private ResourceLocation foundId;
        private BlockPos foundAt;
        private Result result;

        QuestSearch(ServerPlayer player, LocateSpec spec, QuestDef quest, List<ItemSpec> picks) {
            this.spec = spec;
            this.quest = quest;
            this.picks = picks;
            this.job = quest.location().search(player, spec.radiusBlocks(), spec.waypoint(), (id, at) -> {
                foundId = id;
                foundAt = at;
            });
        }

        @Override
        public boolean poll(StoryContext context) {
            if (result != null) return true;
            if (!job.poll(context)) return false;
            if (!job.result().success() || foundId == null) {
                result = new Result(false, Map.of(), spec.failed(), null);
                return true;
            }
            ServerPlayer player = context.player();
            boolean structural = quest.location() instanceof QuestLocations.Structures;
            Map<String, String> vars = new LinkedHashMap<>(structural ? StructureFacts.vars(player, foundId, foundAt) : Map.of());
            StoryQuests.offer(player, quest, quest.location().kind(), foundId, foundAt, picks);   // the map marker waits for the player to accept
            vars.putAll(StoryQuests.questVars(player, quest));
            result = new Result(true, vars, spec.found(), structural ? foundId : null);
            return true;
        }

        @Override
        public void cancel() {
            job.cancel();
        }

        @Override
        public Result result() {
            return result;
        }

        @Override
        public boolean suppressesWaypoint() {
            return !spec.waypoint();
        }
    }

    /** A job that is already finished. */
    private record Finished(Result result, boolean quiet) implements Job {
        static Finished failed(LocateSpec spec) {
            return new Finished(new Result(false, Map.of(), spec.failed(), null), !spec.waypoint());
        }

        @Override public boolean poll(StoryContext context) { return true; }
        @Override public void cancel() {}
        @Override public boolean suppressesWaypoint() { return quiet; }
    }

    /**
     * One search. With a {@code sink} it serves a quest objective instead of a conversation: any structure of the target that
     * can generate here qualifies (the author chose it), and the hit goes to the sink rather than starting a quest offer.
     */
    static final class Search implements Job {
        private final BiConsumer<ResourceLocation, BlockPos> sink;
        private final ServerLevel level;
        private final LocateSpec spec;
        private final QuestDef quest;
        private final long startedAt = System.currentTimeMillis();
        private final LocatorSearchApi api;
        /** Structure IDs the search may return; null means any (general). */
        private final Set<ResourceLocation> candidates;
        private UUID searchId;
        private int attempts;
        private Result result;

        Search(ServerPlayer player, LocateSpec spec, @Nullable QuestDef quest) {
            this(player, spec, quest, null);
        }

        /** A search over a fixed candidate set (a quest or objective location); hits go to the sink. */
        Search(ServerPlayer player, int radiusBlocks, boolean waypoint, Set<ResourceLocation> candidates, BiConsumer<ResourceLocation, BlockPos> sink) {
            this(player, new LocateSpec(radiusBlocks, 0, LocateSpec.Mode.STRUCTURE, null, List.of(), null, null, null, waypoint), null, sink, candidates);
        }

        Search(ServerPlayer player, LocateSpec spec, @Nullable QuestDef quest, @Nullable BiConsumer<ResourceLocation, BlockPos> sink) {
            this(player, spec, quest, sink, null);
        }

        private Search(ServerPlayer player, LocateSpec spec, @Nullable QuestDef quest, @Nullable BiConsumer<ResourceLocation, BlockPos> sink,
                       @Nullable Set<ResourceLocation> given) {
            this.sink = sink;
            this.level = player.serverLevel();
            this.spec = spec;
            this.quest = quest;
            this.candidates = given != null ? given : candidates(player);
            LocatorSearchApi found = candidates != null && candidates.isEmpty() ? null
                : SuperiorServiceRegistry.getOptional(LocatorSearchApi.class).orElse(null);
            this.api = found;
            if (candidates != null && candidates.isEmpty()) {
                fail();   // nothing here can place in this dimension: no search at all
                return;
            }
            if (fromIndex(player)) return;
            if (spec.mode() == LocateSpec.Mode.GENERAL || api == null || !ModList.get().isLoaded("superior_locator")) {
                fail();
                return;
            }
            searchId = begin(player);
        }

        // ------------------------------------------------------------ candidates

        @Nullable
        private Set<ResourceLocation> candidates(ServerPlayer player) {
            Set<ResourceLocation> ids = new LinkedHashSet<>();
            switch (spec.mode()) {
                case GENERAL -> {
                    return null;
                }
                case STRUCTURE -> ids.addAll(select(spec.structure()));
                case POOL -> ids.addAll(StructurePools.members(level, spec.pools()));
                case QUEST -> {
                    return Set.of();   // quest locations search through their own module
                }
                case DEFAULT -> {
                    for (ResourceLocation id : level.registryAccess().registryOrThrow(Registries.STRUCTURE).keySet()) {
                        if (StructureProfiles.resolve(level, id) != StructureProfile.Merged.EMPTY) ids.add(id);
                    }
                }
            }
            // only a random offer (a pool or the derived pool) is limited to structures profiled for quests; an author who names a structure or a quest chose it
            boolean offered = sink == null && (spec.mode() == LocateSpec.Mode.POOL || spec.mode() == LocateSpec.Mode.DEFAULT);
            ids.removeIf(id -> !LocatorStructureDimensions.canPlace(level, id) || (offered && !StructureProfiles.resolve(level, id).quest()));
            return ids;
        }

        private Set<ResourceLocation> select(IdSelector selector) {
            Set<ResourceLocation> ids = new LinkedHashSet<>();
            for (ResourceLocation id : level.registryAccess().registryOrThrow(Registries.STRUCTURE).keySet()) {
                if (selector.match(id, StructureProfiles.tagTester(level, id)) > 0) ids.add(id);
            }
            return ids;
        }

        // ------------------------------------------------------------ index

        private boolean fromIndex(ServerPlayer player) {
            KnownStructureStartsApi index = SuperiorServiceRegistry.getOptional(KnownStructureStartsApi.class).orElse(null);
            if (index == null) return false;
            boolean general = spec.mode() == LocateSpec.Mode.GENERAL;
            Optional<KnownStructureStartsApi.KnownStart> start = index.nearest(level, player.blockPosition(),
                id -> (candidates == null ? StructureProfiles.resolve(level, id).quest() : candidates.contains(id)),
                spec.radiusBlocks(), known -> StoryQuests.sent(player, known.structureId(), known.center())
                    || (general && span(known.boundingBox()) < spec.minSize()));
            if (start.isEmpty()) return false;
            found(player, start.get().structureId(), start.get().center());
            return true;
        }

        private static int span(BoundingBox box) {
            return Math.max(box.getXSpan(), box.getZSpan());
        }

        // ------------------------------------------------------------ Locator search

        private UUID begin(ServerPlayer player) {
            LocatorQuery query = candidates.size() == 1
                ? new LocatorQuery.StructureQuery(candidates.iterator().next().toString(), LocatorMatchMode.EXACT_ID)
                : new LocatorQuery.StructureQuery("*", LocatorMatchMode.ANY);
            LocatorSearchRequest request = new LocatorSearchRequest(player.getUUID(), level.dimension(), player.blockPosition(),
                query, Math.max(1, spec.radiusBlocks() / 16), LocatorResultMode.NEAREST, new LocatorClientOptions(false, false, false));
            Map<String, String> attributes = new HashMap<>();
            attributes.put(LocatorSessionAttributes.SILENT, "true");
            if (candidates.size() > 1) {
                StringBuilder ids = new StringBuilder();
                for (ResourceLocation id : candidates) {
                    if (ids.length() > 0) ids.append(',');
                    ids.append(id);
                }
                attributes.put(LocatorSessionAttributes.ONLY_IDS, ids.toString());
            }
            SuperiorActionContext context = SuperiorActionContext.simple("superior_story", "superior_story:dialogue", "locate")
                .withAttributes(attributes);
            return api.start(level, request, context);
        }

        @Override
        public boolean poll(StoryContext context) {
            if (result != null) return true;
            ServerPlayer player = context.player();
            if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
                cancel();
                fail();
                return true;
            }
            Optional<LocatorProgressSnapshot> progress = api.progress(searchId);
            if (progress.isEmpty() || progress.get().cancelled()) return exhausted();
            if (!progress.get().complete()) return false;
            Optional<LocatorResult> found = api.currentResult(searchId);
            if (found.isEmpty()) return exhausted();
            ResourceLocation id = ResourceLocation.tryParse(found.get().matchedKey());
            if (id != null && candidates.contains(id) && !StoryQuests.sent(player, id, found.get().blockPos())) {
                found(player, id, found.get().blockPos());
                return true;
            }
            if (++attempts > MAX_ATTEMPTS) return exhausted();
            Optional<UUID> next = api.searchNext(searchId);
            if (next.isEmpty()) return exhausted();
            searchId = next.get();
            return false;
        }

        private boolean exhausted() {
            fail();
            return true;
        }

        private void found(ServerPlayer player, ResourceLocation structure, BlockPos position) {
            if (sink != null) {
                sink.accept(structure, position);
                result = new Result(true, Map.of(), null, structure);
                return;
            }
            Map<String, String> vars = new LinkedHashMap<>(StructureFacts.vars(player, structure, position));
            StoryQuests.offer(player, null, "structure", structure, position, List.of());   // the map marker waits for the player to accept
            result = new Result(true, vars, spec.found(), structure);
        }

        private void fail() {
            result = new Result(false, Map.of(), spec.failed(), null);
        }

        @Override
        public Result result() {
            return result;
        }

        @Override
        public void cancel() {
            if (api != null && searchId != null && result == null) api.cancel(searchId);
        }

        @Override
        public boolean suppressesWaypoint() {
            return !spec.waypoint();
        }
    }
}

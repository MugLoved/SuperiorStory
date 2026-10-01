package com.mugloved.superiorstory.server;

import com.google.gson.JsonElement;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.api.StoryHooks.LineKind.Result;
import com.mugloved.superiorstory.dialogue.IdSelector;
import com.mugloved.superiorstory.dialogue.Names;
import com.mugloved.superiorstory.dialogue.QuestDef;
import com.superior.lib.api.runtime.SuperiorActionContext;
import com.superior.lib.api.service.SuperiorServiceRegistry;
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
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** The built-in quest location kinds: {@code structure}, {@code pool}, and {@code biome}. */
public final class QuestLocations {
    private static final int VISIT_RANGE = 48;

    private QuestLocations() {}

    public static void register() {
        StoryHooks.registerLocation("structure", value -> new Structures("structure", QuestDef.selectors(value, "structure"), List.of()));
        StoryHooks.registerLocation("pool", value -> new Structures("pool", List.of(), QuestDef.ids(value, "pool")));
        StoryHooks.registerLocation("biome", QuestLocations::biome);
    }

    // ---------------------------------------------------------------- structures and pools

    /** Structures named by selectors or pools; searched through the known-structure index and Superior Locator. */
    public record Structures(String kind, List<IdSelector> selectors, List<ResourceLocation> pools) implements StoryHooks.Location {
        public Structures {
            selectors = List.copyOf(selectors);
            pools = List.copyOf(pools);
        }

        Set<ResourceLocation> candidates(ServerLevel level) {
            Set<ResourceLocation> ids = new LinkedHashSet<>();
            for (ResourceLocation id : level.registryAccess().registryOrThrow(Registries.STRUCTURE).keySet()) {
                for (IdSelector selector : selectors) {
                    if (selector.match(id, StructureProfiles.tagTester(level, id)) > 0) {
                        ids.add(id);
                        break;
                    }
                }
            }
            if (!pools.isEmpty()) ids.addAll(StructurePools.members(level, pools));
            ids.removeIf(id -> !LocatorStructureDimensions.canPlace(level, id));
            return ids;
        }

        @Override
        public boolean possibleIn(ServerLevel level) {
            return !candidates(level).isEmpty();
        }

        @Override
        public StoryHooks.LineKind.Job search(ServerPlayer player, int radiusBlocks, boolean waypoint, BiConsumer<ResourceLocation, BlockPos> sink) {
            return new LocateLine.Search(player, radiusBlocks, waypoint, candidates(player.serverLevel()), sink);
        }

        @Override
        public boolean arrived(ServerPlayer player, ResourceLocation found, BlockPos position) {
            return Math.hypot(player.getX() - position.getX(), player.getZ() - position.getZ()) <= VISIT_RANGE;
        }

        @Override
        public boolean credits(ServerLevel level, ResourceLocation found, BlockPos position, Vec3 at) {
            return StoryQuests.insideStructure(level, found, position, at);
        }

        @Override
        public String displayName(ServerLevel level, ResourceLocation found) {
            return StructureFacts.displayName(StructureProfiles.resolve(level, found), found);
        }

        @Nullable
        @Override
        public ResourceLocation exactStructure() {
            return pools.isEmpty() && selectors.size() == 1 && selectors.get(0).kind() == IdSelector.Kind.EXACT ? selectors.get(0).id() : null;
        }
    }

    // ---------------------------------------------------------------- biomes

    private static StoryHooks.Location biome(JsonElement value) {
        List<IdSelector> selectors = QuestDef.selectors(value, "biome");
        for (IdSelector selector : selectors) {
            if (selector.kind() == IdSelector.Kind.NAMESPACE) throw new IllegalArgumentException("biome takes biome IDs and #tags, not ns:*");
        }
        return new Biomes(selectors);
    }

    public static Biomes biomes(List<IdSelector> selectors) {
        return new Biomes(List.copyOf(selectors));
    }

    /** A biome by ID or tag: found with Superior Locator's biome search (or where the player already stands). */
    public record Biomes(List<IdSelector> selectors) implements StoryHooks.Location {
        @Override
        public String kind() {
            return "biome";
        }

        boolean matches(Holder<Biome> biome) {
            ResourceLocation key = biome.unwrapKey().map(k -> k.location()).orElse(null);
            if (key == null) return false;
            for (IdSelector selector : selectors) {
                if (selector.match(key, tag -> biome.is(TagKey.create(Registries.BIOME, tag))) > 0) return true;
            }
            return false;
        }

        /** Selectors with at least one biome the level's biome source can place. */
        List<IdSelector> possible(ServerLevel level) {
            Set<Holder<Biome>> placeable = level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
            List<IdSelector> out = new ArrayList<>();
            for (IdSelector selector : selectors) {
                for (Holder<Biome> biome : placeable) {
                    if (new Biomes(List.of(selector)).matches(biome)) {
                        out.add(selector);
                        break;
                    }
                }
            }
            return out;
        }

        @Override
        public boolean possibleIn(ServerLevel level) {
            return !possible(level).isEmpty();
        }

        @Override
        public StoryHooks.LineKind.Job search(ServerPlayer player, int radiusBlocks, boolean waypoint, BiConsumer<ResourceLocation, BlockPos> sink) {
            return new BiomeSearch(player, this, radiusBlocks, waypoint, sink);
        }

        @Override
        public boolean arrived(ServerPlayer player, ResourceLocation found, BlockPos position) {
            return matches(player.serverLevel().getBiome(player.blockPosition()));
        }

        @Override
        public boolean credits(ServerLevel level, ResourceLocation found, BlockPos position, Vec3 at) {
            return matches(level.getBiome(BlockPos.containing(at)));
        }

        @Override
        public String displayName(ServerLevel level, ResourceLocation found) {
            return Names.clean(found.getPath());
        }
    }

    /** Searches each placeable selector in turn until one is found; the player's own biome counts at once. */
    private static final class BiomeSearch implements StoryHooks.LineKind.Job {
        private static final long TIMEOUT_MS = 45_000L;
        private final ServerLevel level;
        private final BiConsumer<ResourceLocation, BlockPos> sink;
        private final boolean waypoint;
        private final int radiusBlocks;
        private final List<IdSelector> queue;
        private final LocatorSearchApi api;
        private final long startedAt = System.currentTimeMillis();
        private UUID searchId;
        private Result result;

        BiomeSearch(ServerPlayer player, Biomes location, int radiusBlocks, boolean waypoint, BiConsumer<ResourceLocation, BlockPos> sink) {
            this.level = player.serverLevel();
            this.sink = sink;
            this.waypoint = waypoint;
            this.radiusBlocks = radiusBlocks;
            this.queue = new ArrayList<>(location.possible(level));
            this.api = ModList.get().isLoaded("superior_locator") ? SuperiorServiceRegistry.getOptional(LocatorSearchApi.class).orElse(null) : null;
            Holder<Biome> here = level.getBiome(player.blockPosition());
            if (location.matches(here)) {
                found(here, player.blockPosition());
            } else if (queue.isEmpty() || api == null) {
                result = new Result(false, Map.of(), null, null);
            } else {
                next(player);
            }
        }

        private void next(ServerPlayer player) {
            IdSelector selector = queue.remove(0);
            LocatorQuery query = new LocatorQuery.BiomeQuery(selector.id().toString(),
                selector.kind() == IdSelector.Kind.TAG ? LocatorMatchMode.TAG_KEY : LocatorMatchMode.EXACT_ID);
            LocatorSearchRequest request = new LocatorSearchRequest(player.getUUID(), level.dimension(), player.blockPosition(),
                query, Math.max(1, radiusBlocks / 16), LocatorResultMode.NEAREST, new LocatorClientOptions(false, false, false));
            SuperiorActionContext context = SuperiorActionContext.simple("superior_story", "superior_story:dialogue", "locate")
                .withAttributes(Map.of(LocatorSessionAttributes.SILENT, "true"));
            searchId = api.start(level, request, context);
        }

        private void found(Holder<Biome> biome, BlockPos at) {
            ResourceLocation id = biome.unwrapKey().map(k -> k.location()).orElse(new ResourceLocation("minecraft", "plains"));
            sink.accept(id, at);
            result = new Result(true, Map.of(), null, null);
        }

        @Override
        public boolean poll(StoryContext context) {
            if (result != null) return true;
            if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
                cancel();
                result = new Result(false, Map.of(), null, null);
                return true;
            }
            Optional<LocatorProgressSnapshot> progress = api.progress(searchId);
            if (progress.isPresent() && !progress.get().cancelled() && !progress.get().complete()) return false;
            Optional<LocatorResult> hit = progress.isEmpty() || progress.get().cancelled() ? Optional.empty() : api.currentResult(searchId);
            if (hit.isPresent()) {
                found(level.getBiome(hit.get().blockPos()), hit.get().blockPos());
                return true;
            }
            if (queue.isEmpty()) {
                result = new Result(false, Map.of(), null, null);
                return true;
            }
            next(context.player());
            return false;
        }

        @Override
        public void cancel() {
            if (api != null && searchId != null && result == null) api.cancel(searchId);
        }

        @Override
        public Result result() {
            return result;
        }

        @Override
        public boolean suppressesWaypoint() {
            return !waypoint;
        }
    }
}

package com.mugloved.superiorstory.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.dialogue.IdSelector;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Structure pools ({@code data/<ns>/superiorstory/pools/*.json}); the file path is the pool ID. Pools merge exactly like
 * Minecraft tags: every datapack's file for the same ID contributes in pack priority order. A file appends its
 * {@code structures} (and {@code exclude}) to what lower packs defined; {@code "replace": true} first discards everything
 * lower packs defined; {@code exclude} applies after the merge. A profile file also defines a pool named by its path,
 * which is the lowest layer of the pool with that ID.
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StructurePools extends SimplePreparableReloadListener<Map<ResourceLocation, List<StructurePools.PoolFile>>> {
    private static final String FOLDER = "superiorstory/pools";
    private static final Gson GSON = new Gson();
    private static volatile Map<ResourceLocation, List<PoolFile>> files = Map.of();

    /** One datapack's file for a pool. */
    public record PoolFile(boolean replace, List<IdSelector> structures, List<IdSelector> exclude) {
        public PoolFile {
            structures = List.copyOf(structures);
            exclude = List.copyOf(exclude);
        }

        public static PoolFile parse(JsonElement element) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Pool must be an object");
            JsonObject object = element.getAsJsonObject();
            for (String key : object.keySet()) {
                if (!Set.of("replace", "structures", "exclude").contains(key)) throw new IllegalArgumentException("Unknown pool field: " + key);
            }
            boolean replace = false;
            if (object.has("replace")) {
                if (!object.get("replace").isJsonPrimitive() || !object.get("replace").getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("replace must be a boolean");
                }
                replace = object.get("replace").getAsBoolean();
            }
            return new PoolFile(replace, selectors(object, "structures"), selectors(object, "exclude"));
        }

        private static List<IdSelector> selectors(JsonObject object, String key) {
            if (!object.has(key)) return List.of();
            JsonElement value = object.get(key);
            List<IdSelector> out = new ArrayList<>();
            if (value.isJsonPrimitive()) {
                out.add(IdSelector.parse(value.getAsString()));
            } else if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                for (JsonElement entry : array) {
                    if (!entry.isJsonPrimitive()) throw new IllegalArgumentException(key + " entries must be strings");
                    out.add(IdSelector.parse(entry.getAsString()));
                }
            } else {
                throw new IllegalArgumentException(key + " must be a selector or an array of selectors");
            }
            return out;
        }
    }

    /** A merged pool: what it includes, less what it excludes. */
    public record Pool(List<IdSelector> include, List<IdSelector> exclude) {
        public Pool {
            include = List.copyOf(include);
            exclude = List.copyOf(exclude);
        }

        public boolean contains(ResourceLocation structure, Predicate<ResourceLocation> inTag) {
            boolean included = false;
            for (IdSelector selector : include) {
                if (selector.match(structure, inTag) > 0) {
                    included = true;
                    break;
                }
            }
            if (!included) return false;
            for (IdSelector selector : exclude) if (selector.match(structure, inTag) > 0) return false;
            return true;
        }
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new StructurePools());
    }

    @Override
    protected Map<ResourceLocation, List<PoolFile>> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, List<PoolFile>> out = new LinkedHashMap<>();
        Map<ResourceLocation, List<Resource>> stacks = manager.listResourceStacks(FOLDER, id -> id.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, List<Resource>> entry : stacks.entrySet()) {
            ResourceLocation file = entry.getKey();
            String path = file.getPath().substring(FOLDER.length() + 1, file.getPath().length() - ".json".length());
            ResourceLocation id = new ResourceLocation(file.getNamespace(), path);
            List<PoolFile> layers = new ArrayList<>();
            for (Resource resource : entry.getValue()) {   // lowest priority first
                try (Reader reader = resource.openAsReader()) {
                    layers.add(PoolFile.parse(JsonParser.parseReader(reader)));
                } catch (Exception exception) {
                    SuperiorStory.LOGGER.error("Invalid structure pool {} in {}: {}", id, resource.sourcePackId(), exception.getMessage());
                }
            }
            if (!layers.isEmpty()) out.put(id, layers);
        }
        return out;
    }

    @Override
    protected void apply(Map<ResourceLocation, List<PoolFile>> prepared, ResourceManager manager, ProfilerFiller profiler) {
        files = Map.copyOf(prepared);
        SuperiorStory.LOGGER.info("Loaded {} structure pool file set(s) from datapacks", files.size());
    }

    /** The merged pool, or null when no profile file and no pool file defines this ID. */
    @Nullable
    public static Pool pool(ResourceLocation id) {
        return merge(StructureProfiles.derivedPools().get(id), files.get(id));
    }

    /** Layering rules of a pool: derived profile layer first, then each pack's file from lowest to highest priority. */
    public static Pool merge(@Nullable List<IdSelector> derived, @Nullable List<PoolFile> layers) {
        if (derived == null && (layers == null || layers.isEmpty())) return null;
        List<IdSelector> include = new ArrayList<>(derived == null ? List.of() : derived);
        List<IdSelector> exclude = new ArrayList<>();
        if (layers != null) {
            for (PoolFile layer : layers) {
                if (layer.replace()) {
                    include.clear();
                    exclude.clear();
                }
                include.addAll(layer.structures());
                exclude.addAll(layer.exclude());
            }
        }
        return new Pool(include, exclude);
    }

    /**
     * The registered structure IDs that belong to any of these pools. An unknown pool is logged and contributes nothing.
     */
    public static Set<ResourceLocation> members(ServerLevel level, List<ResourceLocation> poolIds) {
        List<Pool> pools = new ArrayList<>();
        for (ResourceLocation id : poolIds) {
            Pool pool = pool(id);
            if (pool == null) SuperiorStory.LOGGER.warn("Unknown structure pool {}", id);
            else pools.add(pool);
        }
        Set<ResourceLocation> members = new LinkedHashSet<>();
        if (pools.isEmpty()) return members;
        for (ResourceLocation structure : level.registryAccess().registryOrThrow(Registries.STRUCTURE).keySet()) {
            var inTag = StructureProfiles.tagTester(level, structure);
            for (Pool pool : pools) {
                if (pool.contains(structure, inTag)) {
                    members.add(structure);
                    break;
                }
            }
        }
        return members;
    }
}

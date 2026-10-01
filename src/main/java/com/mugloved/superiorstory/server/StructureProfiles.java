package com.mugloved.superiorstory.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.dialogue.IdSelector;
import com.mugloved.superiorstory.dialogue.StructureProfile;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Datapack structure profiles ({@code data/<ns>/superiorstory/structures/*.json}), merged per structure on lookup.
 * Every profile file also defines a pool named by its path (see {@link StructurePools}).
 */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StructureProfiles extends SimpleJsonResourceReloadListener {
    private static volatile List<StructureProfile> profiles = List.of();
    private static volatile Map<ResourceLocation, List<IdSelector>> derivedPools = Map.of();
    private static volatile Set<ResourceLocation> bosses = Set.of();

    public StructureProfiles() {
        super(new Gson(), "superiorstory/structures");
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new StructureProfiles());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        List<StructureProfile> loaded = new ArrayList<>();
        Map<ResourceLocation, List<IdSelector>> pools = new LinkedHashMap<>();
        Set<ResourceLocation> allBosses = new LinkedHashSet<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString))).forEach(entry -> {
            try {
                List<StructureProfile> file = StructureProfile.parseFile(entry.getValue());
                loaded.addAll(file);
                List<IdSelector> selectors = new ArrayList<>();
                for (StructureProfile profile : file) {
                    selectors.addAll(profile.structures());
                    allBosses.addAll(profile.bosses());
                }
                pools.put(entry.getKey(), List.copyOf(selectors));
            } catch (RuntimeException exception) {
                SuperiorStory.LOGGER.error("Invalid structure profile {}: {}", entry.getKey(), exception.getMessage());
            }
        });
        profiles = List.copyOf(loaded);
        derivedPools = Map.copyOf(pools);
        bosses = Set.copyOf(allBosses);
        QuestDefinitions.invalidate();
        SuperiorStory.LOGGER.info("Loaded {} structure profile(s) from datapacks", profiles.size());
    }

    /** The merged profile for one structure; an unprofiled structure gets the empty profile and still works. */
    public static StructureProfile.Merged resolve(ServerLevel level, ResourceLocation structureId) {
        return StructureProfile.merge(profiles, structureId, tagTester(level, structureId));
    }

    /** Bosses for one exact structure ID; needs no level because exact selectors need no tag lookup. */
    static List<ResourceLocation> bossesOf(ResourceLocation structureId) {
        return StructureProfile.merge(profiles, structureId, tag -> false).bosses();
    }

    /** Every boss any profile lists. */
    static Set<ResourceLocation> allBosses() {
        return bosses;
    }

    /** The pool each profile file defines, keyed by the file's ID. */
    static Map<ResourceLocation, List<IdSelector>> derivedPools() {
        return derivedPools;
    }

    /** What a boss looks like and where it lives, gathered from every profile that lists it; sent to clients for the boss hover. */
    public record BossFact(String look, String lore, List<String> lairs) {}

    /** Facts per boss entity, from profiles whose exact structure IDs list the boss. Tag and namespace selectors add no lair names. */
    static Map<ResourceLocation, BossFact> bossFacts() {
        Map<ResourceLocation, String> looks = new LinkedHashMap<>();
        Map<ResourceLocation, String> lores = new LinkedHashMap<>();
        Map<ResourceLocation, Set<String>> lairs = new LinkedHashMap<>();
        for (StructureProfile profile : profiles) {
            for (IdSelector selector : profile.structures()) {
                if (selector.kind() != IdSelector.Kind.EXACT) continue;
                StructureProfile.Merged merged = StructureProfile.merge(profiles, selector.id(), tag -> false);
                String lair = merged.name() != null ? merged.name() : com.mugloved.superiorstory.dialogue.Names.clean(selector.id().getPath());
                for (ResourceLocation boss : merged.bosses()) {
                    lairs.computeIfAbsent(boss, key -> new LinkedHashSet<>()).add(lair);
                    if (merged.look() != null) looks.putIfAbsent(boss, merged.look());
                    if (merged.lore() != null) lores.putIfAbsent(boss, merged.lore());
                }
            }
        }
        Map<ResourceLocation, BossFact> facts = new LinkedHashMap<>();
        for (var entry : lairs.entrySet()) {
            facts.put(entry.getKey(), new BossFact(looks.getOrDefault(entry.getKey(), ""), lores.getOrDefault(entry.getKey(), ""),
                List.copyOf(entry.getValue())));
        }
        return facts;
    }

    /** Whether the structure belongs to a structure tag, for selectors like {@code #ns:tag}. */
    public static Predicate<ResourceLocation> tagTester(ServerLevel level, ResourceLocation structureId) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Holder<Structure> holder = registry.getHolder(ResourceKey.create(Registries.STRUCTURE, structureId)).orElse(null);
        return tag -> holder != null && holder.is(TagKey.create(Registries.STRUCTURE, tag));
    }
}

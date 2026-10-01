package com.mugloved.superiorstory.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.dialogue.Dialogue;
import com.mugloved.superiorstory.scene.StoryScene;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Server datapacks are the one authoritative source for authored scenes. */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StorySceneLoader extends SimpleJsonResourceReloadListener {
    private static volatile Map<ResourceLocation, StoryScene> scenes = Map.of();
    private static volatile Map<ResourceLocation, Dialogue> dialogues = Map.of();
    private static volatile ResourceLocation firstJoinId;
    private LinePools.Catalogue preparedPools;

    public StorySceneLoader() {
        super(new Gson(), "superiorstory/scenes");
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new StorySceneLoader());
    }

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler) {
        preparedPools = LinePools.load(manager);
        return super.prepare(manager, profiler);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        LinePools.install(preparedPools);
        Map<ResourceLocation, StoryScene> loaded = new LinkedHashMap<>();
        Map<ResourceLocation, Dialogue> loadedDialogues = new LinkedHashMap<>();
        resources.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .forEach(entry -> {
                try {
                    if (!Dialogue.modsLoaded(entry.getValue(), mod -> net.minecraftforge.fml.ModList.get().isLoaded(mod))) return;
                    if (entry.getKey().equals(StoryServer.HARDCODED_INTRO_ID)) {
                        throw new IllegalArgumentException("Reserved hardcoded scene ID");
                    }
                    if (Dialogue.isDialogue(entry.getValue())) {
                        Dialogue dialogue = Dialogue.parse(entry.getKey(), entry.getValue());
                        dialogue.validateTexts();
                        loadedDialogues.put(entry.getKey(), dialogue);
                        var unreachable = dialogue.unreachableBranches();
                        if (!unreachable.isEmpty()) {
                            SuperiorStory.LOGGER.warn("Dialogue {} has unreachable branches {}", entry.getKey(), unreachable);
                        }
                    } else {
                        StoryScene scene = StoryScene.parse(entry.getKey(), entry.getValue());
                        for (int i = 0; i < scene.beats().size(); i++) {
                            var beat = scene.beats().get(i);
                            com.mugloved.superiorstory.api.StoryHooks.validateText(beat.text(), scene.id().getNamespace(), "beats[" + i + "].text");
                            for (var choice : beat.choices()) com.mugloved.superiorstory.api.StoryHooks.validateText(choice, scene.id().getNamespace(), "beats[" + i + "].choices");
                        }
                        loaded.put(entry.getKey(), scene);
                    }
                } catch (RuntimeException exception) {
                    SuperiorStory.LOGGER.error("Invalid story scene data/{}/superiorstory/scenes/{}.json: {}", entry.getKey().getNamespace(), entry.getKey().getPath(), exception.getMessage());
                }
            });
        var firstJoinScenes = loaded.values().stream().filter(StoryScene::firstJoin).map(StoryScene::id).toList();
        if (firstJoinScenes.size() > 1) {
            SuperiorStory.LOGGER.error("Multiple first_join story scenes {}; keeping the hardcoded first-join intro", firstJoinScenes);
        }
        firstJoinId = firstJoinScenes.size() == 1 ? firstJoinScenes.get(0) : null;
        scenes = Map.copyOf(loaded);
        dialogues = Map.copyOf(loadedDialogues);
        SuperiorStory.LOGGER.info("Loaded {} story scene(s) and {} dialogue(s) from datapacks", scenes.size(), dialogues.size());
    }

    public static StoryScene get(ResourceLocation id) {
        return scenes.get(id);
    }

    public static StoryScene firstJoin() {
        ResourceLocation id = firstJoinId;
        return id == null ? null : scenes.get(id);
    }

    /** A loaded dialogue by ID, or null. */
    public static Dialogue dialogue(ResourceLocation id) {
        return dialogues.get(id);
    }

    public static Collection<Dialogue> dialogues() {
        return dialogues.values();
    }

    public static Set<ResourceLocation> ids() {
        return scenes.keySet();
    }
}

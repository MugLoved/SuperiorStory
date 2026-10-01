package com.mugloved.superiorstory.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.dialogue.RewardPool;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Datapack reward pools ({@code data/<ns>/superiorstory/reward_pools/*.json}); the file path is the pool ID. */
@Mod.EventBusSubscriber(modid = SuperiorStory.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RewardPools extends SimpleJsonResourceReloadListener {
    private static volatile Map<ResourceLocation, RewardPool> pools = Map.of();

    public RewardPools() {
        super(new Gson(), "superiorstory/reward_pools");
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new RewardPools());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, RewardPool> loaded = new LinkedHashMap<>();
        resources.forEach((id, json) -> {
            try {
                loaded.put(id, RewardPool.parse(json));
            } catch (RuntimeException exception) {
                SuperiorStory.LOGGER.error("Invalid reward pool {}: {}", id, exception.getMessage());
            }
        });
        pools = Map.copyOf(loaded);
        SuperiorStory.LOGGER.info("Loaded {} reward pool(s)", pools.size());
    }

    @Nullable
    public static RewardPool get(ResourceLocation id) {
        return pools.get(id);
    }
}

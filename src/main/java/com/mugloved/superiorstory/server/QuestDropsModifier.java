package com.mugloved.superiorstory.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.dialogue.QuestDef;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Adds each quest's item to the loot of its entity through Minecraft's own loot pipeline. It always drops, for every
 * player, whether or not they hold the quest, so a rare structure's boss killed early never blocks progression. A global
 * loot modifier is used instead of editing loot tables at load because loot tables load before Story's quest data in the
 * same reload; this one reads the quests when the loot actually rolls. Registered once by Story's bundled
 * {@code forge/loot_modifiers/global_loot_modifiers.json}.
 */
public final class QuestDropsModifier extends LootModifier {
    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> REGISTER =
        DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, SuperiorStory.MODID);
    public static final RegistryObject<Codec<QuestDropsModifier>> CODEC = REGISTER.register("quest_drops",
        () -> RecordCodecBuilder.create(instance -> codecStart(instance).apply(instance, QuestDropsModifier::new)));

    public QuestDropsModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        Entity entity = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        if (entity == null || !context.hasParam(LootContextParams.DAMAGE_SOURCE)) return loot;   // only an entity's death loot
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        for (QuestDef quest : QuestDefinitions.dropsFor(type)) {
            if (quest.chance() < 1f && context.getRandom().nextFloat() >= quest.chance()) continue;
            ItemStack stack = quest.fixedItem().stack();
            if (stack.isEmpty()) continue;
            int left = stack.getCount();
            while (left > 0) {
                ItemStack part = stack.copyWithCount(Math.min(left, stack.getMaxStackSize()));
                left -= part.getCount();
                loot.add(part);
            }
        }
        return loot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}

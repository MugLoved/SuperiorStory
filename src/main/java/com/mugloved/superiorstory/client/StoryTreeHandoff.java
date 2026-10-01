package com.mugloved.superiorstory.client;

import com.mugloved.superiorstory.SuperiorStory;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;

import java.util.Optional;

/** One Story handoff flow with only the tree owner's opening call selected at the boundary. */
final class StoryTreeHandoff {
    private enum Target { SUPERIOR, PUFFISH, NONE }

    private StoryTreeHandoff() {}

    static boolean available() {
        return target() != Target.NONE;
    }

    static KeyMapping boundKey() {
        final String name = switch (target()) {
            case SUPERIOR -> "key.display_skill_tree";
            case PUFFISH -> "key.puffish_skills.open";
            case NONE -> null;
        };
        if (name == null) return null;
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            if (name.equals(mapping.getName())) return mapping.isUnbound() ? null : mapping;
        }
        return null;
    }

    static boolean open() {
        try {
            return switch (target()) {
                case SUPERIOR -> SuperiorBridge.open();
                case PUFFISH -> PuffishBridge.open();
                case NONE -> false;
            };
        } catch (RuntimeException | LinkageError exception) {
            SuperiorStory.LOGGER.error("Unable to open installed Skill Tree", exception);
            return false;
        }
    }

    private static Target target() {
        final ModList mods = ModList.get();
        if (mods.isLoaded("superior_skill_tree")) return Target.SUPERIOR;
        if (mods.isLoaded("puffish_skills")) return Target.PUFFISH;
        return Target.NONE;
    }

    private static final class SuperiorBridge {
        static boolean open() {
            return com.alexh.superiorskilltree.client.skilltree.SkillTreeClientOpen.openSelection();
        }
    }

    private static final class PuffishBridge {
        static boolean open() {
            try {
                final Minecraft minecraft = Minecraft.getInstance();
                final var previousScreen = minecraft.screen;
                final Class<?> client = Class.forName("net.puffish.skillsmod.client.SkillsClientMod");
                final Object instance = client.getMethod("getInstance").invoke(null);
                client.getMethod("openScreen", Optional.class).invoke(instance, Optional.empty());
                return minecraft.screen != null && minecraft.screen != previousScreen;
            } catch (ReflectiveOperationException exception) {
                SuperiorStory.LOGGER.error("Puffish Skills client opening API unavailable", exception);
                return false;
            }
        }
    }
}

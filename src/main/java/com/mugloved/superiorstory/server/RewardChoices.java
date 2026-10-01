package com.mugloved.superiorstory.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.api.StoryBlocked;
import com.mugloved.superiorstory.api.StoryContext;
import com.mugloved.superiorstory.api.StoryHooks;
import com.mugloved.superiorstory.dialogue.Act;
import com.mugloved.superiorstory.dialogue.Dialogue;
import com.mugloved.superiorstory.dialogue.RewardPool;
import com.mugloved.superiorstory.dialogue.Vars;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Choose-your-reward: the {@code reward_choice} action (on a quest {@code reward} or a choice) rolls a few entries of a
 * {@link RewardPool} and stores them as a pending offer in the player's persisted data; the engine then shows the bundled
 * {@code superiorstory:reward_choice} dialogue, whose rows run {@code reward_pick}, until every pick is made. An offer that
 * finds the player outside a conversation waits and opens as narration when they are free.
 */
public final class RewardChoices {
    public static final ResourceLocation DIALOGUE = new ResourceLocation(SuperiorStory.MODID, "reward_choice");
    private static final String KEY = "superiorstory_reward";
    private static final String HISTORY = "superiorstory_reward_history";
    private static final int MAX = Dialogue.MAX_CHOICES;

    private RewardChoices() {}

    public static void register() {
        StoryHooks.registerTrigger("reward_template", new StoryHooks.Trigger() {
            @Override public Object compile(JsonElement value, String where) { return Boolean.TRUE; }
            @Override public boolean matches(Object spec, Object payload) { return false; }   // marks the dialogue the engine opens for a pending offer
        });
        StoryHooks.registerAction("reward_choice", value -> {
            Spec spec = Spec.parse(value);
            return context -> offer(context, spec);
        });
        StoryHooks.registerAction("reward_pick", value -> {
            int n = option(value, "reward_pick");
            return context -> pick(context, n);
        });
        StoryHooks.registerCondition("reward_option", value -> {
            int n = option(value, "reward_option");
            return context -> {
                CompoundTag head = head(context.player());
                return head != null && head.getIntArray("options").length >= n;
            };
        });
    }

    /** Offer {@code count} entries, take {@code pick}; optional {@code fresh} avoids the previous offer for this pool. */
    private record Spec(ResourceLocation pool, int count, int pick, boolean fresh) {
        static Spec parse(JsonElement value) {
            if (value.isJsonPrimitive()) return new Spec(pool(value), 3, 1, false);
            if (!value.isJsonObject()) throw new IllegalArgumentException("reward_choice must be a pool ID or an object");
            JsonObject object = value.getAsJsonObject();
            for (String key : object.keySet()) if (!Set.of("pool", "count", "pick", "fresh").contains(key)) throw new IllegalArgumentException("Unknown reward_choice field: " + key);
            if (!object.has("pool")) throw new IllegalArgumentException("reward_choice needs a pool");
            int count = object.has("count") ? number(object.get("count"), "count") : 3;
            int pick = object.has("pick") ? number(object.get("pick"), "pick") : 1;
            if (count < 1 || count > MAX) throw new IllegalArgumentException("count must be 1-" + MAX);
            if (pick < 1 || pick > count) throw new IllegalArgumentException("pick must be 1 to count");
            if (object.has("fresh") && (!object.get("fresh").isJsonPrimitive() || !object.get("fresh").getAsJsonPrimitive().isBoolean())) {
                throw new IllegalArgumentException("fresh must be a boolean");
            }
            return new Spec(pool(object.get("pool")), count, pick, object.has("fresh") && object.get("fresh").getAsBoolean());
        }

        private static ResourceLocation pool(JsonElement element) {
            ResourceLocation id = element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? ResourceLocation.tryParse(element.getAsString()) : null;
            if (id == null) throw new IllegalArgumentException("pool must be a reward pool ID like ns:name");
            return id;
        }

        private static int number(JsonElement element, String key) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be a whole number");
            return element.getAsInt();
        }
    }

    private static int option(JsonElement value, String key) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || value.getAsInt() < 1 || value.getAsInt() > MAX) {
            throw new IllegalArgumentException(key + " must be a row number 1-" + MAX);
        }
        return value.getAsInt();
    }

    // ---------------------------------------------------------------- offers

    private static ListTag offers(ServerPlayer player) {
        CompoundTag data = StoryServer.persisted(player);
        if (!data.contains(KEY, Tag.TAG_LIST)) data.put(KEY, new ListTag());
        return data.getList(KEY, Tag.TAG_COMPOUND);
    }

    @Nullable
    private static CompoundTag head(ServerPlayer player) {
        ListTag offers = offers(player);
        return offers.isEmpty() ? null : offers.getCompound(0);
    }

    public static boolean pending(ServerPlayer player) {
        return !offers(player).isEmpty();
    }

    private static void offer(StoryContext context, Spec spec) {
        RewardPool pool = RewardPools.get(spec.pool());
        if (pool == null) {
            SuperiorStory.LOGGER.warn("reward_choice references unknown reward pool {}", spec.pool());
            return;
        }
        ServerPlayer player = context.player();
        List<Integer> eligible = new ArrayList<>();
        for (int i = 0; i < pool.entries().size(); i++) if (pool.entries().get(i).guard().passes(context)) eligible.add(i);
        if (eligible.isEmpty()) return;   // nothing in the pool applies to this player right now
        int count = Math.min(spec.count(), eligible.size());
        CompoundTag data = StoryServer.persisted(player);
        CompoundTag history = data.getCompound(HISTORY);
        String poolId = spec.pool().toString();
        List<Integer> chosen = roll(pool, eligible, spec.fresh() ? history.getIntArray(poolId) : new int[0], count, player.getRandom());
        CompoundTag offer = new CompoundTag();
        offer.putString("pool", spec.pool().toString());
        offer.putString("source", context.sourceId() == null ? "" : context.sourceId());
        offer.putInt("total", Math.min(spec.pick(), count));
        offer.putInt("left", Math.min(spec.pick(), count));
        offer.putIntArray("options", chosen);
        offers(player).add(offer);
        if (spec.fresh()) {
            history.putIntArray(poolId, chosen);
            data.put(HISTORY, history);
        }
    }

    /** Shared weighted selection used by every reward offer; the caller supplies the pool's guarded entries. */
    static List<Integer> roll(RewardPool pool, List<Integer> eligible, int[] previous, int count, RandomSource random) {
        eligible = new ArrayList<>(eligible);
        excludePrevious(eligible, previous, count);
        List<Integer> chosen = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            int total = 0;
            for (int index : eligible) total += pool.entries().get(index).weight();
            int roll = random.nextInt(total);
            for (int j = 0; j < eligible.size(); j++) {
                roll -= pool.entries().get(eligible.get(j)).weight();
                if (roll < 0) {
                    chosen.add(eligible.remove(j));
                    break;
                }
            }
        }
        return chosen;
    }

    /** Avoid every previous option when possible; small or newly gated pools reuse only the minimum needed. */
    private static void excludePrevious(List<Integer> eligible, int[] previous, int count) {
        List<Integer> repeated = new ArrayList<>();
        for (int index : previous) if (eligible.remove(Integer.valueOf(index))) repeated.add(index);
        for (int index : repeated) {
            if (eligible.size() >= count) break;
            eligible.add(index);
        }
    }

    /** Sets the variables the choice rows read: {@code reward_1..n} labels, {@code reward_prompt}, and {@code reward_picks} left. */
    public static void fillVars(ServerPlayer player, Map<String, String> vars) {
        for (int i = 1; i <= MAX; i++) vars.remove("reward_" + i);
        CompoundTag head = head(player);
        if (head == null) return;
        RewardPool pool = RewardPools.get(new ResourceLocation(head.getString("pool")));
        int[] options = head.getIntArray("options");
        for (int i = 0; i < options.length && pool != null; i++) {
            RewardPool.Entry entry = options[i] < pool.entries().size() ? pool.entries().get(options[i]) : null;
            String label = entry == null ? null : entry.label();
            vars.put("reward_" + (i + 1), label != null ? label : Vars.LANG + "superiorstory.reward.unnamed");
        }
        int left = head.getInt("left");
        vars.put("reward_picks", String.valueOf(left));
        vars.put("reward_prompt", Vars.LANG + (left == head.getInt("total") ? "superiorstory.reward.choose" : "superiorstory.reward.more"));
    }

    private static void pick(StoryContext context, int n) {
        ServerPlayer player = context.player();
        ListTag offers = offers(player);
        CompoundTag head = offers.isEmpty() ? null : offers.getCompound(0);
        if (head == null) return;
        int[] options = head.getIntArray("options");
        if (n > options.length) return;
        RewardPool pool = RewardPools.get(new ResourceLocation(head.getString("pool")));
        int index = options[n - 1];
        int[] rest = new int[options.length - 1];
        for (int i = 0, j = 0; i < options.length; i++) if (i != n - 1) rest[j++] = options[i];
        head.putIntArray("options", rest);
        head.putInt("left", head.getInt("left") - 1);
        String source = head.getString("source");
        String poolId = head.getString("pool");
        if (head.getInt("left") <= 0 || rest.length == 0) offers.remove(0);
        if (pool == null || index >= pool.entries().size()) {
            SuperiorStory.LOGGER.warn("Reward pool {} changed while an offer was pending; the pick is dropped", poolId);
            return;
        }
        StoryContext paying = source.isEmpty() ? context : context.withSource(source);
        for (Act act : pool.entries().get(index).actions()) {
            try {
                act.action().run(paying);
            } catch (StoryBlocked blocked) {
                throw blocked;
            } catch (RuntimeException exception) {
                SuperiorStory.LOGGER.error("Reward pool {} entry {} action {} failed", poolId, index, act.key(), exception);
            }
        }
    }
}

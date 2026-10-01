package com.mugloved.superiorstory.network;

import com.mugloved.superiorstory.client.DialogueClient;
import com.mugloved.superiorstory.dialogue.Vars;
import com.mugloved.superiorstory.scene.StoryScene.Text;
import com.mugloved.superiorstory.server.DialogueServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * NPC dialogue wire format. The server sends one line at a time, so locked branches and hidden choices
 * never reach the client; the client answers with the index of a visible choice.
 */
public final class DialoguePackets {
    public static final int CONTINUE = -1;
    public static final int LEAVE = -2;
    /** Who is talking: nobody (narration), an entity, or a block. */
    public static final int NONE = 0;
    public static final int ENTITY = 1;
    public static final int BLOCK = 2;
    /** Waypoint height meaning "unknown". */
    public static final int NO_Y = Integer.MIN_VALUE;
    private static final int MAX_CHOICES = 4;
    private static final int MAX_ITEMS = 4;

    private DialoguePackets() {}

    static void register(SimpleChannel channel) {
        channel.messageBuilder(Talk.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Talk::encode).decoder(Talk::decode).consumerMainThread(Talk::handle).add();
        channel.messageBuilder(Talkable.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Talkable::encode).decoder(Talkable::decode).consumerMainThread(Talkable::handle).add();
        channel.messageBuilder(Node.class, 4, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Node::encode).decoder(Node::decode).consumerMainThread(Node::handle).add();
        channel.messageBuilder(Advance.class, 5, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Advance::encode).decoder(Advance::decode).consumerMainThread(Advance::handle).add();
        channel.messageBuilder(Waypoint.class, 6, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Waypoint::encode).decoder(Waypoint::decode).consumerMainThread(Waypoint::handle).add();
        channel.messageBuilder(Bosses.class, 7, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Bosses::encode).decoder(Bosses::decode).consumerMainThread(Bosses::handle).add();
        channel.messageBuilder(Markers.class, 8, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Markers::encode).decoder(Markers::decode).consumerMainThread(Markers::handle).add();
    }

    public static void sendToServer(Object message) {
        StoryNetwork.CHANNEL.sendToServer(message);
    }

    public static void sendToClient(ServerPlayer player, Object message) {
        StoryNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** What the crosshair is on: an entity ID or a block position, depending on {@code kind}. */
    public record Target(int kind, int entityId, BlockPos pos) {
        public static final Target NOTHING = new Target(NONE, -1, BlockPos.ZERO);

        public static Target entity(int id) { return new Target(ENTITY, id, BlockPos.ZERO); }
        public static Target block(BlockPos pos) { return new Target(BLOCK, -1, pos.immutable()); }

        void write(FriendlyByteBuf buf) {
            buf.writeByte(kind);
            if (kind == ENTITY) buf.writeVarInt(entityId);
            else if (kind == BLOCK) buf.writeBlockPos(pos);
        }

        static Target read(FriendlyByteBuf buf) {
            int kind = buf.readByte();
            if (kind == ENTITY) return entity(buf.readVarInt());
            if (kind == BLOCK) return block(buf.readBlockPos());
            return NOTHING;
        }
    }

    /** Client -> server. {@code probe} only asks whether the target can be talked to. */
    public record Talk(Target target, boolean probe) {
        static void encode(Talk msg, FriendlyByteBuf buf) {
            msg.target.write(buf);
            buf.writeBoolean(msg.probe);
        }
        static Talk decode(FriendlyByteBuf buf) {
            return new Talk(Target.read(buf), buf.readBoolean());
        }
        static void handle(Talk msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) DialogueServer.onTalk(player, msg.target, msg.probe);
        }
    }

    /** Server -> client answer to a probe. */
    public record Talkable(Target target, boolean talkable) {
        static void encode(Talkable msg, FriendlyByteBuf buf) {
            msg.target.write(buf);
            buf.writeBoolean(msg.talkable);
        }
        static Talkable decode(FriendlyByteBuf buf) {
            return new Talkable(Target.read(buf), buf.readBoolean());
        }
        static void handle(Talkable msg, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DialogueClient.onTalkable(msg));
        }
    }

    /** Server -> client: entity ID to marker kind ({@code 1} "?" it can talk, {@code 2} "!" talking would progress a quest) for the mobs near the player. */
    public record Markers(Map<Integer, Byte> kinds) {
        static void encode(Markers msg, FriendlyByteBuf buf) {
            buf.writeMap(msg.kinds, FriendlyByteBuf::writeVarInt, (out, kind) -> out.writeByte(kind));
        }
        static Markers decode(FriendlyByteBuf buf) {
            return new Markers(buf.readMap(FriendlyByteBuf::readVarInt, FriendlyByteBuf::readByte));
        }
        static void handle(Markers msg, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mugloved.superiorstory.client.StoryMarkersClient.onMarkers(msg.kinds));
        }
    }

    /** An item a choice takes or uses, shown on its row. */
    public record ItemShow(ResourceLocation item, int quantity) {}

    /** One choice row: its label and the items it shows. */
    public record ChoiceView(Text label, List<ItemShow> items) {
        public ChoiceView(Text label) {
            this(label, List.of());
        }
    }

    /**
     * Server -> client. {@code open} is the first line of a conversation; {@code end} closes it
     * (text, speaker, and choices are then absent). {@code busy} holds the continue marker while the server works
     * (locating a structure); the same line is re-sent with the same serial, {@code busy} cleared, and the final
     * {@code vars} when the work is done. Placeholders in text are filled in from {@code vars} on the client.
     * {@code speaker} selects entity, block, or narration; {@code npcId} is the entity ID and {@code blockPos} the
     * block position for those kinds.
     */
    public record Node(long sessionId, int serial, int speakerKind, int npcId, BlockPos blockPos, boolean open, boolean end,
                       @Nullable Text speaker, Text text, boolean accent, float pace, List<ChoiceView> choices, boolean busy,
                       Map<String, String> vars) {
        private static final Text NONE_TEXT = Text.literal("-");

        public static Node closing(long sessionId, int serial, int speakerKind, int npcId, BlockPos blockPos) {
            return new Node(sessionId, serial, speakerKind, npcId, blockPos, false, true, null, NONE_TEXT, false, 1f, List.of(), false, Map.of());
        }

        public Node updated(boolean busy, Map<String, String> vars) {
            return new Node(sessionId, serial, speakerKind, npcId, blockPos, false, false, speaker, text, accent, pace, choices, busy, Map.copyOf(vars));
        }

        static void encode(Node msg, FriendlyByteBuf buf) {
            buf.writeVarLong(msg.sessionId);
            buf.writeVarInt(msg.serial);
            buf.writeByte(msg.speakerKind);
            buf.writeVarInt(msg.npcId);
            buf.writeBlockPos(msg.blockPos);
            buf.writeBoolean(msg.open);
            buf.writeBoolean(msg.end);
            if (msg.end) return;
            buf.writeBoolean(msg.speaker != null);
            if (msg.speaker != null) msg.speaker.write(buf);
            msg.text.write(buf);
            buf.writeBoolean(msg.accent);
            buf.writeFloat(msg.pace);
            buf.writeVarInt(msg.choices.size());
            for (ChoiceView choice : msg.choices) {
                choice.label().write(buf);
                buf.writeVarInt(choice.items().size());
                for (ItemShow item : choice.items()) {
                    buf.writeResourceLocation(item.item());
                    buf.writeVarInt(item.quantity());
                }
            }
            buf.writeBoolean(msg.busy);
            buf.writeVarInt(msg.vars.size());
            for (Map.Entry<String, String> var : msg.vars.entrySet()) {
                buf.writeUtf(var.getKey(), 32);
                buf.writeUtf(var.getValue(), Vars.MAX_VALUE);
            }
        }

        static Node decode(FriendlyByteBuf buf) {
            long sessionId = buf.readVarLong();
            int serial = buf.readVarInt();
            int kind = buf.readByte();
            int npcId = buf.readVarInt();
            BlockPos blockPos = buf.readBlockPos();
            boolean open = buf.readBoolean();
            if (buf.readBoolean()) return closing(sessionId, serial, kind, npcId, blockPos);
            Text speaker = buf.readBoolean() ? Text.read(buf) : null;
            Text text = Text.read(buf);
            boolean accent = buf.readBoolean();
            float pace = buf.readFloat();
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_CHOICES) throw new IllegalArgumentException("Invalid dialogue choice count");
            List<ChoiceView> choices = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Text label = Text.read(buf);
                int itemCount = buf.readVarInt();
                if (itemCount < 0 || itemCount > MAX_ITEMS) throw new IllegalArgumentException("Invalid dialogue item count");
                List<ItemShow> items = new ArrayList<>(itemCount);
                for (int j = 0; j < itemCount; j++) items.add(new ItemShow(buf.readResourceLocation(), buf.readVarInt()));
                choices.add(new ChoiceView(label, items));
            }
            boolean busy = buf.readBoolean();
            int varCount = buf.readVarInt();
            if (varCount < 0 || varCount > Vars.MAX_VARS) throw new IllegalArgumentException("Invalid dialogue variable count");
            Map<String, String> vars = new LinkedHashMap<>();
            for (int i = 0; i < varCount; i++) vars.put(buf.readUtf(32), buf.readUtf(Vars.MAX_VALUE));
            return new Node(sessionId, serial, kind, npcId, blockPos, open, false, speaker, text, accent, pace, choices, busy, Map.copyOf(vars));
        }

        static void handle(Node msg, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DialogueClient.onNode(msg));
        }
    }

    /**
     * Server -> client: mark a quest place on the Superior Skylines map (the client turns to face it first), or with
     * {@code add} false take the marker away. {@code y} is {@link #NO_Y} when unknown; {@code returning} names the
     * giver to return to instead of a structure.
     */
    public record Waypoint(int x, int y, int z, String name, boolean add, boolean returning) {
        static void encode(Waypoint msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.x);
            buf.writeVarInt(msg.y);
            buf.writeVarInt(msg.z);
            buf.writeUtf(msg.name, 64);
            buf.writeBoolean(msg.add);
            buf.writeBoolean(msg.returning);
        }
        static Waypoint decode(FriendlyByteBuf buf) {
            return new Waypoint(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64), buf.readBoolean(), buf.readBoolean());
        }
        static void handle(Waypoint msg, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DialogueClient.onWaypoint(msg));
        }
    }

    /** One boss in the hover: what it looks like, its lore, and the names of its lairs. */
    public record BossEntry(String look, String lore, List<String> lairs) {}

    /** Server -> client: the facts behind the boss hover, from the structure profiles. Replaces what the client had. */
    public record Bosses(Map<ResourceLocation, BossEntry> facts) {
        private static final int MAX_BOSSES = 1024;
        private static final int MAX_LAIRS = 16;

        static void encode(Bosses msg, FriendlyByteBuf buf) {
            int count = Math.min(MAX_BOSSES, msg.facts.size());
            buf.writeVarInt(count);
            int written = 0;
            for (Map.Entry<ResourceLocation, BossEntry> entry : msg.facts.entrySet()) {
                if (written++ >= count) break;
                buf.writeResourceLocation(entry.getKey());
                buf.writeUtf(entry.getValue().look(), 300);
                buf.writeUtf(entry.getValue().lore(), 300);
                int lairs = Math.min(MAX_LAIRS, entry.getValue().lairs().size());
                buf.writeVarInt(lairs);
                for (int i = 0; i < lairs; i++) buf.writeUtf(entry.getValue().lairs().get(i), 64);
            }
        }

        static Bosses decode(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_BOSSES) throw new IllegalArgumentException("Invalid boss count");
            Map<ResourceLocation, BossEntry> facts = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                ResourceLocation id = buf.readResourceLocation();
                String look = buf.readUtf(300);
                String lore = buf.readUtf(300);
                int lairCount = buf.readVarInt();
                if (lairCount < 0 || lairCount > MAX_LAIRS) throw new IllegalArgumentException("Invalid lair count");
                List<String> lairs = new ArrayList<>(lairCount);
                for (int j = 0; j < lairCount; j++) lairs.add(buf.readUtf(64));
                facts.put(id, new BossEntry(look, lore, lairs));
            }
            return new Bosses(facts);
        }

        static void handle(Bosses msg, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DialogueClient.onBosses(msg));
        }
    }

    /** Client -> server. {@code choice} is a visible choice index, {@link #CONTINUE}, or {@link #LEAVE}. */
    public record Advance(long sessionId, int serial, int choice) {
        static void encode(Advance msg, FriendlyByteBuf buf) {
            buf.writeVarLong(msg.sessionId);
            buf.writeVarInt(msg.serial);
            buf.writeVarInt(msg.choice);
        }
        static Advance decode(FriendlyByteBuf buf) {
            return new Advance(buf.readVarLong(), buf.readVarInt(), buf.readVarInt());
        }
        static void handle(Advance msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) DialogueServer.onAdvance(player, msg.sessionId, msg.serial, msg.choice);
        }
    }
}

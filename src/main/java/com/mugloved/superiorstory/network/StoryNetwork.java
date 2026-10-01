package com.mugloved.superiorstory.network;

import com.mugloved.superiorstory.SuperiorStory;
import com.mugloved.superiorstory.scene.StoryScene;
import com.mugloved.superiorstory.server.StoryServer;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Server sends the selected scene snapshot; client reports when it has been seen and finished.
 *
 * The channel accepts a missing remote, so a client with the mod can still join a server
 * without it (and vice versa) - the intro just won't play there.
 */
public final class StoryNetwork {
    private static final String VERSION = "8";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(SuperiorStory.MODID, "main"))
            .networkProtocolVersion(() -> VERSION)
            .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(VERSION))
            .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(VERSION))
            .simpleChannel();

    public static final int PROGRESS_SEEN = 1;
    public static final int PROGRESS_FINISHED = 2;
    public static final int PROGRESS_CANCELED = 3;

    private StoryNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(IntroMessage.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(IntroMessage::encode)
                .decoder(IntroMessage::decode)
                .consumerMainThread(IntroMessage::handle)
                .add();
        CHANNEL.messageBuilder(ProgressMessage.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ProgressMessage::encode)
                .decoder(ProgressMessage::decode)
                .consumerMainThread(ProgressMessage::handle)
                .add();
        DialoguePackets.register(CHANNEL);
    }

    public static boolean isPresentOn(Connection connection) {
        return connection != null && CHANNEL.isRemotePresent(connection);
    }

    public static void sendScene(ServerPlayer player, boolean play, long sessionId, boolean immediate,
                                 ResourceLocation sceneId, StoryScene scene) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new IntroMessage(play, sessionId, immediate, sceneId, scene));
    }

    public static void sendProgress(long sessionId, int stage) {
        CHANNEL.sendToServer(new ProgressMessage(sessionId, stage));
    }

    /** Server -> client. */
    public record IntroMessage(boolean play, long sessionId, boolean immediate,
                               ResourceLocation sceneId, StoryScene scene) {
        static void encode(IntroMessage msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.play);
            buf.writeVarLong(msg.sessionId);
            buf.writeBoolean(msg.immediate);
            buf.writeResourceLocation(msg.sceneId);
            buf.writeBoolean(msg.scene != null);
            if (msg.scene != null) msg.scene.writeClient(buf);
        }
        static IntroMessage decode(FriendlyByteBuf buf) {
            boolean play = buf.readBoolean();
            long sessionId = buf.readVarLong();
            boolean immediate = buf.readBoolean();
            ResourceLocation sceneId = buf.readResourceLocation();
            StoryScene scene = buf.readBoolean() ? StoryScene.readClient(sceneId, buf) : null;
            return new IntroMessage(play, sessionId, immediate, sceneId, scene);
        }
        static void handle(IntroMessage msg, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.mugloved.superiorstory.client.StoryClient.onServerScene(
                            msg.play, msg.sessionId, msg.immediate, msg.sceneId, msg.scene));
        }
    }

    /** Client -> server. */
    public record ProgressMessage(long sessionId, int stage) {
        static void encode(ProgressMessage msg, FriendlyByteBuf buf) {
            buf.writeVarLong(msg.sessionId);
            buf.writeVarInt(msg.stage);
        }
        static ProgressMessage decode(FriendlyByteBuf buf) {
            return new ProgressMessage(buf.readVarLong(), buf.readVarInt());
        }
        static void handle(ProgressMessage msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) StoryServer.onProgress(player, msg.sessionId, msg.stage);
        }
    }
}

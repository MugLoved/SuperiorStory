package com.mugloved.superiorstory.network;

import com.mugloved.superiorstory.SuperiorStory;
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
 * Two tiny messages:
 *  - server -> client: "play the intro" / "no intro for you" (sent on every login)
 *  - client -> server: "intro seen" (save the flag) / "intro finished" (drop protection)
 *
 * The channel accepts a missing remote, so a client with the mod can still join a server
 * without it (and vice versa) - the intro just won't play there.
 */
public final class StoryNetwork {
    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(SuperiorStory.MODID, "main"))
            .networkProtocolVersion(() -> VERSION)
            .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(VERSION))
            .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(VERSION))
            .simpleChannel();

    public static final int PROGRESS_SEEN = 1;
    public static final int PROGRESS_FINISHED = 2;

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
    }

    public static boolean isPresentOn(Connection connection) {
        return connection != null && CHANNEL.isRemotePresent(connection);
    }

    public static void sendIntro(ServerPlayer player, boolean play) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new IntroMessage(play));
    }

    public static void sendProgress(int stage) {
        CHANNEL.sendToServer(new ProgressMessage(stage));
    }

    /** Server -> client. */
    public record IntroMessage(boolean play) {
        static void encode(IntroMessage msg, FriendlyByteBuf buf) { buf.writeBoolean(msg.play); }
        static IntroMessage decode(FriendlyByteBuf buf) { return new IntroMessage(buf.readBoolean()); }
        static void handle(IntroMessage msg, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.mugloved.superiorstory.client.StoryClient.onServerIntro(msg.play));
        }
    }

    /** Client -> server. */
    public record ProgressMessage(int stage) {
        static void encode(ProgressMessage msg, FriendlyByteBuf buf) { buf.writeVarInt(msg.stage); }
        static ProgressMessage decode(FriendlyByteBuf buf) { return new ProgressMessage(buf.readVarInt()); }
        static void handle(ProgressMessage msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) StoryServer.onProgress(player, msg.stage);
        }
    }
}

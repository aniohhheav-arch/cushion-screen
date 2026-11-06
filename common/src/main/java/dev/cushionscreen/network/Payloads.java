package dev.cushionscreen.network;

import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.screen.MediaInfo;
import dev.cushionscreen.screen.ScreenData;
import dev.cushionscreen.screen.ScreenPermission;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Every packet the mod sends. None of them carries video data or file paths: only screen state, a content
 * fingerprint and the shared clock.
 */
public final class Payloads {
    private Payloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
        return new CustomPacketPayload.Type<>(CushionScreen.id(name));
    }

    // ---------------------------------------------------------------- client -> server

    public enum Action { PLAY, PAUSE, STOP, SEEK, SET_LOOP, EJECT }

    /** A playback control pressed by a player. {@code value} is the seek target in ms, or 0/1 for loop. */
    public record ScreenAction(BlockPos pos, Action action, long value) implements CustomPacketPayload {
        public static final Type<ScreenAction> TYPE = typeOf("action");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenAction> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ScreenAction::pos,
            ByteBufCodecs.idMapper(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal), ScreenAction::action,
            ByteBufCodecs.VAR_LONG, ScreenAction::value,
            ScreenAction::new);

        @Override
        public Type<ScreenAction> type() {
            return TYPE;
        }
    }

    /** A player picked a local file; only its description and fingerprint travel. */
    public record LoadMedia(BlockPos pos, MediaInfo media, boolean autoplay) implements CustomPacketPayload {
        public static final Type<LoadMedia> TYPE = typeOf("load_media");
        public static final StreamCodec<RegistryFriendlyByteBuf, LoadMedia> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, LoadMedia::pos,
            MediaInfo.STREAM_CODEC, LoadMedia::media,
            ByteBufCodecs.BOOL, LoadMedia::autoplay,
            LoadMedia::new);

        @Override
        public Type<LoadMedia> type() {
            return TYPE;
        }
    }

    public record Configure(BlockPos pos, String name, ScreenPermission permission, boolean powered, float brightness,
                            boolean audioEnabled, float volume, int rotation) implements CustomPacketPayload {
        public static final Type<Configure> TYPE = typeOf("configure");
        public static final StreamCodec<RegistryFriendlyByteBuf, Configure> CODEC = StreamCodec.of(
            (buf, c) -> {
                BlockPos.STREAM_CODEC.encode(buf, c.pos);
                buf.writeUtf(c.name, ScreenData.MAX_NAME);
                buf.writeEnum(c.permission);
                buf.writeBoolean(c.powered);
                buf.writeFloat(c.brightness);
                buf.writeBoolean(c.audioEnabled);
                buf.writeFloat(c.volume);
                buf.writeByte(c.rotation);
            },
            buf -> new Configure(BlockPos.STREAM_CODEC.decode(buf), buf.readUtf(ScreenData.MAX_NAME), buf.readEnum(ScreenPermission.class),
                buf.readBoolean(), buf.readFloat(), buf.readBoolean(), buf.readFloat(), buf.readByte()));

        @Override
        public Type<Configure> type() {
            return TYPE;
        }
    }

    /** Clock sync request; {@code clientTimeMs} is echoed back so the client can measure round-trip time. */
    public record ClockPing(long clientTimeMs) implements CustomPacketPayload {
        public static final Type<ClockPing> TYPE = typeOf("clock_ping");
        public static final StreamCodec<RegistryFriendlyByteBuf, ClockPing> CODEC =
            StreamCodec.composite(ByteBufCodecs.LONG, ClockPing::clientTimeMs, ClockPing::new);

        @Override
        public Type<ClockPing> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- server -> client

    /** Screen states for the receiver's current dimension. {@code replaceAll} is set on join and dimension change. */
    public record SyncScreens(boolean replaceAll, List<ScreenData> screens, List<UUID> removed) implements CustomPacketPayload {
        public static final Type<SyncScreens> TYPE = typeOf("sync");
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncScreens> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, SyncScreens::replaceAll,
            ScreenData.STREAM_CODEC.apply(ByteBufCodecs.list(4096)), SyncScreens::screens,
            UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(4096)), SyncScreens::removed,
            SyncScreens::new);

        @Override
        public Type<SyncScreens> type() {
            return TYPE;
        }
    }

    public record ClockPong(long clientTimeMs, long serverClockMs) implements CustomPacketPayload {
        public static final Type<ClockPong> TYPE = typeOf("clock_pong");
        public static final StreamCodec<RegistryFriendlyByteBuf, ClockPong> CODEC = StreamCodec.composite(
            ByteBufCodecs.LONG, ClockPong::clientTimeMs,
            ByteBufCodecs.LONG, ClockPong::serverClockMs,
            ClockPong::new);

        @Override
        public Type<ClockPong> type() {
            return TYPE;
        }
    }

    /** Feedback for the player who sent an action, e.g. a permission refusal. */
    public record Notice(String translationKey) implements CustomPacketPayload {
        public static final Type<Notice> TYPE = typeOf("notice");
        public static final StreamCodec<RegistryFriendlyByteBuf, Notice> CODEC =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(128), Notice::translationKey, Notice::new);

        @Override
        public Type<Notice> type() {
            return TYPE;
        }
    }
}

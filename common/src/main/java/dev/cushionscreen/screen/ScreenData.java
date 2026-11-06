package dev.cushionscreen.screen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

/**
 * Everything one logical screen (a connected rectangle of Cushion Screen blocks) shares: settings, the loaded media
 * and the playback timeline. Immutable; the server replaces it on every change and broadcasts the new value.
 */
public record ScreenData(
    UUID id,
    String name,
    Optional<UUID> owner,
    String ownerName,
    ScreenPermission permission,
    boolean powered,
    float brightness,
    boolean audioEnabled,
    float volume,
    int rotation,
    Optional<MediaInfo> media,
    Playback playback
) {
    public static final int MAX_NAME = 48;

    public static final Codec<ScreenData> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("id").forGetter(ScreenData::id),
        Codec.STRING.optionalFieldOf("name", "").forGetter(ScreenData::name),
        UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(ScreenData::owner),
        Codec.STRING.optionalFieldOf("owner_name", "").forGetter(ScreenData::ownerName),
        ScreenPermission.CODEC.optionalFieldOf("permission", ScreenPermission.EVERYONE).forGetter(ScreenData::permission),
        Codec.BOOL.optionalFieldOf("powered", true).forGetter(ScreenData::powered),
        Codec.FLOAT.optionalFieldOf("brightness", 1f).forGetter(ScreenData::brightness),
        Codec.BOOL.optionalFieldOf("audio", true).forGetter(ScreenData::audioEnabled),
        Codec.FLOAT.optionalFieldOf("volume", 0.8f).forGetter(ScreenData::volume),
        Codec.INT.optionalFieldOf("rotation", 0).forGetter(ScreenData::rotation),
        MediaInfo.CODEC.optionalFieldOf("media").forGetter(ScreenData::media),
        Playback.CODEC.optionalFieldOf("playback", Playback.STOPPED).forGetter(ScreenData::playback)
    ).apply(i, ScreenData::new));

    public static final StreamCodec<ByteBuf, ScreenData> STREAM_CODEC = StreamCodec.of(
        (buf, d) -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            b.writeUUID(d.id);
            b.writeUtf(d.name, MAX_NAME);
            b.writeBoolean(d.owner.isPresent());
            d.owner.ifPresent(b::writeUUID);
            b.writeUtf(d.ownerName, 64);
            b.writeEnum(d.permission);
            b.writeBoolean(d.powered);
            b.writeFloat(d.brightness);
            b.writeBoolean(d.audioEnabled);
            b.writeFloat(d.volume);
            b.writeByte(d.rotation);
            b.writeBoolean(d.media.isPresent());
            d.media.ifPresent(m -> MediaInfo.STREAM_CODEC.encode(buf, m));
            Playback.STREAM_CODEC.encode(buf, d.playback);
        },
        buf -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            UUID id = b.readUUID();
            String name = b.readUtf(MAX_NAME);
            Optional<UUID> owner = b.readBoolean() ? Optional.of(b.readUUID()) : Optional.empty();
            String ownerName = b.readUtf(64);
            ScreenPermission perm = b.readEnum(ScreenPermission.class);
            boolean powered = b.readBoolean();
            float brightness = b.readFloat();
            boolean audio = b.readBoolean();
            float volume = b.readFloat();
            int rotation = b.readByte() & 3;
            Optional<MediaInfo> media = b.readBoolean() ? Optional.of(MediaInfo.STREAM_CODEC.decode(buf)) : Optional.empty();
            Playback playback = Playback.STREAM_CODEC.decode(buf);
            return new ScreenData(id, name, owner, ownerName, perm, powered, brightness, audio, volume, rotation, media, playback);
        });

    public static ScreenData create(UUID id, @Nullable UUID owner, String ownerName) {
        return new ScreenData(id, "", Optional.ofNullable(owner), ownerName, ScreenPermission.EVERYONE, true, 1f, true, 0.8f, 0,
            Optional.empty(), Playback.STOPPED);
    }

    public long durationMs() {
        return media.map(MediaInfo::durationMs).orElse(0L);
    }

    public ScreenData withPlayback(Playback p) {
        return new ScreenData(id, name, owner, ownerName, permission, powered, brightness, audioEnabled, volume, rotation, media, p);
    }

    public ScreenData withMedia(Optional<MediaInfo> m, Playback p) {
        return new ScreenData(id, name, owner, ownerName, permission, powered, brightness, audioEnabled, volume, rotation, m, p);
    }

    public ScreenData withSettings(String newName, ScreenPermission perm, boolean pow, float bright, boolean audio, float vol, int rot) {
        return new ScreenData(id, newName, owner, ownerName, perm, pow, bright, audio, vol, rot & 3, media, playback);
    }

    /** A copy for a screen that split off: same settings and owner, no media. */
    public ScreenData copyAs(UUID newId) {
        return new ScreenData(newId, name, owner, ownerName, permission, powered, brightness, audioEnabled, volume, rotation,
            Optional.empty(), Playback.STOPPED);
    }

    public String displayName() {
        return name.isBlank() ? "Cushion Screen" : name;
    }
}

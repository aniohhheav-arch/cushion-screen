package dev.cushionscreen.screen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Describes a video without revealing where it lives. {@code fingerprint} identifies the exact file contents
 * (see {@code VideoFingerprint}); every client resolves it against its own local library. No path is ever shared.
 */
public record MediaInfo(
    String fingerprint,
    String fileName,
    long sizeBytes,
    long durationMs,
    int width,
    int height,
    double frameRate,
    boolean hasAudio,
    String codec,
    /** SHA-256 of the whole file (empty if unknown); a shared copy must match it exactly. */
    String contentHash
) {
    public static final int MAX_NAME = 128;
    public static final int FINGERPRINT_LENGTH = 64;

    public static final Codec<MediaInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("fingerprint").forGetter(MediaInfo::fingerprint),
        Codec.STRING.fieldOf("file_name").forGetter(MediaInfo::fileName),
        Codec.LONG.fieldOf("size").forGetter(MediaInfo::sizeBytes),
        Codec.LONG.fieldOf("duration_ms").forGetter(MediaInfo::durationMs),
        Codec.INT.fieldOf("width").forGetter(MediaInfo::width),
        Codec.INT.fieldOf("height").forGetter(MediaInfo::height),
        Codec.DOUBLE.optionalFieldOf("fps", 0.0).forGetter(MediaInfo::frameRate),
        Codec.BOOL.optionalFieldOf("audio", true).forGetter(MediaInfo::hasAudio),
        Codec.STRING.optionalFieldOf("codec", "").forGetter(MediaInfo::codec),
        Codec.STRING.optionalFieldOf("content_hash", "").forGetter(MediaInfo::contentHash)
    ).apply(i, MediaInfo::new));

    public static final StreamCodec<ByteBuf, MediaInfo> STREAM_CODEC = StreamCodec.of(
        (buf, m) -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            b.writeUtf(m.fingerprint, FINGERPRINT_LENGTH);
            b.writeUtf(m.fileName, MAX_NAME);
            b.writeVarLong(m.sizeBytes);
            b.writeVarLong(m.durationMs);
            b.writeVarInt(m.width);
            b.writeVarInt(m.height);
            b.writeDouble(m.frameRate);
            b.writeBoolean(m.hasAudio);
            b.writeUtf(m.codec, 32);
            b.writeUtf(m.contentHash, FINGERPRINT_LENGTH);
        },
        buf -> {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            return new MediaInfo(b.readUtf(FINGERPRINT_LENGTH), b.readUtf(MAX_NAME), b.readVarLong(), b.readVarLong(),
                b.readVarInt(), b.readVarInt(), b.readDouble(), b.readBoolean(), b.readUtf(32), b.readUtf(FINGERPRINT_LENGTH));
        });

    /** Server-side validation of data that came from a client. */
    public boolean isValid() {
        return fingerprint.length() == FINGERPRINT_LENGTH
            && fingerprint.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))
            && !fileName.isBlank() && fileName.length() <= MAX_NAME
            && fileName.indexOf('/') < 0 && fileName.indexOf('\\') < 0
            && fileName.chars().noneMatch(c -> c < 0x20 || c == 0x7f || c == '\u00a7')
            && sizeBytes > 0 && durationMs >= 0 && durationMs < 1000L * 60 * 60 * 48
            && width > 0 && height > 0 && width <= 16384 && height <= 16384
            && frameRate >= 0 && frameRate < 1000 && codec.length() <= 32
            && (contentHash.isEmpty() || dev.cushionscreen.share.VideoFingerprint.isHex64(contentHash));
    }

    public String resolutionLabel() {
        String fps = frameRate > 0 ? String.format(java.util.Locale.ROOT, " @ %.3g fps", frameRate) : "";
        return width + "×" + height + fps;
    }
}

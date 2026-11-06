package dev.cushionscreen.client.media;

import dev.cushionscreen.screen.MediaInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.PointerPointer;

/** Reads a file's streams to describe it, and refuses files FFmpeg cannot decode. */
public final class MediaProbe {
    private MediaProbe() {}

    public static MediaInfo probe(Path file) throws IOException {
        if (!FFmpeg.load().join()) throw new IOException("FFmpeg unavailable: " + FFmpeg.failure());
        AVFormatContext fmt = new AVFormatContext(null);
        int err = avformat.avformat_open_input(fmt, file.toAbsolutePath().toString(), null, null);
        if (err < 0) throw new IOException("Cannot open file: " + FFmpeg.error(err));
        try {
            err = avformat.avformat_find_stream_info(fmt, (PointerPointer<?>) null);
            if (err < 0) throw new IOException("Cannot read streams: " + FFmpeg.error(err));
            int v = avformat.av_find_best_stream(fmt, avutil.AVMEDIA_TYPE_VIDEO, -1, -1, (org.bytedeco.ffmpeg.avcodec.AVCodec) null, 0);
            if (v < 0) throw new IOException("No video stream");
            AVStream vs = fmt.streams(v);
            AVCodecParameters par = vs.codecpar();
            if (avcodec.avcodec_find_decoder(par.codec_id()) == null) {
                throw new IOException("Unsupported video codec: " + avcodec.avcodec_get_name(par.codec_id()).getString());
            }
            int a = avformat.av_find_best_stream(fmt, avutil.AVMEDIA_TYPE_AUDIO, -1, v, (org.bytedeco.ffmpeg.avcodec.AVCodec) null, 0);
            boolean audio = a >= 0 && avcodec.avcodec_find_decoder(fmt.streams(a).codecpar().codec_id()) != null;

            long durationMs = 0;
            if (fmt.duration() > 0) durationMs = fmt.duration() / 1000; // AV_TIME_BASE is microseconds
            else if (vs.duration() > 0) durationMs = (long) (vs.duration() * avutil.av_q2d(vs.time_base()) * 1000);

            AVRational fr = vs.avg_frame_rate();
            double fps = fr.den() != 0 ? avutil.av_q2d(fr) : 0;

            int width = par.width(), height = par.height();
            // honour non-square pixels so the picture keeps its intended shape
            AVRational sar = par.sample_aspect_ratio();
            if (sar.num() > 0 && sar.den() > 0 && sar.num() != sar.den()) {
                width = (int) Math.round(width * (double) sar.num() / sar.den());
            }
            if (width <= 0 || height <= 0) throw new IOException("Video has no picture size");

            String name = file.getFileName().toString().codePoints()
                .filter(c -> c >= 0x20 && c != 0x7f && c != '\u00a7')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            if (name.isBlank()) name = "video";
            if (name.length() > MediaInfo.MAX_NAME) name = name.substring(name.length() - MediaInfo.MAX_NAME);
            String codec = avcodec.avcodec_get_name(par.codec_id()).getString();
            if (codec.length() > 32) codec = codec.substring(0, 32);
            return new MediaInfo(VideoFingerprint.of(file), name, Files.size(file), durationMs, width, height, fps, audio, codec);
        } finally {
            avformat.avformat_close_input(fmt);
        }
    }
}

package dev.cushionscreen.client.media;

import dev.cushionscreen.CushionScreen;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;

/**
 * Decodes the audio stream on its own thread into 16-bit PCM chunks (mono or stereo, at the source rate) with
 * timestamps, buffering about a second ahead. Seeks trim the first chunk so audio starts on the exact sample.
 */
public final class AudioDecoder implements AutoCloseable {
    public record Chunk(ByteBuffer pcm, long ptsMs, int samples, int generation) {}

    private static final long MAX_BUFFERED_MS = 1200;

    private final Path file;
    private final int channels;
    private final Thread thread;
    private final ConcurrentLinkedQueue<Chunk> chunks = new ConcurrentLinkedQueue<>();
    private final AtomicLong bufferedSamples = new AtomicLong();

    private volatile boolean closed;
    private volatile boolean seekPending = true;
    private volatile long seekTargetMs;
    private volatile int generation;
    private volatile boolean opened;
    private volatile boolean endOfStream;
    private volatile String error;
    private volatile int sampleRate;

    public AudioDecoder(Path file, long startMs, int channels) {
        this.file = file;
        this.channels = channels;
        this.seekTargetMs = Math.max(0, startMs);
        this.thread = new Thread(this::run, "CushionScreen audio " + file.getFileName());
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public boolean isOpened() {
        return opened;
    }

    public String error() {
        return error;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public int generation() {
        return generation;
    }

    public boolean endOfStream() {
        return endOfStream && chunks.isEmpty();
    }

    public void seek(long targetMs) {
        seekTargetMs = Math.max(0, targetMs);
        generation++;
        seekPending = true;
        endOfStream = false;
    }

    /** Next chunk of the current generation, or null. Chunks of older generations are discarded. */
    public Chunk poll() {
        Chunk c;
        while ((c = chunks.poll()) != null) {
            bufferedSamples.addAndGet(-c.samples);
            if (c.generation == generation) return c;
        }
        return null;
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();
    }

    private void run() {
        AVFormatContext fmt = null;
        AVCodecContext ctx = null;
        AVPacket pkt = null;
        AVFrame frame = null;
        SwrContext swr = null;
        AVChannelLayout outLayout = new AVChannelLayout();
        BytePointer outBuf = null;
        PointerPointer<BytePointer> outPtr = new PointerPointer<BytePointer>(1).zero();
        int outCapacitySamples = 0;
        try {
            fmt = new AVFormatContext(null);
            int err = avformat.avformat_open_input(fmt, file.toAbsolutePath().toString(), null, null);
            if (err < 0) throw new IllegalStateException("open: " + FFmpeg.error(err));
            err = avformat.avformat_find_stream_info(fmt, (PointerPointer<?>) null);
            if (err < 0) throw new IllegalStateException("stream info: " + FFmpeg.error(err));
            int videoIndex = avformat.av_find_best_stream(fmt, avutil.AVMEDIA_TYPE_VIDEO, -1, -1, (AVCodec) null, 0);
            int streamIndex = avformat.av_find_best_stream(fmt, avutil.AVMEDIA_TYPE_AUDIO, -1, videoIndex, (AVCodec) null, 0);
            if (streamIndex < 0) throw new IllegalStateException("no audio stream");
            for (int i = 0; i < fmt.nb_streams(); i++) {
                if (i != streamIndex) fmt.streams(i).discard(avcodec.AVDISCARD_ALL);
            }
            AVStream stream = fmt.streams(streamIndex);
            double timeBase = avutil.av_q2d(stream.time_base());
            long startPts = stream.start_time() == avutil.AV_NOPTS_VALUE ? 0 : stream.start_time();
            // align with the video stream's zero, which the timeline uses
            if (videoIndex >= 0) {
                AVStream vs = fmt.streams(videoIndex);
                if (vs.start_time() != avutil.AV_NOPTS_VALUE) {
                    startPts = (long) (vs.start_time() * avutil.av_q2d(vs.time_base()) / timeBase);
                }
            }

            AVCodec codec = avcodec.avcodec_find_decoder(stream.codecpar().codec_id());
            if (codec == null) throw new IllegalStateException("unsupported audio codec");
            ctx = avcodec.avcodec_alloc_context3(codec);
            avcodec.avcodec_parameters_to_context(ctx, stream.codecpar());
            ctx.pkt_timebase(stream.time_base());
            err = avcodec.avcodec_open2(ctx, codec, (org.bytedeco.ffmpeg.avutil.AVDictionary) null);
            if (err < 0) throw new IllegalStateException("decoder: " + FFmpeg.error(err));

            int rate = Math.max(8000, Math.min(96000, ctx.sample_rate()));
            sampleRate = rate;
            avutil.av_channel_layout_default(outLayout, channels);
            PointerPointer<SwrContext> swrPtr = new PointerPointer<SwrContext>(1).zero(); // *ps must start NULL
            err = swresample.swr_alloc_set_opts2(swrPtr, outLayout, avutil.AV_SAMPLE_FMT_S16, rate,
                ctx.ch_layout(), ctx.sample_fmt(), ctx.sample_rate(), 0, null);
            if (err < 0) throw new IllegalStateException("resampler: " + FFmpeg.error(err));
            swr = new SwrContext(swrPtr.get(0));
            swrPtr.close();
            err = swresample.swr_init(swr);
            if (err < 0) throw new IllegalStateException("resampler init: " + FFmpeg.error(err));

            pkt = avcodec.av_packet_alloc();
            frame = avutil.av_frame_alloc();
            opened = true;

            long floorMs = 0;
            long runningMs = 0;
            boolean draining = false;
            int bytesPerSample = 2 * channels;

            while (!closed) {
                if (seekPending) {
                    seekPending = false;
                    long target = seekTargetMs;
                    long ts = startPts + (long) (target / 1000.0 / timeBase);
                    avformat.av_seek_frame(fmt, streamIndex, ts, avformat.AVSEEK_FLAG_BACKWARD);
                    avcodec.avcodec_flush_buffers(ctx);
                    swresample.swr_init(swr); // drop resampler history
                    floorMs = target;
                    runningMs = target;
                    draining = false;
                    endOfStream = false;
                }
                if (endOfStream || bufferedSamples.get() * 1000 / rate > MAX_BUFFERED_MS) {
                    Thread.sleep(10);
                    continue;
                }
                if (!draining) {
                    int r = avformat.av_read_frame(fmt, pkt);
                    if (r == avutil.AVERROR_EOF()) {
                        avcodec.avcodec_send_packet(ctx, null);
                        draining = true;
                    } else if (r == avutil.AVERROR_EAGAIN()) {
                        Thread.sleep(5);
                        continue;
                    } else if (r < 0) {
                        throw new IllegalStateException("read: " + FFmpeg.error(r));
                    } else {
                        if (pkt.stream_index() == streamIndex) avcodec.avcodec_send_packet(ctx, pkt);
                        avcodec.av_packet_unref(pkt);
                    }
                }
                int gen = generation;
                while (!closed && !seekPending) {
                    int r = avcodec.avcodec_receive_frame(ctx, frame);
                    if (r == avutil.AVERROR_EAGAIN()) break;
                    if (r == avutil.AVERROR_EOF()) {
                        endOfStream = true;
                        break;
                    }
                    if (r < 0) throw new IllegalStateException("decode: " + FFmpeg.error(r));
                    long pts = frame.best_effort_timestamp();
                    long ms = pts == avutil.AV_NOPTS_VALUE ? runningMs : (long) ((pts - startPts) * timeBase * 1000);

                    int maxOut = (int) swresample.swr_get_out_samples(swr, frame.nb_samples());
                    if (maxOut > outCapacitySamples) {
                        if (outBuf != null) avutil.av_free(outBuf);
                        outCapacitySamples = maxOut + 1024;
                        outBuf = new BytePointer(avutil.av_malloc((long) outCapacitySamples * bytesPerSample));
                    }
                    outPtr.put(0, outBuf);
                    int got = swresample.swr_convert(swr, outPtr, outCapacitySamples, frame.extended_data(), frame.nb_samples());
                    avutil.av_frame_unref(frame);
                    if (got <= 0) continue;
                    runningMs = ms + got * 1000L / rate;

                    int skip = 0;
                    if (ms < floorMs) {
                        skip = (int) Math.min(got, (floorMs - ms) * rate / 1000);
                        if (skip >= got) continue;
                        ms = floorMs;
                    }
                    int samples = got - skip;
                    ByteBuffer pcm = ByteBuffer.allocateDirect(samples * bytesPerSample).order(ByteOrder.nativeOrder());
                    ByteBuffer src = outBuf.position(0).capacity((long) outCapacitySamples * bytesPerSample).asByteBuffer();
                    src.position(skip * bytesPerSample).limit(got * bytesPerSample);
                    pcm.put(src).flip();
                    if (gen == generation) {
                        chunks.add(new Chunk(pcm, ms, samples, gen));
                        bufferedSamples.addAndGet(samples);
                    }
                }
            }
        } catch (InterruptedException e) {
            // closing
        } catch (Throwable t) {
            if (!closed) {
                error = t.getMessage() == null ? t.toString() : t.getMessage();
                CushionScreen.LOG.warn("Audio decoding failed for {}: {}", file.getFileName(), error);
            }
        } finally {
            if (swr != null) swresample.swr_free(swr);
            if (outBuf != null) avutil.av_free(outBuf);
            if (frame != null) avutil.av_frame_free(frame);
            if (pkt != null) avcodec.av_packet_free(pkt);
            if (ctx != null) avcodec.avcodec_free_context(ctx);
            if (fmt != null) avformat.avformat_close_input(fmt);
            avutil.av_channel_layout_uninit(outLayout);
            outPtr.close();
            chunks.clear();
        }
    }
}

package dev.cushionscreen.client.media;

import dev.cushionscreen.CushionScreen;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecHWConfig;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

/**
 * Decodes the video stream on its own thread into a small pool of RGBA frames, scaled once to the texture size.
 * The render thread takes the frame whose timestamp matches the shared timeline. Its own demuxer means the video
 * can idle (screen out of view) or seek without disturbing the audio.
 */
public final class VideoDecoder implements AutoCloseable {
    public static final class Frame {
        public final ByteBuffer pixels;
        public final int width;
        public final int height;
        long ptsMs;
        int generation;

        Frame(int width, int height) {
            this.width = width;
            this.height = height;
            this.pixels = ByteBuffer.allocateDirect(width * height * 4);
        }

        public long ptsMs() {
            return ptsMs;
        }
    }

    private static final int POOL = 5;

    private final Path file;
    private final int maxWidth;
    private final int maxHeight;
    private final boolean tryHardware;
    private final Thread thread;
    private final BlockingQueue<Frame> ready = new ArrayBlockingQueue<>(POOL);
    private final BlockingQueue<Frame> free = new ArrayBlockingQueue<>(POOL);
    /** Guards removal from {@link #ready}: the render thread takes due frames, the decoder reclaims late ones. */
    private final Object readyLock = new Object();

    private volatile boolean closed;
    private volatile boolean active = true;
    private volatile long seekTargetMs = 0; // first frame lands exactly on the requested start
    private volatile boolean seekPending = true;
    private volatile int generation;
    private volatile boolean opened;
    private volatile boolean endOfStream;
    private volatile String error;
    private volatile String decoderName = "";
    private volatile int outWidth;
    private volatile int outHeight;
    private volatile long lastDecodedMs = -1;
    // where the shared timeline is, so the thread can skip frames that are already late without converting them
    private volatile long hintMs = -1;
    private volatile long hintAtNanos;
    private volatile boolean hintPlaying;

    public VideoDecoder(Path file, long startMs, int maxWidth, int maxHeight, boolean tryHardware) {
        this.file = file;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.tryHardware = tryHardware;
        this.seekTargetMs = Math.max(0, startMs);
        this.thread = new Thread(this::run, "CushionScreen video " + file.getFileName());
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.NORM_PRIORITY - 1);
        this.thread.start();
    }

    // ---------------------------------------------------------------- render-thread API

    public boolean isOpened() {
        return opened;
    }

    public String error() {
        return error;
    }

    public boolean endOfStream() {
        return endOfStream && ready.isEmpty();
    }

    public int outputWidth() {
        return outWidth;
    }

    public int outputHeight() {
        return outHeight;
    }

    public String decoderName() {
        return decoderName;
    }

    /** Timestamp of the newest decoded frame, or -1. */
    public long lastDecodedMs() {
        return lastDecodedMs;
    }

    /** When false the thread stops reading the file (screen not visible); resume with a seek. */
    public void setActive(boolean value) {
        active = value;
    }

    /** Called every rendered frame with the timeline position; lets decoding keep pace even at a low frame rate. */
    public void setTimelineHint(long targetMs, boolean playing) {
        hintAtNanos = System.nanoTime();
        hintPlaying = playing;
        hintMs = targetMs;
    }

    private long estimatedTimelineMs() {
        long h = hintMs;
        if (h < 0) return -1;
        return hintPlaying ? h + (System.nanoTime() - hintAtNanos) / 1_000_000 : h;
    }

    public void seek(long targetMs) {
        seekTargetMs = Math.max(0, targetMs);
        generation++;
        seekPending = true;
        endOfStream = false;
    }

    /**
     * The newest frame due at {@code targetMs}; older due frames go back to the pool. Returns null if none is due.
     * The caller must hand the frame back with {@link #recycle}.
     */
    public Frame takeFrame(long targetMs) {
        synchronized (readyLock) {
            Frame chosen = null;
            Frame head;
            while ((head = ready.peek()) != null) {
                if (head.generation != generation) {
                    ready.poll();
                    free.offer(head);
                    continue;
                }
                if (head.ptsMs > targetMs) break;
                ready.poll();
                if (chosen != null) free.offer(chosen);
                chosen = head;
            }
            return chosen;
        }
    }

    /** Decoder side: when every buffer is waiting, reuse the oldest one if a newer waiting frame is already due. */
    private Frame reclaimLateFrame(long frameMs) {
        long timeline = estimatedTimelineMs();
        if (timeline < 0) return null;
        synchronized (readyLock) {
            if (ready.size() < 2) return null;
            Frame head = ready.peek();
            Frame second = null;
            int i = 0;
            for (Frame f : ready) {
                if (i++ == 1) {
                    second = f;
                    break;
                }
            }
            if (head == null || second == null) return null;
            boolean stale = head.generation != generation;
            boolean superseded = second.generation == generation && second.ptsMs <= timeline;
            if (stale || superseded) return ready.poll();
            return null;
        }
    }

    /** Timestamp of the next waiting frame, or -1. */
    public long peekNextMs() {
        Frame head = ready.peek();
        return head == null || head.generation != generation ? -1 : head.ptsMs;
    }

    public void recycle(Frame f) {
        if (f != null) free.offer(f);
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();
    }

    // ---------------------------------------------------------------- decoder thread

    private void run() {
        AVFormatContext fmt = null;
        AVCodecContext ctx = null;
        AVPacket pkt = null;
        AVFrame frame = null;
        AVFrame swFrame = null;
        AVBufferRef hwDevice = null;
        SwsContext sws = null;
        PointerPointer<BytePointer> dstData = new PointerPointer<BytePointer>(4).zero();
        IntPointer dstLinesize = new IntPointer(4).zero();
        try {
            fmt = new AVFormatContext(null);
            int err = avformat.avformat_open_input(fmt, file.toAbsolutePath().toString(), null, null);
            if (err < 0) throw new IllegalStateException("open: " + FFmpeg.error(err));
            err = avformat.avformat_find_stream_info(fmt, (PointerPointer<?>) null);
            if (err < 0) throw new IllegalStateException("stream info: " + FFmpeg.error(err));
            int streamIndex = avformat.av_find_best_stream(fmt, avutil.AVMEDIA_TYPE_VIDEO, -1, -1, (AVCodec) null, 0);
            if (streamIndex < 0) throw new IllegalStateException("no video stream");
            // only read the stream we decode
            for (int i = 0; i < fmt.nb_streams(); i++) {
                if (i != streamIndex) fmt.streams(i).discard(avcodec.AVDISCARD_ALL);
            }
            AVStream stream = fmt.streams(streamIndex);
            double timeBase = avutil.av_q2d(stream.time_base());
            long startPts = stream.start_time() == avutil.AV_NOPTS_VALUE ? 0 : stream.start_time();

            AVCodec codec = avcodec.avcodec_find_decoder(stream.codecpar().codec_id());
            if (codec == null) throw new IllegalStateException("unsupported codec");
            ctx = avcodec.avcodec_alloc_context3(codec);
            avcodec.avcodec_parameters_to_context(ctx, stream.codecpar());
            ctx.thread_count(0); // let FFmpeg pick (frame + slice threading)
            ctx.pkt_timebase(stream.time_base());

            int hwPixFmt = -1;
            String hwName = null;
            if (tryHardware) {
                for (String name : HardwareDecoding.candidates()) {
                    int type = avutil.av_hwdevice_find_type_by_name(name);
                    if (type == avutil.AV_HWDEVICE_TYPE_NONE) continue;
                    int fmtForType = -1;
                    for (int i = 0; ; i++) {
                        AVCodecHWConfig cfg = avcodec.avcodec_get_hw_config(codec, i);
                        if (cfg == null) break;
                        if ((cfg.methods() & avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0 && cfg.device_type() == type) {
                            fmtForType = cfg.pix_fmt();
                            break;
                        }
                    }
                    if (fmtForType < 0) continue;
                    AVBufferRef dev = new AVBufferRef(null);
                    if (avutil.av_hwdevice_ctx_create(dev, type, (String) null, null, 0) >= 0) {
                        hwDevice = dev;
                        hwPixFmt = fmtForType;
                        hwName = name;
                        ctx.hw_device_ctx(avutil.av_buffer_ref(dev));
                        break;
                    }
                }
            }

            err = avcodec.avcodec_open2(ctx, codec, (org.bytedeco.ffmpeg.avutil.AVDictionary) null);
            if (err < 0) throw new IllegalStateException("decoder: " + FFmpeg.error(err));
            decoderName = codec.name().getString() + (hwName != null ? " (" + hwName + ")" : "");

            // Fit the picture into the texture budget, keeping its aspect ratio; account for non-square pixels.
            int srcW = ctx.width(), srcH = ctx.height();
            double displayW = srcW;
            if (ctx.sample_aspect_ratio().num() > 0 && ctx.sample_aspect_ratio().den() > 0) {
                displayW = srcW * avutil.av_q2d(ctx.sample_aspect_ratio());
            }
            double scale = Math.min(1.0, Math.min(maxWidth / displayW, maxHeight / (double) srcH));
            int w = Math.max(2, (int) Math.round(displayW * scale) & ~1);
            int h = Math.max(2, (int) Math.round(srcH * scale) & ~1);
            outWidth = w;
            outHeight = h;
            for (int i = 0; i < POOL; i++) free.add(new Frame(w, h));

            pkt = avcodec.av_packet_alloc();
            frame = avutil.av_frame_alloc();
            swFrame = avutil.av_frame_alloc();
            opened = true;

            long frameMs = 0;
            if (stream.avg_frame_rate().den() != 0 && stream.avg_frame_rate().num() > 0) {
                frameMs = (long) (1000 / avutil.av_q2d(stream.avg_frame_rate()));
            }
            long floorMs = 0;
            boolean draining = false;

            while (!closed) {
                if (seekPending) {
                    seekPending = false;
                    long target = seekTargetMs;
                    long ts = startPts + (long) (target / 1000.0 / timeBase);
                    avformat.av_seek_frame(fmt, streamIndex, ts, avformat.AVSEEK_FLAG_BACKWARD);
                    avcodec.avcodec_flush_buffers(ctx);
                    floorMs = target - frameMs / 2;
                    draining = false;
                    endOfStream = false;
                    lastDecodedMs = -1;
                }
                if (!active || endOfStream) {
                    sleep(15);
                    continue;
                }
                if (!draining) {
                    int r = avformat.av_read_frame(fmt, pkt);
                    if (r == avutil.AVERROR_EOF() || r == avutil.AVERROR_EAGAIN()) {
                        if (r == avutil.AVERROR_EAGAIN()) {
                            sleep(5);
                            continue;
                        }
                        avcodec.avcodec_send_packet(ctx, null);
                        draining = true;
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
                    long ms = pts == avutil.AV_NOPTS_VALUE ? lastDecodedMs + Math.max(frameMs, 1) : (long) ((pts - startPts) * timeBase * 1000);
                    lastDecodedMs = ms;
                    if (ms < floorMs) {
                        avutil.av_frame_unref(frame);
                        continue; // decoding forward from the keyframe to the exact seek target
                    }
                    long timeline = estimatedTimelineMs();
                    if (!draining && timeline >= 0 && ms + Math.max(frameMs, 20) < timeline) {
                        avutil.av_frame_unref(frame);
                        continue; // already late: a newer frame is due, skip the colour conversion
                    }
                    AVFrame src = frame;
                    if (hwPixFmt >= 0 && frame.format() == hwPixFmt) {
                        int t = avutil.av_hwframe_transfer_data(swFrame, frame, 0);
                        if (t < 0) throw new IllegalStateException("hw transfer: " + FFmpeg.error(t));
                        src = swFrame;
                    }
                    Frame out = null;
                    while (!closed && !seekPending && out == null) {
                        out = free.poll(5, TimeUnit.MILLISECONDS);
                        if (out == null) out = reclaimLateFrame(frameMs);
                    }
                    if (out == null) {
                        avutil.av_frame_unref(frame);
                        avutil.av_frame_unref(swFrame);
                        break;
                    }
                    sws = swscale.sws_getCachedContext(sws, src.width(), src.height(), src.format(),
                        w, h, avutil.AV_PIX_FMT_RGBA, swscale.SWS_BILINEAR, null, null, (DoublePointer) null);
                    if (sws == null) throw new IllegalStateException("no converter for pixel format " + src.format());
                    dstData.put(0, new BytePointer(out.pixels));
                    dstLinesize.put(0, w * 4);
                    swscale.sws_scale(sws, src.data(), src.linesize(), 0, src.height(), dstData, dstLinesize);
                    avutil.av_frame_unref(frame);
                    avutil.av_frame_unref(swFrame);
                    out.ptsMs = ms;
                    out.generation = gen;
                    if (gen != generation || !ready.offer(out)) free.offer(out);
                }
            }
        } catch (InterruptedException e) {
            // closing
        } catch (Throwable t) {
            if (!closed) {
                error = t.getMessage() == null ? t.toString() : t.getMessage();
                CushionScreen.LOG.warn("Video decoding failed for {}: {}", file.getFileName(), error);
            }
        } finally {
            if (sws != null) swscale.sws_freeContext(sws);
            if (frame != null) avutil.av_frame_free(frame);
            if (swFrame != null) avutil.av_frame_free(swFrame);
            if (pkt != null) avcodec.av_packet_free(pkt);
            if (ctx != null) avcodec.avcodec_free_context(ctx);
            if (hwDevice != null) avutil.av_buffer_unref(hwDevice);
            if (fmt != null) avformat.avformat_close_input(fmt);
            dstData.close();
            dstLinesize.close();
            ready.clear();
            free.clear();
        }
    }

    private void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}

package dev.cushionscreen.client.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content identity of a video file, independent of its name or location: SHA-256 over the file size and three
 * 4 MiB samples (start, middle, end). Fast even for multi-gigabyte files, and two different encodes of a video
 * practically never collide. Files of 12 MiB or less are hashed completely.
 */
public final class VideoFingerprint {
    private static final int SAMPLE = 4 * 1024 * 1024;

    private VideoFingerprint() {}

    public static String of(Path file) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            sha.update(ByteBuffer.allocate(8).putLong(0, size));
            ByteBuffer buf = ByteBuffer.allocate(SAMPLE);
            if (size <= 3L * SAMPLE) {
                readInto(ch, 0, size, buf, sha);
            } else {
                readInto(ch, 0, SAMPLE, buf, sha);
                readInto(ch, size / 2 - SAMPLE / 2, SAMPLE, buf, sha);
                readInto(ch, size - SAMPLE, SAMPLE, buf, sha);
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void readInto(FileChannel ch, long offset, long length, ByteBuffer buf, MessageDigest sha) throws IOException {
        long pos = offset, end = offset + length;
        while (pos < end) {
            buf.clear();
            buf.limit((int) Math.min(buf.capacity(), end - pos));
            int n = ch.read(buf, pos);
            if (n < 0) break;
            buf.flip();
            sha.update(buf);
            pos += n;
        }
    }
}

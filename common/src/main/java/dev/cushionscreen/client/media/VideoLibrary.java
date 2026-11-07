package dev.cushionscreen.client.media;

import dev.cushionscreen.share.VideoFingerprint;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.platform.Platform;
import dev.cushionscreen.screen.MediaInfo;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * This computer's own videos, by fingerprint. A screen's media resolves to a local path only through files this
 * player chose (with the file dialog) or placed in {@code .minecraft/cushionscreen/videos/}, and only when the file's
 * fingerprint matches. Paths never leave this computer.
 */
public final class VideoLibrary {
    public enum Lookup { FOUND, SEARCHING, MISSING }

    private record Entry(String path, long size, long modified) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> EXTENSIONS = Set.of("mp4", "m4v", "webm", "mov", "mkv");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CushionScreen library");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private static final Map<String, Long> searchedAt = new ConcurrentHashMap<>();
    private static final Set<String> searching = ConcurrentHashMap.newKeySet();
    private static boolean loaded;

    private VideoLibrary() {}

    public static Path root() {
        return Platform.INSTANCE.gameDirectory().resolve("cushionscreen");
    }

    public static Path dropFolder() {
        return root().resolve("videos");
    }

    private static Path indexFile() {
        return root().resolve("library.json");
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            Files.createDirectories(dropFolder());
            Path f = indexFile();
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    Map<String, Entry> m = GSON.fromJson(r, new TypeToken<Map<String, Entry>>() {}.getType());
                    if (m != null) entries.putAll(m);
                }
            }
        } catch (Exception e) {
            CushionScreen.LOG.warn("Could not read video library index", e);
        }
    }

    private static void save() {
        Map<String, Entry> copy = new HashMap<>(entries);
        IO.execute(() -> {
            try (Writer w = Files.newBufferedWriter(indexFile())) {
                GSON.toJson(copy, w);
            } catch (IOException e) {
                CushionScreen.LOG.warn("Could not save video library index", e);
            }
        });
    }

    /** Drops what this computer knows about a video (the file itself is left alone). */
    public static void forget(String fingerprint) {
        entries.remove(fingerprint);
        searchedAt.remove(fingerprint);
        save();
    }

    /** Records a file the player picked, after its fingerprint was computed. */
    public static void remember(String fingerprint, Path file) {
        try {
            entries.put(fingerprint, new Entry(file.toAbsolutePath().toString(), Files.size(file), Files.getLastModifiedTime(file).toMillis()));
            searchedAt.remove(fingerprint);
            save();
        } catch (IOException e) {
            CushionScreen.LOG.warn("Could not remember {}", file, e);
        }
    }

    /** Where this computer's copy of the media is, if it has one. Starts a background search on a miss. */
    public static Lookup lookup(MediaInfo media, Path[] out) {
        load();
        Entry e = entries.get(media.fingerprint());
        if (e != null) {
            Path p = Path.of(e.path);
            try {
                if (Files.isRegularFile(p) && Files.size(p) == e.size) {
                    if (Files.getLastModifiedTime(p).toMillis() == e.modified) {
                        out[0] = p;
                        return Lookup.FOUND;
                    }
                }
            } catch (IOException ignored) {
            }
            entries.remove(media.fingerprint()); // moved, edited or deleted: search again
            save();
        }
        if (searching.contains(media.fingerprint())) return Lookup.SEARCHING;
        Long last = searchedAt.get(media.fingerprint());
        if (last == null || System.currentTimeMillis() - last > 15_000) {
            searching.add(media.fingerprint());
            searchedAt.put(media.fingerprint(), System.currentTimeMillis());
            IO.execute(() -> search(media));
            return Lookup.SEARCHING;
        }
        return Lookup.MISSING;
    }

    /** Looks through the drop folder for a file of the right size whose fingerprint matches. */
    private static void search(MediaInfo media) {
        try (Stream<Path> files = Files.walk(dropFolder(), 3)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(p) || !isVideoName(p.getFileName().toString())) continue;
                if (Files.size(p) != media.sizeBytes()) continue;
                if (VideoFingerprint.of(p).equals(media.fingerprint())) {
                    remember(media.fingerprint(), p);
                    return;
                }
            }
        } catch (IOException e) {
            CushionScreen.LOG.debug("Drop folder search failed", e);
        } finally {
            searching.remove(media.fingerprint());
        }
    }

    public static boolean isVideoName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    /** For "locate file": accepts the chosen file only if it is the same video. */
    public static Optional<String> verifyAndRemember(Path file, MediaInfo expected) throws IOException {
        if (Files.size(file) != expected.sizeBytes()) return Optional.of("cushionscreen.locate.wrong_size");
        if (!VideoFingerprint.of(file).equals(expected.fingerprint())) return Optional.of("cushionscreen.locate.wrong_file");
        remember(expected.fingerprint(), file);
        return Optional.empty();
    }

    public static ExecutorService ioExecutor() {
        return IO;
    }
}

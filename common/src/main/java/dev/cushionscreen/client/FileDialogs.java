package dev.cushionscreen.client;

import dev.cushionscreen.CushionScreen;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The operating system's own "open file" dialog, through SDL3 (which Minecraft already uses for its window):
 * Explorer on Windows, NSOpenPanel on macOS, the desktop portal or GTK/KDE dialog on Linux.
 */
public final class FileDialogs {
    private static SDL_DialogFileCallback pending; // keep the native callback alive until SDL calls it
    private static boolean open;

    private FileDialogs() {}

    public static boolean isOpen() {
        return open;
    }

    /** Shows the dialog; {@code onPicked} runs on the client thread with the file, or null if cancelled. */
    public static void pickVideo(Path startDir, Consumer<Path> onPicked) {
        if (open) return;
        Minecraft mc = Minecraft.getInstance();
        open = true;
        SDL_DialogFileCallback callback = SDL_DialogFileCallback.create((userdata, filelist, filter) -> {
            Path picked = null;
            if (filelist == 0) {
                CushionScreen.LOG.warn("File dialog failed: {}", org.lwjgl.sdl.SDLError.SDL_GetError());
            } else {
                long first = MemoryUtil.memGetAddress(filelist);
                if (first != 0) picked = Path.of(MemoryUtil.memUTF8(first));
            }
            Path result = picked;
            mc.execute(() -> {
                open = false;
                SDL_DialogFileCallback done = pending;
                pending = null;
                if (done != null) done.free();
                onPicked.accept(result);
            });
        });
        pending = callback;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.malloc(2, stack);
            filters.get(0).name(stack.UTF8("Videos (MP4, WebM, MOV, MKV)")).pattern(stack.UTF8("mp4;m4v;webm;mov;mkv"));
            filters.get(1).name(stack.UTF8("All files")).pattern(stack.UTF8("*"));
            String location = startDir != null && startDir.toFile().isDirectory() ? startDir.toAbsolutePath() + java.io.File.separator : null;
            SDLDialog.SDL_ShowOpenFileDialog(callback, 0L, mc.getWindow().handle(), filters, location, false);
        } catch (Throwable t) {
            CushionScreen.LOG.error("Could not open the file dialog", t);
            open = false;
            pending = null;
            callback.free();
            onPicked.accept(null);
        }
    }
}

package dev.cushionscreen.client.render;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Remembers per-screen transitions on this client so the renderer can animate them. */
final class ScreenFx {
    private static final class Fx {
        boolean powered;
        long poweredChangedAt;
    }

    private static final Map<UUID, Fx> fx = new HashMap<>();

    private ScreenFx() {}

    /** Milliseconds since the screen's power state last changed on this client; long-ago for the first sighting. */
    static long sincePowerChange(UUID id, boolean powered, long now) {
        Fx f = fx.get(id);
        if (f == null) {
            f = new Fx();
            f.powered = powered;
            f.poweredChangedAt = 0; // already in this state when we first saw it: no animation
            fx.put(id, f);
        } else if (f.powered != powered) {
            f.powered = powered;
            f.poweredChangedAt = now;
        }
        return now - f.poweredChangedAt;
    }

    static void clear() {
        fx.clear();
    }
}

package dev.cushionscreen.screen;

import com.mojang.serialization.Codec;

/** Who may load videos and control playback. Changing settings is always limited to the owner and operators. */
public enum ScreenPermission {
    EVERYONE,
    OWNER,
    OPERATORS;

    public static final Codec<ScreenPermission> CODEC = Codec.STRING.xmap(
        s -> {
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return EVERYONE;
            }
        },
        ScreenPermission::name);

    public ScreenPermission next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String translationKey() {
        return "cushionscreen.permission." + name().toLowerCase(java.util.Locale.ROOT);
    }
}

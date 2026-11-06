package dev.cushionscreen;

import dev.cushionscreen.network.ModNetwork;
import dev.cushionscreen.registry.ModContent;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared entry point. Each loader calls {@link #init()} from its own mod constructor/initializer. */
public final class CushionScreen {
    public static final String MOD_ID = "cushionscreen";
    public static final Logger LOG = LoggerFactory.getLogger("CushionScreen");

    private CushionScreen() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static void init() {
        ModContent.init();
        ModNetwork.init();
    }
}

package dev.cushionscreen.client.gui;

import dev.cushionscreen.client.ClientScreens;
import dev.cushionscreen.screen.ScreenData;
import dev.cushionscreen.screen.ScreenPermission;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Owner settings: name, power, brightness, speaker volume, audio, picture rotation and who may control playback. */
final class ScreenSettingsScreen extends Screen {
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 214;

    private final Screen parent;
    private final BlockPos pos;
    private final long openedAt = System.currentTimeMillis();
    private ScreenData draft;
    private EditBox nameBox;
    private Button powerButton, audioButton, rotationButton, permissionButton;
    private int left, top;

    ScreenSettingsScreen(Screen parent, BlockPos pos) {
        super(Component.translatable("cushionscreen.gui.settings_title"));
        this.parent = parent;
        this.pos = pos;
    }

    private @Nullable ScreenData current() {
        if (minecraft.level == null) return null;
        UUID id = ClientScreens.idAt(minecraft.level, pos);
        return ClientScreens.get(id);
    }

    @Override
    protected void init() {
        if (draft == null) draft = current();
        if (draft == null) {
            onClose();
            return;
        }
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        int x = left + 12, w = PANEL_W - 24, half = (w - 4) / 2;

        nameBox = addRenderableWidget(new EditBox(font, x, top + 34, w, 18, Component.translatable("cushionscreen.gui.name")));
        nameBox.setMaxLength(ScreenData.MAX_NAME);
        nameBox.setValue(draft.name());
        nameBox.setResponder(v -> draft = with(v, draft.permission(), draft.powered(), draft.brightness(), draft.audioEnabled(), draft.volume(), draft.rotation()));

        powerButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            draft = with(draft.name(), draft.permission(), !draft.powered(), draft.brightness(), draft.audioEnabled(), draft.volume(), draft.rotation());
            refresh();
        }).bounds(x, top + 62, half, 20).build());
        audioButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            draft = with(draft.name(), draft.permission(), draft.powered(), draft.brightness(), !draft.audioEnabled(), draft.volume(), draft.rotation());
            refresh();
        }).bounds(x + half + 4, top + 62, half, 20).build());

        addRenderableWidget(new Slider(x, top + 88, half, 20, (draft.brightness() - 0.1) / 0.9, "cushionscreen.gui.brightness") {
            @Override
            protected void applyValue() {
                draft = with(draft.name(), draft.permission(), draft.powered(), (float) (0.1 + value * 0.9), draft.audioEnabled(), draft.volume(), draft.rotation());
            }
        });
        addRenderableWidget(new Slider(x + half + 4, top + 88, half, 20, draft.volume(), "cushionscreen.gui.speaker_volume") {
            @Override
            protected void applyValue() {
                draft = with(draft.name(), draft.permission(), draft.powered(), draft.brightness(), draft.audioEnabled(), (float) value, draft.rotation());
            }
        });

        rotationButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            draft = with(draft.name(), draft.permission(), draft.powered(), draft.brightness(), draft.audioEnabled(), draft.volume(), (draft.rotation() + 1) & 3);
            refresh();
        }).bounds(x, top + 114, half, 20).build());
        permissionButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            draft = with(draft.name(), draft.permission().next(), draft.powered(), draft.brightness(), draft.audioEnabled(), draft.volume(), draft.rotation());
            refresh();
        }).bounds(x + half + 4, top + 114, half, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x, top + PANEL_H - 30, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            ScreenActions.configure(pos, draft);
            onClose();
        }).bounds(x + half + 4, top + PANEL_H - 30, half, 20).build());
        refresh();
    }

    private ScreenData with(String name, ScreenPermission perm, boolean powered, float brightness, boolean audio, float volume, int rotation) {
        return draft.withSettings(name, perm, powered, brightness, audio, volume, rotation);
    }

    private void refresh() {
        powerButton.setMessage(Component.translatable(draft.powered() ? "cushionscreen.gui.power_on" : "cushionscreen.gui.power_off"));
        audioButton.setMessage(Component.translatable(draft.audioEnabled() ? "cushionscreen.gui.audio_on" : "cushionscreen.gui.audio_off"));
        rotationButton.setMessage(Component.translatable("cushionscreen.gui.rotation", draft.rotation() * 90));
        permissionButton.setMessage(Component.translatable("cushionscreen.gui.permission", Component.translatable(draft.permission().translationKey())));
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fillGradient(0, 0, width, height, 0x60000000, 0xA0000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 160f);
        g.pose().pushMatrix();
        g.pose().translate((1 - (1 - (1 - t) * (1 - t))) * 16, 0);
        g.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, 0xFF2A2F3A);
        g.fillGradient(left, top, left + PANEL_W, top + PANEL_H, 0xF0161A22, 0xF00C0E13);
        g.text(font, title, left + 12, top + 10, 0xFFFFFFFF);
        g.text(font, Component.translatable("cushionscreen.gui.name"), left + 12, top + 24, 0xFF8C96A8);
        String owner = draft == null || draft.ownerName().isEmpty() ? "-" : draft.ownerName();
        g.text(font, Component.translatable("cushionscreen.gui.owner", owner), left + 12, top + 144, 0xFF8C96A8);
        g.textWithWordWrap(font, Component.translatable("cushionscreen.gui.settings_note"), left + 12, top + 158, PANEL_W - 24, 0xFF6C7686);
        super.extractRenderState(g, mouseX, mouseY, a);
        g.pose().popMatrix();
    }

    private abstract static class Slider extends AbstractSliderButton {
        private final String key;

        Slider(int x, int y, int w, int h, double value, String key) {
            super(x, y, w, h, Component.empty(), Math.max(0, Math.min(1, value)));
            this.key = key;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(key, Math.round(value * 100)));
        }
    }
}

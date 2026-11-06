package dev.cushionscreen.client.gui;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Playback progress you can click or drag to seek; the seek is sent when the mouse is released. */
final class ProgressBar extends AbstractWidget {
    private final DoubleSupplier progress;
    private final DoubleConsumer onSeek;
    private double dragValue = -1;

    ProgressBar(int x, int y, int width, int height, DoubleSupplier progress, DoubleConsumer onSeek) {
        super(x, y, width, height, Component.translatable("cushionscreen.gui.progress"));
        this.progress = progress;
        this.onSeek = onSeek;
    }

    double shownValue() {
        return dragValue >= 0 ? dragValue : Math.max(0, Math.min(1, progress.getAsDouble()));
    }

    boolean isDragging() {
        return dragValue >= 0;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int mid = y + h / 2;
        boolean hover = isHovered() || isDragging();
        int trackH = hover ? 4 : 2;
        g.fill(x, mid - trackH / 2, x + w, mid + (trackH + 1) / 2, 0x55FFFFFF);
        double v = shownValue();
        int fillX = x + (int) Math.round(w * v);
        g.fill(x, mid - trackH / 2, fillX, mid + (trackH + 1) / 2, active ? 0xFFF0584A : 0xFF777777);
        if (active && hover) {
            g.fill(fillX - 3, mid - 4, fillX + 3, mid + 4, 0xFFFFFFFF);
        }
    }

    private double valueAt(double mouseX) {
        return Math.max(0, Math.min(1, (mouseX - getX()) / (double) getWidth()));
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        dragValue = valueAt(event.x());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        dragValue = valueAt(event.x());
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (dragValue >= 0) onSeek.accept(dragValue);
        dragValue = -1;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}

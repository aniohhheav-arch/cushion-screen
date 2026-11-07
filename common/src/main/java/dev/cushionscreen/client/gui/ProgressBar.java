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
        int trackH = hover ? 6 : 4;
        int top = mid - trackH / 2, bottom = top + trackH;
        g.fill(x, top, x + w, bottom, 0x66FFFFFF);
        double v = shownValue();
        int fillX = x + (int) Math.round(w * v);
        g.fill(x, top, fillX, bottom, active ? 0xFFF0584A : 0xFF777777);
        // the handle is always shown, so it reads as something to drag
        int hw = hover ? 4 : 3;
        g.fill(fillX - hw - 1, mid - 7, fillX + hw + 1, mid + 7, 0xFF1A1D24);
        g.fill(fillX - hw, mid - 6, fillX + hw, mid + 6, active ? 0xFFFFFFFF : 0xFF9A9A9A);
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

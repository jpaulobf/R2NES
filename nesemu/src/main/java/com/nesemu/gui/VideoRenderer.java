package com.nesemu.gui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.Graphics2D;
import java.util.function.Consumer;
import javax.swing.JPanel;

/** Simple panel that draws a 256x240 framebuffer (int ARGB array). */
public class VideoRenderer extends JPanel {
    private final BufferedImage image;
    private final int[] imageData; // direct reference to underlying INT ARGB buffer
    private volatile int[] source; // reference to emulator ARGB buffer (PPU frameBuffer)
    private final int scale;
    private volatile Consumer<Graphics2D> overlay;

    public VideoRenderer(int scale) {
        this.scale = Math.max(1, scale);
        this.image = new BufferedImage(256, 240, BufferedImage.TYPE_INT_ARGB);
        this.imageData = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        setPreferredSize(new Dimension(256 * this.scale, 240 * this.scale));
        // Double buffering desnecessário: usamos nosso próprio back buffer (image)
    }

    public void setFrameBuffer(int[] argb) {
        this.source = argb;
    }

    public void blitAndRepaint() {
        int[] src = source;
        if (src != null) {
            // Copia direta (≈61K ints) – mais leve que setRGB e evita validações extras
            System.arraycopy(src, 0, imageData, 0, 256 * 240);
        }
        repaint();
    }

    public void setOverlay(Consumer<Graphics2D> overlay) {
        this.overlay = overlay;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(image, 0, 0, 256 * scale, 240 * scale, null);
        Consumer<Graphics2D> ov = overlay;
        if (ov != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.scale(scale, scale); // draw overlay in NES pixel space
                ov.accept(g2);
            } finally {
                g2.dispose();
            }
        }
    }
}

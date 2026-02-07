package com.nesemu.gui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.Graphics2D;
import java.util.function.Consumer;
import javax.swing.JPanel;

/**
 * Simple panel that draws a 256x240 framebuffer (int ARGB array).
 */
public class VideoRenderer extends JPanel {

    // Backing image and data
    private final BufferedImage image;
    private final int[] imageData; // direct reference to underlying INT ARGB buffer
    private volatile int[] source; // reference to emulator ARGB buffer (PPU frameBuffer)
    private final int scale;
    private volatile Consumer<Graphics2D> overlay;

    /**
     * Create renderer with given scale factor (1 = native 256x240).
     * 
     * @param scale
     */
    public VideoRenderer(int scale) {
        this.scale = Math.max(1, scale);
        // Use TYPE_INT_RGB to avoid unnecessary alpha blending overhead during draw
        this.image = new BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB);
        this.image.setAccelerationPriority(1.0f); // Hint to JVM to prioritize acceleration
        this.imageData = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        setPreferredSize(new Dimension(256 * this.scale, 240 * this.scale));
    }

    /**
     * Set source framebuffer (must be 256*240 length).
     * 
     * @param argb
     */
    public void setFrameBuffer(int[] argb) {
        this.source = argb;
    }

    /**
     * Copy latest emulator framebuffer into backing image and schedule Swing
     * repaint.
     */
    public void blitAndRepaint() {
        blit();
        repaint();
    }

    /** Copy latest emulator framebuffer into backing image (no repaint). */
    public void blit() {
        int[] src = source;
        if (src != null) {
            System.arraycopy(src, 0, imageData, 0, 256 * 240);
        }
    }

    /** Draw current image + overlay (scaled) into provided Graphics2D. */
    public void drawTo(Graphics2D g) {
        g.drawImage(image, 0, 0, 256 * scale, 240 * scale, null);
        Consumer<Graphics2D> ov = overlay;
        if (ov != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.scale(scale, scale);
                ov.accept(g2);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Set overlay drawing function (can be null to disable).
     * 
     * @param overlay
     */
    public void setOverlay(Consumer<Graphics2D> overlay) {
        this.overlay = overlay;
    }

    /**
     * Get current scale factor.
     * 
     * @return
     */
    public int getScale() {
        return scale;
    }

    /**
     * Get the backing image (for direct drawing or saving).
     * 
     * @return
     */
    public BufferedImage getImage() {
        return image;
    }

    /**
     * Get the overlay drawing function (can be null).
     * 
     * @return
     */
    public Consumer<Graphics2D> getOverlay() {
        return overlay;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        // Test: render with -8px offset on X and Y to reduce visible black borders
        int ox = -8 * scale;
        int oy = -8 * scale;
        g.drawImage(image, ox, oy, 256 * scale, 240 * scale, null);
        Consumer<Graphics2D> ov = overlay;
        if (ov != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.scale(scale, scale); // draw overlay in NES pixel space
                g2.translate(-8, -8); // match the -8px NES offset
                ov.accept(g2);
            } finally {
                g2.dispose();
            }
        }
    }
}

package com.nesemu.gui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

/** Simple panel that draws a 256x240 framebuffer (int ARGB array). */
public class VideoRenderer extends JPanel {
    private final BufferedImage image;
    private volatile int[] source; // reference to emulator ARGB buffer
    private final int scale;

    public VideoRenderer(int scale) {
        this.scale = Math.max(1, scale);
        this.image = new BufferedImage(256, 240, BufferedImage.TYPE_INT_ARGB);
        setPreferredSize(new Dimension(256 * this.scale, 240 * this.scale));
        setDoubleBuffered(true);
    }

    public void setFrameBuffer(int[] argb) {
        this.source = argb;
    }

    public void blitAndRepaint() {
        if (source != null) {
            image.setRGB(0, 0, 256, 240, source, 0, 256);
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(image, 0, 0, 256 * scale, 240 * scale, null);
    }
}

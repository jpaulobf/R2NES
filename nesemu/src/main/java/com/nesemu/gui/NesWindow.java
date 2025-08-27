package com.nesemu.gui;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Basic Swing window that displays the current NES framebuffer. */
public class NesWindow {
    private final JFrame frame;
    private final VideoRenderer renderer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    // FPS tracking
    private volatile double lastFps = 0.0;
    private long fpsWindowStart = 0L;
    private int fpsFrames = 0;

    public NesWindow(String title, int scale) {
        renderer = new VideoRenderer(scale);
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(renderer);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public void show(int[] framebuffer) {
        renderer.setFrameBuffer(framebuffer);
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    public void setOverlay(Consumer<Graphics2D> overlay) {
        renderer.setOverlay(overlay);
    }

    public void requestClose() {
        running.set(false);
        SwingUtilities.invokeLater(frame::dispose);
    }

    public void startRenderLoop(Runnable perFrame, int targetFps) {
        if (running.getAndSet(true))
            return;
        Thread t = new Thread(() -> {
            final long frameDurationNanos = targetFps > 0 ? 1_000_000_000L / targetFps : 0L;
            fpsWindowStart = System.nanoTime();
            while (running.get()) {
                long start = System.nanoTime();
                perFrame.run(); // emulator advances a frame
                renderer.blitAndRepaint();
                fpsFrames++;
                long now = System.nanoTime();
                long windowElapsed = now - fpsWindowStart;
                if (windowElapsed >= 1_000_000_000L) {
                    lastFps = fpsFrames / (windowElapsed / 1_000_000_000.0);
                    fpsFrames = 0;
                    fpsWindowStart = now;
                }
                if (frameDurationNanos > 0) {
                    long elapsed = System.nanoTime() - start;
                    long sleep = frameDurationNanos - elapsed;
                    if (sleep > 0) {
                        try {
                            Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }, "NES-RenderLoop");
        t.setDaemon(true);
        t.start();
    }

    public double getLastFps() {
        return lastFps;
    }
}

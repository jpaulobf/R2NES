package com.nesemu.gui;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
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

    /** Install key listener mapping key pressed/released events to controllers. */
    public void installControllerKeyListener(com.nesemu.io.NesController p1, com.nesemu.io.NesController p2) {
        installControllerKeyListener(p1, p2, null, null);
    }

    /**
     * Extended variant allowing a special reset key token that fires a callback.
     */
    public void installControllerKeyListener(com.nesemu.io.NesController p1, com.nesemu.io.NesController p2,
            String resetToken, Runnable onReset) {
        final String resetTok = resetToken == null ? null : resetToken.toLowerCase();
        frame.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handle(e, true);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                handle(e, false);
            }

            private void handle(KeyEvent e, boolean down) {
                String token = keyEventToToken(e);
                if (token == null)
                    return;
                if (down && resetTok != null && token.equals(resetTok) && onReset != null) {
                    onReset.run();
                    return; // do not forward reset key to controllers
                }
                if (p1 != null)
                    p1.setKeyTokenState(token, down);
                if (p2 != null)
                    p2.setKeyTokenState(token, down);
            }

            private String keyEventToToken(KeyEvent e) {
                int code = e.getKeyCode();
                switch (code) {
                    case KeyEvent.VK_UP:
                        return "up"; // arrow synonyms
                    case KeyEvent.VK_DOWN:
                        return "down";
                    case KeyEvent.VK_LEFT:
                        return "left";
                    case KeyEvent.VK_RIGHT:
                        return "right";
                    case KeyEvent.VK_ENTER:
                        return "enter";
                    case KeyEvent.VK_BACK_SPACE:
                        return "backspace";
                    case KeyEvent.VK_SPACE:
                        return "space";
                    case KeyEvent.VK_ESCAPE:
                        return "escape";
                    case KeyEvent.VK_TAB:
                        return "tab";
                    case KeyEvent.VK_F1:
                        return "f1";
                    case KeyEvent.VK_F2:
                        return "f2";
                    case KeyEvent.VK_F3:
                        return "f3";
                    case KeyEvent.VK_F4:
                        return "f4";
                    case KeyEvent.VK_F5:
                        return "f5";
                    case KeyEvent.VK_F6:
                        return "f6";
                    case KeyEvent.VK_F7:
                        return "f7";
                    case KeyEvent.VK_F8:
                        return "f8";
                    case KeyEvent.VK_F9:
                        return "f9";
                    case KeyEvent.VK_F10:
                        return "f10";
                    case KeyEvent.VK_F11:
                        return "f11";
                    case KeyEvent.VK_F12:
                        return "f12";
                    case KeyEvent.VK_CONTROL: {
                        int loc = e.getKeyLocation();
                        if (loc == KeyEvent.KEY_LOCATION_LEFT)
                            return "lcontrol";
                        if (loc == KeyEvent.KEY_LOCATION_RIGHT)
                            return "rcontrol";
                        return "control";
                    }
                    case KeyEvent.VK_SHIFT: {
                        int loc = e.getKeyLocation();
                        if (loc == KeyEvent.KEY_LOCATION_LEFT)
                            return "lshift";
                        if (loc == KeyEvent.KEY_LOCATION_RIGHT)
                            return "rshift";
                        return "shift";
                    }
                    default:
                        char ch = e.getKeyChar();
                        if (Character.isLetterOrDigit(ch))
                            return String.valueOf(Character.toLowerCase(ch));
                        return null;
                }
            }
        });
    }
}

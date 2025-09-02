package com.nesemu.gui;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.Graphics2D;
import java.awt.Canvas;
import java.awt.image.BufferStrategy;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Basic Swing window that displays the current NES framebuffer. */
public class NesWindow {
    private final JFrame frame;
    private final VideoRenderer renderer; // Swing path
    private final Canvas canvas; // Active rendering path
    private volatile boolean useBufferStrategy = true; // default to active BS
    private BufferStrategy bufferStrategy;
    private final AtomicBoolean running = new AtomicBoolean(false);
    // FPS tracking
    private volatile double lastFps = 0.0;
    private long fpsWindowStart = 0L;
    private int fpsFrames = 0;

    public NesWindow(String title, int scale) {
        renderer = new VideoRenderer(scale);
        canvas = new Canvas();
        canvas.setPreferredSize(new java.awt.Dimension(256 * scale, 240 * scale));
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // Adiciona inicialmente o canvas (path ativo). Se usuário desabilitar depois,
        // faremos swap.
        frame.getContentPane().add(canvas);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public void show(int[] framebuffer) {
        renderer.setFrameBuffer(framebuffer);
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
        // Lazy BS creation after showing (required on some platforms)
        SwingUtilities.invokeLater(() -> {
            if (useBufferStrategy && bufferStrategy == null) {
                try {
                    canvas.createBufferStrategy(3); // triple buffer to reduce tearing
                    bufferStrategy = canvas.getBufferStrategy();
                } catch (Exception e) {
                    useBufferStrategy = false; // fallback silently
                    // Garantir que painel Swing entre em cena se BS falhou
                    if (canvas.getParent() != null) {
                        frame.getContentPane().remove(canvas);
                    }
                    if (renderer.getParent() == null) {
                        frame.getContentPane().add(renderer);
                        frame.pack();
                    }
                }
            }
        });
    }

    public void setOverlay(Consumer<Graphics2D> overlay) {
        renderer.setOverlay(overlay);
    }

    public void requestClose() {
        running.set(false);
        SwingUtilities.invokeLater(frame::dispose);
    }

    public enum PacerMode {
        LEGACY, HR
    }

    public void startRenderLoop(Runnable perFrame, int targetFps) {
        startRenderLoop(perFrame, targetFps, PacerMode.HR);
    }

    public void startRenderLoop(Runnable perFrame, int targetFps, PacerMode mode) {
        if (running.getAndSet(true))
            return;
        Thread t = new Thread(() -> {
            switch (mode) {
                case LEGACY -> runLegacyLoop(perFrame, targetFps);
                case HR -> runHighResLoop(perFrame, targetFps);
            }
        }, "NES-RenderLoop");
        t.setDaemon(true);
        t.start();
    }

    private void runLegacyLoop(Runnable perFrame, int targetFps) {
        final long frameDurationNanos = targetFps > 0 ? 1_000_000_000L / targetFps : 0L;
        fpsWindowStart = System.nanoTime();
        while (running.get()) {
            long start = System.nanoTime();
            perFrame.run();
            blitAndPresent();
            fpsFrames++;
            long now = System.nanoTime();
            long windowElapsed = now - fpsWindowStart;
            if (windowElapsed >= 1_000_000_000L) {
                lastFps = fpsFrames / (windowElapsed / 1_000_000_000.0);
                fpsFrames = 0;
                fpsWindowStart = now;
            }
            if (frameDurationNanos > 0) {
                long elapsed = now - start;
                long sleep = frameDurationNanos - elapsed;
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }

    private void runHighResLoop(Runnable perFrame, int targetFps) {
        final long frameDur = targetFps > 0 ? 1_000_000_000L / targetFps : 0L;
        if (frameDur == 0L) {
            // Sem alvo: roda o mais rápido possível
            fpsWindowStart = System.nanoTime();
            while (running.get()) {
                perFrame.run();
                blitAndPresent();
            }
            return;
        }
        long next = System.nanoTime() + frameDur;
        fpsWindowStart = System.nanoTime();
        while (running.get()) {
            perFrame.run();
            blitAndPresent();
            fpsFrames++;
            long now = System.nanoTime();
            long winElapsed = now - fpsWindowStart;
            if (winElapsed >= 1_000_000_000L) {
                lastFps = fpsFrames / (winElapsed / 1_000_000_000.0);
                fpsFrames = 0;
                fpsWindowStart = now;
            }
            // Espera até 'next'
            long remaining;
            while ((remaining = next - (now = System.nanoTime())) > 2_000_000L) { // >2ms
                try {
                    // Dorme maior parte, deixando ~0.5ms para spin
                    long sleepNs = remaining - 500_000L;
                    if (sleepNs <= 0)
                        break;
                    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                } catch (InterruptedException ignored) {
                }
            }
            while ((remaining = next - (now = System.nanoTime())) > 0) {
                Thread.onSpinWait();
            }
            // Agenda próximo frame
            next += frameDur;
            // Se ficamos MUITO atrasados (mais de 3 frames), realinha suavemente
            if (now - next > frameDur * 3L) {
                next = now + frameDur; // realinha se backlog exagerado
            }
            // Opcional: se frequentemente atrasado, relaxa spin (não implementado ainda)
        }
    }

    private void blitAndPresent() {
        if (useBufferStrategy && bufferStrategy != null) {
            renderer.blit();
            do {
                do {
                    Graphics2D g = (Graphics2D) bufferStrategy.getDrawGraphics();
                    try {
                        renderer.drawTo(g);
                    } finally {
                        g.dispose();
                    }
                } while (bufferStrategy.contentsRestored());
                bufferStrategy.show();
            } while (bufferStrategy.contentsLost());
        } else {
            renderer.blitAndRepaint();
        }
    }

    public void setUseBufferStrategy(boolean enable) {
        if (this.useBufferStrategy == enable)
            return;
        this.useBufferStrategy = enable;
        SwingUtilities.invokeLater(() -> {
            if (enable) {
                // Swap para canvas + BS
                if (renderer.getParent() != null) {
                    frame.getContentPane().remove(renderer);
                }
                if (canvas.getParent() == null) {
                    frame.getContentPane().add(canvas);
                }
                frame.revalidate();
                frame.pack();
                try {
                    canvas.createBufferStrategy(3);
                    bufferStrategy = canvas.getBufferStrategy();
                } catch (Exception e) {
                    useBufferStrategy = false; // falhou, volta para Swing
                    bufferStrategy = null;
                    // Re-adiciona renderer
                    if (canvas.getParent() != null)
                        frame.getContentPane().remove(canvas);
                    if (renderer.getParent() == null)
                        frame.getContentPane().add(renderer);
                    frame.revalidate();
                    frame.pack();
                }
            } else {
                // Swap para painel Swing
                bufferStrategy = null; // GC reclaim
                if (canvas.getParent() != null) {
                    frame.getContentPane().remove(canvas);
                }
                if (renderer.getParent() == null) {
                    frame.getContentPane().add(renderer);
                }
                frame.revalidate();
                frame.pack();
            }
        });
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

package com.nesemu.gui;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.JMenuBar;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import com.nesemu.io.NesController;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Canvas;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Path;
import java.io.File;

/**
 * NES window with integrated rendering loop.
 * Supports two rendering paths:
 * - Active rendering with Canvas + BufferStrategy (default, better performance)
 * - Passive rendering with JPanel + repaint (fallback, more compatible)
 */
public class NesWindow {

    // GUI components
    private final JFrame frame;
    private final VideoRenderer renderer; // Swing path
    private final Canvas canvas; // Active rendering path
    private Rectangle windowedBounds = null;

    // Active rendering state
    private BufferStrategy bufferStrategy;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean useBufferStrategy = true; // default to active BS
    private boolean borderless = false;

    // When true render thread skips presenting (during fullscreen transitions)
    private volatile boolean suspendRendering = false;

    // Proportion modes: 0 = normal (fixed scale centered), 1 = aspect (fit height),
    // 2 = stretch fill window
    private volatile int proportionMode = 0;

    // FPS tracking
    private volatile double lastFps = 0.0;
    private long fpsWindowStart = 0L;
    private int fpsFrames = 0;
    private volatile boolean fastForward = false;
    private volatile int fastForwardMaxFps = 0; // 0 = ilimitado
    private long ffFpsWindowStart = 0L;
    private int ffFpsFrames = 0;

    // Frame timing instrumentation
    private volatile long lastFrameNanos = 0L; // duração do frame anterior
    private volatile double avgFrameNanos = 0.0; // média exponencial suavizada
    private volatile long worstFrameNanos = 0L; // pior frame (reinicia a cada janela de FPS)
    private volatile double jitterNanos = 0.0; // média exponencial da diferença absoluta
    private static final double FRAME_AVG_ALPHA = 0.08;
    private static final double JITTER_ALPHA = 0.08;
    private Cursor hiddenCursor = null;

    // --------------------------- Menu Bar ------------------------------------
    private Runnable onResetCallback; // optional external hook
    private Runnable onExitCallback; // optional external hook (if null -> window dispose)
    private Runnable onCloseRomCallback; // optional external hook for closing current ROM
    private Consumer<Path> onLoadRomCallback; // invoked with selected ROM path
    private Runnable onBeforeOpenLoadRomDialog; // optional hook to pause gameplay before dialog
    private Runnable onAfterLoadRomDialogCancelled; // optional hook to restore state when user cancels
    private Runnable onMiscMenuCallback; // optional hook for the Misc menu item
    private volatile File fileChooserStartDir; // preferred starting directory
    private javax.swing.JMenuItem resetMenuItem;
    private javax.swing.JMenuItem closeRomMenuItem;

    /**
     * Constructor, default scale=2.
     * 
     * @param title
     * @param scale
     */
    public NesWindow(String title, int scale) {
        renderer = new VideoRenderer(scale);
        canvas = new Canvas();
        canvas.setPreferredSize(new java.awt.Dimension(256 * scale, 240 * scale));
        canvas.setBackground(Color.BLACK);
        canvas.setFocusable(true);
        // Allow TAB (and Shift+TAB) to reach KeyListeners (otherwise Swing uses for
        // focus traversal)
        canvas.setFocusTraversalKeysEnabled(false);
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().setBackground(Color.BLACK);
        frame.getRootPane().setDoubleBuffered(false);
        frame.getContentPane().add(canvas);
        frame.pack();
        frame.setLocationRelativeTo(null);
        // Also disable traversal on frame to be safe
        frame.setFocusTraversalKeysEnabled(false);
        buildMenuBar();
    }

    /**
     * Show the window (must be called from Swing thread).
     * 
     * @param framebuffer
     */
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

    /**
     * Update the framebuffer reference without altering visibility/state. Useful
     * when a new ROM/emulator instance is loaded at runtime.
     * 
     * @param framebuffer int[256*240] ARGB buffer from PPU
     */
    public void setFrameBuffer(int[] framebuffer) {
        renderer.setFrameBuffer(framebuffer);
    }

    /** Set callback invoked when user activates File->Reset. */
    public void setOnReset(Runnable r) {
        this.onResetCallback = r;
    }

    /** Set callback invoked when user activates File->Exit. */
    public void setOnExit(Runnable r) {
        this.onExitCallback = r;
    }

    /** Set callback invoked when user activates File->Close ROM. */
    public void setOnCloseRom(Runnable r) {
        this.onCloseRomCallback = r;
    }

    /** Set callback for File->Load ROM (receives Path). */
    public void setOnLoadRom(Consumer<Path> c) {
        this.onLoadRomCallback = c;
    }

    /** Set callback invoked right before showing the Load ROM dialog. */
    public void setOnBeforeOpenLoadRomDialog(Runnable r) {
        this.onBeforeOpenLoadRomDialog = r;
    }

    /** Set callback invoked after closing Load ROM dialog when user cancels. */
    public void setOnAfterLoadRomDialogCancelled(Runnable r) {
        this.onAfterLoadRomDialogCancelled = r;
    }

    /** Set callback invoked when the Options->Misc item is selected. */
    public void setOnMiscMenuSelected(Runnable r) {
        this.onMiscMenuCallback = r;
    }

    /** Define diretório inicial preferido para o diálogo de Load ROM. */
    public void setFileChooserStartDir(Path dir) {
        try {
            this.fileChooserStartDir = (dir != null) ? dir.toFile() : null;
        } catch (Exception ignore) {
        }
    }

    /**
     * Build the menu bar and attach to frame.
     */
    private void buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem load = new JMenuItem("Load ROM...");
        load.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(
                    new FileNameExtensionFilter("NES ROM / ZIP (*.nes; *.zip)", new String[] { "nes", "zip" }));
            try {
                if (fileChooserStartDir != null && fileChooserStartDir.isDirectory()) {
                    fc.setCurrentDirectory(fileChooserStartDir);
                }
            } catch (Exception ignore) {
            }
            // Allow clients (Main) to pause before blocking dialog
            if (onBeforeOpenLoadRomDialog != null) {
                try {
                    onBeforeOpenLoadRomDialog.run();
                } catch (Exception ignore) {
                }
            }
            int res = fc.showOpenDialog(frame);
            if (res == JFileChooser.APPROVE_OPTION && onLoadRomCallback != null) {
                try {
                    File sel = fc.getSelectedFile();
                    if (sel != null) {
                        try {
                            fileChooserStartDir = sel.getParentFile();
                        } catch (Exception ignore2) {
                        }
                        onLoadRomCallback.accept(sel.toPath());
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame, "Failed to load ROM: " + ex.getMessage(),
                            "Load ROM", JOptionPane.ERROR_MESSAGE);
                    // Consider this a cancel for pause restoration purposes
                    if (onAfterLoadRomDialogCancelled != null) {
                        try {
                            onAfterLoadRomDialogCancelled.run();
                        } catch (Exception ignore) {
                        }
                    }
                }
            } else {
                // User cancelled
                if (onAfterLoadRomDialogCancelled != null) {
                    try {
                        onAfterLoadRomDialogCancelled.run();
                    } catch (Exception ignore) {
                    }
                }
            }
            restoreFocus();
        });
        closeRomMenuItem = new JMenuItem("Close ROM");
        closeRomMenuItem.addActionListener(e -> {
            if (onCloseRomCallback != null)
                onCloseRomCallback.run();
            restoreFocus();
        });
        resetMenuItem = new JMenuItem("Reset");
        resetMenuItem.addActionListener(e -> {
            if (onResetCallback != null)
                onResetCallback.run();
            restoreFocus();
        });
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> {
            if (onExitCallback != null)
                onExitCallback.run();
            else
                requestClose();
        });
        file.add(load);
        file.add(closeRomMenuItem);
        file.add(resetMenuItem);
        file.addSeparator();
        file.add(exit);
        mb.add(file);

        // Options menu (placeholders, no functionality yet)
        JMenu options = new JMenu("Options");
        JMenuItem miInput = new JMenuItem("Input");
        JMenuItem miVideo = new JMenuItem("Video");
        JMenuItem miAudio = new JMenuItem("Audio");
        JMenuItem miMisc = new JMenuItem("Misc");
        miMisc.addActionListener(e -> {
            if (onMiscMenuCallback != null) {
                try {
                    onMiscMenuCallback.run();
                } catch (Exception ignore) {
                }
            }
            restoreFocus();
        });
        options.add(miInput);
        options.add(miVideo);
        options.add(miAudio);
        options.add(miMisc);
        mb.add(options);
        frame.setJMenuBar(mb);
    }

    /**
     * Enable/disable Reset menu item. Safe to call from any thread.
     */
    public void setResetEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            if (resetMenuItem != null)
                resetMenuItem.setEnabled(enabled);
        });
    }

    /**
     * Enable/disable Close ROM menu item. Safe to call from any thread.
     */
    public void setCloseRomEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            if (closeRomMenuItem != null)
                closeRomMenuItem.setEnabled(enabled);
        });
    }

    /**
     * Convenience to toggle both Reset and Close ROM at once.
     */
    public void setRomActionsEnabled(boolean enabled) {
        setResetEnabled(enabled);
        setCloseRomEnabled(enabled);
    }

    /**
     * Set or unset borderless fullscreen mode. If the window is already visible, it
     * will dispose/re-show to apply decoration change.
     * 
     * @param enabled
     */
    public void setBorderlessFullscreen(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            suspendRendering = true; // pause drawing while peer recreated
            boolean needChange = frame.isUndecorated() != enabled;
            if (needChange) {
                if (enabled) {
                    windowedBounds = frame.getBounds();
                }
                // Dispose & reconfigure decorations
                bufferStrategy = null; // invalidate
                frame.dispose();
                frame.setUndecorated(enabled);
                frame.setVisible(true);
                if (enabled) {
                    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                    hideCursor();
                } else if (windowedBounds != null) {
                    frame.setBounds(windowedBounds);
                    showCursor();
                }
                borderless = enabled;
            } else if (enabled) { // already undecorated, just ensure maximized
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                hideCursor();
            } else if (!enabled) {
                showCursor();
            }

            if (useBufferStrategy) {
                // Recreate strategy in a later tick so new native peer is ready
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (canvas.getParent() == null) {
                            // ensure canvas in hierarchy
                            if (renderer.getParent() != null)
                                frame.getContentPane().remove(renderer);
                            frame.getContentPane().add(canvas);
                            frame.revalidate();
                        }
                        if (canvas.isDisplayable()) {
                            canvas.createBufferStrategy(3);
                            bufferStrategy = canvas.getBufferStrategy();
                        }
                        // Second chance if first failed (e.g. still not displayable)
                        if (bufferStrategy == null) {
                            SwingUtilities.invokeLater(() -> {
                                try {
                                    if (canvas.isDisplayable()) {
                                        canvas.createBufferStrategy(3);
                                        bufferStrategy = canvas.getBufferStrategy();
                                    }
                                } catch (Exception ignore) {
                                } finally {
                                    suspendRendering = false;
                                    restoreFocus();
                                }
                            });
                        } else {
                            suspendRendering = false;
                            restoreFocus();
                        }
                    } catch (Exception ex) {
                        // Fallback to Swing path
                        useBufferStrategy = false;
                        bufferStrategy = null;
                        if (canvas.getParent() != null)
                            frame.getContentPane().remove(canvas);
                        if (renderer.getParent() == null)
                            frame.getContentPane().add(renderer);
                        frame.revalidate();
                        suspendRendering = false;
                        restoreFocus();
                    }
                });
            } else {
                suspendRendering = false;
                restoreFocus();
            }
        });
    }

    /**
     * Hide mouse cursor (used in borderless fullscreen).
     */
    private void hideCursor() {
        try {
            if (hiddenCursor == null) {
                Toolkit tk = Toolkit.getDefaultToolkit();
                java.awt.Image img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                hiddenCursor = tk.createCustomCursor(img, new java.awt.Point(0, 0), "hidden");
            }
            frame.setCursor(hiddenCursor);
            canvas.setCursor(hiddenCursor);
        } catch (Exception ignore) {
        }
    }

    /**
     * Show mouse cursor (used when exiting borderless fullscreen).
     */
    private void showCursor() {
        try {
            java.awt.Cursor def = Cursor.getDefaultCursor();
            frame.setCursor(def);
            canvas.setCursor(def);
        } catch (Exception ignore) {
        }
    }

    /**
     * Return whether borderless fullscreen mode is active.
     * 
     * @return
     */
    public boolean isBorderlessFullscreen() {
        return borderless;
    }

    /**
     * Expose underlying JFrame for advanced integrations (limited use).
     */
    public JFrame getFrame() {
        return frame;
    }

    /**
     * Add a KeyListener to both frame and canvas so it keeps working regardless of
     * focus target.
     */
    public void addGlobalKeyListener(KeyListener l) {
        frame.addKeyListener(l);
        canvas.addKeyListener(l);
    }

    /**
     * 
     * @param overlay
     */
    public void setOverlay(Consumer<Graphics2D> overlay) {
        renderer.setOverlay(overlay);
    }

    /**
     * Request the window to close (stop render loop and dispose frame).
     */
    public void requestClose() {
        running.set(false);
        SwingUtilities.invokeLater(frame::dispose);
    }

    /**
     * Start the rendering loop in a separate thread. If already running, does
     * nothing.
     * 
     * @param perFrame
     * @param targetFps
     */
    public void startRenderLoop(Runnable perFrame, int targetFps) {
        startRenderLoop(perFrame, targetFps, PacerMode.HR);
    }

    /**
     * Start the rendering loop in a separate thread. If already running, does
     * nothing.
     * 
     * @param perFrame
     * @param targetFps
     * @param mode
     */
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

    /**
     * Runs Legacy render loop with simple sleep pacing.
     * 
     * @param perFrame
     * @param targetFps
     */
    private void runLegacyLoop(Runnable perFrame, int targetFps) {
        final long frameDurationNanos = targetFps > 0 ? 1_000_000_000L / targetFps : 0L;
        fpsWindowStart = System.nanoTime();
        while (running.get()) {
            long start = System.nanoTime();
            perFrame.run();
            blitAndPresent();
            fpsFrames++;
            long end = System.nanoTime();
            long frameNs = end - start;
            lastFrameNanos = frameNs;
            if (avgFrameNanos == 0) {
                avgFrameNanos = frameNs;
                jitterNanos = 0;
            } else {
                avgFrameNanos = avgFrameNanos * (1 - FRAME_AVG_ALPHA) + frameNs * FRAME_AVG_ALPHA;
                double delta = Math.abs(frameNs - avgFrameNanos);
                jitterNanos = jitterNanos * (1 - JITTER_ALPHA) + delta * JITTER_ALPHA;
            }
            if (frameNs > worstFrameNanos)
                worstFrameNanos = frameNs;
            if (fastForward && fastForwardMaxFps > 0) {
                // Throttle simples baseado em janela de 1 segundo
                long nowCheck = System.nanoTime();
                if (ffFpsWindowStart == 0L)
                    ffFpsWindowStart = nowCheck;
                ffFpsFrames++;
                long winElapsed = nowCheck - ffFpsWindowStart;
                if (winElapsed >= 1_000_000_000L) {
                    // reset a cada segundo
                    ffFpsFrames = 0;
                    ffFpsWindowStart = nowCheck;
                } else {
                    double currentFps = ffFpsFrames / (winElapsed / 1_000_000_000.0);
                    if (currentFps > fastForwardMaxFps) {
                        // dorme pequeno slice para reduzir
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
            long now = System.nanoTime();
            long windowElapsed = now - fpsWindowStart;
            if (windowElapsed >= 1_000_000_000L) {
                lastFps = fpsFrames / (windowElapsed / 1_000_000_000.0);
                fpsFrames = 0;
                fpsWindowStart = now;
            }
            if (!fastForward && frameDurationNanos > 0) {
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

    /**
     * Runs High-Res render loop with busy-wait pacing.
     * 
     * @param perFrame
     * @param targetFps
     */
    private void runHighResLoop(Runnable perFrame, int targetFps) {
        final long frameDur = targetFps > 0 ? 1_000_000_000L / targetFps : 0L;
        if (frameDur == 0L) {
            // Sem alvo: roda o mais rápido possível
            fpsWindowStart = System.nanoTime();
            while (running.get()) {
                long start = System.nanoTime();
                perFrame.run();
                blitAndPresent();
                long end = System.nanoTime();
                long frameNs = end - start;
                lastFrameNanos = frameNs;
                if (avgFrameNanos == 0) {
                    avgFrameNanos = frameNs;
                    jitterNanos = 0;
                } else {
                    avgFrameNanos = avgFrameNanos * (1 - FRAME_AVG_ALPHA) + frameNs * FRAME_AVG_ALPHA;
                    double delta = Math.abs(frameNs - avgFrameNanos);
                    jitterNanos = jitterNanos * (1 - JITTER_ALPHA) + delta * JITTER_ALPHA;
                }
                if (frameNs > worstFrameNanos)
                    worstFrameNanos = frameNs;
            }
            return;
        }
        long next = System.nanoTime() + frameDur;
        fpsWindowStart = System.nanoTime();
        while (running.get()) {
            long start = System.nanoTime();
            perFrame.run();
            blitAndPresent();
            long end = System.nanoTime();
            long frameNs = end - start;
            lastFrameNanos = frameNs;
            if (avgFrameNanos == 0) {
                avgFrameNanos = frameNs;
                jitterNanos = 0;
            } else {
                avgFrameNanos = avgFrameNanos * (1 - FRAME_AVG_ALPHA) + frameNs * FRAME_AVG_ALPHA;
                double delta = Math.abs(frameNs - avgFrameNanos);
                jitterNanos = jitterNanos * (1 - JITTER_ALPHA) + delta * JITTER_ALPHA;
            }
            if (frameNs > worstFrameNanos)
                worstFrameNanos = frameNs;
            fpsFrames++;
            if (fastForward && fastForwardMaxFps > 0) {
                long nowCheck = System.nanoTime();
                if (ffFpsWindowStart == 0L)
                    ffFpsWindowStart = nowCheck;
                ffFpsFrames++;
                long winElapsed = nowCheck - ffFpsWindowStart;
                if (winElapsed >= 1_000_000_000L) {
                    ffFpsFrames = 0;
                    ffFpsWindowStart = nowCheck;
                } else {
                    double currentFps = ffFpsFrames / (winElapsed / 1_000_000_000.0);
                    if (currentFps > fastForwardMaxFps) {
                        // Aguarda leve para conter
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
            long now = System.nanoTime();
            long winElapsed = now - fpsWindowStart;
            if (winElapsed >= 1_000_000_000L) {
                lastFps = fpsFrames / (winElapsed / 1_000_000_000.0);
                fpsFrames = 0;
                fpsWindowStart = now;
            }
            if (fastForward) {
                // Pula qualquer espera e realinha next para evitar drift acumulado
                next = System.nanoTime() + frameDur;
                continue;
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
            // Atrasou mais que 1 frame? Realinha imediatamente (evita burst).
            if (now - next > frameDur) {
                next = now + frameDur;
            }
        }
    }

    /**
     * Verify and perform blit + present according to current mode.
     */
    private void blitAndPresent() {
        if (suspendRendering) {
            return; // skip while transitioning
        }
        if (useBufferStrategy) {
            if (bufferStrategy == null) {
                // Try lazy creation (covers cases where initial show toggled quickly)
                try {
                    if (canvas.isDisplayable()) {
                        canvas.createBufferStrategy(3);
                        bufferStrategy = canvas.getBufferStrategy();
                    }
                } catch (Exception ignored) {
                    // If still null, skip frame
                }
                if (bufferStrategy == null) {
                    return;
                }
            }
            // From here bufferStrategy is non-null
            renderer.blit();
            // Determine destination rectangle according to proportionMode
            int baseScaleW = 256 * renderer.getScale();
            int baseScaleH = 240 * renderer.getScale();
            int winW = frame.getWidth();
            int winH = frame.getHeight();
            int nesW, nesH, cx, cy; // dest rect and position
            double scaleX, scaleY; // overlay scaling factors
            int mode = proportionMode; // local copy
            switch (mode) {
                default:
                case 0: // normal centered integer scaling
                    nesW = baseScaleW;
                    nesH = baseScaleH;
                    cx = (winW - nesW) / 2;
                    cy = (winH - nesH) / 2;
                    scaleX = renderer.getScale();
                    scaleY = renderer.getScale();
                    break;
                case 1: // proportional scaled to full window height (maintain aspect)
                    nesH = winH;
                    nesW = (int) Math.round(nesH * (256.0 / 240.0));
                    // position: top centered horizontally
                    cx = (winW - nesW) / 2;
                    cy = 0;
                    scaleY = nesH / 240.0;
                    scaleX = nesW / 256.0;
                    break;
                case 2: // stretched fill
                    nesW = winW;
                    nesH = winH;
                    cx = 0;
                    cy = 0;
                    scaleX = nesW / 256.0;
                    scaleY = nesH / 240.0;
                    break;
            }
            try {
                do {
                    do {
                        Graphics2D g = (Graphics2D) bufferStrategy.getDrawGraphics();
                        try {
                            g.setColor(Color.BLACK);
                            g.fillRect(0, 0, winW, winH);
                            // Test: render with -8 NES px offset in both axes
                            int offsetX = (int) Math.round(cx + (-8 * scaleX));
                            int offsetY = (int) Math.round(cy + (-8 * scaleY));
                            g.drawImage(renderer.getImage(), offsetX, offsetY, nesW, nesH, null);
                            var ov = renderer.getOverlay();
                            if (ov != null) {
                                Graphics2D g2 = (Graphics2D) g.create();
                                try {
                                    g2.translate(offsetX, offsetY);
                                    g2.scale(scaleX, scaleY);
                                    g2.translate(-8, -8); // NES-space -8px offset for overlay
                                    try {
                                        ov.accept(g2);
                                    } catch (Throwable overlayErr) {
                                        // Keep rendering even if overlay fails; avoids black screen
                                    }
                                } finally {
                                    g2.dispose();
                                }
                            }
                        } finally {
                            g.dispose();
                        }
                    } while (bufferStrategy.contentsRestored());
                    bufferStrategy.show();
                } while (bufferStrategy != null && bufferStrategy.contentsLost());
            } catch (NullPointerException | IllegalStateException race) {
                // Race: strategy invalidated mid-draw. Reset and skip.
                bufferStrategy = null;
            }
        } else {
            renderer.blitAndRepaint();
        }
    }

    /** Cycle proportion mode 0->1->2->0. */
    public void cycleProportionMode() {
        int next = (proportionMode + 1) % 3;
        proportionMode = next;
        restoreFocus();
    }

    /**
     * Get current proportion mode.
     * 
     * @return
     */
    public int getProportionMode() {
        return proportionMode;
    }

    /**
     * Ensure focus is on canvas (if displayable) or frame (otherwise).
     */
    private void restoreFocus() {
        SwingUtilities.invokeLater(() -> {
            if (canvas.isDisplayable()) {
                if (!canvas.isFocusOwner()) {
                    canvas.requestFocus();
                }
            } else {
                if (!frame.isFocusOwner()) {
                    frame.requestFocus();
                }
            }
        });
    }

    /**
     * Enable or disable use of BufferStrategy (active rendering). If disabled, the
     * window will use Swing repaint path. This can be toggled at runtime, but
     * requires temporarily suspending rendering while the component hierarchy is
     * reconfigured. This method is safe to call from any thread, but the actual
     * change will be applied asynchronously on the Swing thread.
     * 
     * @param enable
     */
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

    /**
     * Get last measured FPS (updated about once per second).
     * 
     * @return
     */
    public double getLastFps() {
        return lastFps;
    }

    // Fast-forward API
    public void setFastForward(boolean enable) {
        this.fastForward = enable;
    }

    public boolean isFastForward() {
        return fastForward;
    }

    public void setFastForwardMaxFps(int max) {
        this.fastForwardMaxFps = Math.max(0, max);
    }

    public int getFastForwardMaxFps() {
        return fastForwardMaxFps;
    }

    /** Install key listener mapping key pressed/released events to controllers. */
    public void installControllerKeyListener(NesController p1, NesController p2) {
        installControllerKeyListener(p1, p2, null, null);
    }

    /**
     * Extended variant allowing a special reset key token that fires a callback.
     */
    public void installControllerKeyListener(NesController p1, NesController p2,
            String resetToken, Runnable onReset) {
        final String resetTok = resetToken == null ? null : resetToken.toLowerCase();
        KeyAdapter adapter = new KeyAdapter() {
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
        };
        frame.addKeyListener(adapter);
        canvas.addKeyListener(adapter);
    }

    // ----------------------------------- Helper ---------------------------------

    /**
     * Helper Enum
     */
    public enum PacerMode {
        LEGACY, HR
    }

    // ----------------------------- Instrumentation Getters ---------------------
    // getLastFps already defined earlier
    public long getLastFrameNanos() {
        return lastFrameNanos;
    }

    public double getAvgFrameNanos() {
        return avgFrameNanos;
    }

    public long getWorstFrameNanos() {
        return worstFrameNanos;
    }

    public double getJitterNanos() {
        return jitterNanos;
    }
}
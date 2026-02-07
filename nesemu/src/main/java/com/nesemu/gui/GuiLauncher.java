package com.nesemu.gui;

import com.nesemu.app.EmulatorContext;
import com.nesemu.audio.AudioPlayer;
import com.nesemu.config.AppOptions;
import com.nesemu.config.ConfigUtils;
import com.nesemu.config.RuntimeSettings;
import com.nesemu.config.UserConfig;
import com.nesemu.emulator.NesEmulator;
import com.nesemu.input.GamepadPoller;
import com.nesemu.io.NesController;
import com.nesemu.rom.INesRom;
import com.nesemu.rom.RomLoader;
import com.nesemu.util.Log;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.swing.JOptionPane;

import static com.nesemu.util.Log.Cat.GENERAL;

/**
 * Handles the graphical user interface setup and lifecycle.
 * Extracted from Main to reduce complexity.
 */
public class GuiLauncher {

    private final EmulatorContext context;
    private final AppOptions options;
    private final UserConfig userConfig;
    private final NesController pad1;
    private final NesController pad2;
    private final GamepadPoller gamepadPoller;

    // Transient UI state
    private boolean paused = false;
    private boolean pausePrev = false;
    private long resetMsgExpireNs = 0L;
    private long stateMsgExpireNs = 0L;
    private String stateMsg = null;
    private boolean hudState;

    public GuiLauncher(EmulatorContext context, AppOptions options, UserConfig userConfig,
                       NesController pad1, NesController pad2, GamepadPoller gamepadPoller) {
        this.context = context;
        this.options = options;
        this.userConfig = userConfig;
        this.pad1 = pad1;
        this.pad2 = pad2;
        this.gamepadPoller = gamepadPoller;
        this.hudState = options.hud;
    }

    public void launch() {
        String title = (context.romPath != null) ? ("R2-NES - " + context.romPath.getFileName()) : "R2-NES (no ROM)";
        NesWindow window = new NesWindow(title, 3);

        // Prevent automatic dispose so Alt+F4 / close button can confirm like ESC
        try {
            window.getFrame().setDefaultCloseOperation(javax.swing.JFrame.DO_NOTHING_ON_CLOSE);
        } catch (Exception ignore) {
        }

        configureWindowSettings(window);
        setupMenus(window);
        setupInputAndHotkeys(window);
        setupOverlay(window);

        // Prepare RuntimeSettings for ROM reloads
        RuntimeSettings runtimeSettings = new RuntimeSettings(options.tileMatrixMode,
                options.pipeLogLimit, options.dbgBgSample, options.dbgBgAll,
                options.timingSimple, options.timingModeOpt, options.forceBg, options.unlimitedSprites,
                options.spriteYMode, options.forceSprite0Hit, options.leftColumnModeOpt,
                options.logAttrLimit, options.logNtLimit, options.ntBaseline, options.paletteLogLimit,
                options.mmc1LogLimit, options.spinWatchThreshold, options.spinDumpBytes, options.initialMaskOverride);

        setupRomLoadingCallbacks(window, runtimeSettings);

        // Show and start loop
        window.show(context.emulator.getPpu().getFrameBuffer());
        Log.info(GENERAL, "Iniciando GUI (Ctrl+C para sair)");

        NesWindow.PacerMode pm = (options.pacerModeOpt == null || options.pacerModeOpt.equals("hr"))
                ? NesWindow.PacerMode.HR
                : NesWindow.PacerMode.LEGACY;
        Log.info(GENERAL, "Pacer mode: %s", pm);

        if (options.bufferStrategyOpt != null) {
            window.setUseBufferStrategy(options.bufferStrategyOpt);
            Log.info(GENERAL, "BufferStrategy: %s", options.bufferStrategyOpt ? "ON" : "OFF (Swing repaint)");
        } else {
            Log.info(GENERAL, "BufferStrategy: DEFAULT(ON)");
        }

        window.startRenderLoop(() -> {
            if (!paused) {
                context.emulator.stepFrame();
            } else {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignore) {
                }
            }
        }, 60, pm);
    }

    private void configureWindowSettings(NesWindow window) {
        // Fast-forward config
        if (options.fastForwardMaxFps < 0) options.fastForwardMaxFps = 0;
        if (options.fastForwardMaxFps > 0) {
            window.setFastForwardMaxFps(options.fastForwardMaxFps);
            Log.info(GENERAL, "Fast-Forward max FPS: %d", options.fastForwardMaxFps);
        }

        // Initial directory for file chooser
        Path chooserStartDir = userConfig.resolvePreferredRomDirectory();
        if (chooserStartDir == null) {
            try {
                if (options.romPath != null && !options.romPath.isBlank()) {
                    Path rp = Path.of(options.romPath);
                    if (Files.isDirectory(rp)) {
                        chooserStartDir = rp;
                    } else if (Files.isRegularFile(rp) && rp.getParent() != null) {
                        chooserStartDir = rp.getParent();
                    }
                }
            } catch (Exception ignore) {
            }
        }
        if (chooserStartDir != null) {
            window.setFileChooserStartDir(chooserStartDir);
        }

        // Borderless
        if (options.borderlessFullscreen != null && options.borderlessFullscreen) {
            window.setBorderlessFullscreen(true);
            Log.info(GENERAL, "Borderless fullscreen: ON");
        } else if (options.borderlessFullscreen != null) {
            Log.info(GENERAL, "Borderless fullscreen: OFF");
        }

        // Initial ROM actions state
        window.setRomActionsEnabled(context.romPath != null);
    }

    private void setupMenus(NesWindow window) {
        window.setOnMiscMenuSelected(() -> {
            RomDirectoryConfigDialog dialog = new RomDirectoryConfigDialog(window.getFrame(),
                    userConfig.getDefaultRomDirectory(), userConfig.getLastRomDirectory());
            RomDirectoryConfigDialog.Result result = dialog.showDialog();
            if (!result.isSaved()) return;

            boolean cleared = result.isCleared();
            Path chosen = result.getDirectory().orElse(null);
            if (cleared) {
                userConfig.clearDefaultRomDirectory();
            } else {
                userConfig.setDefaultRomDirectory(chosen);
            }
            if (!userConfig.save()) {
                JOptionPane.showMessageDialog(window.getFrame(),
                        "Não foi possível salvar a configuração do diretório padrão.",
                        "Configuração de ROM", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Path nextDir = userConfig.resolvePreferredRomDirectory();
            if (nextDir == null && context.romPath != null && context.romPath.getParent() != null) {
                nextDir = context.romPath.getParent();
            }
            window.setFileChooserStartDir(nextDir);

            if (cleared) {
                JOptionPane.showMessageDialog(window.getFrame(),
                        "Diretório padrão removido. O último diretório utilizado continuará sendo lembrado.",
                        "Configuração de ROM", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(window.getFrame(),
                        "Diretório padrão atualizado:\n" + chosen,
                        "Configuração de ROM", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        // Exit handler
        window.setOnExit(() -> confirmAndExit(window));

        // Window close handler
        try {
            window.getFrame().addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    confirmAndExit(window);
                }
            });
        } catch (Exception ex) {
            Log.warn(GENERAL, "Falha ao registrar window close confirm: %s", ex.getMessage());
        }
    }

    private void confirmAndExit(NesWindow window) {
        boolean romLoaded = (context.romPath != null);
        if (romLoaded) {
            pausePrev = paused;
            paused = true;
        }
        int res = JOptionPane.showConfirmDialog(window.getFrame(),
                "You really want exit?", "Confirm Exit",
                JOptionPane.YES_NO_OPTION);
        if (res == JOptionPane.YES_OPTION) {
            performExit();
        } else {
            if (romLoaded) paused = pausePrev;
        }
    }

    private void performExit() {
        try {
            if (context.emulator != null) {
                context.emulator.forceAutoSave();
                Log.info(GENERAL, "AutoSave (.sav) antes de sair");
            }
        } catch (Exception ex) {
            Log.warn(GENERAL, "Falha autosave na saída: %s", ex.getMessage());
        }
        try {
            if (gamepadPoller != null) gamepadPoller.stop();
        } catch (Exception ignore) {
        }
        System.exit(0);
    }

    private void setupInputAndHotkeys(NesWindow window) {
        if (pad1 != null) {
            String resetTok = options.resetKeyToken == null ? null : options.resetKeyToken.toLowerCase(Locale.ROOT).trim();
            window.installControllerKeyListener(pad1, pad2, resetTok, () -> {
                if (context.romPath != null) {
                    Log.info(GENERAL, "RESET key pressed (%s)", resetTok);
                    context.emulator.reset();
                    resetMsgExpireNs = System.nanoTime() + 2_000_000_000L;
                }
            });
        }

        // Load pause tokens
        final java.util.Set<String> pauseKeyTokens = new java.util.HashSet<>();
        try {
            var tmpCfg = ConfigUtils.loadInputConfig();
            pauseKeyTokens.addAll(ConfigUtils.getPauseKeyTokens(tmpCfg));
        } catch (Exception ignore) {
        }

        // Global hotkeys
        window.addGlobalKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    confirmAndExit(window);
                    return;
                }
                String tok = KeyTokens.from(e);
                if (tok == null) return;

                handleHotkey(tok, window, pauseKeyTokens);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                String tok = KeyTokens.from(e);
                if (tok == null) return;
                String ffKey = options.fastForwardKey == null ? null : options.fastForwardKey.toLowerCase(Locale.ROOT).trim();
                if (ffKey != null && tok.equals(ffKey)) {
                    if (window.isFastForward()) {
                        window.setFastForward(false);
                        Log.info(GENERAL, "Fast-Forward OFF");
                    }
                }
            }
        });
    }

    private void handleHotkey(String tok, NesWindow window, java.util.Set<String> pauseKeyTokens) {
        String fsKey = options.toggleFullscreenKey == null ? null : options.toggleFullscreenKey.toLowerCase(Locale.ROOT).trim();
        String hudKey = options.toggleHudKey == null ? null : options.toggleHudKey.toLowerCase(Locale.ROOT).trim();
        String propKey = options.toggleFullscreenProportionKey == null ? null : options.toggleFullscreenProportionKey.toLowerCase(Locale.ROOT).trim();
        String saveKey = options.saveStateKey == null ? null : options.saveStateKey.toLowerCase(Locale.ROOT).trim();
        String loadKey = options.loadStateKey == null ? null : options.loadStateKey.toLowerCase(Locale.ROOT).trim();
        String ffKey = options.fastForwardKey == null ? null : options.fastForwardKey.toLowerCase(Locale.ROOT).trim();
        String warnKey = options.logWarnKey == null ? null : options.logWarnKey.toLowerCase(Locale.ROOT).trim();

        if (fsKey != null && tok.equals(fsKey)) {
            boolean newState = !window.isBorderlessFullscreen();
            window.setBorderlessFullscreen(newState);
            Log.info(GENERAL, "Toggle fullscreen -> %s", newState ? "ON" : "OFF");
        }
        if (hudKey != null && tok.equals(hudKey)) {
            hudState = !hudState;
            Log.info(GENERAL, "Toggle HUD -> %s", hudState ? "ON" : "OFF");
        }
        if (propKey != null && tok.equals(propKey)) {
            window.cycleProportionMode();
            Log.info(GENERAL, "Proportion mode cycled");
        }
        if (saveKey != null && tok.equals(saveKey)) {
            handleSaveState();
        }
        if (loadKey != null && tok.equals(loadKey)) {
            handleLoadState();
        }
        if (ffKey != null && tok.equals(ffKey)) {
            if (context.romPath != null && !window.isFastForward()) {
                window.setFastForward(true);
                Log.info(GENERAL, "Fast-Forward ON");
            }
        }
        if (warnKey != null && tok.equals(warnKey)) {
            if (context.emulator != null) context.emulator.dumpWarnSnapshot("manual-hotkey");
        }
        if (!pauseKeyTokens.isEmpty() && pauseKeyTokens.contains(tok)) {
            if (context.romPath != null) {
                paused = !paused;
                Log.info(GENERAL, "Pause -> %s", paused ? "ON" : "OFF");
            }
        }
    }

    private void handleSaveState() {
        if (context.romPath == null) return;
        try {
            Path dir = (options.saveStatePath != null) ? Path.of(options.saveStatePath) : Path.of(".");
            Files.createDirectories(dir);
            String base = context.romPath.getFileName().toString();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            Path target = dir.resolve(base + ".state");
            context.emulator.saveState(target);
            Log.info(GENERAL, "SaveState salvo: %s", target.toAbsolutePath());
            stateMsg = "SAVING";
            stateMsgExpireNs = System.nanoTime() + 1_500_000_000L;
        } catch (Exception ex) {
            Log.warn(GENERAL, "Falha save-state: %s", ex.getMessage());
            stateMsg = "SAVE ERR";
            stateMsgExpireNs = System.nanoTime() + 1_500_000_000L;
        }
    }

    private void handleLoadState() {
        if (context.romPath == null) return;
        try {
            Path dir = (options.saveStatePath != null) ? Path.of(options.saveStatePath) : Path.of(".");
            String base = context.romPath.getFileName().toString();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            Path target = dir.resolve(base + ".state");
            if (context.emulator.loadState(target)) {
                Log.info(GENERAL, "SaveState carregado: %s", target.toAbsolutePath());
                stateMsg = "LOADING";
            } else {
                stateMsg = "NO STATE";
            }
            stateMsgExpireNs = System.nanoTime() + 1_500_000_000L;
        } catch (Exception ex) {
            Log.warn(GENERAL, "Falha load-state: %s", ex.getMessage());
            stateMsg = "LOAD ERR";
            stateMsgExpireNs = System.nanoTime() + 1_500_000_000L;
        }
    }

    private void setupOverlay(NesWindow window) {
        final boolean slEnabled = Boolean.parseBoolean(System.getProperty("r2nes.scanlines.enabled", "false"));
        final float slAlpha = Float.parseFloat(System.getProperty("r2nes.scanlines.alpha", "0.5"));

        window.setOverlay(g2 -> {
            var ppu = context.emulator.getPpu();
            // Scanlines
            if (slEnabled) {
                java.awt.Composite oldComp = g2.getComposite();
                g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, slAlpha));
                g2.setColor(java.awt.Color.BLACK);
                java.awt.Rectangle clip = g2.getClipBounds();
                int h = clip != null ? clip.height : 240 * 3;
                int w = clip != null ? clip.width : 256 * 3;
                for (int y = 0; y < h; y += 2) {
                    g2.drawLine(0, y, w, y);
                }
                g2.setComposite(oldComp);
            }
            // HUD
            if (hudState) {
                final int hudOffX = 12;
                final int hudOffY = 8;
                int padLocal = 4;
                String l1 = String.format("Frame:%d FPS:%.1f", ppu.getFrame(), window.getLastFps());
                String l2 = String.format("Scan:%d Cyc:%d VRAM:%04X", ppu.getScanline(), ppu.getCycle(),
                        ppu.getVramAddress() & 0x3FFF);
                String l3 = String.format("MASK:%02X STAT:%02X fineX:%d", ppu.getMaskRegister(),
                        ppu.getStatusRegister(), ppu.getFineX());
                String btns = pad1 != null ? pad1.pressedButtonsString() : "-";
                String l4 = "Pad1: " + btns;
                g2.setColor(new java.awt.Color(0, 0, 0, 160));
                g2.fillRect(hudOffX, hudOffY, 260, 56);
                g2.setColor(java.awt.Color.WHITE);
                g2.drawString(l1, padLocal + hudOffX, 12 + hudOffY);
                g2.drawString(l2, padLocal + hudOffX, 24 + hudOffY);
                g2.drawString(l3, padLocal + hudOffX, 36 + hudOffY);
                g2.drawString(l4, padLocal + hudOffX, 48 + hudOffY);
            }
            // Messages
            drawCenteredMessage(g2, "RESET", resetMsgExpireNs, java.awt.Color.YELLOW);
            drawCenteredMessage(g2, stateMsg, stateMsgExpireNs, java.awt.Color.CYAN);
            if (window.isFastForward() && context.romPath != null) {
                double factor = Math.max(0.01, window.getLastFps() / 60.0);
                drawCenteredMessage(g2, String.format("FFWD x%.1f", factor), Long.MAX_VALUE, java.awt.Color.ORANGE);
            }
            if (paused && context.romPath != null) {
                drawCenteredMessage(g2, "PAUSED", Long.MAX_VALUE, java.awt.Color.GREEN);
            }
        });
    }

    private void drawCenteredMessage(java.awt.Graphics2D g2, String msg, long expireNs, java.awt.Color color) {
        if (msg == null || System.nanoTime() >= expireNs) return;
        java.awt.Rectangle c = g2.getClipBounds();
        if (c == null) c = new java.awt.Rectangle(0, 0, 256, 240);
        java.awt.FontMetrics fm = g2.getFontMetrics();
        int textW = fm.stringWidth(msg);
        int boxW = Math.max(textW + 20, 96);
        int boxH = fm.getAscent() + 12;
        int x = 12 + c.x + (c.width - boxW) / 2;
        int y = (c.y + c.height / 2) - boxH / 2;
        g2.setColor(new java.awt.Color(0, 0, 0, 170));
        g2.fillRect(x, y, boxW, boxH);
        g2.setColor(color);
        g2.drawString(msg, x + (boxW - textW) / 2, y + (boxH - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void setupRomLoadingCallbacks(NesWindow window, RuntimeSettings runtimeSettings) {
        window.setOnReset(() -> {
            if (context.romPath != null) {
                Log.info(GENERAL, "Menu Reset invoked");
                context.emulator.reset();
                resetMsgExpireNs = System.nanoTime() + 2_000_000_000L;
            }
        });

        window.setOnBeforeOpenLoadRomDialog(() -> {
            if (context.romPath != null) {
                pausePrev = paused;
                paused = true;
            }
        });

        window.setOnAfterLoadRomDialogCancelled(() -> {
            if (context.romPath != null) paused = pausePrev;
        });

        window.setOnCloseRom(() -> {
            if (context.romPath == null) return;
            try {
                context.emulator.forceAutoSave();
                context.stopAudio();
                NesEmulator black = NesEmulator.createBlackScreenInstance();
                context.emulator = black;
                window.setFrameBuffer(black.getPpu().getFrameBuffer());
                context.rom = null;
                context.romPath = null;
                window.setRomActionsEnabled(false);
                window.getFrame().setTitle("R2-NES (no ROM)");
                resetMsgExpireNs = 0L;
                stateMsg = null;
                Log.info(GENERAL, "ROM closed; returned to black screen");
            } catch (Exception ex) {
                Log.warn(GENERAL, "Falha ao fechar ROM: %s", ex.getMessage());
            }
        });

        window.setOnLoadRom(path -> {
            Log.info(GENERAL, "Menu Load ROM: %s", path);
            paused = true;
            try {
                if (context.emulator != null) context.emulator.forceAutoSave();
                INesRom newRom = RomLoader.load(path);
                context.rom = newRom;
                context.romPath = path;

                NesEmulator newEmu;
                if (options.savePathOverride != null && !options.savePathOverride.isBlank()) {
                    Files.createDirectories(Path.of(options.savePathOverride));
                    newEmu = new NesEmulator(newRom, path, Path.of(options.savePathOverride));
                } else {
                    newEmu = new NesEmulator(newRom, path);
                }
                com.nesemu.config.EmulatorConfigurator.apply(newEmu, runtimeSettings);
                context.emulator = newEmu;

                context.stopAudio();
                try {
                    context.audio = new AudioPlayer((com.nesemu.apu.APU) newEmu.getApu(), 44100);
                    context.audio.start();
                } catch (Exception e) {
                    Log.warn(GENERAL, "Audio init falhou (reload): %s", e.getMessage());
                }

                if (pad1 != null || pad2 != null) {
                    newEmu.getBus().attachControllers(pad1, pad2);
                }

                window.setFrameBuffer(newEmu.getPpu().getFrameBuffer());
                window.getFrame().setTitle("R2-NES - " + path.getFileName());
                window.setRomActionsEnabled(true);

                try {
                    Path parent = path.getParent();
                    if (parent != null && Files.isDirectory(parent)) {
                        userConfig.setLastRomDirectory(parent);
                        userConfig.save();
                        if (!userConfig.hasDefaultRomDirectory()) {
                            window.setFileChooserStartDir(parent);
                        }
                    }
                } catch (Exception ignore) {
                }

                stateMsg = "LOADED";
                stateMsgExpireNs = System.nanoTime() + 1_500_000_000L;
                paused = false;
                Log.info(GENERAL, "ROM loaded successfully: %s", path);
            } catch (Exception ex) {
                Log.warn(GENERAL, "Falha carregando ROM %s: %s", path, ex.getMessage());
                JOptionPane.showMessageDialog(window.getFrame(), "Failed to load ROM: " + ex.getMessage(), "Load ROM", JOptionPane.ERROR_MESSAGE);
                paused = pausePrev;
            }
        });
    }
}

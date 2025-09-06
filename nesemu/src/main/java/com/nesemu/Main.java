package com.nesemu;

import java.nio.file.*;
import java.util.EnumSet;
import java.util.Locale;
import com.nesemu.bus.Bus;
import com.nesemu.emulator.NesEmulator;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;
import com.nesemu.gui.NesWindow;
import com.nesemu.gui.KeyTokens;
import com.nesemu.input.InputConfig;
import com.nesemu.config.AppOptions;
import com.nesemu.config.CLIOptionsParser;
import com.nesemu.config.ConfigUtils;
import com.nesemu.config.RuntimeSettings;
import com.nesemu.rom.INesRom;
import com.nesemu.rom.RomLoader;
import com.nesemu.io.NesController;
import com.nesemu.ppu.PPU;

/**
 * Simple headless runner: loads a .nes ROM, executes a number of frames and
 * dumps background info.
 */
public class Main {
    private static NesController controllerPad1;
    private static NesController controllerPad2;

    public static void main(String[] args) throws Exception {
        // Parse CLI into an options holder (smaller Main)
        AppOptions applicationOptions = CLIOptionsParser.parse(args);

        final INesRom[] romRef = new INesRom[1]; // referências mutáveis para uso em lambdas
        final NesEmulator[] emuRef = new NesEmulator[1];
        boolean patternStandalone = false; // modo especial: renderiza apenas padrão sintético sem ROM/CPU

        // Load configuration file and merge INI defaults/fallbacks into CLI options
        // (ROM override may occur here)
        AppOptions.checkOptionsFromIni(applicationOptions);

        // Resolve caminho da ROM (precedência: CLI > INI > default)
        if (applicationOptions.testPattern != null) {
            // Sempre isola test-pattern para não sofrer efeitos de ROM / CPU / mappers
            patternStandalone = true;
            Log.info(ROM, "Modo test-pattern standalone: ignorando carregamento de ROM");
        }
        Path romFilePath = null;
        if (!patternStandalone) {
            if (applicationOptions.romPath == null || applicationOptions.romPath.isBlank()) {
                if (!applicationOptions.gui) {
                    Log.error(ROM, "Nenhuma ROM especificada. Use argumento CLI ou defina 'rom=' no emulator.ini");
                    Log.error(ROM, "Execução abortada (headless requer ROM ou test-pattern)");
                    return;
                } else {
                    Log.warn(ROM, "Nenhuma ROM especificada. Iniciando GUI em tela preta (PPU idle)");
                }
            } else {
                Path rp = Path.of(applicationOptions.romPath);
                if (Files.isDirectory(rp)) {
                    if (applicationOptions.gui) {
                        Log.info(ROM, "INI rom aponta para diretório: %s (GUI usará como pasta inicial)", rp);
                        romFilePath = null;
                    } else {
                        try {
                            var opt = Files.list(rp)
                                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nes"))
                                    .findFirst();
                            if (opt.isPresent()) {
                                romFilePath = opt.get();
                                Log.info(ROM, "Headless: usando primeira ROM encontrada: %s", romFilePath);
                            } else {
                                Log.error(ROM, "Diretório não contém ROM .nes: %s", rp);
                                return;
                            }
                        } catch (Exception ex) {
                            Log.error(ROM, "Falha ao listar diretório de ROM: %s", ex.getMessage());
                            return;
                        }
                    }
                } else {
                    romFilePath = rp;
                    if (!Files.exists(romFilePath)) {
                        if (!applicationOptions.gui) {
                            Log.error(ROM, "ROM não encontrada: %s", applicationOptions.romPath);
                            return;
                        } else {
                            Log.warn(ROM, "ROM inexistente: %s. Iniciando em tela preta.", applicationOptions.romPath);
                            romFilePath = null;
                        }
                    }
                }
            }
            if (romFilePath != null) {
                Log.info(ROM, "Carregando ROM: %s", romFilePath.toAbsolutePath());
                romRef[0] = RomLoader.load(romFilePath);
                Path saveDir = null;
                if (applicationOptions.savePathOverride != null) {
                    try {
                        saveDir = Path.of(applicationOptions.savePathOverride);
                    } catch (Exception ignore) {
                    }
                }
                if (saveDir != null) {
                    emuRef[0] = new NesEmulator(romRef[0], romFilePath, saveDir);
                    Log.info(GENERAL, "save-path override: %s", saveDir.toAbsolutePath());
                } else {
                    emuRef[0] = new NesEmulator(romRef[0], romFilePath);
                }
            } else if (applicationOptions.gui) {
                romRef[0] = null;
                emuRef[0] = NesEmulator.createBlackScreenInstance();
            }
        }

        if (!patternStandalone) {
            // Agora que 'emu' existe, anexar controllers (se config carregada)
            try {
                InputConfig cfgForPads = ConfigUtils.loadInputConfig();
                var pad1 = new NesController(cfgForPads.getController(0));
                var pad2 = new NesController(cfgForPads.getController(1));
                // Apply turbo-fast option if present (global for now)
                String turboFastOpt = cfgForPads.getOption("turbo-fast");
                boolean turboFast = turboFastOpt != null && turboFastOpt.equalsIgnoreCase("true");
                if (turboFast) {
                    pad1.setTurboFast(true);
                    pad2.setTurboFast(true);
                }
                // Parse scanlines visual options
                boolean scanlinesEnabled = false;
                double scanlinesAlpha = 0.05;
                String scanlinesOpt = cfgForPads.getOption("scanlines");
                if (scanlinesOpt != null && scanlinesOpt.equalsIgnoreCase("true")) {
                    scanlinesEnabled = true;
                }
                String scanlinesAlphaOpt = cfgForPads.getOption("scanlines-alpha");
                if (scanlinesAlphaOpt != null) {
                    try {
                        double v = Double.parseDouble(scanlinesAlphaOpt.trim());
                        if (v >= 0.0 && v <= 1.0)
                            scanlinesAlpha = v;
                    } catch (NumberFormatException ignore) {
                    }
                }
                final boolean slEnabledFinal = scanlinesEnabled;
                final float slAlphaFinal = (float) scanlinesAlpha;
                // Store in static holders for later overlay lambda (via fields or closures).
                // We'll place into system properties for now.
                System.setProperty("r2nes.scanlines.enabled", String.valueOf(slEnabledFinal));
                System.setProperty("r2nes.scanlines.alpha", String.valueOf(slAlphaFinal));
                emuRef[0].getBus().attachControllers(pad1, pad2);
                controllerPad1 = pad1;
                controllerPad2 = pad2;
            } catch (Exception e) {
                Log.warn(CONTROLLER, "Falha ao reprocessar config para controllers: %s", e.getMessage());
            }
        }
        // Aplicar política de verbosidade
        if (applicationOptions.quiet) {
            com.nesemu.ppu.PPU.setVerboseLogging(false);
            Bus.setGlobalVerbose(false);
        } else if (applicationOptions.verboseFlag != null && applicationOptions.verboseFlag) {
            com.nesemu.ppu.PPU.setVerboseLogging(true);
            Bus.setGlobalVerbose(true);
        }
        // Configurar nível de log se fornecido
        if (applicationOptions.logLevelOpt != null) {
            try {
                Log.setLevel(Log.Level.valueOf(applicationOptions.logLevelOpt.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                Log.warn(GENERAL, "Nível de log inválido: %s (usar TRACE|DEBUG|INFO|WARN|ERROR)", applicationOptions.logLevelOpt);
            }
        } else if (applicationOptions.verboseFlag != null && applicationOptions.verboseFlag && !applicationOptions.quiet) {
            Log.setLevel(Log.Level.DEBUG);
        }
        // Configurar categorias
        if (applicationOptions.logCatsOpt != null) {
            if (applicationOptions.logCatsOpt.equalsIgnoreCase("ALL")) {
                Log.setCategories(EnumSet.allOf(Log.Cat.class));
            } else {
                EnumSet<Log.Cat> set = EnumSet.noneOf(Log.Cat.class);
                for (String c : applicationOptions.logCatsOpt.split(",")) {
                    c = c.trim();
                    if (c.isEmpty())
                        continue;
                    try {
                        set.add(Log.Cat.valueOf(c.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ex) {
                        Log.warn(GENERAL, "Categoria de log inválida ignorada: %s", c);
                    }
                }
                if (set.isEmpty()) {
                    Log.warn(GENERAL, "Nenhuma categoria válida em --log-cats, mantendo padrão.");
                } else {
                    Log.setCategories(set);
                }
            }
        }
        if (applicationOptions.logTimestamps) {
            Log.setTimestamps(true);
        }
        if (!patternStandalone) {
            if (applicationOptions.tileMatrixMode != null) {
                emuRef[0].getPpu().setTileMatrixMode(applicationOptions.tileMatrixMode);
                Log.info(PPU, "Tile matrix mode: %s", applicationOptions.tileMatrixMode);
            }
        }
        // Se solicitou pipe-log e nenhum modo de tile matrix foi especificado, usar
        // 'center'
        if (!patternStandalone) {
            if (applicationOptions.pipeLogLimit > 0 && applicationOptions.tileMatrixMode == null) {
                emuRef[0].getPpu().setTileMatrixMode("center");
                Log.debug(PPU, "PIPE-LOG ajustando tileMatrixMode=center");
            }
            if (applicationOptions.pipeLogLimit > 0) {
                emuRef[0].getPpu().enablePipelineLog(applicationOptions.pipeLogLimit);
                Log.info(PPU, "PIPE-LOG habilitado limite=%d", applicationOptions.pipeLogLimit);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.dbgBgSample > 0) {
                if (applicationOptions.dbgBgAll)
                    emuRef[0].getPpu().enableBackgroundSampleDebugAll(applicationOptions.dbgBgSample);
                else
                    emuRef[0].getPpu().enableBackgroundSampleDebug(applicationOptions.dbgBgSample);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.timingSimple) {
                emuRef[0].getPpu().setSimpleTiming(true);
            }
            if (applicationOptions.timingModeOpt != null) {
                if (applicationOptions.timingModeOpt.equals("interleaved")) {
                    emuRef[0].setTimingMode(NesEmulator.TimingMode.INTERLEAVED);
                } else {
                    emuRef[0].setTimingMode(NesEmulator.TimingMode.SIMPLE);
                }
                Log.info(GENERAL, "Timing mode: %s", applicationOptions.timingModeOpt);
            }
            if (applicationOptions.spinWatchThreshold > 0) {
                emuRef[0].enableSpinWatch(applicationOptions.spinWatchThreshold);
                Log.info(GENERAL, "Spin watch ativo: threshold=%d", applicationOptions.spinWatchThreshold);
                if (applicationOptions.spinDumpBytes > 0) {
                    emuRef[0].setSpinDumpBytes(applicationOptions.spinDumpBytes);
                    Log.info(GENERAL, "Spin dump bytes=%d", applicationOptions.spinDumpBytes);
                }
            }
            if (applicationOptions.mmc1LogLimit > 0 && emuRef[0].getMapper() instanceof com.nesemu.mapper.Mapper1 m1) {
                m1.enableBankLogging(applicationOptions.mmc1LogLimit);
                Log.info(GENERAL, "MMC1 logging ativo (limite=%d)", applicationOptions.mmc1LogLimit);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.forceBg) {
                emuRef[0].getPpu().setForceBackgroundEnable(true);
                Log.info(PPU, "FORCE-BG bit3 PPUMASK");
            }
        }
        // Test pattern: se standalone, aplicaremos em PPU isolada depois
        if (!patternStandalone) {
            if (applicationOptions.testPattern != null) {
                emuRef[0].getPpu().setTestPatternMode(applicationOptions.testPattern);
                Log.info(PPU, "TEST-PATTERN modo=%s", applicationOptions.testPattern);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.unlimitedSprites != null) {
                emuRef[0].getPpu().setUnlimitedSprites(applicationOptions.unlimitedSprites);
                Log.info(PPU, "Unlimited sprites: %s", applicationOptions.unlimitedSprites ? "ON" : "OFF");
            }
            if (applicationOptions.spriteYMode != null) {
                boolean hw = applicationOptions.spriteYMode.equals("hardware");
                emuRef[0].getPpu().setSpriteYHardware(hw);
                Log.info(PPU, "Sprite Y mode: %s", hw ? "HARDWARE(+1)" : "TEST(EXACT)");
            }
            if (applicationOptions.forceSprite0Hit) {
                emuRef[0].getPpu().setForceSprite0Hit(true);
                Log.warn(PPU, "[DEBUG] Force sprite0 hit habilitado");
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.logAttrLimit > 0) {
                emuRef[0].getPpu().enableAttributeRuntimeLog(applicationOptions.logAttrLimit);
            }
            if (applicationOptions.logNtLimit > 0) {
                emuRef[0].getPpu().enableNametableRuntimeLog(applicationOptions.logNtLimit,
                        applicationOptions.ntBaseline == null ? -1 : applicationOptions.ntBaseline);
            }
            if (applicationOptions.paletteLogLimit > 0) {
                emuRef[0].getPpu().enablePaletteWriteLog(applicationOptions.paletteLogLimit);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.initScroll) {
                emuRef[0].getBus().write(0x2000, 0x10);
                emuRef[0].getBus().write(0x2005, 0x00);
                emuRef[0].getBus().write(0x2005, 0x00);
                emuRef[0].getBus().write(0x2006, 0x20);
                emuRef[0].getBus().write(0x2006, 0x00);
                Log.debug(PPU, "INIT-SCROLL VRAM inicializada nametable0 pattern $1000");
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.showHeader) {
                var h = romRef[0].getHeader();
                Log.info(ROM, "Header: PRG=%d x16KB (%d bytes) CHR=%d x8KB (%d bytes) Mapper=%d Mirroring=%s",
                        h.getPrgRomPages(), h.getPrgRomPages() * 16384, h.getChrRomPages(), h.getChrRomPages() * 8192,
                        h.getMapper(),
                        h.isVerticalMirroring() ? "VERTICAL" : "HORIZONTAL");
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.chrLog) {
                emuRef[0].getBus().getMapper0().enableChrLogging(256);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.logPpuReg) {
                emuRef[0].getBus().enablePpuRegLogging(800);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.breakReadAddr >= 0) {
                emuRef[0].getBus().setWatchReadAddress(applicationOptions.breakReadAddr, applicationOptions.breakReadCount);
                Log.info(BUS, "Watch leitura %04X count=%d", applicationOptions.breakReadAddr, applicationOptions.breakReadCount);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.traceNmi) {
                emuRef[0].getPpu().setNmiCallback(() -> {
                    int pc = emuRef[0].getCpu().getPC();
                    Log.debug(PPU, "NMI frame=%d PC=%04X cycles=%d", emuRef[0].getFrame(), pc,
                            emuRef[0].getCpu().getTotalCycles());
                });
            }
        }
        if (patternStandalone) {
            if (!applicationOptions.gui) {
                Log.info(GENERAL, "Standalone test-pattern headless (sem GUI)");
            }
            // Modo puro de padrão: instância isolada de PPU
            PPU ppu = new PPU();
            ppu.reset();
            ppu.setTestPatternMode(applicationOptions.testPattern); // já validado não-nulo
            // Habilita background (bit 3) para permitir pipeline & render (necessário para
            // passar renderingEnabled())
            // Habilita background (bit3) e garante BG_LEFT (bit1) para visualizar primeiros
            // 8 pixels
            ppu.writeRegister(1, 0x08 | 0x02);
            if (applicationOptions.gui) {
                NesWindow window = new NesWindow("R2-NES TestPattern-" + applicationOptions.testPattern, 3);
                window.show(ppu.getFrameBuffer());
                Log.info(GENERAL, "Iniciando GUI TestPattern (Ctrl+C para sair)");
                window.startRenderLoop(() -> {
                    long f = ppu.getFrame();
                    while (ppu.getFrame() == f) {
                        ppu.clock();
                    }
                }, 60, NesWindow.PacerMode.HR);
            } else {
                for (int i = 0; i < applicationOptions.frames; i++) {
                    long f = ppu.getFrame();
                    while (ppu.getFrame() == f) {
                        ppu.clock();
                    }
                }
                // Dump opcional / estatísticas simples
                Path out = Path.of("testpattern.ppm");
                ppu.dumpBackgroundToPpm(out);
                Log.info(PPU, "PPM (test-pattern) gerado: %s", out.toAbsolutePath());
            }
            return; // encerra main
        }
        // Optionally force background enable early (most games set this quickly anyway)
        // Escreve PPUMASK inicial para acelerar primeiros frames (BG + coluna esquerda
        // por padrão)
        int initMask = (applicationOptions.initialMaskOverride != null) ? applicationOptions.initialMaskOverride : 0x08;
        emuRef[0].getBus().write(0x2001, initMask);
        Log.info(PPU, "PPUMASK inicial=%02X%s", initMask, (applicationOptions.initialMaskOverride != null ? " (override)" : ""));
        if (applicationOptions.leftColumnModeOpt != null) {
            com.nesemu.ppu.PPU.LeftColumnMode mode = com.nesemu.ppu.PPU.LeftColumnMode.HARDWARE;
            switch (applicationOptions.leftColumnModeOpt) {
                case "always":
                    mode = com.nesemu.ppu.PPU.LeftColumnMode.ALWAYS;
                    break;
                case "crop":
                    mode = com.nesemu.ppu.PPU.LeftColumnMode.CROP;
                    break;
                case "hardware":
                default:
                    mode = com.nesemu.ppu.PPU.LeftColumnMode.HARDWARE;
                    break;
            }
            ((PPU) emuRef[0].getPpu()).setLeftColumnMode(mode);
            Log.info(PPU, "Left-column-mode=%s", mode.name().toLowerCase(Locale.ROOT));
        }
        if (applicationOptions.gui) {
            String title = (romFilePath != null) ? ("R2-NES - " + romFilePath.getFileName()) : "R2-NES (no ROM)";
            NesWindow window = new NesWindow(title, 3);
            // Prevent automatic dispose so Alt+F4 / close button can confirm like ESC
            try {
                window.getFrame().setDefaultCloseOperation(javax.swing.JFrame.DO_NOTHING_ON_CLOSE);
            } catch (Exception ignore) {
            }
            // Apply fast-forward config (CLI override precedence)
            String effectiveFfKey = applicationOptions.fastForwardKey;
            if (applicationOptions.fastForwardMaxFps < 0)
                applicationOptions.fastForwardMaxFps = 0;
            if (applicationOptions.fastForwardMaxFps > 0) {
                window.setFastForwardMaxFps(applicationOptions.fastForwardMaxFps);
                Log.info(GENERAL, "Fast-Forward max FPS: %d", applicationOptions.fastForwardMaxFps);
            }
            final Path[] romFilePathHolder = new Path[] { romFilePath }; // may be null in black screen mode
            final String[] savePathOverrideHolder = new String[] { applicationOptions.savePathOverride }; // mutable for menu reload
            // If INI rom option points to a directory, use it as the initial chooser dir
            try {
                if (applicationOptions.romPath != null) {
                    Path rp = Path.of(applicationOptions.romPath);
                    if (Files.isDirectory(rp)) {
                        window.setFileChooserStartDir(rp);
                    }
                }
            } catch (Exception ignore) {
            }
            final String[] saveStatePathHolder = new String[] { applicationOptions.saveStatePath }; // mutable wrapper for closures
            if (applicationOptions.borderlessFullscreen != null && applicationOptions.borderlessFullscreen) {
                window.setBorderlessFullscreen(true);
                Log.info(GENERAL, "Borderless fullscreen: ON");
            } else if (applicationOptions.borderlessFullscreen != null) {
                Log.info(GENERAL, "Borderless fullscreen: OFF");
            }
            // Autosave hook: attach window listener to save SRAM on close
            // Unified exit confirmation logic (ESC or Alt+F4 / window close)
            Runnable exitConfirmed = () -> {
                try {
                    if (emuRef[0] != null) {
                        emuRef[0].forceAutoSave();
                        Log.info(GENERAL, "AutoSave (.sav) antes de sair");
                    }
                } catch (Exception ex) {
                    Log.warn(GENERAL, "Falha autosave na saída: %s", ex.getMessage());
                }
                System.exit(0);
            };
            // Pause state (moved earlier so window listener can access)
            final boolean[] paused = new boolean[] { false };
            // Stores previous pause state before showing an exit confirmation dialog
            final boolean[] pausePrev = new boolean[] { false };
            try {
                java.awt.event.WindowListener wlConfirm = new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        // Auto-pause before showing confirmation dialog, preserving prior state
                        pausePrev[0] = paused[0];
                        paused[0] = true;
                        int res = javax.swing.JOptionPane.showConfirmDialog(window.getFrame(),
                                "You really want exit?", "Confirm Exit",
                                javax.swing.JOptionPane.YES_NO_OPTION);
                        if (res == javax.swing.JOptionPane.YES_OPTION) {
                            exitConfirmed.run();
                        } else { // NO -> restore previous pause state
                            paused[0] = pausePrev[0];
                        }
                        // NO_OPTION -> ignore (keep running)
                    }
                };
                window.getFrame().addWindowListener(wlConfirm);
            } catch (Exception ex) {
                Log.warn(GENERAL, "Falha ao registrar window close confirm: %s", ex.getMessage());
            }
            final long[] resetMsgExpireNs = new long[] { 0L };
            final long[] stateMsgExpireNs = new long[] { 0L };
            final String[] stateMsg = new String[] { null };
            if (controllerPad1 != null) {
                final String resetTok = applicationOptions.resetKeyToken == null ? null
                        : applicationOptions.resetKeyToken.toLowerCase(Locale.ROOT).trim();
                window.installControllerKeyListener(controllerPad1, controllerPad2, resetTok, () -> {
                    Log.info(GENERAL, "RESET key pressed (%s)", resetTok);
                    emuRef[0].reset();
                    resetMsgExpireNs[0] = System.nanoTime() + 2_000_000_000L; // show for ~2s
                });
            }
            // Runtime toggles (fullscreen/HUD) via additional key listener
            // Mutable HUD state wrapper for inner classes
            final boolean[] hudState = new boolean[] { applicationOptions.hud };
            final java.util.Set<String> pauseKeyTokens = new java.util.HashSet<>();
            // Load pause-emulation tokens from INI (simple reload; negligible cost)
            try {
                var tmpCfg = ConfigUtils.loadInputConfig();
                pauseKeyTokens.addAll(ConfigUtils.getPauseKeyTokens(tmpCfg));
                if (!pauseKeyTokens.isEmpty()) {
                    Log.info(GENERAL, "Pause mapping tokens: %s", pauseKeyTokens);
                }
            } catch (Exception ex) {
                Log.warn(GENERAL, "Falha ao carregar pause-emulation: %s", ex.getMessage());
            }
            if (applicationOptions.toggleFullscreenKey != null || applicationOptions.toggleHudKey != null || applicationOptions.toggleFullscreenProportionKey != null
                    || applicationOptions.saveStateKey != null || applicationOptions.loadStateKey != null || effectiveFfKey != null
                    || applicationOptions.logWarnKey != null) {
                String fsKey = applicationOptions.toggleFullscreenKey == null ? null
                        : applicationOptions.toggleFullscreenKey.toLowerCase(Locale.ROOT).trim();
                String hudKey = applicationOptions.toggleHudKey == null ? null : applicationOptions.toggleHudKey.toLowerCase(Locale.ROOT).trim();
                String propKey = applicationOptions.toggleFullscreenProportionKey == null ? null
                        : applicationOptions.toggleFullscreenProportionKey.toLowerCase(Locale.ROOT).trim();
                String saveKey = applicationOptions.saveStateKey == null ? null : applicationOptions.saveStateKey.toLowerCase(Locale.ROOT).trim();
                String loadKey = applicationOptions.loadStateKey == null ? null : applicationOptions.loadStateKey.toLowerCase(Locale.ROOT).trim();
                String ffKey = effectiveFfKey == null ? null : effectiveFfKey.toLowerCase(Locale.ROOT).trim();
                String warnKey = applicationOptions.logWarnKey == null ? null : applicationOptions.logWarnKey.toLowerCase(Locale.ROOT).trim();
                java.awt.event.KeyAdapter adapter = new java.awt.event.KeyAdapter() {
                    @Override
                    public void keyPressed(java.awt.event.KeyEvent e) {
                        // ESC -> confirmação de saída (não configurável)
                        if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                            // Auto-pause before confirmation dialog (store previous state)
                            pausePrev[0] = paused[0];
                            paused[0] = true;
                            int res = javax.swing.JOptionPane.showConfirmDialog(window.getFrame(),
                                    "You really want exit?", "Confirm Exit",
                                    javax.swing.JOptionPane.YES_NO_OPTION);
                            if (res == javax.swing.JOptionPane.YES_OPTION) {
                                exitConfirmed.run();
                            } else { // restore previous state if user cancels
                                paused[0] = pausePrev[0];
                            }
                            return; // não propaga ESC
                        }
                        String tok = KeyTokens.from(e);
                        if (tok == null)
                            return;
                        if (fsKey != null && tok.equals(fsKey)) {
                            boolean newState = !window.isBorderlessFullscreen();
                            window.setBorderlessFullscreen(newState);
                            Log.info(GENERAL, "Toggle fullscreen -> %s", newState ? "ON" : "OFF");
                        }
                        if (hudKey != null && tok.equals(hudKey)) {
                            hudState[0] = !hudState[0];
                            Log.info(GENERAL, "Toggle HUD -> %s", hudState[0] ? "ON" : "OFF");
                        }
                        if (propKey != null && tok.equals(propKey)) {
                            window.cycleProportionMode();
                            int mode = window.getProportionMode();
                            String label = switch (mode) {
                                case 0 -> "NORMAL";
                                case 1 -> "PROPORTIONAL-HEIGHT";
                                case 2 -> "STRETCH";
                                default -> String.valueOf(mode);
                            };
                            Log.info(GENERAL, "Proportion mode -> %s", label);
                        }
                        if (saveKey != null && tok.equals(saveKey)) {
                            // Build state filename (ROM base + .state) in save-state-path or ROM directory
                            try {
                                Path dir;
                                if (saveStatePathHolder[0] != null)
                                    dir = Path.of(saveStatePathHolder[0]);
                                else
                                    dir = Path.of(".");
                                try {
                                    Files.createDirectories(dir);
                                } catch (Exception ignore) {
                                }
                                String base = romFilePathHolder[0].getFileName().toString();
                                int dot = base.lastIndexOf('.');
                                if (dot > 0)
                                    base = base.substring(0, dot);
                                Path target = dir.resolve(base + ".state");
                                emuRef[0].saveState(target);
                                Log.info(GENERAL, "SaveState salvo: %s", target.toAbsolutePath());
                                stateMsg[0] = "SAVING";
                                stateMsgExpireNs[0] = System.nanoTime() + 1_500_000_000L; // 1.5s
                            } catch (Exception ex) {
                                Log.warn(GENERAL, "Falha save-state: %s", ex.getMessage());
                                stateMsg[0] = "SAVE ERR";
                                stateMsgExpireNs[0] = System.nanoTime() + 1_500_000_000L;
                            }
                        }
                        if (loadKey != null && tok.equals(loadKey)) {
                            try {
                                Path dir;
                                if (saveStatePathHolder[0] != null)
                                    dir = Path.of(saveStatePathHolder[0]);
                                else
                                    dir = Path.of(".");
                                String base = romFilePathHolder[0].getFileName().toString();
                                int dot = base.lastIndexOf('.');
                                if (dot > 0)
                                    base = base.substring(0, dot);
                                Path target = dir.resolve(base + ".state");
                                boolean ok = emuRef[0].loadState(target);
                                if (ok) {
                                    Log.info(GENERAL, "SaveState carregado: %s", target.toAbsolutePath());
                                    stateMsg[0] = "LOADING";
                                } else {
                                    stateMsg[0] = "NO STATE";
                                }
                                stateMsgExpireNs[0] = System.nanoTime() + 1_500_000_000L;
                            } catch (Exception ex) {
                                Log.warn(GENERAL, "Falha load-state: %s", ex.getMessage());
                                stateMsg[0] = "LOAD ERR";
                                stateMsgExpireNs[0] = System.nanoTime() + 1_500_000_000L;
                            }
                        }
                        if (ffKey != null && tok.equals(ffKey)) {
                            if (!window.isFastForward()) {
                                window.setFastForward(true);
                                Log.info(GENERAL, "Fast-Forward ON");
                            }
                        }
                        if (warnKey != null && tok.equals(warnKey)) {
                            if (emuRef[0] != null) {
                                emuRef[0].dumpWarnSnapshot("manual-hotkey");
                            }
                        }
                        if (!pauseKeyTokens.isEmpty() && pauseKeyTokens.contains(tok)) {
                            paused[0] = !paused[0];
                            Log.info(GENERAL, "Pause -> %s", paused[0] ? "ON" : "OFF");
                        }
                    }

                    @Override
                    public void keyReleased(java.awt.event.KeyEvent e) {
                        String tok = KeyTokens.from(e);
                        if (tok == null)
                            return;
                        if (ffKey != null && tok.equals(ffKey)) {
                            if (window.isFastForward()) {
                                window.setFastForward(false);
                                Log.info(GENERAL, "Fast-Forward OFF");
                            }
                        }
                    }

                };
                window.addGlobalKeyListener(adapter);
            }
            // Unified overlay: HUD (optional) + transient reset/message overlays.
            // NOTE: Do NOT capture PPU instance here; it can change on runtime ROM load.
            // Use dynamic HUD state (reads hud variable each frame via array wrapper)
            final boolean slEnabled = Boolean.parseBoolean(System.getProperty("r2nes.scanlines.enabled", "false"));
            final float slAlpha = Float.parseFloat(System.getProperty("r2nes.scanlines.alpha", "0.5"));
            window.setOverlay(g2 -> {
                var ppu = emuRef[0].getPpu();
                // Scanlines (draw first so HUD/text draws above)
                if (slEnabled) {
                    java.awt.Composite oldComp = g2.getComposite();
                    g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, slAlpha));
                    g2.setColor(java.awt.Color.BLACK);
                    java.awt.Rectangle clip = g2.getClipBounds();
                    int h = clip != null ? clip.height : 240 * 3; // fallback
                    int w = clip != null ? clip.width : 256 * 3;
                    // Draw every other horizontal line (even indices dark) – simple 50% pattern
                    for (int y = 0; y < h; y += 2) {
                        g2.drawLine(0, y, w, y);
                    }
                    g2.setComposite(oldComp);
                }
                if (hudState[0]) {
                    int padLocal = 4;
                    String l1 = String.format("Frame:%d FPS:%.1f", ppu.getFrame(), window.getLastFps());
                    String l2 = String.format("Scan:%d Cyc:%d VRAM:%04X", ppu.getScanline(), ppu.getCycle(),
                            ppu.getVramAddress() & 0x3FFF);
                    String l3 = String.format("MASK:%02X STAT:%02X fineX:%d", ppu.getMaskRegister(),
                            ppu.getStatusRegister(), ppu.getFineX());
                    String btns = controllerPad1 != null ? controllerPad1.pressedButtonsString() : "-";
                    String l4 = "Pad1: " + btns;
                    int boxH = 56;
                    g2.setColor(new java.awt.Color(0, 0, 0, 160));
                    g2.fillRect(0, 0, 260, boxH);
                    g2.setColor(java.awt.Color.WHITE);
                    g2.drawString(l1, padLocal, 12);
                    g2.drawString(l2, padLocal, 24);
                    g2.drawString(l3, padLocal, 36);
                    g2.drawString(l4, padLocal, 48);
                }
                if (System.nanoTime() < resetMsgExpireNs[0]) {
                    String msg = "RESET";
                    g2.setColor(new java.awt.Color(0, 0, 0, 170));
                    g2.fillRect(90, 80, 100, 24);
                    g2.setColor(java.awt.Color.YELLOW);
                    g2.drawString(msg, 100, 96);
                }
                if (System.nanoTime() < stateMsgExpireNs[0] && stateMsg[0] != null) {
                    String msg = stateMsg[0];
                    g2.setColor(new java.awt.Color(0, 0, 0, 170));
                    g2.fillRect(80, 110, 120, 24);
                    g2.setColor(java.awt.Color.CYAN);
                    g2.drawString(msg, 92, 126);
                }
                if (window.isFastForward()) {
                    double factor = window.getLastFps() / 60.0;
                    if (factor < 0.01)
                        factor = 0.01;
                    String msg = String.format("FFWD x%.1f", factor);
                    g2.setColor(new java.awt.Color(0, 0, 0, 170));
                    g2.fillRect(8, 200, 120, 24);
                    g2.setColor(java.awt.Color.ORANGE);
                    g2.drawString(msg, 16, 216);
                }
                if (paused[0]) {
                    g2.setColor(new java.awt.Color(0, 0, 0, 180));
                    g2.fillRect(92, 140, 120, 28);
                    g2.setColor(java.awt.Color.GREEN);
                    g2.drawString("PAUSED", 108, 158);
                }
            });
            window.show(emuRef[0].getPpu().getFrameBuffer());
            Log.info(GENERAL, "Iniciando GUI (Ctrl+C para sair)");
            NesWindow.PacerMode pm = (applicationOptions.pacerModeOpt == null || applicationOptions.pacerModeOpt.equals("hr"))
                    ? NesWindow.PacerMode.HR
                    : NesWindow.PacerMode.LEGACY;
            Log.info(GENERAL, "Pacer mode: %s", pm);
            if (applicationOptions.bufferStrategyOpt != null) {
                window.setUseBufferStrategy(applicationOptions.bufferStrategyOpt);
                Log.info(GENERAL, "BufferStrategy: %s", applicationOptions.bufferStrategyOpt ? "ON" : "OFF (Swing repaint)");
            } else {
                Log.info(GENERAL, "BufferStrategy: DEFAULT(ON)");
            }
            // Capture runtime-config to reapply on ROM swap for consistent behavior
            RuntimeSettings runtime = new RuntimeSettings(applicationOptions.tileMatrixMode,
                    applicationOptions.pipeLogLimit, applicationOptions.dbgBgSample, applicationOptions.dbgBgAll, applicationOptions.timingSimple,
                    applicationOptions.timingModeOpt, applicationOptions.forceBg, applicationOptions.unlimitedSprites, applicationOptions.spriteYMode,
                    applicationOptions.forceSprite0Hit, applicationOptions.leftColumnModeOpt, applicationOptions.logAttrLimit, applicationOptions.logNtLimit,
                    applicationOptions.ntBaseline, applicationOptions.paletteLogLimit, applicationOptions.mmc1LogLimit, applicationOptions.spinWatchThreshold,
                    applicationOptions.spinDumpBytes, applicationOptions.initialMaskOverride);

            // Apply once (already applied above), but keep for completeness if needed later
            // com.nesemu.config.EmulatorConfigurator.apply(emuRef[0], runtime);

            // ---------------- Menu callback wiring (after overlay & show)
            // -----------------
            window.setOnReset(() -> {
                Log.info(GENERAL, "Menu Reset invoked");
                emuRef[0].reset();
                resetMsgExpireNs[0] = System.nanoTime() + 2_000_000_000L;
            });
            window.setOnExit(() -> {
                // Reuse same confirmation logic as ESC / window close
                pausePrev[0] = paused[0];
                paused[0] = true;
                int res = javax.swing.JOptionPane.showConfirmDialog(window.getFrame(),
                        "You really want exit?", "Confirm Exit",
                        javax.swing.JOptionPane.YES_NO_OPTION);
                if (res == javax.swing.JOptionPane.YES_OPTION) {
                    exitConfirmed.run();
                } else {
                    paused[0] = pausePrev[0];
                }
            });
            window.setOnLoadRom(path -> {
                Log.info(GENERAL, "Menu Load ROM: %s", path);
                paused[0] = true; // pause while swapping
                try {
                    if (emuRef[0] != null) {
                        try {
                            emuRef[0].forceAutoSave();
                        } catch (Exception ignore) {
                        }
                    }
                    INesRom newRom = RomLoader.load(path);
                    romRef[0] = newRom;
                    romFilePathHolder[0] = path; // update base for savestates
                    // Build new emulator (respect save directory override if present)
                    NesEmulator newEmu;
                    if (savePathOverrideHolder[0] != null && !savePathOverrideHolder[0].isBlank()) {
                        try {
                            Files.createDirectories(Path.of(savePathOverrideHolder[0]));
                        } catch (Exception ignore) {
                        }
                        newEmu = new NesEmulator(newRom, path, Path.of(savePathOverrideHolder[0]));
                    } else {
                        newEmu = new NesEmulator(newRom, path);
                    }
                    // Re-apply all runtime flags consistently (matches startup path)
                    com.nesemu.config.EmulatorConfigurator.apply(newEmu, runtime);
                    // Point references to new emulator
                    emuRef[0] = newEmu;
                    // Reattach controllers so $4016/$4017 read from the same pads used by the key
                    // listener
                    try {
                        if (controllerPad1 != null || controllerPad2 != null) {
                            newEmu.getBus().attachControllers(controllerPad1, controllerPad2);
                        }
                    } catch (Exception ignore) {
                    }
                    // Update framebuffer reference for renderer
                    window.setFrameBuffer(newEmu.getPpu().getFrameBuffer());
                    // Update title
                    try {
                        window.getFrame().setTitle("R2-NES - " + path.getFileName());
                    } catch (Exception ignore) {
                    }
                    try {
                        if (path.getParent() != null)
                            window.setFileChooserStartDir(path.getParent());
                    } catch (Exception ignore) {
                    }
                    // Visual feedback
                    stateMsg[0] = "LOADED";
                    stateMsgExpireNs[0] = System.nanoTime() + 1_500_000_000L;
                    paused[0] = false; // resume
                    Log.info(GENERAL, "ROM loaded successfully: %s", path);
                } catch (Exception ex) {
                    Log.warn(GENERAL, "Falha carregando ROM %s: %s", path, ex.getMessage());
                    javax.swing.JOptionPane.showMessageDialog(window.getFrame(),
                            "Failed to load ROM: " + ex.getMessage(), "Load ROM",
                            javax.swing.JOptionPane.ERROR_MESSAGE);
                    paused[0] = pausePrev[0]; // revert pause state
                }
            });

            window.startRenderLoop(() -> {
                if (!paused[0]) {
                    emuRef[0].stepFrame();
                } else {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignore) {
                    }
                }
            }, 60, pm); // target 60 fps
        } else {
            long start = System.nanoTime();
            if (applicationOptions.untilVblank) {
                long executed = 0;
                long maxInstr = (applicationOptions.traceInstrCount > 0) ? applicationOptions.traceInstrCount : 1_000_000;
                long startCpuCycles = emuRef[0].getCpu().getTotalCycles();
                while (!emuRef[0].getPpu().isInVBlank() && executed < maxInstr) {
                    long before = emuRef[0].getCpu().getTotalCycles();
                    emuRef[0].getCpu().stepInstruction();
                    long after = emuRef[0].getCpu().getTotalCycles();
                    long cpuSpent = after - before;
                    for (long c = 0; c < cpuSpent * 3; c++) {
                        emuRef[0].getPpu().clock();
                    }
                    executed++;
                }
                if (emuRef[0].getPpu().isInVBlank()) {
                    Log.info(PPU, "UNTIL-VBLANK atingido instr=%d cpuCycles~%d frame=%d scan=%d cyc=%d status=%02X",
                            executed, (emuRef[0].getCpu().getTotalCycles() - startCpuCycles),
                            emuRef[0].getPpu().getFrame(),
                            emuRef[0].getPpu().getScanline(), emuRef[0].getPpu().getCycle(),
                            emuRef[0].getPpu().getStatusRegister());
                } else {
                    Log.warn(PPU, "UNTIL-VBLANK limite instr (%d) sem vblank scan=%d cyc=%d frame=%d",
                            maxInstr, emuRef[0].getPpu().getScanline(), emuRef[0].getPpu().getCycle(),
                            emuRef[0].getPpu().getFrame());
                }
                for (int i = 0; i < applicationOptions.frames; i++)
                    emuRef[0].stepFrame();
            } else if (applicationOptions.traceInstrCount > 0) {
                long executed = 0;
                while (executed < applicationOptions.traceInstrCount) {
                    int pc = emuRef[0].getCpu().getPC();
                    int opcode = emuRef[0].getBus().read(pc);
                    Log.trace(CPU, "TRACE PC=%04X OP=%02X A=%02X X=%02X Y=%02X P=%02X SP=%02X CYC=%d",
                            pc, opcode, emuRef[0].getCpu().getA(), emuRef[0].getCpu().getX(), emuRef[0].getCpu().getY(),
                            emuRef[0].getCpu().getStatusByte(), emuRef[0].getCpu().getSP(),
                            emuRef[0].getCpu().getTotalCycles());
                    long before = emuRef[0].getCpu().getTotalCycles();
                    emuRef[0].getCpu().stepInstruction();
                    long after = emuRef[0].getCpu().getTotalCycles();
                    long cpuSpent = after - before;
                    for (long c = 0; c < cpuSpent * 3; c++) {
                        emuRef[0].getPpu().clock();
                    }
                    executed++;
                    if (applicationOptions.breakAtPc != null && emuRef[0].getCpu().getPC() == (applicationOptions.breakAtPc & 0xFFFF)) {
                        Log.info(CPU, "BREAK PC=%04X após %d instruções", applicationOptions.breakAtPc, executed);
                        break;
                    }
                    if (applicationOptions.breakReadAddr >= 0 && emuRef[0].getBus().isWatchTriggered()) {
                        Log.info(BUS, "BREAK leitura %04X atingida count=%d após %d instr",
                                applicationOptions.breakReadAddr, applicationOptions.breakReadCount, executed);
                        break;
                    }
                    if (applicationOptions.traceNmi && executed % 5000 == 0) {
                        Log.debug(CPU, "trace progress instr=%d", executed);
                    }
                }
                for (int i = 0; i < applicationOptions.frames; i++)
                    emuRef[0].stepFrame();
            } else {
                for (int i = 0; i < applicationOptions.frames; i++) {
                    emuRef[0].stepFrame();
                }
            }
            long elapsedNs = System.nanoTime() - start;
            double fpsSim = applicationOptions.frames / (elapsedNs / 1_000_000_000.0);
            Log.info(GENERAL, "Frames simulados: %d (%.2f fps)", applicationOptions.frames, fpsSim);
            if (applicationOptions.pipeLogLimit > 0) {
                Log.info(PPU, "--- PIPELINE LOG ---");
                Log.info(PPU, "%s", emuRef[0].getPpu().consumePipelineLog());
            }
            if (applicationOptions.dbgBgSample > 0) {
                emuRef[0].getPpu().dumpFirstBackgroundSamples(Math.min(applicationOptions.dbgBgSample, 50));
            }
            Log.info(PPU, "--- Tile index matrix (hex of first pixel per tile) ---");
            emuRef[0].getPpu().printTileIndexMatrix();
            emuRef[0].getPpu().printBackgroundIndexHistogram();
            if (applicationOptions.bgColStats) {
                emuRef[0].getPpu().printBackgroundColumnStats();
            }
            Path out = Path.of("background.ppm");
            emuRef[0].getPpu().dumpBackgroundToPpm(out);
            Log.info(PPU, "PPM gerado: %s", out.toAbsolutePath());
            if (applicationOptions.dumpNt) {
                emuRef[0].getPpu().printNameTableTileIds(0);
            }
            if (applicationOptions.dumpPattern != null) {
                emuRef[0].getPpu().dumpPatternTile(applicationOptions.dumpPattern);
            }
            if (applicationOptions.dumpPatternsList != null) {
                for (String part : applicationOptions.dumpPatternsList.split(",")) {
                    part = part.trim();
                    if (part.isEmpty())
                        continue;
                    try {
                        int t = Integer.parseInt(part, 16);
                        emuRef[0].getPpu().dumpPatternTile(t);
                    } catch (NumberFormatException e) {
                        Log.error(PPU, "Tile inválido em lista: %s", part);
                    }
                }
            }
        }
    }
}
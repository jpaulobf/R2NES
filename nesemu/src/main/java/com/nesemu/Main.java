package com.nesemu;

import java.nio.file.*;
import java.util.EnumSet;
import java.util.Locale;
import com.nesemu.bus.Bus;
import com.nesemu.emulator.NesEmulator;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;
import com.nesemu.gui.NesWindow;
import com.nesemu.gui.GuiLauncher;
import com.nesemu.headless.HeadlessLauncher;
import com.nesemu.app.EmulatorContext;
import com.nesemu.input.InputConfig;
import com.nesemu.input.GamepadPoller;
import com.nesemu.config.AppOptions;
import com.nesemu.config.CLIOptionsParser;
import com.nesemu.config.ConfigUtils;
import com.nesemu.config.UserConfig;
import com.nesemu.rom.RomLoader;
import com.nesemu.io.NesController;
import com.nesemu.ppu.PPU;
import com.nesemu.audio.AudioPlayer;

/**
 * Simple headless runner: loads a .nes ROM, executes a number of frames and
 * dumps background info.
 */
public class Main {
    private static NesController controllerPad1;
    private static NesController controllerPad2;
    private static GamepadPoller gamepadPoller;

    public static void main(String[] args) throws Exception {
        // Parse CLI into an options holder (smaller Main)
        AppOptions applicationOptions = CLIOptionsParser.parse(args);

        final EmulatorContext context = new EmulatorContext();
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
                                    .filter(p -> {
                                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                                        return n.endsWith(".nes") || n.endsWith(".zip");
                                    })
                                    .sorted((a, b) -> { // prefer .nes if both present
                                        String an = a.getFileName().toString().toLowerCase(Locale.ROOT);
                                        String bn = b.getFileName().toString().toLowerCase(Locale.ROOT);
                                        boolean aesNes = an.endsWith(".nes");
                                        boolean besNes = bn.endsWith(".nes");
                                        if (aesNes == besNes)
                                            return an.compareTo(bn);
                                        return aesNes ? -1 : 1;
                                    })
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
                context.rom = RomLoader.load(romFilePath);
                context.romPath = romFilePath;
                Path saveDir = null;
                if (applicationOptions.savePathOverride != null) {
                    try {
                        saveDir = Path.of(applicationOptions.savePathOverride);
                    } catch (Exception ignore) {
                    }
                }
                if (saveDir != null) {
                    context.emulator = new NesEmulator(context.rom, romFilePath, saveDir);
                    // Start audio
                    try {
                        context.audio = new AudioPlayer((com.nesemu.apu.APU) context.emulator.getApu(), 44100);
                        context.audio.start();
                    } catch (Exception e) {
                        Log.warn(GENERAL, "Audio init falhou: %s", e.getMessage());
                    }
                    Log.info(GENERAL, "save-path override: %s", saveDir.toAbsolutePath());
                } else {
                    context.emulator = new NesEmulator(context.rom, romFilePath);
                    try {
                        context.audio = new AudioPlayer((com.nesemu.apu.APU) context.emulator.getApu(), 44100);
                        context.audio.start();
                    } catch (Exception e) {
                        Log.warn(GENERAL, "Audio init falhou: %s", e.getMessage());
                    }
                }
            } else if (applicationOptions.gui) {
                context.emulator = NesEmulator.createBlackScreenInstance();
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
                if (context.emulator != null)
                    context.emulator.getBus().attachControllers(pad1, pad2);
                controllerPad1 = pad1;
                controllerPad2 = pad2;

                // Optional: start LWJGL gamepad poller when enabled in emulator.ini
                // (gamepad=true)
                boolean gamepadEnabled = false;
                String gamepadOpt = cfgForPads.getOption("gamepad");
                if (gamepadOpt != null && gamepadOpt.equalsIgnoreCase("true"))
                    gamepadEnabled = true;
                if (gamepadEnabled) {
                    try {
                        gamepadPoller = new GamepadPoller(controllerPad1);
                        gamepadPoller.start();
                        Log.info(CONTROLLER, "Gamepad (GLFW/LWJGL) iniciado");
                    } catch (Throwable t) {
                        Log.warn(CONTROLLER, "Gamepad init falhou: %s", t.getMessage());
                    }
                }
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
                Log.warn(GENERAL, "Nível de log inválido: %s (usar TRACE|DEBUG|INFO|WARN|ERROR)",
                        applicationOptions.logLevelOpt);
            }
        } else if (applicationOptions.verboseFlag != null && applicationOptions.verboseFlag
                && !applicationOptions.quiet) {
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
                context.emulator.getPpu().setTileMatrixMode(applicationOptions.tileMatrixMode);
                Log.info(PPU, "Tile matrix mode: %s", applicationOptions.tileMatrixMode);
            }
        }
        // Se solicitou pipe-log e nenhum modo de tile matrix foi especificado, usar
        // 'center'
        if (!patternStandalone) {
            if (applicationOptions.pipeLogLimit > 0 && applicationOptions.tileMatrixMode == null) {
                context.emulator.getPpu().setTileMatrixMode("center");
                Log.debug(PPU, "PIPE-LOG ajustando tileMatrixMode=center");
            }
            if (applicationOptions.pipeLogLimit > 0) {
                context.emulator.getPpu().enablePipelineLog(applicationOptions.pipeLogLimit);
                Log.info(PPU, "PIPE-LOG habilitado limite=%d", applicationOptions.pipeLogLimit);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.dbgBgSample > 0) {
                if (applicationOptions.dbgBgAll)
                    context.emulator.getPpu().enableBackgroundSampleDebugAll(applicationOptions.dbgBgSample);
                else
                    context.emulator.getPpu().enableBackgroundSampleDebug(applicationOptions.dbgBgSample);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.timingSimple) {
                context.emulator.getPpu().setSimpleTiming(true);
            }
            if (applicationOptions.timingModeOpt != null) {
                if (applicationOptions.timingModeOpt.equals("interleaved")) {
                    context.emulator.setTimingMode(NesEmulator.TimingMode.INTERLEAVED);
                } else {
                    context.emulator.setTimingMode(NesEmulator.TimingMode.SIMPLE);
                }
                Log.info(GENERAL, "Timing mode: %s", applicationOptions.timingModeOpt);
            }
            if (applicationOptions.spinWatchThreshold > 0) {
                context.emulator.enableSpinWatch(applicationOptions.spinWatchThreshold);
                Log.info(GENERAL, "Spin watch ativo: threshold=%d", applicationOptions.spinWatchThreshold);
                if (applicationOptions.spinDumpBytes > 0) {
                    context.emulator.setSpinDumpBytes(applicationOptions.spinDumpBytes);
                    Log.info(GENERAL, "Spin dump bytes=%d", applicationOptions.spinDumpBytes);
                }
            }
            if (applicationOptions.mmc1LogLimit > 0
                    && context.emulator.getMapper() instanceof com.nesemu.mapper.Mapper1 m1) {
                m1.enableBankLogging(applicationOptions.mmc1LogLimit);
                Log.info(GENERAL, "MMC1 logging ativo (limite=%d)", applicationOptions.mmc1LogLimit);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.forceBg) {
                context.emulator.getPpu().setForceBackgroundEnable(true);
                Log.info(PPU, "FORCE-BG bit3 PPUMASK");
            }
        }
        // Test pattern: se standalone, aplicaremos em PPU isolada depois
        if (!patternStandalone) {
            if (applicationOptions.testPattern != null) {
                context.emulator.getPpu().setTestPatternMode(applicationOptions.testPattern);
                Log.info(PPU, "TEST-PATTERN modo=%s", applicationOptions.testPattern);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.unlimitedSprites != null) {
                context.emulator.getPpu().setUnlimitedSprites(applicationOptions.unlimitedSprites);
                Log.info(PPU, "Unlimited sprites: %s", applicationOptions.unlimitedSprites ? "ON" : "OFF");
            }
            if (applicationOptions.spriteYMode != null) {
                boolean hw = applicationOptions.spriteYMode.equals("hardware");
                context.emulator.getPpu().setSpriteYHardware(hw);
                Log.info(PPU, "Sprite Y mode: %s", hw ? "HARDWARE(+1)" : "TEST(EXACT)");
            }
            if (applicationOptions.forceSprite0Hit) {
                context.emulator.getPpu().setForceSprite0Hit(true);
                Log.warn(PPU, "[DEBUG] Force sprite0 hit habilitado");
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.logAttrLimit > 0) {
                context.emulator.getPpu().enableAttributeRuntimeLog(applicationOptions.logAttrLimit);
            }
            if (applicationOptions.logNtLimit > 0) {
                context.emulator.getPpu().enableNametableRuntimeLog(applicationOptions.logNtLimit,
                        applicationOptions.ntBaseline == null ? -1 : applicationOptions.ntBaseline);
            }
            if (applicationOptions.paletteLogLimit > 0) {
                context.emulator.getPpu().enablePaletteWriteLog(applicationOptions.paletteLogLimit);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.initScroll) {
                context.emulator.getBus().write(0x2000, 0x10);
                context.emulator.getBus().write(0x2005, 0x00);
                context.emulator.getBus().write(0x2005, 0x00);
                context.emulator.getBus().write(0x2006, 0x20);
                context.emulator.getBus().write(0x2006, 0x00);
                Log.debug(PPU, "INIT-SCROLL VRAM inicializada nametable0 pattern $1000");
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.showHeader) {
                var h = context.rom.getHeader();
                Log.info(ROM, "Header: PRG=%d x16KB (%d bytes) CHR=%d x8KB (%d bytes) Mapper=%d Mirroring=%s",
                        h.getPrgRomPages(), h.getPrgRomPages() * 16384, h.getChrRomPages(), h.getChrRomPages() * 8192,
                        h.getMapper(),
                        h.isVerticalMirroring() ? "VERTICAL" : "HORIZONTAL");
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.chrLog) {
                context.emulator.getBus().getMapper0().enableChrLogging(256);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.logPpuReg) {
                context.emulator.getBus().enablePpuRegLogging(800);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.breakReadAddr >= 0) {
                context.emulator.getBus().setWatchReadAddress(applicationOptions.breakReadAddr,
                        applicationOptions.breakReadCount);
                Log.info(BUS, "Watch leitura %04X count=%d", applicationOptions.breakReadAddr,
                        applicationOptions.breakReadCount);
            }
        }
        if (!patternStandalone) {
            if (applicationOptions.traceNmi) {
                context.emulator.getPpu().setNmiCallback(() -> {
                    int pc = context.emulator.getCpu().getPC();
                    Log.debug(PPU, "NMI frame=%d PC=%04X cycles=%d", context.emulator.getFrame(), pc,
                            context.emulator.getCpu().getTotalCycles());
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
        if (context.emulator != null)
            context.emulator.getBus().write(0x2001, initMask);
        Log.info(PPU, "PPUMASK inicial=%02X%s", initMask,
                (applicationOptions.initialMaskOverride != null ? " (override)" : ""));
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
            if (context.emulator != null)
                ((PPU) context.emulator.getPpu()).setLeftColumnMode(mode);
            Log.info(PPU, "Left-column-mode=%s", mode.name().toLowerCase(Locale.ROOT));
        }
        if (applicationOptions.gui) {
            final UserConfig userConfig = UserConfig.load();
            new GuiLauncher(context, applicationOptions, userConfig, controllerPad1, controllerPad2, gamepadPoller)
                    .launch();
        } else {
            new HeadlessLauncher(context, applicationOptions).launch();
        }
    }
}
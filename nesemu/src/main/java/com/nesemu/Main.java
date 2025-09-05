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
import com.nesemu.config.ConfigUtils;
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
        var cli = com.nesemu.config.CLIOptionsParser.parse(args);
        boolean gui = cli.gui;
        boolean guiCliSpecified = cli.guiCliSpecified; // registra se usuário definiu GUI explicitamente na CLI
        String romPath = cli.romPath;
        int frames = cli.frames;
        boolean dumpNt = cli.dumpNt;
        Integer dumpPattern = cli.dumpPattern; // tile opcional para dump
        String dumpPatternsList = cli.dumpPatternsList; // lista separada por vírgula (hex)
        boolean showHeader = cli.showHeader;
        boolean chrLog = cli.chrLog;
        String tileMatrixMode = cli.tileMatrixMode; // first|center|nonzero
        long traceInstrCount = cli.traceInstrCount;
        boolean traceNmi = cli.traceNmi;
        boolean logPpuReg = cli.logPpuReg;
        Integer breakAtPc = cli.breakAtPc;
        int breakReadAddr = cli.breakReadAddr;
        int breakReadCount = cli.breakReadCount;
        boolean untilVblank = cli.untilVblank; // novo modo: roda até primeiro vblank
        int dbgBgSample = cli.dbgBgSample; // N amostras de pixels de background para debug
        boolean dbgBgAll = cli.dbgBgAll; // logar todos (até limite)
        boolean initScroll = cli.initScroll; // inicializar scroll/addr manualmente
        boolean timingSimple = cli.timingSimple; // modo simples de mapeamento ciclo->pixel
        int logAttrLimit = cli.logAttrLimit; // ativa logging de attribute table writes
        int logNtLimit = cli.logNtLimit; // logging de writes de nametable (tiles)
        Integer ntBaseline = cli.ntBaseline; // filtrar valor repetitivo
        boolean forceBg = cli.forceBg; // força bit 3 do PPUMASK
        boolean bgColStats = cli.bgColStats; // imprime estatísticas de colunas
        boolean hud = cli.hud; // exibe HUD na GUI
        String testPattern = cli.testPattern; // modos: h, v, checker
        int pipeLogLimit = cli.pipeLogLimit; // ativa log do pipeline de fetch de background
        boolean quiet = cli.quiet; // desabilita logs verbosos
        Boolean verboseFlag = cli.verboseFlag; // se usuário força verbose
        String logLevelOpt = cli.logLevelOpt; // --log-level=TRACE|DEBUG|INFO|WARN|ERROR
        String logCatsOpt = cli.logCatsOpt; // --log-cats=CPU,PPU,... or ALL
        boolean logTimestamps = cli.logTimestamps; // --log-ts
        String resetKeyToken = cli.resetKeyToken; // configurable reset key (from ini)
        String toggleFullscreenKey = cli.toggleFullscreenKey; // INI/CLI
        String toggleHudKey = cli.toggleHudKey; // INI/CLI
        String toggleFullscreenProportionKey = cli.toggleFullscreenProportionKey; // INI/CLI
        int paletteLogLimit = cli.paletteLogLimit; // --log-palette[=N]
        Boolean unlimitedSprites = cli.unlimitedSprites; // --unlimited-sprites[=true|false]
        String spriteYMode = cli.spriteYMode; // --sprite-y=hardware|test
        String pacerModeOpt = cli.pacerModeOpt; // --pacer=legacy|hr
        Boolean bufferStrategyOpt = cli.bufferStrategyOpt; // --buffer-strategy[=true|false]
        Integer initialMaskOverride = cli.initialMaskOverride; // --initial-mask (none in CLI yet)
        Boolean borderlessFullscreen = cli.borderlessFullscreen; // --borderless-fullscreen
        String savePathOverride = cli.savePathOverride; // INI
        String saveStatePath = cli.saveStatePath; // INI
        String saveStateKey = cli.saveStateKey; // INI
        String loadStateKey = cli.loadStateKey; // INI
        String timingModeOpt = cli.timingModeOpt; // --timing-mode
        String fastForwardKey = cli.fastForwardKey; // INI
        String fastForwardKeyCli = cli.fastForwardKeyCli; // CLI override
        int fastForwardMaxFps = cli.fastForwardMaxFps; // INI
        Integer fastForwardMaxFpsCli = cli.fastForwardMaxFpsCli; // CLI override
        String leftColumnModeOpt = cli.leftColumnModeOpt; // --left-column-mode
        long spinWatchThreshold = cli.spinWatchThreshold; // --spin-watch
        int mmc1LogLimit = cli.mmc1LogLimit; // --log-mmc1
        int spinDumpBytes = cli.spinDumpBytes; // --spin-dump-bytes
        String logWarnKey = cli.logWarnKey; // INI
        boolean forceSprite0Hit = cli.forceSprite0Hit; // --force-sprite0-hit

        final INesRom[] romRef = new INesRom[1]; // referências mutáveis para uso em lambdas
        final NesEmulator[] emuRef = new NesEmulator[1];
        boolean patternStandalone = false; // modo especial: renderiza apenas padrão sintético sem ROM/CPU
        // Load configuration file for controller + global option fallbacks (ROM
        // override may occur here)
        InputConfig inputCfg;
        try {
            inputCfg = ConfigUtils.loadInputConfig();
            // Apply fallback options (only where CLI not specified)
            if (inputCfg.hasOption("quiet") && !quiet)
                quiet = Boolean.parseBoolean(inputCfg.getOption("quiet"));
            // ROM override: only if user didn't pass ROM via CLI (romPath still null)
            if (romPath == null && inputCfg.hasOption("ROM")) {
                String iniRom = inputCfg.getOption("ROM").trim();
                if (!iniRom.isEmpty()) {
                    romPath = iniRom;
                    Log.info(ROM, "ROM definida via INI: %s", romPath);
                }
            }
            if (!guiCliSpecified && !gui && inputCfg.hasOption("gui"))
                gui = Boolean.parseBoolean(inputCfg.getOption("gui"));
            if (verboseFlag == null && inputCfg.hasOption("verbose"))
                verboseFlag = Boolean.parseBoolean(inputCfg.getOption("verbose"));
            if (!hud && inputCfg.hasOption("hud"))
                hud = Boolean.parseBoolean(inputCfg.getOption("hud"));
            if (logLevelOpt == null && inputCfg.hasOption("log-level"))
                logLevelOpt = inputCfg.getOption("log-level");
            if (logCatsOpt == null && inputCfg.hasOption("log-cats"))
                logCatsOpt = inputCfg.getOption("log-cats");
            if (!logTimestamps && inputCfg.hasOption("log-ts"))
                logTimestamps = Boolean.parseBoolean(inputCfg.getOption("log-ts"));
            if (tileMatrixMode == null && inputCfg.hasOption("tile-matrix"))
                tileMatrixMode = inputCfg.getOption("tile-matrix");
            if (!chrLog && inputCfg.hasOption("chr-log"))
                chrLog = Boolean.parseBoolean(inputCfg.getOption("chr-log"));
            if (!dumpNt && inputCfg.hasOption("dump-nt"))
                dumpNt = Boolean.parseBoolean(inputCfg.getOption("dump-nt"));
            if (dumpPattern == null && inputCfg.hasOption("dump-pattern"))
                try {
                    dumpPattern = Integer.parseInt(inputCfg.getOption("dump-pattern"), 16);
                } catch (Exception ignore) {
                }
            if (dumpPatternsList == null && inputCfg.hasOption("dump-patterns"))
                dumpPatternsList = inputCfg.getOption("dump-patterns");
            if (!traceNmi && inputCfg.hasOption("trace-nmi"))
                traceNmi = Boolean.parseBoolean(inputCfg.getOption("trace-nmi"));
            if (!logPpuReg && inputCfg.hasOption("log-ppu-reg"))
                logPpuReg = Boolean.parseBoolean(inputCfg.getOption("log-ppu-reg"));
            if (breakAtPc == null && inputCfg.hasOption("break-at"))
                try {
                    breakAtPc = Integer.parseInt(inputCfg.getOption("break-at"), 16);
                } catch (Exception ignore) {
                }
            if (breakReadAddr < 0 && inputCfg.hasOption("break-read"))
                try {
                    var spec = inputCfg.getOption("break-read");
                    var parts = spec.split(",");
                    breakReadAddr = Integer.parseInt(parts[0], 16);
                    if (parts.length > 1)
                        breakReadCount = Integer.parseInt(parts[1]);
                } catch (Exception ignore) {
                }
            if (!untilVblank && inputCfg.hasOption("until-vblank"))
                untilVblank = Boolean.parseBoolean(inputCfg.getOption("until-vblank"));
            if (dbgBgSample == 0 && inputCfg.hasOption("dbg-bg-sample"))
                try {
                    dbgBgSample = Integer.parseInt(inputCfg.getOption("dbg-bg-sample"));
                } catch (Exception ignore) {
                }
            if (!dbgBgAll && inputCfg.hasOption("dbg-bg-all"))
                dbgBgAll = Boolean.parseBoolean(inputCfg.getOption("dbg-bg-all"));
            if (!initScroll && inputCfg.hasOption("init-scroll"))
                initScroll = Boolean.parseBoolean(inputCfg.getOption("init-scroll"));
            if (!timingSimple && inputCfg.hasOption("timing-simple"))
                timingSimple = Boolean.parseBoolean(inputCfg.getOption("timing-simple"));
            if (logAttrLimit == 0 && inputCfg.hasOption("log-attr"))
                try {
                    logAttrLimit = Integer.parseInt(inputCfg.getOption("log-attr"));
                } catch (Exception ignore) {
                }
            if (logNtLimit == 0 && inputCfg.hasOption("log-nt"))
                try {
                    logNtLimit = Integer.parseInt(inputCfg.getOption("log-nt"));
                } catch (Exception ignore) {
                }
            // nt-baseline (apenas parsing – não deve conter lógica de ROM)
            if (ntBaseline == null && inputCfg.hasOption("nt-baseline")) {
                try {
                    ntBaseline = Integer.parseInt(inputCfg.getOption("nt-baseline"), 16) & 0xFF;
                } catch (Exception ignore) {
                }
            }
            if (!forceBg && inputCfg.hasOption("force-bg"))
                forceBg = Boolean.parseBoolean(inputCfg.getOption("force-bg"));
            if (leftColumnModeOpt == null && inputCfg.hasOption("left-column-mode"))
                leftColumnModeOpt = inputCfg.getOption("left-column-mode").trim().toLowerCase(Locale.ROOT);
            if (!bgColStats && inputCfg.hasOption("bg-col-stats"))
                bgColStats = Boolean.parseBoolean(inputCfg.getOption("bg-col-stats"));
            if (testPattern == null && inputCfg.hasOption("test-pattern"))
                testPattern = inputCfg.getOption("test-pattern");
            if (pipeLogLimit == 0 && inputCfg.hasOption("pipe-log"))
                try {
                    pipeLogLimit = Integer.parseInt(inputCfg.getOption("pipe-log"));
                } catch (Exception ignore) {
                }
            if (frames == 60 && inputCfg.hasOption("frames"))
                try {
                    frames = Integer.parseInt(inputCfg.getOption("frames"));
                } catch (Exception ignore) {
                }
            if (resetKeyToken == null && inputCfg.hasOption("reset")) {
                resetKeyToken = inputCfg.getOption("reset");
            }
            if (inputCfg.hasOption("toggle-fullscreen")) {
                toggleFullscreenKey = inputCfg.getOption("toggle-fullscreen");
            }
            if (inputCfg.hasOption("toggle-hud")) {
                toggleHudKey = inputCfg.getOption("toggle-hud");
            }
            if (inputCfg.hasOption("log-warn-key")) {
                logWarnKey = inputCfg.getOption("log-warn-key");
            }
            if (inputCfg.hasOption("toogle-fullscreen-proportion")) { // note: key spelled 'toogle' per INI
                toggleFullscreenProportionKey = inputCfg.getOption("toogle-fullscreen-proportion");
            }
            if (paletteLogLimit == 0 && inputCfg.hasOption("log-palette")) {
                try {
                    paletteLogLimit = Integer.parseInt(inputCfg.getOption("log-palette"));
                } catch (Exception ignore) {
                    paletteLogLimit = 256; // fallback default if present but not numeric
                }
            }
            if (unlimitedSprites == null && inputCfg.hasOption("unlimited-sprites")) {
                try {
                    String v = inputCfg.getOption("unlimited-sprites").trim().toLowerCase(Locale.ROOT);
                    if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                        unlimitedSprites = Boolean.TRUE;
                    else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                        unlimitedSprites = Boolean.FALSE;
                } catch (Exception ignore) {
                }
            }
            if (spriteYMode == null && inputCfg.hasOption("sprite-y")) {
                String v = inputCfg.getOption("sprite-y").trim().toLowerCase(Locale.ROOT);
                if (v.equals("hardware") || v.equals("test"))
                    spriteYMode = v;
            }
            if (timingModeOpt == null && inputCfg.hasOption("timing-mode")) {
                String v = inputCfg.getOption("timing-mode").trim().toLowerCase(Locale.ROOT);
                if (v.equals("simple") || v.equals("interleaved"))
                    timingModeOpt = v;
            }
            if (pacerModeOpt == null && inputCfg.hasOption("pacer")) {
                if (traceInstrCount == 0 && inputCfg.hasOption("trace-cpu")) {
                    try {
                        traceInstrCount = Long.parseLong(inputCfg.getOption("trace-cpu").trim());
                    } catch (Exception ignored) {
                    }
                }
                if (!forceSprite0Hit && inputCfg.hasOption("force-sprite0-hit")) {
                    // Enable debug forced sprite0 hit via INI (boolean; true=enable)
                    try {
                        forceSprite0Hit = Boolean.parseBoolean(inputCfg.getOption("force-sprite0-hit").trim());
                    } catch (Exception ignored) {
                        /* fallback remains false */ }
                }
                if (spinWatchThreshold == 0 && inputCfg.hasOption("spin-watch")) {
                    try {
                        spinWatchThreshold = Long.parseLong(inputCfg.getOption("spin-watch").trim());
                    } catch (Exception ignored) {
                    }
                }
                if (spinDumpBytes == 0 && inputCfg.hasOption("spin-dump-bytes")) {
                    try {
                        spinDumpBytes = Integer.parseInt(inputCfg.getOption("spin-dump-bytes").trim());
                    } catch (Exception ignored) {
                    }
                }
                if (mmc1LogLimit == 0 && inputCfg.hasOption("log-mmc1")) {
                    String v = inputCfg.getOption("log-mmc1").trim();
                    if (v.isEmpty())
                        mmc1LogLimit = 128;
                    else {
                        try {
                            mmc1LogLimit = Integer.parseInt(v);
                        } catch (Exception ignored) {
                        }
                    }
                }
                String v = inputCfg.getOption("pacer").trim().toLowerCase(Locale.ROOT);
                if (v.equals("legacy") || v.equals("hr"))
                    pacerModeOpt = v;
            }
            if (bufferStrategyOpt == null && inputCfg.hasOption("buffer-strategy")) {
                try {
                    String v = inputCfg.getOption("buffer-strategy").trim().toLowerCase(Locale.ROOT);
                    if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                        bufferStrategyOpt = Boolean.TRUE;
                    else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                        bufferStrategyOpt = Boolean.FALSE;
                } catch (Exception ignore) {
                }
            }
            if (borderlessFullscreen == null && inputCfg.hasOption("borderless-fullscreen")) {
                try {
                    String v = inputCfg.getOption("borderless-fullscreen").trim().toLowerCase(Locale.ROOT);
                    if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                        borderlessFullscreen = Boolean.TRUE;
                    else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                        borderlessFullscreen = Boolean.FALSE;
                } catch (Exception ignore) {
                }
            }
            if (inputCfg.hasOption("save-path")) { // permite diretório alternativo para .sav
                try {
                    String sp = inputCfg.getOption("save-path").trim();
                    if (!sp.isEmpty())
                        savePathOverride = sp;
                } catch (Exception ignore) {
                }
            }
            if (inputCfg.hasOption("save-state-path")) {
                try {
                    String sp = inputCfg.getOption("save-state-path").trim();
                    if (!sp.isEmpty())
                        saveStatePath = sp;
                } catch (Exception ignore) {
                }
            }
            if (inputCfg.hasOption("save-state")) {
                saveStateKey = inputCfg.getOption("save-state");
            }
            if (inputCfg.hasOption("load-state")) {
                loadStateKey = inputCfg.getOption("load-state");
            }
            if (inputCfg.hasOption("fast-foward")) { // manter grafia conforme INI
                fastForwardKey = inputCfg.getOption("fast-foward");
            }
            if (inputCfg.hasOption("fast-foward-max-fps")) { // INI throttle
                try {
                    fastForwardMaxFps = Integer.parseInt(inputCfg.getOption("fast-foward-max-fps").trim());
                } catch (Exception ignore) {
                }
            }
            // (Adia attach de controllers até após criação do emu)
        } catch (Exception ex) {
            Log.warn(CONTROLLER, "Falha ao carregar configuração de input: %s", ex.getMessage());
        }

        // Resolve caminho da ROM (precedência: CLI > INI > default)
        if (testPattern != null) {
            // Sempre isola test-pattern para não sofrer efeitos de ROM / CPU / mappers
            patternStandalone = true;
            Log.info(ROM, "Modo test-pattern standalone: ignorando carregamento de ROM");
        }
        Path romFilePath = null;
        if (!patternStandalone) {
            if (romPath == null || romPath.isBlank()) {
                if (!gui) {
                    Log.error(ROM, "Nenhuma ROM especificada. Use argumento CLI ou defina 'rom=' no emulator.ini");
                    Log.error(ROM, "Execução abortada (headless requer ROM ou test-pattern)");
                    return;
                } else {
                    Log.warn(ROM, "Nenhuma ROM especificada. Iniciando GUI em tela preta (PPU idle)");
                }
            } else {
                Path rp = Path.of(romPath);
                if (Files.isDirectory(rp)) {
                    if (gui) {
                        // Não tente carregar diretório como ROM. GUI iniciará com tela preta e o
                        // File->Load ROM abrirá diretamente neste diretório (configurado adiante).
                        Log.info(ROM, "INI rom aponta para diretório: %s (GUI usará como pasta inicial)", rp);
                        romFilePath = null; // tela preta + chooser naquela pasta
                    } else {
                        // Headless: tenta escolher a primeira .nes dentro do diretório.
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
                        if (!gui) {
                            Log.error(ROM, "ROM não encontrada: %s", romPath);
                            return;
                        } else {
                            Log.warn(ROM, "ROM inexistente: %s. Iniciando em tela preta.", romPath);
                            romFilePath = null; // força tela preta
                        }
                    }
                }
            }
            if (romFilePath != null) {
                Log.info(ROM, "Carregando ROM: %s", romFilePath.toAbsolutePath());
                romRef[0] = RomLoader.load(romFilePath);
                Path saveDir = null;
                if (savePathOverride != null) {
                    try {
                        saveDir = Path.of(savePathOverride);
                    } catch (Exception ignore) {
                    }
                }
                if (saveDir != null) {
                    emuRef[0] = new NesEmulator(romRef[0], romFilePath, saveDir);
                    Log.info(GENERAL, "save-path override: %s", saveDir.toAbsolutePath());
                } else {
                    emuRef[0] = new NesEmulator(romRef[0], romFilePath);
                }
            } else if (gui) {
                // Criar emulador "vazio" apenas com PPU para tela preta (HUD/ESC funcionam)
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
        if (quiet) {
            com.nesemu.ppu.PPU.setVerboseLogging(false);
            Bus.setGlobalVerbose(false);
        } else if (verboseFlag != null && verboseFlag) {
            com.nesemu.ppu.PPU.setVerboseLogging(true);
            Bus.setGlobalVerbose(true);
        }
        // Configurar nível de log se fornecido
        if (logLevelOpt != null) {
            try {
                Log.setLevel(Log.Level.valueOf(logLevelOpt.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                Log.warn(GENERAL, "Nível de log inválido: %s (usar TRACE|DEBUG|INFO|WARN|ERROR)", logLevelOpt);
            }
        } else if (verboseFlag != null && verboseFlag && !quiet) {
            Log.setLevel(Log.Level.DEBUG);
        }
        // Configurar categorias
        if (logCatsOpt != null) {
            if (logCatsOpt.equalsIgnoreCase("ALL")) {
                Log.setCategories(EnumSet.allOf(Log.Cat.class));
            } else {
                EnumSet<Log.Cat> set = EnumSet.noneOf(Log.Cat.class);
                for (String c : logCatsOpt.split(",")) {
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
        if (logTimestamps) {
            Log.setTimestamps(true);
        }
        if (!patternStandalone) {
            if (tileMatrixMode != null) {
                emuRef[0].getPpu().setTileMatrixMode(tileMatrixMode);
                Log.info(PPU, "Tile matrix mode: %s", tileMatrixMode);
            }
        }
        // Se solicitou pipe-log e nenhum modo de tile matrix foi especificado, usar
        // 'center'
        if (!patternStandalone) {
            if (pipeLogLimit > 0 && tileMatrixMode == null) {
                emuRef[0].getPpu().setTileMatrixMode("center");
                Log.debug(PPU, "PIPE-LOG ajustando tileMatrixMode=center");
            }
            if (pipeLogLimit > 0) {
                emuRef[0].getPpu().enablePipelineLog(pipeLogLimit);
                Log.info(PPU, "PIPE-LOG habilitado limite=%d", pipeLogLimit);
            }
        }
        if (!patternStandalone) {
            if (dbgBgSample > 0) {
                if (dbgBgAll)
                    emuRef[0].getPpu().enableBackgroundSampleDebugAll(dbgBgSample);
                else
                    emuRef[0].getPpu().enableBackgroundSampleDebug(dbgBgSample);
            }
        }
        if (!patternStandalone) {
            if (timingSimple) {
                emuRef[0].getPpu().setSimpleTiming(true); // deprecated path retained
            }
            // Aplica modo de temporização se definido (default SIMPLE já é padrão)
            if (timingModeOpt != null) {
                if (timingModeOpt.equals("interleaved")) {
                    emuRef[0].setTimingMode(NesEmulator.TimingMode.INTERLEAVED);
                } else {
                    emuRef[0].setTimingMode(NesEmulator.TimingMode.SIMPLE);
                }
                Log.info(GENERAL, "Timing mode: %s", timingModeOpt);
            }
            if (spinWatchThreshold > 0) {
                emuRef[0].enableSpinWatch(spinWatchThreshold);
                Log.info(GENERAL, "Spin watch ativo: threshold=%d", spinWatchThreshold);
                if (spinDumpBytes > 0) {
                    emuRef[0].setSpinDumpBytes(spinDumpBytes);
                    Log.info(GENERAL, "Spin dump bytes=%d", spinDumpBytes);
                }
            }
            if (mmc1LogLimit > 0 && emuRef[0].getMapper() instanceof com.nesemu.mapper.Mapper1 m1) {
                m1.enableBankLogging(mmc1LogLimit);
                Log.info(GENERAL, "MMC1 logging ativo (limite=%d)", mmc1LogLimit);
            }
        }
        if (!patternStandalone) {
            if (forceBg) {
                emuRef[0].getPpu().setForceBackgroundEnable(true);
                Log.info(PPU, "FORCE-BG bit3 PPUMASK");
            }
        }
        // Test pattern: se standalone, aplicaremos em PPU isolada depois
        if (!patternStandalone) {
            if (testPattern != null) {
                emuRef[0].getPpu().setTestPatternMode(testPattern);
                Log.info(PPU, "TEST-PATTERN modo=%s", testPattern);
            }
        }
        if (!patternStandalone) {
            if (unlimitedSprites != null) {
                emuRef[0].getPpu().setUnlimitedSprites(unlimitedSprites);
                Log.info(PPU, "Unlimited sprites: %s", unlimitedSprites ? "ON" : "OFF");
            }
            if (spriteYMode != null) {
                boolean hw = spriteYMode.equals("hardware");
                emuRef[0].getPpu().setSpriteYHardware(hw);
                Log.info(PPU, "Sprite Y mode: %s", hw ? "HARDWARE(+1)" : "TEST(EXACT)");
            }
            if (forceSprite0Hit) {
                emuRef[0].getPpu().setForceSprite0Hit(true);
                Log.warn(PPU, "[DEBUG] Force sprite0 hit habilitado");
            }
        }
        if (!patternStandalone) {
            if (logAttrLimit > 0) {
                emuRef[0].getPpu().enableAttributeRuntimeLog(logAttrLimit);
            }
            if (logNtLimit > 0) {
                emuRef[0].getPpu().enableNametableRuntimeLog(logNtLimit, ntBaseline == null ? -1 : ntBaseline);
            }
            if (paletteLogLimit > 0) {
                emuRef[0].getPpu().enablePaletteWriteLog(paletteLogLimit);
            }
        }
        if (!patternStandalone) {
            if (initScroll) {
                // Configura scroll e VRAM address iniciais (coarse/fine = 0, nametable 0)
                emuRef[0].getBus().write(0x2000, 0x10); // background pattern table = $1000
                emuRef[0].getBus().write(0x2005, 0x00); // X scroll
                emuRef[0].getBus().write(0x2005, 0x00); // Y scroll
                emuRef[0].getBus().write(0x2006, 0x20); // high byte (0x2000)
                emuRef[0].getBus().write(0x2006, 0x00); // low byte
                Log.debug(PPU, "INIT-SCROLL VRAM inicializada nametable0 pattern $1000");
            }
        }
        if (!patternStandalone) {
            if (showHeader) {
                var h = romRef[0].getHeader();
                Log.info(ROM, "Header: PRG=%d x16KB (%d bytes) CHR=%d x8KB (%d bytes) Mapper=%d Mirroring=%s",
                        h.getPrgRomPages(), h.getPrgRomPages() * 16384, h.getChrRomPages(), h.getChrRomPages() * 8192,
                        h.getMapper(),
                        h.isVerticalMirroring() ? "VERTICAL" : "HORIZONTAL");
            }
        }
        if (!patternStandalone) {
            if (chrLog) {
                emuRef[0].getBus().getMapper0().enableChrLogging(256);
            }
        }
        if (!patternStandalone) {
            if (logPpuReg) {
                emuRef[0].getBus().enablePpuRegLogging(800);
            }
        }
        if (!patternStandalone) {
            if (breakReadAddr >= 0) {
                emuRef[0].getBus().setWatchReadAddress(breakReadAddr, breakReadCount);
                Log.info(BUS, "Watch leitura %04X count=%d", breakReadAddr, breakReadCount);
            }
        }
        if (!patternStandalone) {
            if (traceNmi) {
                emuRef[0].getPpu().setNmiCallback(() -> {
                    int pc = emuRef[0].getCpu().getPC();
                    Log.debug(PPU, "NMI frame=%d PC=%04X cycles=%d", emuRef[0].getFrame(), pc,
                            emuRef[0].getCpu().getTotalCycles());
                });
            }
        }
        if (patternStandalone) {
            if (!gui) {
                Log.info(GENERAL, "Standalone test-pattern headless (sem GUI)");
            }
            // Modo puro de padrão: instância isolada de PPU
            PPU ppu = new PPU();
            ppu.reset();
            ppu.setTestPatternMode(testPattern); // já validado não-nulo
            // Habilita background (bit 3) para permitir pipeline & render (necessário para
            // passar renderingEnabled())
            // Habilita background (bit3) e garante BG_LEFT (bit1) para visualizar primeiros
            // 8 pixels
            ppu.writeRegister(1, 0x08 | 0x02);
            if (gui) {
                NesWindow window = new NesWindow("R2-NES TestPattern-" + testPattern, 3);
                window.show(ppu.getFrameBuffer());
                Log.info(GENERAL, "Iniciando GUI TestPattern (Ctrl+C para sair)");
                window.startRenderLoop(() -> {
                    long f = ppu.getFrame();
                    while (ppu.getFrame() == f) {
                        ppu.clock();
                    }
                }, 60, NesWindow.PacerMode.HR);
            } else {
                for (int i = 0; i < frames; i++) {
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
        int initMask = (initialMaskOverride != null) ? initialMaskOverride : 0x08; // default: BG only (left column
                                                                                   // blanked)
        emuRef[0].getBus().write(0x2001, initMask);
        Log.info(PPU, "PPUMASK inicial=%02X%s", initMask, (initialMaskOverride != null ? " (override)" : ""));
        if (leftColumnModeOpt != null) {
            com.nesemu.ppu.PPU.LeftColumnMode mode = com.nesemu.ppu.PPU.LeftColumnMode.HARDWARE;
            switch (leftColumnModeOpt) {
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
        if (gui) {
            String title = (romFilePath != null) ? ("R2-NES - " + romFilePath.getFileName()) : "R2-NES (no ROM)";
            NesWindow window = new NesWindow(title, 3);
            // Prevent automatic dispose so Alt+F4 / close button can confirm like ESC
            try {
                window.getFrame().setDefaultCloseOperation(javax.swing.JFrame.DO_NOTHING_ON_CLOSE);
            } catch (Exception ignore) {
            }
            // Apply fast-forward config (CLI override precedence)
            String effectiveFfKey = fastForwardKeyCli != null ? fastForwardKeyCli : fastForwardKey;
            if (fastForwardMaxFpsCli != null)
                fastForwardMaxFps = fastForwardMaxFpsCli;
            if (fastForwardMaxFps < 0)
                fastForwardMaxFps = 0;
            if (fastForwardMaxFps > 0) {
                window.setFastForwardMaxFps(fastForwardMaxFps);
                Log.info(GENERAL, "Fast-Forward max FPS: %d", fastForwardMaxFps);
            }
            final Path[] romFilePathHolder = new Path[] { romFilePath }; // may be null in black screen mode
            final String[] savePathOverrideHolder = new String[] { savePathOverride }; // mutable for menu reload
            // If INI rom option points to a directory, use it as the initial chooser dir
            try {
                if (romPath != null) {
                    Path rp = Path.of(romPath);
                    if (Files.isDirectory(rp)) {
                        window.setFileChooserStartDir(rp);
                    }
                }
            } catch (Exception ignore) {
            }
            final String[] saveStatePathHolder = new String[] { saveStatePath }; // mutable wrapper for closures
            if (borderlessFullscreen != null && borderlessFullscreen) {
                window.setBorderlessFullscreen(true);
                Log.info(GENERAL, "Borderless fullscreen: ON");
            } else if (borderlessFullscreen != null) {
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
                final String resetTok = resetKeyToken == null ? null : resetKeyToken.toLowerCase(Locale.ROOT).trim();
                window.installControllerKeyListener(controllerPad1, controllerPad2, resetTok, () -> {
                    Log.info(GENERAL, "RESET key pressed (%s)", resetTok);
                    emuRef[0].reset();
                    resetMsgExpireNs[0] = System.nanoTime() + 2_000_000_000L; // show for ~2s
                });
            }
            // Runtime toggles (fullscreen/HUD) via additional key listener
            // Mutable HUD state wrapper for inner classes
            final boolean[] hudState = new boolean[] { hud };
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
            if (toggleFullscreenKey != null || toggleHudKey != null || toggleFullscreenProportionKey != null
                    || saveStateKey != null || loadStateKey != null || effectiveFfKey != null || logWarnKey != null) {
                String fsKey = toggleFullscreenKey == null ? null : toggleFullscreenKey.toLowerCase(Locale.ROOT).trim();
                String hudKey = toggleHudKey == null ? null : toggleHudKey.toLowerCase(Locale.ROOT).trim();
                String propKey = toggleFullscreenProportionKey == null ? null
                        : toggleFullscreenProportionKey.toLowerCase(Locale.ROOT).trim();
                String saveKey = saveStateKey == null ? null : saveStateKey.toLowerCase(Locale.ROOT).trim();
                String loadKey = loadStateKey == null ? null : loadStateKey.toLowerCase(Locale.ROOT).trim();
                String ffKey = effectiveFfKey == null ? null : effectiveFfKey.toLowerCase(Locale.ROOT).trim();
                String warnKey = logWarnKey == null ? null : logWarnKey.toLowerCase(Locale.ROOT).trim();
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
            NesWindow.PacerMode pm = (pacerModeOpt == null || pacerModeOpt.equals("hr")) ? NesWindow.PacerMode.HR
                    : NesWindow.PacerMode.LEGACY;
            Log.info(GENERAL, "Pacer mode: %s", pm);
            if (bufferStrategyOpt != null) {
                window.setUseBufferStrategy(bufferStrategyOpt);
                Log.info(GENERAL, "BufferStrategy: %s", bufferStrategyOpt ? "ON" : "OFF (Swing repaint)");
            } else {
                Log.info(GENERAL, "BufferStrategy: DEFAULT(ON)");
            }
            // ---------------- Menu callback wiring (after overlay & show)
            // -----------------
            final boolean forceSprite0HitFinal = forceSprite0Hit; // capture config for reloads
            final Boolean unlimitedSpritesFinal = unlimitedSprites;
            final String leftColumnModeOptFinal = leftColumnModeOpt;
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
                    // Re-apply runtime flags
                    if (forceSprite0HitFinal) {
                        newEmu.getPpu().setForceSprite0Hit(true);
                    }
                    if (unlimitedSpritesFinal != null) {
                        newEmu.getPpu().setUnlimitedSprites(unlimitedSpritesFinal);
                    }
                    if (leftColumnModeOptFinal != null) {
                        com.nesemu.ppu.PPU.LeftColumnMode mode = com.nesemu.ppu.PPU.LeftColumnMode.HARDWARE;
                        switch (leftColumnModeOptFinal) {
                            case "always" -> mode = com.nesemu.ppu.PPU.LeftColumnMode.ALWAYS;
                            case "crop" -> mode = com.nesemu.ppu.PPU.LeftColumnMode.CROP;
                            case "hardware" -> mode = com.nesemu.ppu.PPU.LeftColumnMode.HARDWARE;
                        }
                        ((PPU) newEmu.getPpu()).setLeftColumnMode(mode);
                    }
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
            if (untilVblank) {
                long executed = 0;
                long maxInstr = (traceInstrCount > 0) ? traceInstrCount : 1_000_000; // guarda de segurança
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
                // Depois roda frames solicitados (se frames>0)
                for (int i = 0; i < frames; i++)
                    emuRef[0].stepFrame();
            } else if (traceInstrCount > 0) {
                // Trace N instruções ignorando frames, depois continua frames restantes se
                // definido
                long executed = 0;
                while (executed < traceInstrCount) {
                    int pc = emuRef[0].getCpu().getPC();
                    int opcode = emuRef[0].getBus().read(pc);
                    Log.trace(CPU, "TRACE PC=%04X OP=%02X A=%02X X=%02X Y=%02X P=%02X SP=%02X CYC=%d",
                            pc, opcode, emuRef[0].getCpu().getA(), emuRef[0].getCpu().getX(), emuRef[0].getCpu().getY(),
                            emuRef[0].getCpu().getStatusByte(), emuRef[0].getCpu().getSP(),
                            emuRef[0].getCpu().getTotalCycles());
                    // Executa instrução enquanto alimenta PPU com 3 ciclos por ciclo de CPU gasto.
                    long before = emuRef[0].getCpu().getTotalCycles();
                    emuRef[0].getCpu().stepInstruction();
                    long after = emuRef[0].getCpu().getTotalCycles();
                    long cpuSpent = after - before;
                    for (long c = 0; c < cpuSpent * 3; c++) {
                        emuRef[0].getPpu().clock();
                    }
                    executed++;
                    if (breakAtPc != null && emuRef[0].getCpu().getPC() == (breakAtPc & 0xFFFF)) {
                        Log.info(CPU, "BREAK PC=%04X após %d instruções", breakAtPc, executed);
                        break;
                    }
                    if (breakReadAddr >= 0 && emuRef[0].getBus().isWatchTriggered()) {
                        Log.info(BUS, "BREAK leitura %04X atingida count=%d após %d instr",
                                breakReadAddr, breakReadCount, executed);
                        break;
                    }
                    if (traceNmi && executed % 5000 == 0) {
                        Log.debug(CPU, "trace progress instr=%d", executed);
                    }
                }
                // Depois roda frames solicitados (se frames>0)
                for (int i = 0; i < frames; i++)
                    emuRef[0].stepFrame();
            } else {
                for (int i = 0; i < frames; i++) {
                    emuRef[0].stepFrame();
                }
            }
            long elapsedNs = System.nanoTime() - start;
            double fpsSim = frames / (elapsedNs / 1_000_000_000.0);
            Log.info(GENERAL, "Frames simulados: %d (%.2f fps)", frames, fpsSim);
            if (pipeLogLimit > 0) {
                Log.info(PPU, "--- PIPELINE LOG ---");
                Log.info(PPU, "%s", emuRef[0].getPpu().consumePipelineLog());
            }
            if (dbgBgSample > 0) {
                emuRef[0].getPpu().dumpFirstBackgroundSamples(Math.min(dbgBgSample, 50));
            }
            // Dump ASCII matrix (first pixel per tile)
            Log.info(PPU, "--- Tile index matrix (hex of first pixel per tile) ---");
            emuRef[0].getPpu().printTileIndexMatrix();
            emuRef[0].getPpu().printBackgroundIndexHistogram();
            if (bgColStats) {
                emuRef[0].getPpu().printBackgroundColumnStats();
            }
            // Dump PPM (palette index grayscale)
            Path out = Path.of("background.ppm");
            emuRef[0].getPpu().dumpBackgroundToPpm(out);
            Log.info(PPU, "PPM gerado: %s", out.toAbsolutePath());
            if (dumpNt) {
                emuRef[0].getPpu().printNameTableTileIds(0);
            }
            if (dumpPattern != null) {
                emuRef[0].getPpu().dumpPatternTile(dumpPattern);
            }
            if (dumpPatternsList != null) {
                for (String part : dumpPatternsList.split(",")) {
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
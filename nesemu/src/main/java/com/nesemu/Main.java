package com.nesemu;

import java.nio.file.*;
import java.util.EnumSet;
import java.util.Locale;

import com.nesemu.bus.Bus;
import com.nesemu.emulator.NesEmulator;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;
import com.nesemu.gui.NesWindow;
import com.nesemu.input.InputConfig;
import com.nesemu.rom.INesRom;
import com.nesemu.rom.RomLoader;
import com.nesemu.io.NesController;
import com.nesemu.ppu.Ppu2C02;

/**
 * Simple headless runner: loads a .nes ROM, executes a number of frames and
 * dumps background info.
 */
public class Main {
    private static NesController controllerPad1;
    private static NesController controllerPad2;

    public static void main(String[] args) throws Exception {
        boolean gui = false;
        boolean guiCliSpecified = false; // registra se usuário definiu GUI explicitamente na CLI
        String romPath = null;
        int frames = 60;
        boolean dumpNt = false;
        Integer dumpPattern = null; // tile opcional para dump
        String dumpPatternsList = null; // lista separada por vírgula (hex)
        boolean showHeader = false;
        boolean chrLog = false;
        String tileMatrixMode = null; // first|center|nonzero
        long traceInstrCount = 0;
        boolean traceNmi = false;
        boolean logPpuReg = false;
        Integer breakAtPc = null;
        int breakReadAddr = -1;
        int breakReadCount = 1;
        boolean untilVblank = false; // novo modo: roda até primeiro vblank
        int dbgBgSample = 0; // N amostras de pixels de background para debug
        boolean dbgBgAll = false; // logar todos (até limite)
        boolean initScroll = false; // inicializar scroll/addr manualmente
        boolean timingSimple = false; // modo simples de mapeamento ciclo->pixel
        int logAttrLimit = 0; // ativa logging de attribute table writes
        int logNtLimit = 0; // logging de writes de nametable (tiles)
        Integer ntBaseline = null; // filtrar valor repetitivo
        boolean forceBg = false; // força bit 3 do PPUMASK
        boolean bgColStats = false; // imprime estatísticas de colunas
        boolean hud = false; // exibe HUD na GUI
        String testPattern = null; // modos: h, v, checker
        int pipeLogLimit = 0; // ativa log do pipeline de fetch de background
        boolean quiet = false; // desabilita logs verbosos
        Boolean verboseFlag = null; // se usuário força verbose
        String logLevelOpt = null; // --log-level=TRACE|DEBUG|INFO|WARN|ERROR
        String logCatsOpt = null; // --log-cats=CPU,PPU,... or ALL
        boolean logTimestamps = false; // --log-ts
        String resetKeyToken = null; // configurable reset key (from ini)
        int paletteLogLimit = 0; // --log-palette[=N]
        Boolean unlimitedSprites = null; // --unlimited-sprites[=true|false]
        String spriteYMode = null; // --sprite-y=hardware|test
        String pacerModeOpt = null; // --pacer=legacy|hr
        Boolean bufferStrategyOpt = null; // --buffer-strategy[=true|false]
        Integer initialMaskOverride = null; // --initial-mask=HEX (ppumask value to write early)
        final INesRom[] romRef = new INesRom[1]; // referências mutáveis para uso em lambdas
        final NesEmulator[] emuRef = new NesEmulator[1];
        boolean patternStandalone = false; // modo especial: renderiza apenas padrão sintético sem ROM/CPU
        for (String a : args) {
            if (a.equalsIgnoreCase("--gui")) {
                gui = true;
                guiCliSpecified = true;
            } else if (a.equalsIgnoreCase("--no-gui")) {
                gui = false;
                guiCliSpecified = true;
            } else if (a.startsWith("--gui=")) {
                String v = a.substring(6).trim().toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on")) {
                    gui = true;
                    guiCliSpecified = true;
                } else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off")) {
                    gui = false;
                    guiCliSpecified = true;
                } else
                    Log.warn(GENERAL, "Valor inválido em --gui= (usar true|false)");
            } else if (a.startsWith("--frames="))
                frames = Integer.parseInt(a.substring(9));
            else if (a.equalsIgnoreCase("--dump-nt"))
                dumpNt = true;
            else if (a.equalsIgnoreCase("--until-vblank"))
                untilVblank = true;
            else if (a.startsWith("--dump-pattern=")) {
                try {
                    dumpPattern = Integer.parseInt(a.substring(15), 16);
                } catch (NumberFormatException e) {
                    Log.error(GENERAL, "Valor de tile inválido em --dump-pattern (usar hex)");
                }
            } else if (a.startsWith("--dump-patterns=")) {
                dumpPatternsList = a.substring(16);
            } else if (a.equalsIgnoreCase("--header"))
                showHeader = true;
            else if (a.equalsIgnoreCase("--chr-log"))
                chrLog = true;
            else if (a.startsWith("--tile-matrix=")) {
                tileMatrixMode = a.substring(14).trim();
            } else if (a.startsWith("--trace-cpu=")) {
                try {
                    traceInstrCount = Long.parseLong(a.substring(12));
                } catch (NumberFormatException e) {
                    Log.error(CPU, "Valor inválido para --trace-cpu (usar número de instruções)");
                }
            } else if (a.equalsIgnoreCase("--trace-nmi"))
                traceNmi = true;
            else if (a.equalsIgnoreCase("--log-ppu-reg"))
                logPpuReg = true;
            else if (a.startsWith("--break-at=")) {
                try {
                    breakAtPc = Integer.parseInt(a.substring(11), 16);
                } catch (NumberFormatException e) {
                    Log.error(CPU, "PC inválido em --break-at (hex)");
                }
            } else if (a.startsWith("--break-read=")) {
                String spec = a.substring(13);
                String[] parts = spec.split(",");
                try {
                    breakReadAddr = Integer.parseInt(parts[0], 16);
                    if (parts.length > 1)
                        breakReadCount = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    Log.error(CPU, "Formato --break-read=ADDR[,count] inválido");
                }
            } else if (a.startsWith("--dbg-bg-sample=")) {
                try {
                    dbgBgSample = Integer.parseInt(a.substring(17));
                } catch (NumberFormatException e) {
                    Log.error(PPU, "Valor inválido em --dbg-bg-sample (usar número)");
                }
            } else if (a.equalsIgnoreCase("--dbg-bg-all")) {
                dbgBgAll = true;
            } else if (a.equalsIgnoreCase("--init-scroll")) {
                initScroll = true;
            } else if (a.equalsIgnoreCase("--timing-simple")) {
                timingSimple = true;
            } else if (a.startsWith("--log-attr")) {
                if (a.contains("=")) {
                    try {
                        logAttrLimit = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    } catch (NumberFormatException e) {
                        Log.error(PPU, "Valor inválido em --log-attr= (usar número)");
                    }
                } else {
                    logAttrLimit = 200; // default
                }
            } else if (a.startsWith("--log-nt")) {
                if (a.contains("=")) {
                    try {
                        logNtLimit = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    } catch (NumberFormatException e) {
                        Log.error(PPU, "Valor inválido em --log-nt= (usar número)");
                    }
                } else {
                    logNtLimit = 200;
                }
            } else if (a.equalsIgnoreCase("--force-bg")) {
                forceBg = true;
            } else if (a.equalsIgnoreCase("--bg-col-stats")) {
                bgColStats = true;
            } else if (a.equalsIgnoreCase("--hud")) {
                hud = true;
            } else if (a.equalsIgnoreCase("--test-bands")) { // retrocompatível -> horizontal
                testPattern = "h";
            } else if (a.equalsIgnoreCase("--test-bands-h")) {
                testPattern = "h";
            } else if (a.equalsIgnoreCase("--test-bands-v")) {
                testPattern = "v";
            } else if (a.equalsIgnoreCase("--test-checker") || a.equalsIgnoreCase("--test-xadrez")) {
                testPattern = "checker";
            } else if (a.startsWith("--nt-baseline=")) {
                try {
                    int eq = a.indexOf('=');
                    if (eq >= 0 && eq + 1 < a.length()) {
                        ntBaseline = Integer.parseInt(a.substring(eq + 1), 16) & 0xFF;
                    } else {
                        throw new NumberFormatException("empty");
                    }
                } catch (NumberFormatException e) {
                    Log.error(PPU, "Valor inválido em --nt-baseline= (usar hex)");
                }
            } else if (a.startsWith("--pipe-log")) {
                int eq = a.indexOf('=');
                if (eq >= 0 && eq + 1 < a.length()) {
                    try {
                        pipeLogLimit = Integer.parseInt(a.substring(eq + 1));
                    } catch (NumberFormatException e) {
                        Log.error(PPU, "Valor inválido em --pipe-log= (usar número)");
                    }
                } else {
                    pipeLogLimit = 400; // default se não informado
                }
            } else if (a.equalsIgnoreCase("--quiet")) {
                quiet = true;
            } else if (a.equalsIgnoreCase("--no-debug")) {
                quiet = true;
            } else if (a.equalsIgnoreCase("--verbose")) {
                verboseFlag = Boolean.TRUE;
            } else if (a.startsWith("--log-level=")) {
                logLevelOpt = a.substring(12).trim();
            } else if (a.startsWith("--log-cats=")) {
                logCatsOpt = a.substring(11).trim();
            } else if (a.equalsIgnoreCase("--log-ts")) {
                logTimestamps = true;
            } else if (a.startsWith("--log-palette")) {
                if (a.contains("=")) {
                    try {
                        paletteLogLimit = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    } catch (NumberFormatException e) {
                        Log.warn(PPU, "Valor inválido em --log-palette= (usar número)");
                    }
                } else {
                    paletteLogLimit = 256; // default
                }
            } else if (a.startsWith("--reset-key=")) {
                resetKeyToken = a.substring(12).trim();
            } else if (a.equalsIgnoreCase("--unlimited-sprites")) {
                unlimitedSprites = Boolean.TRUE;
            } else if (a.startsWith("--unlimited-sprites=")) {
                String v = a.substring(20).trim().toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                    unlimitedSprites = Boolean.TRUE;
                else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                    unlimitedSprites = Boolean.FALSE;
                else
                    Log.warn(PPU, "Valor inválido em --unlimited-sprites= (usar true|false)");
            } else if (a.startsWith("--sprite-y=")) {
                String v = a.substring(11).trim().toLowerCase(Locale.ROOT);
                if (v.equals("hardware") || v.equals("test")) {
                    spriteYMode = v;
                } else {
                    Log.warn(PPU, "Valor inválido em --sprite-y= (usar hardware|test)");
                }
            } else if (a.startsWith("--pacer=")) {
                String v = a.substring(8).trim().toLowerCase(Locale.ROOT);
                if (v.equals("legacy") || v.equals("hr")) {
                    pacerModeOpt = v;
                } else {
                    Log.warn(GENERAL, "Valor inválido em --pacer= (usar legacy|hr)");
                }
            } else if (a.equalsIgnoreCase("--buffer-strategy")) {
                bufferStrategyOpt = Boolean.TRUE;
            } else if (a.startsWith("--buffer-strategy=")) {
                int eq = a.indexOf('=');
                String v = (eq >= 0 && eq + 1 < a.length()) ? a.substring(eq + 1) : "";
                v = v.trim().toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                    bufferStrategyOpt = Boolean.TRUE;
                else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                    bufferStrategyOpt = Boolean.FALSE;
                else
                    Log.warn(GENERAL, "Valor inválido em --buffer-strategy= (usar true|false)");
            } else if (!a.startsWith("--"))
                romPath = a;
        }
        // Load configuration file for controller + global option fallbacks (ROM
        // override may occur here)
        InputConfig inputCfg;
        try {
            var inputPath = Path.of("emulator.ini");
            if (Files.exists(inputPath))
                inputCfg = InputConfig.load(inputPath);
            else {
                var devPath = Path.of("src/main/java/com/nesemu/config/emulator.ini");
                if (Files.exists(devPath))
                    inputCfg = InputConfig.load(devPath);
                else
                    inputCfg = new InputConfig();
            }
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
            if (pacerModeOpt == null && inputCfg.hasOption("pacer")) {
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
            if (romPath == null) {
                romPath = "roms/donkeykong.nes"; // fallback relativo
                Log.info(ROM, "ROM fallback padrão: %s", romPath);
            }
            romFilePath = Path.of(romPath);
            if (!Files.exists(romFilePath)) {
                Log.error(ROM, "ROM não encontrada: %s", romPath);
                return;
            }
            Log.info(ROM, "Carregando ROM: %s", romFilePath.toAbsolutePath());
            romRef[0] = RomLoader.load(romFilePath);
            emuRef[0] = new NesEmulator(romRef[0]);
        }

        if (!patternStandalone) {
            // Agora que 'emu' existe, anexar controllers (se config carregada)
            try {
                var inputPath = Path.of("emulator.ini");
                InputConfig cfgForPads;
                if (Files.exists(inputPath))
                    cfgForPads = InputConfig.load(inputPath);
                else {
                    var devPath = Path.of("src/main/java/com/nesemu/config/emulator.ini");
                    if (Files.exists(devPath))
                        cfgForPads = InputConfig.load(devPath);
                    else
                        cfgForPads = new InputConfig();
                }
                var pad1 = new NesController(cfgForPads.getController(0));
                var pad2 = new NesController(cfgForPads.getController(1));
                emuRef[0].getBus().attachControllers(pad1, pad2);
                controllerPad1 = pad1;
                controllerPad2 = pad2;
            } catch (Exception e) {
                Log.warn(CONTROLLER, "Falha ao reprocessar config para controllers: %s", e.getMessage());
            }
        }
        // Aplicar política de verbosidade
        if (quiet) {
            Ppu2C02.setVerboseLogging(false);
            Bus.setGlobalVerbose(false);
        } else if (verboseFlag != null && verboseFlag) {
            Ppu2C02.setVerboseLogging(true);
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
                emuRef[0].getPpu().setSimpleTiming(true);
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
                emuRef[0].getBus().cpuWrite(0x2000, 0x10); // background pattern table = $1000
                emuRef[0].getBus().cpuWrite(0x2005, 0x00); // X scroll
                emuRef[0].getBus().cpuWrite(0x2005, 0x00); // Y scroll
                emuRef[0].getBus().cpuWrite(0x2006, 0x20); // high byte (0x2000)
                emuRef[0].getBus().cpuWrite(0x2006, 0x00); // low byte
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
            Ppu2C02 ppu = new Ppu2C02();
            ppu.reset();
            ppu.setTestPatternMode(testPattern); // já validado não-nulo
            // Habilita background (bit 3) para permitir pipeline & render (necessário para
            // passar renderingEnabled())
            // Habilita background (bit3) e garante BG_LEFT (bit1) para visualizar primeiros
            // 8 pixels
            ppu.writeRegister(1, 0x08 | 0x02);
            if (gui) {
                NesWindow window = new NesWindow("NESemu TestPattern-" + testPattern, 3);
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
        int initMask = (initialMaskOverride != null) ? initialMaskOverride : 0x0A; // 0x08 BG | 0x02 BG_LEFT
        emuRef[0].getBus().cpuWrite(0x2001, initMask);
        Log.info(PPU, "PPUMASK inicial=%02X%s", initMask, (initialMaskOverride != null ? " (override)" : ""));
        if (gui) {
            NesWindow window = new NesWindow("NESemu - " + romFilePath.getFileName(), 3);
            final long[] resetMsgExpireNs = new long[] { 0L };
            if (controllerPad1 != null) {
                final String resetTok = resetKeyToken == null ? null : resetKeyToken.toLowerCase(Locale.ROOT).trim();
                window.installControllerKeyListener(controllerPad1, controllerPad2, resetTok, () -> {
                    Log.info(GENERAL, "RESET key pressed (%s)", resetTok);
                    emuRef[0].reset();
                    resetMsgExpireNs[0] = System.nanoTime() + 2_000_000_000L; // show for ~2s
                });
            }
            // Unified overlay: HUD (optional) + transient reset message
            var ppu = emuRef[0].getPpu();
            final boolean hudFinal = hud; // capture value for lambda
            window.setOverlay(g2 -> {
                if (hudFinal) {
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
            window.startRenderLoop(() -> emuRef[0].stepFrame(), 60, pm); // target 60 fps
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
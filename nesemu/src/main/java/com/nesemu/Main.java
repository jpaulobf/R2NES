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
import com.nesemu.ppu.PPU;

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
        String toggleFullscreenKey = null; // INI: toggle-fullscreen
        String toggleHudKey = null; // INI: toggle-hud
        String toggleFullscreenProportionKey = null; // INI: toogle-fullscreen-proportion (cycles scaling)
        int paletteLogLimit = 0; // --log-palette[=N]
        Boolean unlimitedSprites = null; // --unlimited-sprites[=true|false]
        String spriteYMode = null; // --sprite-y=hardware|test
        String pacerModeOpt = null; // --pacer=legacy|hr
        Boolean bufferStrategyOpt = null; // --buffer-strategy[=true|false]
        Integer initialMaskOverride = null; // --initial-mask=HEX (ppumask value to write early)
        Boolean borderlessFullscreen = null; // --borderless-fullscreen / INI borderless-fullscreen
        String savePathOverride = null; // INI: save-path (diretório para arquivos .sav)
        String saveStatePath = null; // INI: save-state-path (dir for .state snapshots)
        String saveStateKey = null; // INI: save-state (hotkey token e.g. f5)
        String loadStateKey = null; // INI: load-state (hotkey token e.g. f7)
        String timingModeOpt = null; // --timing-mode=simple|interleaved
        String fastForwardKey = null; // INI: fast-foward (hold to disable pacing)
        String fastForwardKeyCli = null; // CLI override
        int fastForwardMaxFps = 0; // 0 = unlimited
        Integer fastForwardMaxFpsCli = null; // CLI override
        String leftColumnModeOpt = null; // --left-column-mode=hardware|always|crop (INI: left-column-mode)
        long spinWatchThreshold = 0; // --spin-watch=cycles (PC repeat cycles)
        int mmc1LogLimit = 0; // --log-mmc1[=N]
        int spinDumpBytes = 0; // --spin-dump-bytes=N
        String logWarnKey = null; // INI: log-warn-key (manual snapshot)
        boolean forceSprite0Hit = false; // --force-sprite0-hit (debug)

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
            } else if (a.startsWith("--timing-mode=")) {
                String v = a.substring(14).trim().toLowerCase(Locale.ROOT);
                if (v.equals("simple") || v.equals("interleaved")) {
                    timingModeOpt = v;
                } else {
                    Log.warn(GENERAL, "Valor inválido em --timing-mode= (usar simple|interleaved)");
                }
            } else if (a.startsWith("--fast-forward-key=")) {
                fastForwardKeyCli = a.substring(19).trim().toLowerCase(Locale.ROOT);
                if (fastForwardKeyCli.isEmpty())
                    fastForwardKeyCli = null;
            } else if (a.startsWith("--fast-forward-max-fps=")) {
                try {
                    fastForwardMaxFpsCli = Integer.parseInt(a.substring(24).trim());
                } catch (NumberFormatException e) {
                    Log.warn(GENERAL, "Valor inválido em --fast-forward-max-fps= (usar número inteiro)");
                }
            } else if (a.startsWith("--left-column-mode=")) {
                leftColumnModeOpt = a.substring(19).trim().toLowerCase(Locale.ROOT);
            } else if (a.startsWith("--spin-watch=")) {
                try {
                    spinWatchThreshold = Long.parseLong(a.substring(13).trim());
                } catch (NumberFormatException e) {
                    Log.warn(GENERAL, "Valor inválido em --spin-watch= (usar número)");
                }
            } else if (a.startsWith("--spin-dump-bytes=")) {
                try {
                    spinDumpBytes = Integer.parseInt(a.substring(18).trim());
                } catch (NumberFormatException e) {
                    Log.warn(GENERAL, "Valor inválido em --spin-dump-bytes= (usar número)");
                }
            } else if (a.equalsIgnoreCase("--force-sprite0-hit")) {
                forceSprite0Hit = true;
            } else if (a.equalsIgnoreCase("--log-mmc1")) {
                mmc1LogLimit = 128; // default
            } else if (a.startsWith("--log-mmc1=")) {
                try {
                    mmc1LogLimit = Integer.parseInt(a.substring(12).trim());
                } catch (NumberFormatException e) {
                    Log.warn(GENERAL, "Valor inválido em --log-mmc1= (usar número)");
                }
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
            } else if (a.equalsIgnoreCase("--borderless-fullscreen")) {
                borderlessFullscreen = Boolean.TRUE;
            } else if (a.equalsIgnoreCase("--no-borderless-fullscreen")) {
                borderlessFullscreen = Boolean.FALSE;
            } else if (a.startsWith("--borderless-fullscreen=")) {
                String v = a.substring(a.indexOf('=') + 1).trim().toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                    borderlessFullscreen = Boolean.TRUE;
                else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                    borderlessFullscreen = Boolean.FALSE;
                else
                    Log.warn(GENERAL, "Valor inválido em --borderless-fullscreen= (usar true|false)");
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
                romFilePath = Path.of(romPath);
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
                var inputPath = Path.of("emulator.ini");
                InputConfig tmpCfg;
                if (Files.exists(inputPath))
                    tmpCfg = InputConfig.load(inputPath);
                else {
                    var devPath = Path.of("src/main/java/com/nesemu/config/emulator.ini");
                    if (Files.exists(devPath))
                        tmpCfg = InputConfig.load(devPath);
                    else
                        tmpCfg = new InputConfig();
                }
                String pauseOptRaw = tmpCfg.getOption("pause-emulation");
                if (pauseOptRaw != null && !pauseOptRaw.isBlank()) {
                    for (String t : pauseOptRaw.split("/")) {
                        t = t.trim().toLowerCase(Locale.ROOT);
                        if (!t.isEmpty())
                            pauseKeyTokens.add(t);
                    }
                    if (!pauseKeyTokens.isEmpty()) {
                        Log.info(GENERAL, "Pause mapping tokens: %s", pauseKeyTokens);
                    }
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
                        String tok = keyEventToToken(e);
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
                        String tok = keyEventToToken(e);
                        if (tok == null)
                            return;
                        if (ffKey != null && tok.equals(ffKey)) {
                            if (window.isFastForward()) {
                                window.setFastForward(false);
                                Log.info(GENERAL, "Fast-Forward OFF");
                            }
                        }
                    }

                    private String keyEventToToken(java.awt.event.KeyEvent e) {
                        int code = e.getKeyCode();
                        switch (code) {
                            case java.awt.event.KeyEvent.VK_UP:
                                return "up";
                            case java.awt.event.KeyEvent.VK_DOWN:
                                return "down";
                            case java.awt.event.KeyEvent.VK_LEFT:
                                return "left";
                            case java.awt.event.KeyEvent.VK_RIGHT:
                                return "right";
                            case java.awt.event.KeyEvent.VK_ENTER:
                                return "enter";
                            case java.awt.event.KeyEvent.VK_BACK_SPACE:
                                return "backspace";
                            case java.awt.event.KeyEvent.VK_SPACE:
                                return "space";
                            case java.awt.event.KeyEvent.VK_ESCAPE:
                                return "escape";
                            case java.awt.event.KeyEvent.VK_PAUSE:
                                return "pause";
                            case java.awt.event.KeyEvent.VK_TAB:
                                return "tab";
                            case java.awt.event.KeyEvent.VK_F1:
                                return "f1";
                            case java.awt.event.KeyEvent.VK_F2:
                                return "f2";
                            case java.awt.event.KeyEvent.VK_F3:
                                return "f3";
                            case java.awt.event.KeyEvent.VK_F4:
                                return "f4";
                            case java.awt.event.KeyEvent.VK_F5:
                                return "f5";
                            case java.awt.event.KeyEvent.VK_F6:
                                return "f6";
                            case java.awt.event.KeyEvent.VK_F7:
                                return "f7";
                            case java.awt.event.KeyEvent.VK_F8:
                                return "f8";
                            case java.awt.event.KeyEvent.VK_F9:
                                return "f9";
                            case java.awt.event.KeyEvent.VK_F10:
                                return "f10";
                            case java.awt.event.KeyEvent.VK_F11:
                                return "f11";
                            case java.awt.event.KeyEvent.VK_F12:
                                return "f12";
                            case java.awt.event.KeyEvent.VK_CONTROL: {
                                int loc = e.getKeyLocation();
                                if (loc == java.awt.event.KeyEvent.KEY_LOCATION_LEFT)
                                    return "lcontrol";
                                if (loc == java.awt.event.KeyEvent.KEY_LOCATION_RIGHT)
                                    return "rcontrol";
                                return "control";
                            }
                            case java.awt.event.KeyEvent.VK_SHIFT: {
                                int loc = e.getKeyLocation();
                                if (loc == java.awt.event.KeyEvent.KEY_LOCATION_LEFT)
                                    return "lshift";
                                if (loc == java.awt.event.KeyEvent.KEY_LOCATION_RIGHT)
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
                window.addGlobalKeyListener(adapter);
            }
            // Unified overlay: HUD (optional) + transient reset message
            var ppu = emuRef[0].getPpu();
            // Use dynamic HUD state (reads hud variable each frame via array wrapper)
            final boolean slEnabled = Boolean.parseBoolean(System.getProperty("r2nes.scanlines.enabled", "false"));
            final float slAlpha = Float.parseFloat(System.getProperty("r2nes.scanlines.alpha", "0.5"));
            window.setOverlay(g2 -> {
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
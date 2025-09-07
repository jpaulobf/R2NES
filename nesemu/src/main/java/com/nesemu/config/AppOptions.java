package com.nesemu.config;

import java.util.Locale;
import com.nesemu.input.InputConfig;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;

/**
 * Holder for application options parsed from CLI. Defaults match Main.java.
 * INI merging still happens in Main; this object only captures CLI results.
 *
 * Notes on sources and precedence:
 * - CLI values go here (filled by CLIOptionsParser).
 * - INI values are merged later in Main (and may override when CLI did not
 * specify).
 * - Some fields are INI-only and remain null/default after CLI parsing.
 */
public class AppOptions {

    /**
     * Enables GUI mode. CLI: --gui / --no-gui / --gui=true|false. Default: false.
     */
    public boolean gui = false;

    /**
     * True if the user specified GUI on the CLI explicitly (affects INI
     * precedence).
     */
    public boolean guiCliSpecified = false;

    /**
     * ROM path or file passed positionally on CLI (non --flag). May be overridden
     * by INI.
     */
    public String romPath = null;

    /**
     * Number of frames to simulate in headless mode. CLI: --frames=N. Default: 60.
     */
    public int frames = 60;

    /** Dump nametable info to console. CLI: --dump-nt. */
    public boolean dumpNt = false;

    /** Dump a single pattern tile index (hex). CLI: --dump-pattern=HH. */
    public Integer dumpPattern = null;

    /**
     * Dump a comma-separated list of pattern tile indices (hex). CLI:
     * --dump-patterns=HH,HH,...
     */
    public String dumpPatternsList = null;

    /** Print iNES header summary. CLI: --header. */
    public boolean showHeader = false;

    /** Enable CHR read logging (Mapper0 helper). CLI: --chr-log. */
    public boolean chrLog = false;
    
    /**
     * Tile matrix visualization mode (first|center|nonzero). CLI:
     * --tile-matrix=MODE.
     */
    public String tileMatrixMode = null;

    /**
     * Trace a fixed number of CPU instructions. CLI: --trace-cpu=N. Default: 0
     * (disabled).
     */
    public long traceInstrCount = 0;

    /** Log each NMI entry. CLI: --trace-nmi. */
    public boolean traceNmi = false;

    /** Log PPU register accesses. CLI: --log-ppu-reg. */
    public boolean logPpuReg = false;

    /** Break when PC hits this address (hex). CLI: --break-at=HHHH. */
    public Integer breakAtPc = null;

    /** Break on reads from address (hex). CLI: --break-read=ADDR[,count]. */
    public int breakReadAddr = -1;

    /** Number of reads to count before breaking. Defaults to 1. */
    public int breakReadCount = 1;

    /** Step until first vblank then run frames. CLI: --until-vblank. */
    public boolean untilVblank = false;

    /** Background sample debug count. CLI: --dbg-bg-sample=N. */
    public int dbgBgSample = 0;

    /** Log all background samples (bounded). CLI: --dbg-bg-all. */
    public boolean dbgBgAll = false;

    /** Initialize scroll/VRAM address at startup. CLI: --init-scroll. */
    public boolean initScroll = false;

    /** Use simple PPU timing mode flag (legacy). CLI: --timing-simple. */
    public boolean timingSimple = false;

    /** Attribute table write log limit. CLI: --log-attr[=N]. */
    public int logAttrLimit = 0;

    /** Nametable write log limit. CLI: --log-nt[=N]. */
    public int logNtLimit = 0;

    /**
     * Optional baseline value to filter nametable logs (hex byte). CLI:
     * --nt-baseline=HH.
     */
    public Integer ntBaseline = null;

    /** Force PPUMASK background enable (bit3). CLI: --force-bg. */
    public boolean forceBg = false;

    /** Print background column statistics. CLI: --bg-col-stats. */
    public boolean bgColStats = false;

    /** Show on-screen HUD in GUI. CLI: --hud. INI: hud=. */
    public boolean hud = false;

    /**
     * Synthetic test pattern mode: h|v|checker. CLI: --test-bands[-h|-v] |
     * --test-checker.
     */
    public String testPattern = null;

    /** Background pipeline fetch log size. CLI: --pipe-log[=N]. */
    public int pipeLogLimit = 0;

    /** Quiet mode (suppresses verbose logging). CLI: --quiet | --no-debug. */
    public boolean quiet = false;

    /** Force verbose mode. CLI: --verbose. */
    public Boolean verboseFlag = null;

    /** Log level. CLI: --log-level=TRACE|DEBUG|INFO|WARN|ERROR. */
    public String logLevelOpt = null;

    /** Enabled log categories. CLI: --log-cats=CPU,PPU,... or ALL. */
    public String logCatsOpt = null;

    /** Prepend timestamps to log lines. CLI: --log-ts. */
    public boolean logTimestamps = false;

    /** Custom reset hotkey token. CLI: --reset-key=token. INI: reset=. */
    public String resetKeyToken = null;

    /** Toggle fullscreen hotkey token. INI: toggle-fullscreen=. */
    public String toggleFullscreenKey = null;

    /** Toggle HUD hotkey token. INI: toggle-hud=. */
    public String toggleHudKey = null;

    /**
     * Cycle fullscreen proportion hotkey token. INI: toogle-fullscreen-proportion=.
     */
    public String toggleFullscreenProportionKey = null;

    /** Palette writes log limit. CLI: --log-palette[=N]. */
    public int paletteLogLimit = 0;

    /** Unlimited sprites toggle. CLI: --unlimited-sprites[=true|false]. */
    public Boolean unlimitedSprites = null;

    /** Sprite Y evaluation mode. CLI: --sprite-y=hardware|test. */
    public String spriteYMode = null;

    /** Render pacer mode. CLI: --pacer=legacy|hr. */
    public String pacerModeOpt = null;

    /** Use BufferStrategy in renderer. CLI: --buffer-strategy[=true|false]. */
    public Boolean bufferStrategyOpt = null;

    /** Initial PPUMASK override (hex). Currently INI-only. */
    public Integer initialMaskOverride = null; 
    
    /** Borderless fullscreen toggle. CLI: --borderless-fullscreen[=true|false]. */
    public Boolean borderlessFullscreen = null;

    /** Alternate save directory for .sav files. INI-only: save-path=. */
    public String savePathOverride = null; // INI only

    /** Directory for save states (*.state). INI-only: save-state-path=. */
    public String saveStatePath = null; // INI only

    /** Save state hotkey token. INI-only: save-state=. */
    public String saveStateKey = null; // INI only

    /** Load state hotkey token. INI-only: load-state=. */
    public String loadStateKey = null; // INI only

    /**
     * PPU timing mode (simple|interleaved). CLI: --timing-mode=. INI: timing-mode=.
     */
    public String timingModeOpt = null;

    /** Fast-forward hold hotkey token. INI-only: fast-foward=. */
    public String fastForwardKey = null; // INI only

    /**
     * Fast-forward hold hotkey token from CLI (overrides INI). CLI:
     * --fast-forward-key=.
     */
    public String fastForwardKeyCli = null;

    /**
     * Max FPS cap while fast-forwarding (0=unlimited). INI-only:
     * fast-foward-max-fps=.
     */
    public int fastForwardMaxFps = 0; // INI only

    /** Max FPS cap override from CLI. CLI: --fast-forward-max-fps=. */
    public Integer fastForwardMaxFpsCli = null;

    /**
     * Left column rendering mode. CLI: --left-column-mode=hardware|always|crop.
     * INI: left-column-mode=.
     */
    public String leftColumnModeOpt = null;

    /**
     * Spin-watch threshold: detects CPU spin loops (cycles). CLI: --spin-watch=.
     */
    public long spinWatchThreshold = 0;

    /** MMC1 bank change logging limit. CLI: --log-mmc1[=N]. */
    public int mmc1LogLimit = 0;

    /**
     * Number of bytes to dump when spin-watch triggers. CLI: --spin-dump-bytes=.
     */
    public int spinDumpBytes = 0;

    /** Manual warn-snapshot hotkey token. INI-only: log-warn-key=. */
    public String logWarnKey = null; // INI only
    
    /** Force sprite0 hit (debug aid). CLI: --force-sprite0-hit. */
    public boolean forceSprite0Hit = false;
    
    /**
     * Merge settings from emulator.ini into the provided options instance.
     * Only fills values that are not explicitly set via CLI (keeps current values).
     * Also supports INI-only options and ROM override if CLI didn't provide one.
     */
    public static void checkOptionsFromIni(AppOptions cli) {
        try {
            InputConfig inputCfg = ConfigUtils.loadInputConfig();
            if (inputCfg.hasOption("quiet") && !cli.quiet)
                cli.quiet = Boolean.parseBoolean(inputCfg.getOption("quiet"));
            if (cli.romPath == null && inputCfg.hasOption("ROM")) {
                String iniRom = inputCfg.getOption("ROM").trim();
                if (!iniRom.isEmpty()) {
                    cli.romPath = iniRom;
                    Log.info(ROM, "ROM definida via INI: %s", cli.romPath);
                }
            }
            if (!cli.guiCliSpecified && !cli.gui && inputCfg.hasOption("gui"))
                cli.gui = Boolean.parseBoolean(inputCfg.getOption("gui"));
            if (cli.verboseFlag == null && inputCfg.hasOption("verbose"))
                cli.verboseFlag = Boolean.parseBoolean(inputCfg.getOption("verbose"));
            if (!cli.hud && inputCfg.hasOption("hud"))
                cli.hud = Boolean.parseBoolean(inputCfg.getOption("hud"));
            if (cli.logLevelOpt == null && inputCfg.hasOption("log-level"))
                cli.logLevelOpt = inputCfg.getOption("log-level");
            if (cli.logCatsOpt == null && inputCfg.hasOption("log-cats"))
                cli.logCatsOpt = inputCfg.getOption("log-cats");
            if (!cli.logTimestamps && inputCfg.hasOption("log-ts"))
                cli.logTimestamps = Boolean.parseBoolean(inputCfg.getOption("log-ts"));
            if (cli.tileMatrixMode == null && inputCfg.hasOption("tile-matrix"))
                cli.tileMatrixMode = inputCfg.getOption("tile-matrix");
            if (!cli.chrLog && inputCfg.hasOption("chr-log"))
                cli.chrLog = Boolean.parseBoolean(inputCfg.getOption("chr-log"));
            if (!cli.dumpNt && inputCfg.hasOption("dump-nt"))
                cli.dumpNt = Boolean.parseBoolean(inputCfg.getOption("dump-nt"));
            if (cli.dumpPattern == null && inputCfg.hasOption("dump-pattern"))
                try {
                    cli.dumpPattern = Integer.parseInt(inputCfg.getOption("dump-pattern"), 16);
                } catch (Exception ignore) {
                }
            if (cli.dumpPatternsList == null && inputCfg.hasOption("dump-patterns"))
                cli.dumpPatternsList = inputCfg.getOption("dump-patterns");
            if (!cli.traceNmi && inputCfg.hasOption("trace-nmi"))
                cli.traceNmi = Boolean.parseBoolean(inputCfg.getOption("trace-nmi"));
            if (!cli.logPpuReg && inputCfg.hasOption("log-ppu-reg"))
                cli.logPpuReg = Boolean.parseBoolean(inputCfg.getOption("log-ppu-reg"));
            if (cli.breakAtPc == null && inputCfg.hasOption("break-at"))
                try {
                    cli.breakAtPc = Integer.parseInt(inputCfg.getOption("break-at"), 16);
                } catch (Exception ignore) {
                }
            if (cli.breakReadAddr < 0 && inputCfg.hasOption("break-read"))
                try {
                    var spec = inputCfg.getOption("break-read");
                    var parts = spec.split(",");
                    cli.breakReadAddr = Integer.parseInt(parts[0], 16);
                    if (parts.length > 1)
                        cli.breakReadCount = Integer.parseInt(parts[1]);
                } catch (Exception ignore) {
                }
            if (!cli.untilVblank && inputCfg.hasOption("until-vblank"))
                cli.untilVblank = Boolean.parseBoolean(inputCfg.getOption("until-vblank"));
            if (cli.dbgBgSample == 0 && inputCfg.hasOption("dbg-bg-sample"))
                try {
                    cli.dbgBgSample = Integer.parseInt(inputCfg.getOption("dbg-bg-sample"));
                } catch (Exception ignore) {
                }
            if (!cli.dbgBgAll && inputCfg.hasOption("dbg-bg-all"))
                cli.dbgBgAll = Boolean.parseBoolean(inputCfg.getOption("dbg-bg-all"));
            if (!cli.initScroll && inputCfg.hasOption("init-scroll"))
                cli.initScroll = Boolean.parseBoolean(inputCfg.getOption("init-scroll"));
            if (!cli.timingSimple && inputCfg.hasOption("timing-simple"))
                cli.timingSimple = Boolean.parseBoolean(inputCfg.getOption("timing-simple"));
            if (cli.logAttrLimit == 0 && inputCfg.hasOption("log-attr"))
                try {
                    cli.logAttrLimit = Integer.parseInt(inputCfg.getOption("log-attr"));
                } catch (Exception ignore) {
                }
            if (cli.logNtLimit == 0 && inputCfg.hasOption("log-nt"))
                try {
                    cli.logNtLimit = Integer.parseInt(inputCfg.getOption("log-nt"));
                } catch (Exception ignore) {
                }
            if (cli.ntBaseline == null && inputCfg.hasOption("nt-baseline")) {
                try {
                    cli.ntBaseline = Integer.parseInt(inputCfg.getOption("nt-baseline"), 16) & 0xFF;
                } catch (Exception ignore) {
                }
            }
            if (!cli.forceBg && inputCfg.hasOption("force-bg"))
                cli.forceBg = Boolean.parseBoolean(inputCfg.getOption("force-bg"));
            if (cli.leftColumnModeOpt == null && inputCfg.hasOption("left-column-mode"))
                cli.leftColumnModeOpt = inputCfg.getOption("left-column-mode").trim().toLowerCase(Locale.ROOT);
            if (!cli.bgColStats && inputCfg.hasOption("bg-col-stats"))
                cli.bgColStats = Boolean.parseBoolean(inputCfg.getOption("bg-col-stats"));
            if (cli.testPattern == null && inputCfg.hasOption("test-pattern"))
                cli.testPattern = inputCfg.getOption("test-pattern");
            if (cli.pipeLogLimit == 0 && inputCfg.hasOption("pipe-log"))
                try {
                    cli.pipeLogLimit = Integer.parseInt(inputCfg.getOption("pipe-log"));
                } catch (Exception ignore) {
                }
            if (cli.frames == 60 && inputCfg.hasOption("frames"))
                try {
                    cli.frames = Integer.parseInt(inputCfg.getOption("frames"));
                } catch (Exception ignore) {
                }
            if (cli.resetKeyToken == null && inputCfg.hasOption("reset")) {
                cli.resetKeyToken = inputCfg.getOption("reset");
            }
            if (inputCfg.hasOption("toggle-fullscreen")) {
                cli.toggleFullscreenKey = inputCfg.getOption("toggle-fullscreen");
            }
            if (inputCfg.hasOption("toggle-hud")) {
                cli.toggleHudKey = inputCfg.getOption("toggle-hud");
            }
            if (inputCfg.hasOption("log-warn-key")) {
                cli.logWarnKey = inputCfg.getOption("log-warn-key");
            }
            if (inputCfg.hasOption("toogle-fullscreen-proportion")) { // note: key spelled 'toogle' per INI
                cli.toggleFullscreenProportionKey = inputCfg.getOption("toogle-fullscreen-proportion");
            }
            if (cli.paletteLogLimit == 0 && inputCfg.hasOption("log-palette")) {
                try {
                    cli.paletteLogLimit = Integer.parseInt(inputCfg.getOption("log-palette"));
                } catch (Exception ignore) {
                    cli.paletteLogLimit = 256;
                }
            }
            if (cli.unlimitedSprites == null && inputCfg.hasOption("unlimited-sprites")) {
                try {
                    String v = inputCfg.getOption("unlimited-sprites").trim().toLowerCase(Locale.ROOT);
                    if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                        cli.unlimitedSprites = Boolean.TRUE;
                    else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                        cli.unlimitedSprites = Boolean.FALSE;
                } catch (Exception ignore) {
                }
            }
            if (cli.spriteYMode == null && inputCfg.hasOption("sprite-y")) {
                String v = inputCfg.getOption("sprite-y").trim().toLowerCase(Locale.ROOT);
                if (v.equals("hardware") || v.equals("test"))
                    cli.spriteYMode = v;
            }
            if (cli.timingModeOpt == null && inputCfg.hasOption("timing-mode")) {
                String v = inputCfg.getOption("timing-mode").trim().toLowerCase(Locale.ROOT);
                if (v.equals("simple") || v.equals("interleaved"))
                    cli.timingModeOpt = v;
            }
            if (cli.pacerModeOpt == null && inputCfg.hasOption("pacer")) {
                if (cli.traceInstrCount == 0 && inputCfg.hasOption("trace-cpu")) {
                    try {
                        cli.traceInstrCount = Long.parseLong(inputCfg.getOption("trace-cpu").trim());
                    } catch (Exception ignored) {
                    }
                }
                if (!cli.forceSprite0Hit && inputCfg.hasOption("force-sprite0-hit")) {
                    try {
                        cli.forceSprite0Hit = Boolean.parseBoolean(inputCfg.getOption("force-sprite0-hit").trim());
                    } catch (Exception ignored) {
                    }
                }
                if (cli.spinWatchThreshold == 0 && inputCfg.hasOption("spin-watch")) {
                    try {
                        cli.spinWatchThreshold = Long.parseLong(inputCfg.getOption("spin-watch").trim());
                    } catch (Exception ignored) {
                    }
                }
                if (cli.spinDumpBytes == 0 && inputCfg.hasOption("spin-dump-bytes")) {
                    try {
                        cli.spinDumpBytes = Integer.parseInt(inputCfg.getOption("spin-dump-bytes").trim());
                    } catch (Exception ignored) {
                    }
                }
                if (cli.mmc1LogLimit == 0 && inputCfg.hasOption("log-mmc1")) {
                    String v = inputCfg.getOption("log-mmc1").trim();
                    if (v.isEmpty())
                        cli.mmc1LogLimit = 128;
                    else {
                        try {
                            cli.mmc1LogLimit = Integer.parseInt(v);
                        } catch (Exception ignored) {
                        }
                    }
                }
                String v = inputCfg.getOption("pacer").trim().toLowerCase(Locale.ROOT);
                if (v.equals("legacy") || v.equals("hr"))
                    cli.pacerModeOpt = v;
            }
            if (cli.bufferStrategyOpt == null && inputCfg.hasOption("buffer-strategy")) {
                try {
                    String v = inputCfg.getOption("buffer-strategy").trim().toLowerCase(Locale.ROOT);
                    if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                        cli.bufferStrategyOpt = Boolean.TRUE;
                    else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                        cli.bufferStrategyOpt = Boolean.FALSE;
                } catch (Exception ignore) {
                }
            }
            if (cli.borderlessFullscreen == null && inputCfg.hasOption("borderless-fullscreen")) {
                try {
                    String v = inputCfg.getOption("borderless-fullscreen").trim().toLowerCase(Locale.ROOT);
                    if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on"))
                        cli.borderlessFullscreen = Boolean.TRUE;
                    else if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"))
                        cli.borderlessFullscreen = Boolean.FALSE;
                } catch (Exception ignore) {
                }
            }
            if (inputCfg.hasOption("save-path")) {
                try {
                    String sp = inputCfg.getOption("save-path").trim();
                    if (!sp.isEmpty())
                        cli.savePathOverride = sp;
                } catch (Exception ignore) {
                }
            }
            if (inputCfg.hasOption("save-state-path")) {
                try {
                    String sp = inputCfg.getOption("save-state-path").trim();
                    if (!sp.isEmpty())
                        cli.saveStatePath = sp;
                } catch (Exception ignore) {
                }
            }
            if (inputCfg.hasOption("save-state")) {
                cli.saveStateKey = inputCfg.getOption("save-state");
            }
            if (inputCfg.hasOption("load-state")) {
                cli.loadStateKey = inputCfg.getOption("load-state");
            }
            if (inputCfg.hasOption("fast-foward")) {
                cli.fastForwardKey = inputCfg.getOption("fast-foward");
            }
            if (inputCfg.hasOption("fast-foward-max-fps")) {
                try {
                    cli.fastForwardMaxFps = Integer.parseInt(inputCfg.getOption("fast-foward-max-fps").trim());
                } catch (Exception ignore) {
                }
            }
        } catch (Exception ex) {
            Log.warn(CONTROLLER, "Falha ao carregar configuração de input: %s", ex.getMessage());
        }
    }
}

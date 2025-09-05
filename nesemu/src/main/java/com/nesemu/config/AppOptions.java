package com.nesemu.config;

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
    public Integer initialMaskOverride = null; // not set by CLI currently
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
}

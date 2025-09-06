package com.nesemu.config;

/**
 * Runtime (post-construction) emulator/PPU options to apply to a NesEmulator
 * instance. These are options that aren't intrinsic to the ROM file itself and
 * must be re-applied when the user swaps ROMs at runtime.
 */
public class RuntimeSettings {
    String tileMatrixMode; // null or one of first|center|nonzero
    int pipeLogLimit; // >0 enables pipeline log
    int dbgBgSample; // >0 enables background sample debug
    boolean dbgBgAll; // widen sample window
    boolean timingSimple; // legacy simple timing flag
    String timingModeOpt; // null|"simple"|"interleaved"
    boolean forceBg; // force PPUMASK BG bit
    Boolean unlimitedSprites; // null to leave default
    String spriteYMode; // null|"hardware"|"test"
    boolean forceSprite0Hit; // debug aid
    String leftColumnModeOpt; // null|hardware|always|crop
    int logAttrLimit; // >0 enable attribute writes log
    int logNtLimit; // >0 enable nametable writes log
    Integer ntBaseline; // optional baseline filter
    int paletteLogLimit; // >0 enable palette writes log
    int mmc1LogLimit; // >0 enable MMC1 bank logging
    long spinWatchThreshold; // >0 enable spin watchdog
    int spinDumpBytes; // bytes to dump on spin
    Integer initialMaskOverride;// optional PPUMASK initial value

    /** All-args constructor for convenient initialization. */
    public RuntimeSettings(
            String tileMatrixMode,
            int pipeLogLimit,
            int dbgBgSample,
            boolean dbgBgAll,
            boolean timingSimple,
            String timingModeOpt,
            boolean forceBg,
            Boolean unlimitedSprites,
            String spriteYMode,
            boolean forceSprite0Hit,
            String leftColumnModeOpt,
            int logAttrLimit,
            int logNtLimit,
            Integer ntBaseline,
            int paletteLogLimit,
            int mmc1LogLimit,
            long spinWatchThreshold,
            int spinDumpBytes,
            Integer initialMaskOverride) {
        this.tileMatrixMode = tileMatrixMode;
        this.pipeLogLimit = pipeLogLimit;
        this.dbgBgSample = dbgBgSample;
        this.dbgBgAll = dbgBgAll;
        this.timingSimple = timingSimple;
        this.timingModeOpt = timingModeOpt;
        this.forceBg = forceBg;
        this.unlimitedSprites = unlimitedSprites;
        this.spriteYMode = spriteYMode;
        this.forceSprite0Hit = forceSprite0Hit;
        this.leftColumnModeOpt = leftColumnModeOpt;
        this.logAttrLimit = logAttrLimit;
        this.logNtLimit = logNtLimit;
        this.ntBaseline = ntBaseline;
        this.paletteLogLimit = paletteLogLimit;
        this.mmc1LogLimit = mmc1LogLimit;
        this.spinWatchThreshold = spinWatchThreshold;
        this.spinDumpBytes = spinDumpBytes;
        this.initialMaskOverride = initialMaskOverride;
    }
}

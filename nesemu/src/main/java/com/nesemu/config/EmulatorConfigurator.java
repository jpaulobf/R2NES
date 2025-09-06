package com.nesemu.config;

import com.nesemu.emulator.NesEmulator;
import com.nesemu.mapper.Mapper1;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;

/**
 * Applies runtime emulator/PPU options to a NesEmulator instance.
 */
public final class EmulatorConfigurator {
    private EmulatorConfigurator() {
    }

    public static void apply(NesEmulator emu, RuntimeSettings s) {
        if (emu == null || s == null)
            return;
        var ppu = emu.getPpu();
        if (s.tileMatrixMode != null) {
            ppu.setTileMatrixMode(s.tileMatrixMode);
            Log.info(PPU, "Tile matrix mode: %s", s.tileMatrixMode);
        }
        if (s.pipeLogLimit > 0) {
            if (s.tileMatrixMode == null) {
                ppu.setTileMatrixMode("center");
                Log.debug(PPU, "PIPE-LOG ajustando tileMatrixMode=center");
            }
            ppu.enablePipelineLog(s.pipeLogLimit);
            Log.info(PPU, "PIPE-LOG habilitado limite=%d", s.pipeLogLimit);
        }
        if (s.dbgBgSample > 0) {
            if (s.dbgBgAll)
                ppu.enableBackgroundSampleDebugAll(s.dbgBgSample);
            else
                ppu.enableBackgroundSampleDebug(s.dbgBgSample);
        }
        if (s.timingSimple) {
            ppu.setSimpleTiming(true);
        }
        if (s.timingModeOpt != null) {
            if (s.timingModeOpt.equals("interleaved")) {
                emu.setTimingMode(NesEmulator.TimingMode.INTERLEAVED);
            } else {
                emu.setTimingMode(NesEmulator.TimingMode.SIMPLE);
            }
            Log.info(GENERAL, "Timing mode: %s", s.timingModeOpt);
        }
        if (s.spinWatchThreshold > 0) {
            emu.enableSpinWatch(s.spinWatchThreshold);
            Log.info(GENERAL, "Spin watch ativo: threshold=%d", s.spinWatchThreshold);
            if (s.spinDumpBytes > 0) {
                emu.setSpinDumpBytes(s.spinDumpBytes);
                Log.info(GENERAL, "Spin dump bytes=%d", s.spinDumpBytes);
            }
        }
        if (s.mmc1LogLimit > 0 && emu.getMapper() instanceof Mapper1 m1) {
            m1.enableBankLogging(s.mmc1LogLimit);
            Log.info(GENERAL, "MMC1 logging ativo (limite=%d)", s.mmc1LogLimit);
        }
        if (s.forceBg) {
            ppu.setForceBackgroundEnable(true);
            Log.info(PPU, "FORCE-BG bit3 PPUMASK");
        }
        if (s.unlimitedSprites != null) {
            ppu.setUnlimitedSprites(s.unlimitedSprites);
            Log.info(PPU, "Unlimited sprites: %s", s.unlimitedSprites ? "ON" : "OFF");
        }
        if (s.spriteYMode != null) {
            boolean hw = s.spriteYMode.equals("hardware");
            ppu.setSpriteYHardware(hw);
            Log.info(PPU, "Sprite Y mode: %s", hw ? "HARDWARE(+1)" : "TEST(EXACT)");
        }
        if (s.forceSprite0Hit) {
            ppu.setForceSprite0Hit(true);
            Log.warn(PPU, "[DEBUG] Force sprite0 hit habilitado");
        }
        if (s.logAttrLimit > 0) {
            ppu.enableAttributeRuntimeLog(s.logAttrLimit);
        }
        if (s.logNtLimit > 0) {
            ppu.enableNametableRuntimeLog(s.logNtLimit, s.ntBaseline == null ? -1 : s.ntBaseline);
        }
        if (s.paletteLogLimit > 0) {
            ppu.enablePaletteWriteLog(s.paletteLogLimit);
        }
        // Initial PPUMASK write (accelerate first frames) – mirror startup behavior
        int initMask = (s.initialMaskOverride != null) ? s.initialMaskOverride : 0x08; // BG on by default
        emu.getBus().write(0x2001, initMask);
        Log.info(PPU, "PPUMASK inicial=%02X%s", initMask, (s.initialMaskOverride != null ? " (override)" : ""));
        // Left-column mode
        if (s.leftColumnModeOpt != null) {
            com.nesemu.ppu.PPU.LeftColumnMode mode = com.nesemu.ppu.PPU.LeftColumnMode.HARDWARE;
            switch (s.leftColumnModeOpt) {
                case "always" -> mode = com.nesemu.ppu.PPU.LeftColumnMode.ALWAYS;
                case "crop" -> mode = com.nesemu.ppu.PPU.LeftColumnMode.CROP;
                case "hardware" -> mode = com.nesemu.ppu.PPU.LeftColumnMode.HARDWARE;
            }
            ((com.nesemu.ppu.PPU) ppu).setLeftColumnMode(mode);
            Log.info(PPU, "Left-column-mode=%s", mode.name().toLowerCase(java.util.Locale.ROOT));
        }
    }
}

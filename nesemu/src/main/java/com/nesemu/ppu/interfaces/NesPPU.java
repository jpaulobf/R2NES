package com.nesemu.ppu.interfaces;

import com.nesemu.cpu.interfaces.NesCPU;
import com.nesemu.emulator.Clockable;
import com.nesemu.mapper.Mapper;

/**
 * Interface for the NES Picture Processing Unit (PPU).
 * This interface defines methods for resetting the PPU,
 * clocking the PPU, and handling the rendering process.
 */
public interface NesPPU extends Clockable {

    /** Link CPU so PPU can raise NMI (and future IRQ) signals. */
    void attachCPU(NesCPU cpu);

    /** Attach mapper to resolve CHR / nametable mirroring. */
    void attachMapper(Mapper mapper);

    /** Register callback fired when NMI should be asserted (entering VBlank). */
    void setNmiCallback(Runnable cb);

    /** Read from one of the 8 PPU registers (0x2000-0x2007 mirrored). */
    int readRegister(int reg);

    /** Write to one of the 8 PPU registers. */
    void writeRegister(int reg, int value);

    /** OAM DMA byte write (index 0-255). */
    void dmaOamWrite(int index, int value);

    /** Read OAM byte (debug/testing). */
    int dmaOamRead(int index);

    /** Enable internal fetch pipeline logging (up to limit entries). */
    void enablePipelineLog(int limit);

    /** Consume and clear accumulated pipeline log (returns string dump). */
    String consumePipelineLog();

    /** True if currently in vertical blanking interval. */
    boolean isInVBlank();

    /** True if current scanline renders visible pixels (0-239). */
    boolean isVisibleScanline();

    /** True if pre-render scanline (-1 / 261). */
    boolean isPreRender();

    /** True if post-render scanline (240). */
    boolean isPostRender();

    /** Enable logging of palette writes (limit entries). */
    void enablePaletteWriteLog(int limit);

    /** Get final background tile index after scrolling addressing at pixel. */
    int getBackgroundIndex(int x, int y);

    /** Background tile index ignoring override modes (raw nametable lookup). */
    int getRawBackgroundIndex(int x, int y);

    /** Snapshot copy of entire background tile index buffer (debug). */
    int[] getBackgroundIndexBufferCopy();

    /** Current t (temporary VRAM address latch). */
    int getTempAddress();

    /** Power-on reset (clears registers, VRAM address, frame counter). */
    void reset();

    /**
     * Current scanline (-1/261 pre-render, 0-239 visible, 240 post, 241-260
     * vblank).
     */
    int getScanline();

    /** Current cycle (0-340) within scanline. */
    int getCycle();

    /** Frame counter (increments after pre-render completes). */
    long getFrame();

    /** Current v (VRAM address) including coarse/fine scroll bits. */
    int getVramAddress();

    /** Fine X scroll (0-7). */
    int getFineX();

    /** Force VRAM address (test hook). */
    void setVramAddressForTest(int v);

    /** Force temporary VRAM address t (test hook). */
    void setTempAddressForTest(int t);

    /** Write nametable byte at offset (debug/manual injection). */
    void pokeNameTable(int offset, int value);

    /** Write pattern table byte (CHR RAM or injected view) for debugging. */
    void pokePattern(int addr, int value);

    /** Current 16-bit shift register (low pattern bits) value snapshot. */
    int getPatternLowShift();

    /** Current 16-bit shift register (high pattern bits) value snapshot. */
    int getPatternHighShift();

    /** Attribute low shift register snapshot. */
    int getAttributeLowShift();

    /** Attribute high shift register snapshot. */
    int getAttributeHighShift();

    /** Latched nametable tile ID fetched this pipeline step. */
    int getNtLatch();

    /** Latched attribute byte (before quadrant selection). */
    int getAtLatch();

    /**
     * Get final rendered pixel value at coordinates (already palette-resolved
     * index).
     */
    int getPixel(int x, int y);

    /** Direct reference to internal 256x240 framebuffer array (mutable). */
    int[] getFrameBufferRef();

    /** Copy of framebuffer (defensive). */
    int[] getFrameBuffer();

    /** Force write to palette RAM entry (0x00-0x1F effective). */
    void pokePalette(int addr, int value);

    /** Read palette RAM entry. */
    int readPalette(int addr);

    /**
     * Raw PPUSTATUS (with side-effects already applied when read through register
     * path).
     */
    int getStatusRegister();

    /** Raw PPUMASK value. */
    int getMaskRegister();

    /** Dump current background layer to a PPM image file. */
    void dumpBackgroundToPpm(java.nio.file.Path path);

    /** Print matrix of tile indices (debug). */
    void printTileIndexMatrix();

    /** Set special tile matrix print mode (debug option). */
    void setTileMatrixMode(String mode);

    /** Print histogram of background tile index usage. */
    void printBackgroundIndexHistogram();

    /** Print tile IDs for selected logical nametable (0-3 after mirroring). */
    void printNameTableTileIds(int logicalIndex);

    /** Dump pattern for a single tile ID (debug). */
    void dumpPatternTile(int tile);

    /** Enable sample logging for background pixel pipeline (limit entries). */
    void enableBackgroundSampleDebug(int limit);

    /** Enable exhaustive background sample logging (heavier). */
    void enableBackgroundSampleDebugAll(int limit);

    /** Toggle simplified timing mode (test shortcuts). */
    void setSimpleTiming(boolean simple);

    /** Select test pattern rendering mode (diagnostics). */
    void setTestPatternMode(String mode);

    /** Enable runtime logging of attribute fetch/evaluation. */
    void enableAttributeRuntimeLog(int limit);

    /** Enable runtime logging of nametable fetches (optional filter). */
    void enableNametableRuntimeLog(int limit, int baselineFilter);

    /** Dump first N background pixel samples (diagnostics). */
    void dumpFirstBackgroundSamples(int n);

    /** Print per-column statistics for background fetches. */
    void printBackgroundColumnStats();

    /** Force background enable override (ignores PPUMASK). */
    void setForceBackgroundEnable(boolean enable);

    /** Force sprite enable override. */
    void setForceSpriteEnable(boolean enable);

    /** Force NMI enable override (simulate PPUMASK/PPUCTRL combination). */
    void setForceNmiEnable(boolean enable);

    /** Enable logging of NMI events up to a limit. */
    void enableNmiDebugLog(int limit);

    /** Allow rendering more than 8 sprites per scanline (diagnostic). */
    void setUnlimitedSprites(boolean enable);

    /** True if unlimited sprites mode is active. */
    boolean isUnlimitedSprites();

    /** Number of sprites found on the last processed scanline. */
    int getLastSpriteCountThisLine();

    /** Snapshot of secondary OAM after sprite evaluation (size 32 bytes). */
    byte[] getSecondaryOamSnapshot();

    /** Scanline index for which secondary OAM snapshot was prepared. */
    int getSecondaryOamPreparedLine();

    /** Copy of primary OAM (256 bytes). */
    byte[] getOamCopy();

    /** Direct read of a single OAM byte. */
    int getOamByte(int index);

    /** Toggle original hardware Y coordinate semantics (vs adjusted version). */
    void setSpriteYHardware(boolean enable);

    /** True if hardware-accurate sprite Y interpretation enabled. */
    boolean isSpriteYHardware();
}

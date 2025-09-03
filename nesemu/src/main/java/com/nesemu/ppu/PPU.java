package com.nesemu.ppu;

import com.nesemu.cpu.interfaces.NesCPU;
import com.nesemu.mapper.Mapper;
import com.nesemu.mapper.Mapper.MirrorType;
import com.nesemu.ppu.interfaces.NesPPU;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;

/**
 * 2C02 PPU implementation (NTSC variant).
 * Partial implementation focused on basic background and sprite rendering
 * sufficient to run simple test ROMs and homebrew.
 * Does not implement all PPU quirks or modes (no scrolling,
 * no sprite zero hit, no sprite overflow, no
 * advanced mappers with IRQ, no DMC/PCM audio, no extended palettes,
 * no PPU1.0 quirks, etc.).
 */
public class PPU implements NesPPU {

    // Optional CPU callback for NMI (set by Bus/emulator)
    private NesCPU cpu;

    // Optional mapper reference (for CHR access + mirroring metadata)
    private Mapper mapper;

    // Registers
    private int regCTRL; // $2000
    private int regMASK; // $2001
    private int regSTATUS; // $2002
    private int oamAddr; // $2003

    // $2004 OAMDATA (not implemented yet)
    // Object Attribute Memory (64 sprites * 4 bytes)
    private final byte[] oam = new byte[256];

    // Sprite evaluation buffer: hardware draws max 8, but in extended (unlimited)
    // mode we allow drawing up to 64 for debugging/visualization. Buffer sized for
    // 64.
    private static final int HW_SPRITE_LIMIT = 8;
    private static final int EXTENDED_SPRITE_DRAW_LIMIT = 64; // extended debug: allow drawing all sprites on a scanline
    private final int[] spriteIndices = new int[EXTENDED_SPRITE_DRAW_LIMIT];
    private int spriteCountThisLine = 0;

    // Debug/feature flag: allow disabling the hardware 8-sprite-per-scanline limit
    private boolean unlimitedSprites = false;

    // Cached sprite vertical ranges (top/bottom) to avoid recomputing each scanline
    private final int[] spriteTop = new int[64];
    private final int[] spriteBottom = new int[64];
    private boolean spriteRangesDirty = true;
    private int cachedSpriteHeight = -1;

    // Sprite Y semantics flag: false = test-friendly (OAM Y is top), true =
    // hardware (OAM Y = top-1)
    private boolean spriteYHardware = false;

    // Secondary OAM simulation and prepared sprite list for next scanline
    private final byte[] secondaryOam = new byte[32]; // 8 sprites * 4 bytes
    private final int[] preparedSpriteIndices = new int[EXTENDED_SPRITE_DRAW_LIMIT];
    private int preparedSpriteCount = 0;
    private int preparedLine = -2; // which scanline the prepared list corresponds to
                                   // $2005 PPUSCROLL (x,y latch)
                                   // $2006 PPUADDR (VRAM address latch)
                                   // $2007 PPUDATA

    // VRAM address latch / toggle
    private boolean addrLatchHigh = true; // true -> next write is high byte
    private int vramAddress; // current VRAM address
    private int tempAddress; // temp (t) during address/scroll sequence
    private int fineX; // fine X scroll (0..7) from first scroll write

    // Buffered read (PPUDATA) behavior
    private int readBuffer; // internal buffer

    // Timing counters
    private int cycle; // 0..340
    private int scanline; // -1 pre-render, 0..239 visible, 240 post, 241..260 vblank
    private long frame; // frame counter

    // Single-shot NMI latch (prevent multiple nmi() calls inside same vblank entry)
    private boolean nmiFiredThisVblank = false;

    // Debug instrumentation for NMI vs VBlank timing
    private boolean debugNmiLog = false;
    private int debugNmiLogLimit = 200;
    private int debugNmiLogCount = 0;

    // Optional test hook: a callback invoked whenever an NMI would be signalled
    private Runnable nmiCallback;

    // Frame buffer storing final 32-bit ARGB color and parallel index buffer
    private final int[] frameBuffer = new int[256 * 240]; // ARGB color
    private final int[] frameIndexBuffer = new int[256 * 240]; // composite (background then sprites)
    private final int[] bgBaseIndexBuffer = new int[256 * 240]; // original background only (pre-sprite)

    // Palette subsystem
    private final Palette palette = new Palette();

    // --- Background rendering simplified ---
    // Pattern tables + nametables (2x1KB) temporary internal storage
    private final byte[] patternTables = new byte[0x2000]; // CHR ROM/RAM placeholder
    private final byte[] nameTables = new byte[0x0800]; // two nametables (no mirroring control yet)

    // Shift registers (16-bit each like real PPU)
    private int patternLowShift, patternHighShift;
    private int attributeLowShift, attributeHighShift; // replicated attribute bits

    // Latches
    private int ntLatch, atLatch, patternLowLatch, patternHighLatch;

    // Pre-render prefetch promotion state
    private boolean prefetchHadFirstTile = false;
    private int prefetchPatternLowA = 0;
    private int prefetchPatternHighA = 0;
    private int prefetchAttrLowA = 0;
    private int prefetchAttrHighA = 0;

    // --- Early register write logging (first few only to avoid spam) ---
    private static final int EARLY_WRITE_LOG_LIMIT = 40; // cap (unless LOG_EXTENDED)
    private int earlyWriteLogCount = 0;

    // Tile matrix sampling mode (default "first")
    private String tileMatrixMode = "first";

    // Força habilitar background independentemente do valor escrito em $2001
    private boolean forceBgEnable = false;

    // Força habilitar sprites independentemente do valor escrito em $2001
    private boolean forceSpriteEnable = false;

    // Força habilitar NMI mesmo se bit não setado pelo jogo
    private boolean forceNmiEnable = false;

    @Override
    public void attachCPU(NesCPU cpu) {
        this.cpu = cpu;
    }

    @Override
    public void attachMapper(Mapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void setNmiCallback(Runnable cb) {
        this.nmiCallback = cb;
    }

    @Override
    public void reset() {
        regCTRL = regMASK = 0;
        regSTATUS = 0;
        oamAddr = 0;
        addrLatchHigh = true;
        vramAddress = tempAddress = 0;
        fineX = 0;
        readBuffer = 0;
        cycle = 0;
        scanline = -1; // pre-render
        frame = 0;
        nmiFiredThisVblank = false;
        // Background fetch / rendering state
        patternLowShift = patternHighShift = 0;
        attributeLowShift = attributeHighShift = 0;
        ntLatch = atLatch = patternLowLatch = patternHighLatch = 0;
        prefetchHadFirstTile = false;
        prefetchPatternLowA = prefetchPatternHighA = 0;
        prefetchAttrLowA = prefetchAttrHighA = 0;
        // no per-scanline pre-shift flag to reset
        // firstTileReady / scanlinePixelCounter removed
        for (int i = 0; i < frameBuffer.length; i++) {
            frameBuffer[i] = 0xFF000000;
            frameIndexBuffer[i] = 0;
            bgBaseIndexBuffer[i] = 0;
        }
    }

    @Override
    public void clock() {
        // Advance one PPU cycle (3x CPU speed in real hardware, handled externally).
        cycle++;
        if (cycle > 340) {
            cycle = 0;
            scanline++;
            // new scanline (nothing to reset for left-shift pipeline)
            if (isVisibleScanline()) {
                publishPreparedSpritesForCurrentLine();
            } else if (scanline == 240) { // post-render
                spriteCountThisLine = 0;
            }
            if (scanline > 260) {
                scanline = -1; // wrap to pre-render
                // entering pre-render of next frame: reset prefetch state
                prefetchHadFirstTile = false;
                frame++;
                if (DEBUG) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("[PPU] First scanline indices: ");
                    for (int i = 0; i < 32; i++) {
                        sb.append(String.format("%02x ", frameIndexBuffer[i] & 0xFF));
                    }
                    Log.debug(PPU, sb.toString());
                }
            }
        }

        // Odd frame cycle skip (short frame): skip cycle 340 on pre-render line (-1)
        // when rendering enabled and frame is odd. We implement by skipping directly
        // from cycle 339 to new scanline 0 (dropping the would-be 340).
        if (isPreRender() && cycle == 339 && renderingEnabled() && (frame & 1) == 1) {
            cycle = 0; // start next scanline
            scanline = 0; // move to first visible scanline
            // frame counter NOT incremented here (increment happens when wrapping 260->-1)
            return;
        }

        // Sprite evaluation pipeline: prepare NEXT visible scanline at cycle 257
        if ((isVisibleScanline() || isPreRender()) && cycle == 257) {
            int target = isPreRender() ? 0 : (scanline + 1);
            if (target >= 0 && target <= 239) {
                evaluateSpritesForLine(target);
            } else {
                preparedSpriteCount = 0;
                preparedLine = target;
            }
        }

        // (No priming hack) – rely on pipeline latency.

        // Enter vblank at scanline 241, cycle 1 (NES spec; some docs cite cycle 0)
        if (scanline == 241 && cycle == 1) {
            regSTATUS |= PpuRegs.STATUS_VBLANK; // set VBlank
            nmiFiredThisVblank = false;
            if ((regCTRL & PpuRegs.CTRL_NMI_ENABLE) != 0) {
                fireNmi();
            } else {
                verboseLog("[PPU VBL NO-NMI] frame=%d scan=%d cyc=%d ctrl=%02X\n", frame, scanline, cycle,
                        regCTRL & 0xFF);
            }
        } else if (scanline == -1 && cycle == 1) {
            regSTATUS &= ~PpuRegs.STATUS_VBLANK; // clear VBlank
            regSTATUS &= 0x1F; // keep lower status bits (clears sprite hit/overflow)
            nmiFiredThisVblank = true;
        } else if (isInVBlank() && (regCTRL & PpuRegs.CTRL_NMI_ENABLE) != 0 && !nmiFiredThisVblank) {
            fireNmi();
        }

        // --- Rendering address logic (loopy v/t/x) ---
        // Only active when background or sprite rendering enabled (MASK bits 3 or 4)
        if (renderingEnabled()) {
            // Visible or pre-render scanlines only
            if (isVisibleScanline() || isPreRender()) {
                // Background pipeline per-cycle operations (simplified subset)
                // Shift at start of each visible cycle before sampling pixel
                // Fetch pipeline for this cycle
                backgroundPipeline();
                // Produce pixel using current shift register state (before shifting)
                if (isVisibleScanline() && cycle >= 1 && cycle <= 256) {
                    produceBackgroundPixel();
                    // After background pixel, overlay sprite pixel (simple priority rules)
                    if ((regMASK & 0x10) != 0) { // sprites enabled
                        overlaySpritePixel();
                    }
                    // Shift after sampling (hardware shifts once per pixel after use)
                    shiftBackgroundRegisters();
                }
                // At cycle 256 increment Y (vertical position)
                if (cycle == 256) {
                    incrementY();
                }
                // At cycle 257 copy horizontal bits from t to v
                if (cycle == 257) {
                    copyHorizontalBits();
                }
                // During pre-render line cycles 280-304 copy vertical bits from t to v
                if (isPreRender() && cycle >= 280 && cycle <= 304) {
                    copyVerticalBits();
                }
            }
        }
    }

    @Override
    public int readRegister(int reg) {
        switch (reg & 0x7) {
            case 2: { // $2002 PPUSTATUS
                int value = regSTATUS;
                // Reading status clears vblank bit and address latch
                regSTATUS &= ~0x80;
                addrLatchHigh = true;
                return value;
            }
            case 4: { // OAMDATA (stub)
                return oam[oamAddr & 0xFF] & 0xFF;
            }
            case 7: { // PPUDATA
                int addr = vramAddress & 0x3FFF;
                int value;
                if (addr >= 0x3F00 && addr < 0x4000) {
                    // Palette reads are immediate (not buffered) on real hardware.
                    value = ppuMemoryRead(addr) & 0xFF;
                } else {
                    // Buffered read behaviour: return previous buffer, then fill with current.
                    value = readBuffer;
                    readBuffer = ppuMemoryRead(addr) & 0xFF;
                }
                incrementVram();
                return value;
            }
            default:
                return 0; // unimplemented / write-only
        }
    }

    @Override
    public void writeRegister(int reg, int value) {
        value &= 0xFF;
        switch (reg & 0x7) {
            case 0: // $2000 PPUCTRL
                int prevCTRL = regCTRL;
                regCTRL = value;
                if (forceNmiEnable && (regCTRL & PpuRegs.CTRL_NMI_ENABLE) == 0) {
                    int before = regCTRL;
                    regCTRL |= PpuRegs.CTRL_NMI_ENABLE;
                    verboseLog("[PPU FORCE NMI] frame=%d scan=%d cyc=%d ctrl antes=%02X depois=%02X%n", frame,
                            scanline, cycle, before & 0xFF, regCTRL & 0xFF);
                }
                if (((prevCTRL ^ regCTRL) & PpuRegs.CTRL_NMI_ENABLE) != 0) {
                    verboseLog("[PPU CTRL NMI CHG] frame=%d scan=%d cyc=%d nmi=%d prev=%02X new=%02X%n", frame,
                            scanline, cycle,
                            (regCTRL & PpuRegs.CTRL_NMI_ENABLE) != 0 ? 1 : 0, prevCTRL & 0xFF, regCTRL & 0xFF);
                }
                tempAddress = (tempAddress & 0x73FF) | ((value & 0x03) << 10); // nametable bits into t
                // Sprite height (bit5) change invalidates cached ranges
                spriteRangesDirty = true;
                logEarlyWrite(reg, value);
                break;
            case 1: // $2001 PPUMASK
                int prevMask = regMASK;
                regMASK = value;
                boolean changedBg = ((prevMask ^ regMASK) & 0x08) != 0;
                boolean changedSpr = ((prevMask ^ regMASK) & 0x10) != 0;
                if (forceBgEnable && (regMASK & 0x08) == 0) {
                    int before = regMASK;
                    regMASK |= 0x08;
                    verboseLog("[PPU FORCE BG] frame=%d scan=%d cyc=%d mask antes=%02X depois=%02X%n", frame,
                            scanline, cycle, before & 0xFF, regMASK & 0xFF);
                }
                if (forceSpriteEnable && (regMASK & 0x10) == 0) {
                    int before = regMASK;
                    regMASK |= 0x10;
                    verboseLog("[PPU FORCE SPR] frame=%d scan=%d cyc=%d mask antes=%02X depois=%02X%n", frame,
                            scanline, cycle, before & 0xFF, regMASK & 0xFF);
                }
                if (changedBg || changedSpr) {
                    verboseLog("[PPU MASK CHG] frame=%d scan=%d cyc=%d -> BG=%d SPR=%d raw=%02X\n", frame,
                            scanline, cycle,
                            (regMASK & 0x08) != 0 ? 1 : 0, (regMASK & 0x10) != 0 ? 1 : 0, regMASK & 0xFF);
                }
                logEarlyWrite(reg, value);
                break; // removed mid-scanline priming hack
            case 2: // STATUS is read-only
                break;
            case 3: // OAMADDR
                oamAddr = value & 0xFF;
                logEarlyWrite(reg, value);
                break;
            case 4: // OAMDATA (stub)
                oam[oamAddr & 0xFF] = (byte) value;
                oamAddr = (oamAddr + 1) & 0xFF;
                spriteRangesDirty = true; // OAM changed
                preparedLine = -2; // invalidate prepared sprites
                logEarlyWrite(reg, value);
                break;
            case 5: // PPUSCROLL
                if (addrLatchHigh) {
                    fineX = value & 0x07; // coarse X goes into tempAddress bits 0-4; fine X separate
                    int coarseX = (value >> 3) & 0x1F;
                    tempAddress = (tempAddress & 0x7FE0) | coarseX;
                    addrLatchHigh = false;
                } else {
                    int coarseY = (value >> 3) & 0x1F;
                    int fineY = value & 0x07;
                    tempAddress = (tempAddress & 0x0C1F) | (coarseY << 5) | (fineY << 12);
                    addrLatchHigh = true;
                }
                logEarlyWrite(reg, value);
                break;
            case 6: // PPUADDR
                if (addrLatchHigh) {
                    tempAddress = (tempAddress & 0x00FF) | ((value & 0x3F) << 8); // high 6 bits (mask 0x3F)
                    addrLatchHigh = false;
                } else {
                    tempAddress = (tempAddress & 0x7F00) | value;
                    vramAddress = tempAddress;
                    addrLatchHigh = true;
                }
                logEarlyWrite(reg, value);
                break;
            case 7: // PPUDATA
                ppuMemoryWrite(vramAddress, value);
                incrementVram();
                logEarlyWrite(reg, value);
                break;
        }
    }

    @Override
    public void dmaOamWrite(int index, int value) {
        oam[index & 0xFF] = (byte) (value & 0xFF);
        spriteRangesDirty = true;
        preparedLine = -2;
    }

    @Override
    public int dmaOamRead(int index) {
        return oam[index & 0xFF] & 0xFF;
    }

    @Override
    public void enablePipelineLog(int limit) {
        this.pipelineLogEnabled = true;
        if (limit > 0)
            this.pipelineLogLimit = limit;
        this.pipelineLogCount = 0;
        pipelineLog.setLength(0);
        Log.info(PPU, "Pipeline log enabled limit=%d", pipelineLogLimit);
    }

    @Override
    public String consumePipelineLog() {
        String s = pipelineLog.toString();
        pipelineLog.setLength(0);
        pipelineLogCount = 0;
        return s;
    }

    @Override
    public int getScanline() {
        return scanline;
    }

    @Override
    public int getCycle() {
        return cycle;
    }

    @Override
    public boolean isInVBlank() {
        return (regSTATUS & 0x80) != 0;
    }

    @Override
    public boolean isVisibleScanline() {
        return scanline >= 0 && scanline <= 239;
    }

    @Override
    public boolean isPreRender() {
        return scanline == -1;
    }

    @Override
    public boolean isPostRender() {
        return scanline == 240;
    }

    @Override
    public long getFrame() {
        return frame;
    }

    @Override
    public void enablePaletteWriteLog(int limit) {
        this.paletteWriteLog = true;
        this.paletteWriteLogLimit = Math.max(0, limit);
        this.paletteWriteLogCount = 0;
    }

    @Override
    public int getBackgroundIndex(int x, int y) {
        if (x < 0 || x >= 256 || y < 0 || y >= 240)
            return 0;
        return frameIndexBuffer[y * 256 + x] & 0xFF;
    }

    @Override
    public int getRawBackgroundIndex(int x, int y) {
        if (x < 0 || x >= 256 || y < 0 || y >= 240)
            return 0;
        return bgBaseIndexBuffer[y * 256 + x] & 0xFF;
    }

    @Override
    public int[] getBackgroundIndexBufferCopy() {
        int[] copy = new int[frameIndexBuffer.length];
        System.arraycopy(frameIndexBuffer, 0, copy, 0, frameIndexBuffer.length);
        return copy;
    }

    @Override
    public int getVramAddress() {
        return vramAddress & 0x7FFF;
    }

    @Override
    public int getTempAddress() {
        return tempAddress & 0x7FFF;
    }

    @Override
    public int getFineX() {
        return fineX & 0x7;
    }

    @Override
    public void setVramAddressForTest(int v) {
        this.vramAddress = v & 0x7FFF;
    }

    @Override
    public void setTempAddressForTest(int t) {
        this.tempAddress = t & 0x7FFF;
    }

    @Override
    public void pokeNameTable(int offset, int value) {
        if (offset >= 0 && offset < nameTables.length)
            nameTables[offset] = (byte) value;
    }

    @Override
    public void pokePattern(int addr, int value) {
        if (addr >= 0 && addr < patternTables.length)
            patternTables[addr] = (byte) value;
    }

    @Override
    public int getPatternLowShift() {
        return patternLowShift & 0xFFFF;
    }

    @Override
    public int getPatternHighShift() {
        return patternHighShift & 0xFFFF;
    }

    @Override
    public int getAttributeLowShift() {
        return attributeLowShift & 0xFFFF;
    }

    @Override
    public int getAttributeHighShift() {
        return attributeHighShift & 0xFFFF;
    }

    @Override
    public int getNtLatch() {
        return ntLatch & 0xFF;
    }

    @Override
    public int getAtLatch() {
        return atLatch & 0xFF;
    }

    @Override
    public int getPixel(int x, int y) {
        if (x < 0 || x >= 256 || y < 0 || y >= 240)
            return 0;
        return frameIndexBuffer[y * 256 + x];
    }

    @Override
    public int[] getFrameBufferRef() { // returns ARGB buffer
        return frameBuffer;
    }

    @Override
    public int[] getFrameBuffer() {
        return frameBuffer;
    }

    @Override
    public void pokePalette(int addr, int value) {
        palette.write(addr, value);
    }

    @Override
    public int readPalette(int addr) {
        return palette.read(addr);
    }

    @Override
    public int getStatusRegister() {
        return regSTATUS & 0xFF;
    }

    @Override
    public int getMaskRegister() {
        return regMASK & 0xFF;
    }

    @Override
    public void dumpBackgroundToPpm(java.nio.file.Path path) {
        try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(path)) {
            w.write("P3\n");
            w.write("256 240\n255\n");
            for (int y = 0; y < 240; y++) {
                for (int x = 0; x < 256; x++) {
                    int v = frameIndexBuffer[y * 256 + x] & 0x0F;
                    int g = v * 17; // expand 0..15 to 0..255
                    w.write(g + " " + g + " " + g + (x == 255 ? "" : " "));
                }
                w.write("\n");
            }
        } catch (Exception e) {
            Log.error(PPU, "dumpBackgroundToPpm failed: %s", e.getMessage());
        }
    }

    @Override
    public void printTileIndexMatrix() {
        // Mode options: "first" (top-left pixel), "center" (pixel 4,4), "nonzero"
        // (first non-zero pixel in tile)
        String mode = tileMatrixMode;
        StringBuilder sb = new StringBuilder();
        for (int ty = 0; ty < 30; ty++) {
            for (int tx = 0; tx < 32; tx++) {
                int v = 0;
                if ("first".equals(mode)) {
                    v = frameIndexBuffer[(ty * 8) * 256 + tx * 8] & 0x0F;
                } else if ("center".equals(mode)) {
                    int x = tx * 8 + 4;
                    int y = ty * 8 + 4;
                    v = frameIndexBuffer[y * 256 + x] & 0x0F;
                } else if ("nonzero".equals(mode)) {
                    int baseY = ty * 8;
                    int baseX = tx * 8;
                    int found = 0;
                    outer: for (int py = 0; py < 8; py++) {
                        int rowOff = (baseY + py) * 256 + baseX;
                        for (int px = 0; px < 8; px++) {
                            int val = frameIndexBuffer[rowOff + px] & 0x0F;
                            if (val != 0) {
                                found = val;
                                break outer;
                            }
                        }
                    }
                    v = found;
                } else {
                    // fallback to first
                    v = frameIndexBuffer[(ty * 8) * 256 + tx * 8] & 0x0F;
                }
                sb.append(String.format("%X", v));
            }
            sb.append('\n');
        }
        Log.info(PPU, "TileIndexMatrix\n%s", sb.toString());
    }

    @Override
    public void setTileMatrixMode(String mode) {
        if (mode == null)
            return;
        switch (mode.toLowerCase()) {
            case "first":
            case "center":
            case "nonzero":
                tileMatrixMode = mode.toLowerCase();
                break;
            default:
                // ignore invalid, keep previous
        }
    }

    @Override
    public void printBackgroundIndexHistogram() {
        int[] counts = new int[16];
        for (int i = 0; i < frameIndexBuffer.length; i++) {
            counts[frameIndexBuffer[i] & 0x0F]++;
        }
        Log.info(PPU, "--- Background index histogram (count) ---");
        for (int i = 0; i < 16; i++) {
            int c = counts[i];
            if (c > 0) {
                verboseLog("%X: %d%n", i, c);
            }
        }
    }

    @Override
    public void printNameTableTileIds(int logicalIndex) {
        if (logicalIndex < 0 || logicalIndex > 3)
            logicalIndex = 0;
        verboseLog("--- NameTable %d tile IDs ---\n", logicalIndex);
        MirrorType mt = (mapper != null) ? mapper.getMirrorType() : MirrorType.VERTICAL;
        for (int row = 0; row < 30; row++) {
            StringBuilder sb = new StringBuilder();
            for (int col = 0; col < 32; col++) {
                int logicalAddr = 0x2000 + logicalIndex * 0x0400 + row * 32 + col; // dentro da parte de tiles
                                                                                   // (0..0x03BF)
                int nt = (logicalAddr - 0x2000) & 0x0FFF;
                int index = nt & 0x03FF; // posição dentro da tabela lógica
                int table = (nt >> 10) & 0x03; // tabela lógica 0..3
                int physical;
                switch (mt) {
                    case VERTICAL -> physical = table & 0x01;
                    case HORIZONTAL -> physical = (table >> 1);
                    case SINGLE0 -> physical = 0;
                    case SINGLE1 -> physical = 1;
                    default -> physical = table & 0x01;
                }
                int value = nameTables[(physical * 0x0400) + index] & 0xFF;
                sb.append(String.format("%02X", value));
                if (col != 31)
                    sb.append(' ');
            }
            Log.info(PPU, sb.toString());
        }
    }

    @Override
    public void dumpPatternTile(int tile) {
        tile &= 0xFF;
        // Usa bit 4 (0x10) de PPUCTRL para seleção da pattern table de BACKGROUND
        // (igual ao pipeline)
        int base = ((regCTRL & 0x10) != 0 ? 0x1000 : 0x0000) + tile * 16;
        verboseLog("--- Pattern tile %02X (base=%04X) ---\n", tile, base);
        for (int row = 0; row < 8; row++) {
            int lo = ppuMemoryRead(base + row) & 0xFF;
            int hi = ppuMemoryRead(base + row + 8) & 0xFF;
            StringBuilder bits = new StringBuilder();
            for (int bit = 7; bit >= 0; bit--) {
                int b0 = (lo >> bit) & 1;
                int b1 = (hi >> bit) & 1;
                int pix = (b1 << 1) | b0;
                bits.append(pix);
            }
            Log.info(PPU, bits.toString());
        }
    }

    @Override
    public void enableBackgroundSampleDebug(int limit) {
        this.debugBgSample = true;
        this.debugBgSampleLimit = (limit <= 0 ? 50 : limit);
        this.debugBgSampleCount = 0;
        verboseLog("[PPU] Background sample debug enabled (limit=%d)\n", this.debugBgSampleLimit);
    }

    @Override
    public void enableBackgroundSampleDebugAll(int limit) {
        this.debugBgSampleAll = true;
        this.debugBgSample = false; // prevalece modo ALL
        this.debugBgSampleLimit = (limit <= 0 ? 200 : limit);
        this.debugBgSampleCount = 0;
        verboseLog("[PPU] Background sample ALL debug enabled (limit=%d)\n", this.debugBgSampleLimit);
    }

    @Override
    public void setSimpleTiming(boolean simple) {
        // Deprecated: manter assinatura para compatibilidade de CLI, sem efeito.
        if (simple) {
            Log.info(PPU, "simpleTiming ignorado (deprecated, pipeline unificado)");
        }
    }

    @Override
    public void setTestPatternMode(String mode) {
        int prev = this.testPatternMode;
        switch (mode == null ? "" : mode.toLowerCase()) {
            case "h":
            case "hor":
            case "hbands":
            case "bands-h":
                testPatternMode = TEST_BANDS_H;
                break;
            case "v":
            case "ver":
            case "vbands":
            case "bands-v":
                testPatternMode = TEST_BANDS_V;
                break;
            case "checker":
            case "xadrez":
            case "check":
                testPatternMode = TEST_CHECKER;
                break;
            default:
                testPatternMode = TEST_NONE;
                break;
        }
        if (testPatternMode != TEST_NONE) {
            // Initialize palette entries for indices 1..5 with distinct vivid colors.
            palette.write(0x3F00, 0x00);
            int[] cols = { 0x01, 0x21, 0x11, 0x31, 0x16 }; // reused set
            for (int i = 0; i < cols.length; i++) {
                palette.write(0x3F01 + i, cols[i]);
            }
        }
        Log.info(PPU, "testPatternMode=%d (prev=%d)", testPatternMode, prev);
    }

    @Override
    public void enableAttributeRuntimeLog(int limit) {
        this.attrRuntimeLog = true;
        if (limit > 0)
            this.attrLogLimit = limit;
        this.attrLogCount = 0;
        verboseLog("[PPU] Attribute runtime logging enabled (limit=%d)\n", attrLogLimit);
    }

    @Override
    public void enableNametableRuntimeLog(int limit, int baselineFilter) {
        this.nametableRuntimeLog = true;
        if (limit > 0)
            this.nametableLogLimit = limit;
        this.nametableLogCount = 0;
        this.nametableBaselineFilter = baselineFilter;
        verboseLog("[PPU] Nametable runtime logging enabled (limit=%d, baselineFilter=%s)\n", nametableLogLimit,
                baselineFilter >= 0 ? String.format("%02X", baselineFilter) : "NONE");
    }

    @Override
    public void dumpFirstBackgroundSamples(int n) {
        if (!debugBgSample)
            return;
        if (debugBgSampleCount > 0)
            return; // já temos logs realtime
        int printed = 0;
        for (int y = 0; y < 240 && printed < n; y++) {
            for (int x = 0; x < 256 && printed < n; x++) {
                int idx = frameIndexBuffer[y * 256 + x] & 0x0F;
                if (idx != 0) {
                    verboseLog("[BG-SAMPLE-FALLBACK] frame=%d x=%d y=%d idx=%X\n", frame, x, y, idx);
                    printed++;
                }
            }
        }
        if (printed == 0) {
            Log.info(PPU, "[BG-SAMPLE-FALLBACK] Nenhum pixel não-zero encontrado neste frame.");
        }
    }

    @Override
    public void printBackgroundColumnStats() {
        int[] pixelCounts = new int[256];
        for (int y = 0; y < 240; y++) {
            int rowOff = y * 256;
            for (int x = 0; x < 256; x++) {
                if ((frameIndexBuffer[rowOff + x] & 0x0F) != 0)
                    pixelCounts[x]++;
            }
        }
        int[] tileCounts = new int[32];
        for (int t = 0; t < 32; t++) {
            int sum = 0;
            for (int x = t * 8; x < t * 8 + 8; x++)
                sum += pixelCounts[x];
            tileCounts[t] = sum; // max 8*240=1920
        }
        Log.info(PPU, "--- Background non-zero pixel counts per pixel column (only columns >0) ---");
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int x = 0; x < 256; x++) {
            if (pixelCounts[x] != 0) {
                sb.append(String.format("%d:%d ", x, pixelCounts[x]));
                shown++;
                if (shown % 16 == 0) {
                    Log.info(PPU, sb.toString());
                    sb.setLength(0);
                }
            }
        }
        if (sb.length() > 0)
            Log.info(PPU, sb.toString());
        if (shown == 0)
            Log.info(PPU, "(nenhuma coluna com pixels !=0)");
        Log.info(PPU, "--- Background non-zero pixel counts per tile column (0..31) ---");
        for (int t = 0; t < 32; t++) {
            int cnt = tileCounts[t];
            double pct = (cnt / 1920.0) * 100.0;
            verboseLog("T%02d=%4d (%.1f%%)  %s%n", t, cnt, pct, (t < 4 ? "<- esquerda 32px" : ""));
        }
    }

    @Override
    public void setForceBackgroundEnable(boolean enable) {
        this.forceBgEnable = enable;
        Log.info(PPU, "forceBgEnable=%s", enable);
    }

    @Override
    public void setForceSpriteEnable(boolean enable) {
        this.forceSpriteEnable = enable;
        Log.info(PPU, "forceSpriteEnable=%s", enable);
    }

    @Override
    public void setForceNmiEnable(boolean enable) {
        this.forceNmiEnable = enable;
        Log.info(PPU, "forceNmiEnable=%s", enable);
    }

    @Override
    public void enableNmiDebugLog(int limit) {
        this.debugNmiLog = true;
        if (limit > 0)
            this.debugNmiLogLimit = limit;
        this.debugNmiLogCount = 0;
        verboseLog("[PPU] NMI debug log enabled (limit=%d)\n", debugNmiLogLimit);
    }

    @Override
    public void setUnlimitedSprites(boolean enable) {
        this.unlimitedSprites = enable;
    }

    @Override
    public boolean isUnlimitedSprites() {
        return unlimitedSprites;
    }

    @Override
    public int getLastSpriteCountThisLine() {
        return spriteCountThisLine;
    }

    @Override
    public byte[] getSecondaryOamSnapshot() {
        byte[] copy = new byte[secondaryOam.length];
        System.arraycopy(secondaryOam, 0, copy, 0, secondaryOam.length);
        return copy;
    }

    @Override
    public int getSecondaryOamPreparedLine() {
        return preparedLine;
    }

    @Override
    public byte[] getOamCopy() {
        byte[] c = new byte[oam.length];
        System.arraycopy(oam, 0, c, 0, oam.length);
        return c;
    }

    // --- Save state helpers (formal accessors) ---
    /** Snapshot of full nametable memory (0x800 bytes after mirroring). */
    public byte[] getNameTableCopy() {
        byte[] c = new byte[nameTables.length];
        System.arraycopy(nameTables, 0, c, 0, nameTables.length);
        return c;
    }

    /** Snapshot of palette internal RAM (32 bytes expanded) */
    public byte[] getPaletteCopy() {
        return palette.copyRaw();
    }

    /** Load OAM from snapshot (length must be 256). */
    public void loadOam(byte[] data) {
        if (data != null && data.length == oam.length) {
            System.arraycopy(data, 0, oam, 0, oam.length);
            spriteRangesDirty = true;
        }
    }

    /** Load nametables from snapshot (length must be 0x800). */
    public void loadNameTable(byte[] data) {
        if (data != null && data.length == nameTables.length) {
            System.arraycopy(data, 0, nameTables, 0, nameTables.length);
        }
    }

    /** Load palette raw bytes (expects 32) */
    public void loadPalette(byte[] data) {
        if (data != null)
            palette.loadRaw(data);
    }

    /** Expose CTRL register for save-state */
    public int getCtrl() {
        return regCTRL & 0xFF;
    }

    /** Force core timing/state (used by save-state load). */
    public void forceCoreState(int mask, int status, int ctrl, int scan, int cyc, int vram, int tAddr, int fineXVal,
            int frameVal) {
        this.regMASK = mask & 0xFF;
        this.regSTATUS = status & 0xFF;
        this.regCTRL = ctrl & 0xFF;
        // Clamp incoming scanline/cycle to valid ranges to avoid negative/overflow
        // indices
        this.scanline = scan;
        this.cycle = cyc;
        if (this.scanline < -1 || this.scanline > 260) {
            this.scanline = -1; // pre-render baseline
        }
        if (this.cycle < 0 || this.cycle > 340) {
            this.cycle = 0;
        }
        this.vramAddress = vram & 0x3FFF;
        this.tempAddress = tAddr & 0x3FFF;
        this.fineX = fineXVal & 0x07;
        this.frame = frameVal & 0xFFFFFFFFL;
        this.nmiFiredThisVblank = false; // reset latch to allow NMI logic to resync
        // Invalidate cached sprite prep so evaluation restarts clean next scanline
        this.preparedLine = -2;
        this.preparedSpriteCount = 0;
        this.spriteRangesDirty = true; // force recalc in case OAM restored
    }

    /**
     * After loading a save-state captured mid-frame we lack the transient fetch
     * pipeline (shift registers / latches). To avoid visual freeze or inconsistent
     * background, normalize PPU timing to the start of a new pre-render scanline
     * and clear transient state. Keeps persistent VRAM/OAM/palette intact.
     */
    public void normalizeTimingAfterLoad() {
        this.scanline = -1; // pre-render
        this.cycle = 0;
        this.prefetchHadFirstTile = false;
        this.patternLowShift = this.patternHighShift = 0;
        this.attributeLowShift = this.attributeHighShift = 0;
        this.ntLatch = this.atLatch = this.patternLowLatch = this.patternHighLatch = 0;
        this.preparedLine = -2;
        this.preparedSpriteCount = 0;
        // Do not touch frame counter, VRAM addresses, registers, OAM, nametables,
        // palette.
    }

    // --- Extended internal state helpers (save-state v2) ---
    public boolean isAddrLatchHigh() {
        return addrLatchHigh;
    }

    public int getOamAddr() {
        return oamAddr & 0xFF;
    }

    public int getReadBuffer() {
        return readBuffer & 0xFF;
    }

    public void loadMiscInternalState(boolean latchHigh, int oamAddrVal, int readBuf) {
        this.addrLatchHigh = latchHigh;
        this.oamAddr = oamAddrVal & 0xFF;
        this.readBuffer = readBuf & 0xFF;
    }

    @Override
    public int getOamByte(int index) {
        return oam[index & 0xFF] & 0xFF;
    }

    @Override
    public void setSpriteYHardware(boolean enable) {
        if (this.spriteYHardware != enable) {
            this.spriteYHardware = enable;
            spriteRangesDirty = true; // recalc ranges
            Log.info(PPU, "spriteYHardware=%s", enable);
        }
    }

    @Override
    public boolean isSpriteYHardware() {
        return spriteYHardware;
    }

    /**
     * Internal PPU memory read abstraction.
     * Resolves CHR via mapper (if attached), applies nametable mirroring and
     * palette
     * mirroring rules. Only addresses within $0000-$3FFF are valid; higher bits are
     * mirrored by masking. Palette range ($3F00-$3FFF) is handled by Palette
     * helper.
     */
    private int ppuMemoryRead(int addr) {
        addr &= 0x3FFF;
        if (addr < 0x2000) { // pattern tables
            if (mapper != null) {
                return mapper.ppuRead(addr) & 0xFF;
            }
            return patternTables[addr] & 0xFF; // fallback (tests / bootstrap)
        } else if (addr < 0x3F00) { // nametables (0x2000-0x2FFF)
            int nt = (addr - 0x2000) & 0x0FFF;
            int index = nt & 0x03FF; // 1KB region within a logical table
            int table = (nt >> 10) & 0x03; // 0..3 logical tables before mirroring
            int physical = table; // map to 0 or 1 based on mirroring
            MirrorType mt = (mapper != null) ? mapper.getMirrorType() : MirrorType.VERTICAL; // default vertical
            switch (mt) {
                case VERTICAL -> physical = table & 0x01; // 0,1,0,1
                case HORIZONTAL -> physical = (table >> 1); // 0,0,1,1
                case SINGLE0 -> physical = 0;
                case SINGLE1 -> physical = 1;
                default -> physical = table & 0x01;
            }
            return nameTables[(physical * 0x0400) + index] & 0xFF;
        } else if (addr < 0x4000) {
            // Palette RAM $3F00-$3F1F mirrored every 32 bytes up to 0x3FFF
            return palette.read(addr);
        }
        return 0;
    }

    /**
     * Internal PPU memory write abstraction.
     * Writes to CHR go through mapper (CHR RAM) or local patternTables fallback.
     * Nametable writes honor current mirroring via mapper.getMirrorType().
     * Palette writes are forwarded to Palette helper; attribute & nametable runtime
     * logs may be emitted for diagnostics.
     */
    private void ppuMemoryWrite(int addr, int value) {
        addr &= 0x3FFF;
        value &= 0xFF;
        if (addr < 0x2000) {
            if (mapper != null) {
                mapper.ppuWrite(addr, value);
            } else {
                patternTables[addr] = (byte) value; // CHR RAM case
            }
        } else if (addr < 0x3F00) {
            int nt = (addr - 0x2000) & 0x0FFF;
            int index = nt & 0x03FF;
            int table = (nt >> 10) & 0x03; // logical
            int physical;
            MirrorType mt = (mapper != null) ? mapper.getMirrorType() : MirrorType.VERTICAL;
            switch (mt) {
                case VERTICAL -> physical = table & 0x01;
                case HORIZONTAL -> physical = (table >> 1);
                case SINGLE0 -> physical = 0;
                case SINGLE1 -> physical = 1;
                default -> physical = table & 0x01;
            }
            nameTables[(physical * 0x0400) + index] = (byte) value;
            // Attribute table logging ($23C0-$23FF etc.) after mirroring mapping
            // Reconstruct base logical address for determining attribute section
            int logicalBase = 0x2000 | nt; // before mirroring
            int logicalInTable = logicalBase & 0x03FF; // 0..0x3FF inside logically selected table
            boolean isAttr = (logicalInTable & 0x03C0) == 0x03C0;
            if (!isAttr && nametableRuntimeLog) {
                boolean pass = true;
                if (nametableBaselineFilter >= 0 && value == nametableBaselineFilter)
                    pass = false;
                if (pass && nametableLogCount < nametableLogLimit) {
                    verboseLog(
                            "[PPU NT WR] addr=%04X val=%02X frame=%d scan=%d cyc=%d table=%d phys=%d index=%03X%n",
                            logicalBase, value & 0xFF, frame, scanline, cycle, table, physical, index);
                    nametableLogCount++;
                }
            }
            if (isAttr && (LOG_ATTR || attrRuntimeLog)) {
                if (!attrRuntimeLog || attrLogCount < attrLogLimit) {
                    verboseLog("[PPU ATTR WR] addr=%04X val=%02X frame=%d scan=%d cyc=%d table=%d phys=%d%n",
                            logicalBase, value & 0xFF, frame, scanline, cycle, table, physical);
                    attrLogCount++;
                }
            }
        } else if (addr < 0x4000) {
            palette.write(addr, value);
            if (paletteWriteLog && (paletteWriteLogLimit == 0 || paletteWriteLogCount < paletteWriteLogLimit)) {
                verboseLog("[PPU PAL WR] addr=%04X val=%02X frame=%d scan=%d cyc=%d%n", addr, value & 0xFF, frame,
                        scanline, cycle);
                paletteWriteLogCount++;
            }
        }
    }

    /**
     * Build sprite list for a target scanline (next visible line or fallback if
     * late).
     * Implements simplified secondary OAM evaluation: determines which sprites
     * overlap
     * the line, copies first 8 (or all if unlimitedSprites) into secondaryOam and
     * records indices for rendering. Also computes sprite overflow flag when ninth
     * sprite is found (unless unlimited mode).
     */
    private void evaluateSpritesForLine(int targetLine) {
        int spriteHeight = ((regCTRL & PpuRegs.CTRL_SPR_SIZE_8x16) != 0) ? 16 : 8;
        if (spriteRangesDirty || spriteHeight != cachedSpriteHeight) {
            for (int i = 0; i < 64; i++) {
                int yRaw = oam[i << 2] & 0xFF;
                // Real hardware: OAM Y holds (top - 1). Sentinel values >= 0xF0 hide the
                // sprite.
                // Importante: NÃO fazer wrap com &0xFF após +1; se yRaw==0xFF virar 0 causa
                // sprite fantasma na linha 0.
                int top = spriteYHardware ? (yRaw + 1) : yRaw; // sem wrap
                if (top >= 256) { // yRaw==0xFF => top=256 => oculto
                    spriteTop[i] = 512; // fora de alcance
                    spriteBottom[i] = 511;
                    continue;
                }
                int bottom = top + spriteHeight - 1;
                spriteTop[i] = top;
                spriteBottom[i] = bottom;
            }
            spriteRangesDirty = false;
            cachedSpriteHeight = spriteHeight;
        }
        for (int i = 0; i < 32; i++)
            secondaryOam[i] = (byte) 0xFF;
        int found = 0;
        boolean overflow = false;
        int capacity = unlimitedSprites ? EXTENDED_SPRITE_DRAW_LIMIT : HW_SPRITE_LIMIT;
        for (int i = 0; i < 64; i++) {
            int top = spriteTop[i];
            int bottom = spriteBottom[i];
            if (targetLine >= top && targetLine <= bottom) {
                if (found < capacity) {
                    preparedSpriteIndices[found] = i;
                }
                if (found < HW_SPRITE_LIMIT) {
                    int base = i << 2;
                    int sec = found << 2;
                    secondaryOam[sec] = oam[base];
                    secondaryOam[sec + 1] = oam[base + 1];
                    secondaryOam[sec + 2] = oam[base + 2];
                    secondaryOam[sec + 3] = oam[base + 3];
                }
                found++;
                if (found > HW_SPRITE_LIMIT && !overflow) {
                    overflow = true; // 9th sprite encountered
                    if (!unlimitedSprites) {
                        break; // stop only in hardware mode
                    }
                }
            }
        }
        if (overflow) {
            regSTATUS |= PpuRegs.STATUS_SPR_OVERFLOW;
        }
        preparedSpriteCount = Math.min(found, capacity);
        preparedLine = targetLine;
    }

    /**
     * Promote previously prepared sprite list (from evaluateSpritesForLine) to the
     * active list for the current scanline. If preparation is missing or stale,
     * falls back to a late evaluation to avoid blanking sprites.
     */
    private void publishPreparedSpritesForCurrentLine() {
        if (preparedLine != scanline) {
            evaluateSpritesForLine(scanline); // fallback
        }
        spriteCountThisLine = preparedSpriteCount;
        for (int i = 0; i < spriteCountThisLine; i++) {
            spriteIndices[i] = preparedSpriteIndices[i];
        }
    }

    /**
     * Per-pixel sprite overlay logic executed after background pixel is produced.
     * Iterates prepared sprites in front-to-back order (OAM priority) applying
     * horizontal/vertical flips, fetches pattern row bytes, resolves palette index,
     * handles transparency and priority bits, sets sprite zero hit flag, and
     * updates
     * framebuffer only if sprite pixel is opaque and has priority over background.
     */
    private void overlaySpritePixel() {
        int xPixel = cycle - 1;
        int sl = scanline;
        // Defensive guard: after a save-state load the clock position may point to
        // pre-render or out-of-range cycle; ensure we don't index negative.
        if (sl < 0 || sl >= 240) {
            return;
        }
        if (xPixel < 0 || xPixel >= 256) {
            return;
        }
        int spriteHeight = ((regCTRL & PpuRegs.CTRL_SPR_SIZE_8x16) != 0) ? 16 : 8;
        if (xPixel < 8 && (regMASK & PpuRegs.MASK_SPR_LEFT) == 0)
            return;
        int bgOriginal = bgBaseIndexBuffer[sl * 256 + xPixel] & 0x0F;
        int maxDraw = unlimitedSprites ? EXTENDED_SPRITE_DRAW_LIMIT : HW_SPRITE_LIMIT;
        int drawCount = Math.min(spriteCountThisLine, Math.min(maxDraw, spriteIndices.length));
        for (int si = 0; si < drawCount; si++) {
            int spriteIndex = spriteIndices[si];
            int base = spriteIndex * 4;
            int y = oam[base] & 0xFF;
            int tile = oam[base + 1] & 0xFF;
            int attr = oam[base + 2] & 0xFF;
            int x = oam[base + 3] & 0xFF;
            if (xPixel < x || xPixel >= x + 8)
                continue;
            int spriteTopY = spriteYHardware ? (y + 1) : y; // sem wrap
            if (spriteTopY >= 256)
                continue; // y==0xFF sentinel -> oculto
            int rowInSprite = sl - spriteTopY;
            if (rowInSprite < 0 || rowInSprite >= spriteHeight)
                continue;
            boolean flipV = (attr & 0x80) != 0;
            boolean flipH = (attr & 0x40) != 0;
            int row = flipV ? (spriteHeight - 1 - rowInSprite) : rowInSprite;
            int addrLo, addrHi;
            if (spriteHeight == 16) {
                int tableSelect = tile & 0x01;
                int baseTileIndex = tile & 0xFE;
                int half = row / 8;
                int tileRow = row & 0x7;
                int actualTile = baseTileIndex + half;
                int patternTableBase = tableSelect * 0x1000;
                addrLo = patternTableBase + actualTile * 16 + tileRow;
                addrHi = addrLo + 8;
            } else {
                int patternTableBase = ((regCTRL & PpuRegs.CTRL_SPR_TABLE) != 0 ? 0x1000 : 0x0000);
                int tileRow = row & 0x7;
                addrLo = patternTableBase + tile * 16 + tileRow;
                addrHi = addrLo + 8;
            }
            int lo = ppuMemoryRead(addrLo) & 0xFF;
            int hi = ppuMemoryRead(addrHi) & 0xFF;
            int colInSprite = xPixel - x;
            int bit = flipH ? colInSprite : (7 - colInSprite);
            int p0 = (lo >> bit) & 1;
            int p1 = (hi >> bit) & 1;
            int pattern = (p1 << 1) | p0;
            if (pattern == 0)
                continue;
            int paletteGroup = attr & 0x03;
            int paletteIndex = palette.read(0x3F10 + paletteGroup * 4 + pattern);
            boolean bgTransparent = bgOriginal == 0;
            boolean spritePriorityFront = (attr & 0x20) == 0;
            if (spriteIndex == 0 && pattern != 0 && bgOriginal != 0) {
                boolean allow = true;
                if (xPixel < 8) {
                    if ((regMASK & PpuRegs.MASK_BG_LEFT) == 0 || (regMASK & PpuRegs.MASK_SPR_LEFT) == 0)
                        allow = false;
                }
                if (allow)
                    regSTATUS |= PpuRegs.STATUS_SPR0_HIT;
            }
            if (spritePriorityFront || bgTransparent) {
                frameIndexBuffer[sl * 256 + xPixel] = paletteIndex & 0x0F;
                frameBuffer[sl * 256 + xPixel] = palette.getArgb(paletteIndex, regMASK);
            }
            break;
        }
    }

    /**
     * Issue NMI to CPU (if connected) once per vblank entry when enabled.
     * Also invokes optional external callback for testing or UI hooks. Guards
     * against multiple firings within same vblank interval via nmiFiredThisVblank.
     */
    private void fireNmi() {
        nmiFiredThisVblank = true;
        if (cpu != null) {
            if (debugNmiLog && debugNmiLogCount < debugNmiLogLimit) {
                Log.debug(PPU, "[PPU NMI->CPU] frame=%d scan=%d cyc=%d", frame, scanline, cycle);
                debugNmiLogCount++;
            }
            cpu.nmi();
        }
        if (nmiCallback != null)
            nmiCallback.run();
    }

    /**
     * Conditional register write logger for the first few early writes (or
     * unlimited
     * if LOG_EXTENDED). Aids debugging initialisation sequences without flooding
     * logs.
     */
    private void logEarlyWrite(int reg, int val) {
        if (LOG_EXTENDED || earlyWriteLogCount < EARLY_WRITE_LOG_LIMIT) {
            verboseLog("[PPU WR %02X] val=%02X frame=%d scan=%d cyc=%d v=%04X t=%04X fineX=%d%n",
                    0x2000 + (reg & 0x7), val & 0xFF, frame, scanline, cycle, vramAddress & 0x7FFF,
                    tempAddress & 0x7FFF, fineX);
            earlyWriteLogCount++;
        }
    }

    /**
     * Increment VRAM address by 1 or 32 after PPUDATA access depending on control
     * register increment bit (affects horizontal vs vertical scroll increments).
     */
    private void incrementVram() {
        int increment = ((regCTRL & PpuRegs.CTRL_VRAM_INC_32) != 0) ? 32 : 1;
        vramAddress = (vramAddress + increment) & 0x7FFF; // 15-bit
    }

    /**
     * Check if either background or sprite rendering enable bits are set. Used to
     * gate scrolling address updates and pipeline execution.
     */
    private boolean renderingEnabled() {
        return (regMASK & (PpuRegs.MASK_BG_ENABLE | PpuRegs.MASK_SPR_ENABLE)) != 0;
    }

    /**
     * Increment coarse X scroll component within VRAM address, toggling horizontal
     * nametable bit when wrapping from 31 to 0 per NES scrolling rules.
     */
    private void incrementCoarseX() {
        if ((vramAddress & 0x001F) == 31) { // coarse X == 31
            vramAddress &= ~0x001F; // coarse X = 0
            vramAddress ^= 0x0400; // switch horizontal nametable
        } else {
            vramAddress++;
        }
    }

    /**
     * Increment vertical scroll portion (fine Y then coarse Y) with correct
     * wrapping
     * and vertical nametable toggling as defined by loopy's algorithm.
     */
    private void incrementY() {
        int fineY = (vramAddress >> 12) & 0x7;
        if (fineY < 7) {
            fineY++;
            vramAddress = (vramAddress & 0x8FFF) | (fineY << 12);
        } else {
            fineY = 0;
            vramAddress &= 0x8FFF; // clear fine Y
            int coarseY = (vramAddress >> 5) & 0x1F;
            if (coarseY == 29) {
                coarseY = 0;
                vramAddress ^= 0x0800; // switch vertical nametable
            } else if (coarseY == 31) {
                coarseY = 0; // 30 & 31 wrap without toggling
            } else {
                coarseY++;
            }
            vramAddress = (vramAddress & ~0x03E0) | (coarseY << 5);
        }
    }

    /**
     * Copy horizontal scroll components (coarse X and horizontal nametable bit)
     * from
     * temp address 't' into current VRAM address 'v' at cycle 257 of each scanline.
     */
    private void copyHorizontalBits() {
        // Copy coarse X (bits 0-4) and horizontal nametable (bit 10) from t to v
        vramAddress = (vramAddress & ~0x041F) | (tempAddress & 0x041F);
    }

    /**
     * Copy vertical scroll components (fine Y, coarse Y, vertical nametable bit)
     * from
     * temp address 't' into 'v' during pre-render line cycles 280-304.
     */
    private void copyVerticalBits() {
        // Copy fine Y (12-14), coarse Y (5-9) and vertical nametable (bit 11)
        vramAddress = (vramAddress & ~0x7BE0) | (tempAddress & 0x7BE0);
    }

    /**
     * Execute one step of the background fetch/decode pipeline for the current
     * cycle: fetch nametable, attribute, low/high pattern bytes, then reload shift
     * registers every 8 cycles. Incorporates pre-render prefetch promotion so first
     * visible pixel has two tiles primed. Logged optionally for diagnostics.
     */
    private void backgroundPipeline() {
        // Only execute during visible cycles 1-256 or prefetch cycles 321-336 on
        // visible/pre-render lines
        boolean fetchRegion = (isVisibleScanline() && cycle >= 1 && cycle <= 256)
                || (cycle >= 321 && cycle <= 336 && isVisibleScanline())
                || (isPreRender() && ((cycle >= 321 && cycle <= 336) || (cycle >= 1 && cycle <= 256)));
        if (!fetchRegion)
            return;
        int phase = cycle & 0x7; // 8-cycle tile fetch phase (1,3,5,7 fetch; 0 reload)
        if (pipelineLogEnabled && pipelineLogCount < pipelineLogLimit && isVisibleScanline() && cycle <= 256) {
            // Log skeleton before action (phase + v + coarseX/Y)
            int v = vramAddress;
            int coarseX = v & 0x1F;
            int coarseY = (v >> 5) & 0x1F;
            pipelineLog.append(String.format("F frame=%d sl=%d cyc=%d ph=%d coarseX=%02d coarseY=%02d v=%04X\n",
                    frame, scanline, cycle, phase, coarseX, coarseY, v & 0x7FFF));
            pipelineLogCount++;
        }
        switch (phase) {
            case 1: // Fetch nametable byte
                ntLatch = ppuMemoryRead(0x2000 | (vramAddress & 0x0FFF));
                if (pipelineLogEnabled && pipelineLogCount < pipelineLogLimit && isVisibleScanline() && cycle <= 256) {
                    pipelineLog.append(String.format("  NT nt=%02X v=%04X\n", ntLatch & 0xFF, vramAddress & 0x7FFF));
                    pipelineLogCount++;
                }
                break;
            case 3: { // Fetch attribute byte
                int v = vramAddress;
                int coarseX = v & 0x1F;
                int coarseY = (v >> 5) & 0x1F;
                int attributeAddr = 0x23C0 | (v & 0x0C00) | ((coarseY >> 2) << 3) | (coarseX >> 2);
                atLatch = ppuMemoryRead(attributeAddr);
                if (pipelineLogEnabled && pipelineLogCount < pipelineLogLimit && isVisibleScanline() && cycle <= 256) {
                    pipelineLog.append(String.format("  AT at=%02X addr=%04X\n", atLatch & 0xFF, attributeAddr));
                    pipelineLogCount++;
                }
                break;
            }
            case 5: {
                int fineY = (vramAddress >> 12) & 0x07;
                int base = ((regCTRL & PpuRegs.CTRL_BG_TABLE) != 0 ? 0x1000 : 0x0000) + (ntLatch * 16) + fineY;
                patternLowLatch = ppuMemoryRead(base);
                break;
            }
            case 7: {
                int fineY = (vramAddress >> 12) & 0x07;
                int base = ((regCTRL & PpuRegs.CTRL_BG_TABLE) != 0 ? 0x1000 : 0x0000) + (ntLatch * 16) + fineY + 8;
                patternHighLatch = ppuMemoryRead(base);
                break;
            }
            case 0: // Reload immediately (canonical pipeline: insert into low 8 bits)
                loadShiftRegisters();
                // Pre-render prefetch promotion: during scanline -1 cycles 321-336 we load two
                // tiles.
                // First reload (tile A): capture its bytes after load.
                // Second reload (tile B): promote A into high byte so that at pixel 0
                // high=tileA, low=tileB.
                if (isPreRender() && cycle >= 321 && cycle <= 336) {
                    if (!prefetchHadFirstTile) {
                        prefetchPatternLowA = patternLowShift & 0x00FF;
                        prefetchPatternHighA = patternHighShift & 0x00FF;
                        prefetchAttrLowA = attributeLowShift & 0x00FF;
                        prefetchAttrHighA = attributeHighShift & 0x00FF;
                        prefetchHadFirstTile = true;
                    } else {
                        patternLowShift = ((prefetchPatternLowA & 0xFF) << 8) | (patternLowShift & 0x00FF);
                        patternHighShift = ((prefetchPatternHighA & 0xFF) << 8) | (patternHighShift & 0x00FF);
                        attributeLowShift = ((prefetchAttrLowA & 0xFF) << 8) | (attributeLowShift & 0x00FF);
                        attributeHighShift = ((prefetchAttrHighA & 0xFF) << 8) | (attributeHighShift & 0x00FF);
                    }
                }
                // Reset flag when leaving pre-render: at start of visible scanline 0 cycle 0 we
                // will
                // naturally begin consuming; ensure next frame re-initializes.
                if (!isPreRender() && prefetchHadFirstTile && scanline == 0 && cycle == 0) {
                    prefetchHadFirstTile = false; // safety reset (should already be clear next frame)
                }
                // Coarse X increment occurs immediately after reload except on cycle 256
                if (cycle != 256) {
                    incrementCoarseX();
                }
                if (pipelineLogEnabled && pipelineLogCount < pipelineLogLimit && isVisibleScanline() && cycle <= 256) {
                    pipelineLog.append(String.format("  RL patLo=%04X patHi=%04X attrLo=%04X attrHi=%04X\n",
                            patternLowShift & 0xFFFF, patternHighShift & 0xFFFF,
                            attributeLowShift & 0xFFFF, attributeHighShift & 0xFFFF));
                    pipelineLogCount++;
                }
                break;
        }
    }

    /**
     * Generate background pixel for current cycle (cycle-1 -> x position): select
     * pattern/attribute bits from shift registers at tap adjusted by fineX, resolve
     * palette index, apply left-column masking and test pattern overrides, write to
     * frame buffers, and optionally log sampling details.
     */
    private void produceBackgroundPixel() {
        boolean bgEnabled = (regMASK & PpuRegs.MASK_BG_ENABLE) != 0;
        int x = cycle - 1;
        if (x < 8 && (regMASK & PpuRegs.MASK_BG_LEFT) == 0) {
            return;
        }
        if (x < 0 || x >= 256 || !isVisibleScanline())
            return;
        // Test mode: render 5 horizontal color bands ignoring normal pipeline/mask.
        // Test patterns override normal pipeline
        if (testPatternMode != TEST_NONE) {
            int paletteIndex = 0;
            switch (testPatternMode) {
                case TEST_BANDS_H: {
                    int band = scanline / 48; // 5 bands
                    if (band > 4)
                        band = 4;
                    paletteIndex = (band + 1) & 0x0F;
                    break;
                }
                case TEST_BANDS_V: {
                    int band = x / 51; // 256/5 ≈ 51
                    if (band > 4)
                        band = 4;
                    paletteIndex = (band + 1) & 0x0F;
                    break;
                }
                case TEST_CHECKER: {
                    // Alterna por tile (8x8). Usa 4 cores repetindo 1..4
                    int tileX = x / 8;
                    int tileY = scanline / 8;
                    int idx = ((tileX + tileY) & 0x03) + 1; // 1..4
                    paletteIndex = idx;
                    break;
                }
            }
            frameIndexBuffer[scanline * 256 + x] = paletteIndex;
            int colorIndex = palette.read(0x3F00 + paletteIndex);
            frameBuffer[scanline * 256 + x] = palette.getArgb(colorIndex, regMASK & ~0x01); // força color (remove
                                                                                            // grayscale bit)
            return;
        }
        if (!bgEnabled) {
            return;
        }
        if ((regMASK & PpuRegs.MASK_BG_LEFT) == 0 && x < 8) {
            frameBuffer[scanline * 256 + x] = 0;
            frameIndexBuffer[scanline * 256 + x] = 0;
            return;
        }
        // Left-shift model: pixel bits reside at (15 - fineX)
        int tap = 15 - (fineX & 7);
        int bit0 = (patternLowShift >> tap) & 0x1;
        int bit1 = (patternHighShift >> tap) & 0x1;
        int attrLow = (attributeLowShift >> tap) & 0x1;
        int attrHigh = (attributeHighShift >> tap) & 0x1;
        int pattern = (bit1 << 1) | bit0;
        int attr = (attrHigh << 1) | attrLow;
        int paletteIndex = (attr << 2) | pattern; // 0..15
        int store = (pattern == 0) ? 0 : paletteIndex; // 0 => transparent
        int pos = scanline * 256 + x;
        frameIndexBuffer[pos] = store; // initial background value (may be replaced by sprite)
        bgBaseIndexBuffer[pos] = store; // immutable background reference
        int colorIndex = palette.read(0x3F00 + ((pattern == 0) ? 0 : paletteIndex));
        frameBuffer[scanline * 256 + x] = palette.getArgb(colorIndex, regMASK);
        if ((debugBgSample || debugBgSampleAll) && debugBgSampleCount < debugBgSampleLimit) {
            // Log sample with enough context to reason about shift orientation
            verboseLog(
                    "[BG-SAMPLE] frame=%d scan=%d cyc=%d x=%d fineX=%d tap=%d patLoSh=%04X patHiSh=%04X attrLoSh=%04X attrHiSh=%04X nt=%02X at=%02X bits={%d%d attr=%d} palIdx=%X store=%X\n",
                    frame, scanline, cycle, x, fineX, 15 - (fineX & 7),
                    patternLowShift & 0xFFFF, patternHighShift & 0xFFFF,
                    attributeLowShift & 0xFFFF, attributeHighShift & 0xFFFF,
                    ntLatch & 0xFF, atLatch & 0xFF,
                    bit1, bit0, attr, paletteIndex, store);
            debugBgSampleCount++;
        }
    }

    /**
     * Transfer freshly fetched tile pattern and attribute bits into the low byte of
     * the dual 16-bit shift registers; upper bytes retain prior tile data.
     * Attribute
     * bits are expanded to 0x00 or 0xFF masks for easier per-bit extraction during
     * pixel generation.
     */
    private void loadShiftRegisters() {
        // Load freshly fetched pattern bytes into low 8 bits; keep existing high 8
        // (already shifting toward bit15)
        patternLowShift = (patternLowShift & 0xFF00) | (patternLowLatch & 0xFF);
        patternHighShift = (patternHighShift & 0xFF00) | (patternHighLatch & 0xFF);
        // Attribute quadrant selection
        int coarseX = vramAddress & 0x1F;
        int coarseY = (vramAddress >> 5) & 0x1F;
        int quadSelector = ((coarseY & 0x02) << 1) | (coarseX & 0x02); // 0,2,4,6
        int shift;
        switch (quadSelector) {
            case 0:
                shift = 0;
                break; // TL
            case 2:
                shift = 2;
                break; // TR
            case 4:
                shift = 4;
                break; // BL
            case 6:
                shift = 6;
                break; // BR
            default:
                shift = 0;
                break;
        }
        int attributeBits = (atLatch >> shift) & 0x03;
        int lowBit = attributeBits & 0x01;
        int highBit = (attributeBits >> 1) & 0x01;
        attributeLowShift = (attributeLowShift & 0xFF00) | (lowBit != 0 ? 0x00FF : 0x0000);
        attributeHighShift = (attributeHighShift & 0xFF00) | (highBit != 0 ? 0x00FF : 0x0000);
    }

    /**
     * Advance background shift registers one bit left after producing a pixel so
     * that next bit pair becomes available at correct tap for subsequent pixel.
     */
    private void shiftBackgroundRegisters() {
        patternLowShift = ((patternLowShift << 1) & 0xFFFF);
        patternHighShift = ((patternHighShift << 1) & 0xFFFF);
        attributeLowShift = ((attributeLowShift << 1) & 0xFFFF);
        attributeHighShift = ((attributeHighShift << 1) & 0xFFFF);
    }

    // ------------------- Helpers -------------------

    // Global verbose logging toggle (covers internal debug/instrumentation prints)
    private static volatile boolean verboseLogging = true;

    // Synthetic test patterns
    private static final int TEST_NONE = 0;
    private static final int TEST_BANDS_H = 1;
    private static final int TEST_BANDS_V = 2;
    private static final int TEST_CHECKER = 3;
    private int testPatternMode = TEST_NONE;

    // Debug flag (can be toggled via system property -Dnes.ppu.debug=true)
    private static final boolean DEBUG = Boolean.getBoolean("nes.ppu.debug");

    // Extended attribute writes log
    private static final boolean LOG_ATTR = Boolean.getBoolean("nes.ppu.logAttr");

    // If true, don't cap register write logs
    private static final boolean LOG_EXTENDED = Boolean.getBoolean("nes.ppu.logExtended");

    // Palette write logging
    private boolean paletteWriteLog = false;
    private int paletteWriteLogLimit = 0;
    private int paletteWriteLogCount = 0;

    // --- Pipeline diagnostics ---
    private boolean pipelineLogEnabled = false;
    private int pipelineLogLimit = 600; // enough for first few tiles
    private int pipelineLogCount = 0;
    private final StringBuilder pipelineLog = new StringBuilder();

    // --- Debug helpers for background sampling investigation ---
    private boolean debugBgSample = false;
    private boolean debugBgSampleAll = false; // log mesmo se for muitos pixels (até limite)
    private int debugBgSampleLimit = 0;
    private int debugBgSampleCount = 0;

    // simpleTiming removido: pipeline agora sempre usa mapeamento ciclo 1->x0.
    // Runtime attribute logging
    private boolean attrRuntimeLog = false;
    private int attrLogLimit = 200;
    private int attrLogCount = 0;

    // Nametable runtime logging
    private boolean nametableRuntimeLog = false;
    private int nametableLogLimit = 200;
    private int nametableLogCount = 0;
    private int nametableBaselineFilter = -1; // se >=0 filtra esse valor

    /**
     * Verbose logging helper.
     * 
     * @param fmt
     * @param args
     */
    private static void verboseLog(String fmt, Object... args) {
        if (verboseLogging)
            Log.debug(PPU, fmt, args);
    }

    public static void setVerboseLogging(boolean enable) {
        verboseLogging = enable;
    }

    public static boolean isVerboseLogging() {
        return verboseLogging;
    }
}
package com.nesemu.ppu;

import com.nesemu.cpu.CPU;
import com.nesemu.mapper.Mapper;
import com.nesemu.mapper.Mapper.MirrorType;

/**
 * Minimal 2C02 PPU skeleton: implements core registers and a basic
 * cycle/scanline counter.
 *
 * Exposed behaviour right now:
 * - write/read $2000-$2007 via Bus (Bus will call readRegister/writeRegister
 * when integrated)
 * - status ($2002) vblank bit set at scanline 241, cleared at pre-render (-1)
 * - simple VRAM address latch (PPUADDR) and increment logic (PPUDATA increments
 * by 1 or 32)
 * - internal buffer for PPUDATA read delay emulation (return buffered & fill
 * new)
 *
 * Missing (future): pattern fetch pipeline, nametable/palette storage,
 * mirroring, sprite system.
 */
public class Ppu2C02 implements PPU {

    // Optional CPU callback for NMI (set by Bus/emulator)
    private CPU cpu;
    // Optional mapper reference (for CHR access + mirroring metadata)
    private Mapper mapper;

    public void attachCPU(CPU cpu) {
        this.cpu = cpu;
    }

    public void attachMapper(Mapper mapper) {
        this.mapper = mapper;
    }

    // Registers
    private int regCTRL; // $2000
    private int regMASK; // $2001
    private int regSTATUS; // $2002
    private int oamAddr; // $2003
    // $2004 OAMDATA (not implemented yet)
    // Object Attribute Memory (64 sprites * 4 bytes)
    private final byte[] oam = new byte[256];
    // Sprite evaluation buffer (indices of up to 8 sprites on current scanline)
    private final int[] spriteIndices = new int[8];
    private int spriteCountThisLine = 0;
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
    private final int[] frameIndexBuffer = new int[256 * 240]; // raw palette index (0..15 background)

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
    // Raw background palette index per pixel (0..15) stored for tests (pattern 0 ->
    // 0)
    // Already used internally in produceBackgroundPixel; accessor added below.
    // Removed hack fields (firstTileReady, scanlinePixelCounter) in favour of
    // direct cycle-based x computation

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
        // firstTileReady / scanlinePixelCounter removed
        for (int i = 0; i < frameBuffer.length; i++) {
            frameBuffer[i] = 0xFF000000;
            frameIndexBuffer[i] = 0;
        }
    }

    @Override
    public void clock() {
        // Advance one PPU cycle (3x CPU speed in real hardware, handled externally).
        cycle++;
        if (cycle > 340) {
            cycle = 0;
            scanline++;
            if (scanline > 260) {
                scanline = -1; // wrap to pre-render
                frame++;
                if (DEBUG) {
                    // Dump first 32 background indices of scanline 0 for debugging
                    StringBuilder sb = new StringBuilder();
                    sb.append("[PPU] First scanline indices: ");
                    for (int i = 0; i < 32; i++) {
                        sb.append(String.format("%02x ", frameIndexBuffer[i] & 0xFF));
                    }
                    System.out.println(sb.toString());
                }
            }
            // New visible scanline: reset pixel counters
            // no per-scanline pixel counter reset needed now
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

        // Sprite evaluation at start of each visible scanline (very simplified)
        if (isVisibleScanline() && cycle == 0) {
            evaluateSpritesForScanline();
        }

        // (No priming hack) – rely on 8‑cycle pipeline latency.

        // Enter vblank at scanline 241, cycle 1 (NES spec; some docs cite cycle 0)
        if (scanline == 241 && cycle == 1) {
            regSTATUS |= 0x80; // set VBlank flag
            nmiFiredThisVblank = false; // allow a new NMI if enabled
            if (debugNmiLog && debugNmiLogCount < debugNmiLogLimit) {
                System.out.printf("[PPU VBLANK-SET] frame=%d scan=%d cyc=%d nmiEnable=%d\n", frame, scanline, cycle,
                        (regCTRL >> 7) & 1);
                debugNmiLogCount++;
            }
            if (DEBUG)
                System.out.printf("[PPU] VBLANK SET frame=%d scan=%d cyc=%d\n", frame, scanline, cycle);
            if ((regCTRL & 0x80) != 0) {
                fireNmi();
            }
        }
        // Pre-render line: clear vblank at cycle 1
        else if (scanline == -1 && cycle == 1) {
            regSTATUS &= ~0x80; // clear VBlank
            // Clear sprite 0 hit (bit 6) & overflow (bit 5) placeholder
            regSTATUS &= 0x1F;
            nmiFiredThisVblank = true; // block until next vblank start
            if (debugNmiLog && debugNmiLogCount < debugNmiLogLimit) {
                System.out.printf("[PPU VBLANK-CLEAR] frame=%d scan=%d cyc=%d\n", frame, scanline, cycle);
                debugNmiLogCount++;
            }
            if (DEBUG)
                System.out.printf("[PPU] VBLANK CLEAR frame=%d scan=%d cyc=%d\n", frame, scanline, cycle);
        }
        // Mid-vblank: If NMI enable toggled on after start, spec allows late NMI
        // (edge). Simplify: fire once if enabled.
        else if (isInVBlank() && (regCTRL & 0x80) != 0 && !nmiFiredThisVblank) {
            if (debugNmiLog && debugNmiLogCount < debugNmiLogLimit) {
                System.out.printf("[PPU LATE-NMI-EDGE] frame=%d scan=%d cyc=%d\n", frame, scanline, cycle);
                debugNmiLogCount++;
            }
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
                // Increment coarse X at cycles 8,16,...,256 and 328,336 (simplified: every 8
                // cycles in the fetch regions)
                if (((cycle >= 1 && cycle <= 256) || (cycle >= 321 && cycle <= 336)) && (cycle & 0x7) == 0) {
                    incrementCoarseX();
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

    private void fireNmi() {
        nmiFiredThisVblank = true;
        if (cpu != null) {
            if (debugNmiLog && debugNmiLogCount < debugNmiLogLimit) {
                System.out.printf("[PPU NMI->CPU] frame=%d scan=%d cyc=%d\n", frame, scanline, cycle);
                debugNmiLogCount++;
            }
            cpu.nmi();
        }
        if (nmiCallback != null)
            nmiCallback.run();
    }

    // --- Register access (to be wired by Bus) ---
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

    public void writeRegister(int reg, int value) {
        value &= 0xFF;
        switch (reg & 0x7) {
            case 0: // $2000 PPUCTRL
                regCTRL = value;
                tempAddress = (tempAddress & 0x73FF) | ((value & 0x03) << 10); // nametable bits into t
                logEarlyWrite(reg, value);
                break;
            case 1: // $2001 PPUMASK
                regMASK = value;
                if (forceBgEnable && (regMASK & 0x08) == 0) {
                    int before = regMASK;
                    regMASK |= 0x08;
                    System.out.printf("[PPU FORCE BG] frame=%d scan=%d cyc=%d mask antes=%02X depois=%02X%n", frame,
                            scanline, cycle, before & 0xFF, regMASK & 0xFF);
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

    // --- Early register write logging (first few only to avoid spam) ---
    private static final int EARLY_WRITE_LOG_LIMIT = 40; // cap (unless LOG_EXTENDED)
    private int earlyWriteLogCount = 0;

    private void logEarlyWrite(int reg, int val) {
        if (LOG_EXTENDED || earlyWriteLogCount < EARLY_WRITE_LOG_LIMIT) {
            System.out.printf("[PPU WR %02X] val=%02X frame=%d scan=%d cyc=%d v=%04X t=%04X fineX=%d%n",
                    0x2000 + (reg & 0x7), val & 0xFF, frame, scanline, cycle, vramAddress & 0x7FFF,
                    tempAddress & 0x7FFF, fineX);
            earlyWriteLogCount++;
        }
    }

    private void incrementVram() {
        int increment = ((regCTRL & 0x04) != 0) ? 32 : 1;
        vramAddress = (vramAddress + increment) & 0x7FFF; // 15-bit
    }

    private boolean renderingEnabled() {
        return (regMASK & 0x18) != 0; // background or sprites
    }

    // --- Loopy address helpers ---
    private void incrementCoarseX() {
        if ((vramAddress & 0x001F) == 31) { // coarse X == 31
            vramAddress &= ~0x001F; // coarse X = 0
            vramAddress ^= 0x0400; // switch horizontal nametable
        } else {
            vramAddress++;
        }
    }

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

    private void copyHorizontalBits() {
        // Copy coarse X (bits 0-4) and horizontal nametable (bit 10) from t to v
        vramAddress = (vramAddress & ~0x041F) | (tempAddress & 0x041F);
    }

    private void copyVerticalBits() {
        // Copy fine Y (12-14), coarse Y (5-9) and vertical nametable (bit 11)
        vramAddress = (vramAddress & ~0x7BE0) | (tempAddress & 0x7BE0);
    }

    private void backgroundPipeline() {
        // Only execute during visible cycles 1-256 or prefetch cycles 321-336 on
        // visible/pre-render lines
        boolean fetchRegion = (isVisibleScanline() && cycle >= 1 && cycle <= 256)
                || (cycle >= 321 && cycle <= 336 && isVisibleScanline())
                || (isPreRender() && ((cycle >= 321 && cycle <= 336) || (cycle >= 1 && cycle <= 256)));
        if (!fetchRegion)
            return;
        int phase = cycle & 0x7; // 8-cycle tile fetch phase (1,3,5,7 fetch; 0 reload)
        switch (phase) {
            case 1: // Fetch nametable byte
                ntLatch = ppuMemoryRead(0x2000 | (vramAddress & 0x0FFF));
                break;
            case 3: { // Fetch attribute byte
                int v = vramAddress;
                int coarseX = v & 0x1F;
                int coarseY = (v >> 5) & 0x1F;
                int attributeAddr = 0x23C0 | (v & 0x0C00) | ((coarseY >> 2) << 3) | (coarseX >> 2);
                atLatch = ppuMemoryRead(attributeAddr);
                break;
            }
            case 5: { // Pattern low
                int fineY = (vramAddress >> 12) & 0x07;
                // PPUCTRL bit 3 (0x08) selects background pattern table (0: $0000, 1: $1000)
                int base = ((regCTRL & 0x08) != 0 ? 0x1000 : 0x0000) + (ntLatch * 16) + fineY;
                patternLowLatch = ppuMemoryRead(base);
                break;
            }
            case 7: { // Pattern high
                int fineY = (vramAddress >> 12) & 0x07;
                // Use same (corrected) background pattern table selection (bit 3)
                int base = ((regCTRL & 0x08) != 0 ? 0x1000 : 0x0000) + (ntLatch * 16) + fineY + 8;
                patternHighLatch = ppuMemoryRead(base);
                break;
            }
            case 0: // Reload: insert new tile bytes into low 8 bits (faithful orientation)
                loadShiftRegisters();
                break;
        }
    }

    private void produceBackgroundPixel() {
        boolean bgEnabled = (regMASK & 0x08) != 0;
        // Cycle->pixel mapping with 8-cycle fetch latency: first visible pixel (x=0) at
        // cycle 9 no modo pipeline completo; em modo simples usamos cycle 1
        int x = simpleTiming ? (cycle - 1) : (cycle - 9);
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
            if (debugBgSampleAll && debugBgSampleCount < debugBgSampleLimit) {
                System.out.printf("[BG-DISABLED] frame=%d scan=%d cyc=%d x=%d regMASK=%02X\n", frame, scanline, cycle,
                        x,
                        regMASK & 0xFF);
                debugBgSampleCount++;
            }
            return;
        }
        if ((regMASK & 0x02) == 0 && x < 8) { // left column clip
            frameBuffer[scanline * 256 + x] = 0;
            frameIndexBuffer[scanline * 256 + x] = 0;
            return;
        }
        int tap = 15 - (fineX & 0x7);
        int bit0 = (patternLowShift >> tap) & 0x1;
        int bit1 = (patternHighShift >> tap) & 0x1;
        int attrLow = (attributeLowShift >> tap) & 0x1;
        int attrHigh = (attributeHighShift >> tap) & 0x1;
        int pattern = (bit1 << 1) | bit0;
        int attr = (attrHigh << 1) | attrLow;
        int paletteIndex = (attr << 2) | pattern; // 0..15
        int store = (pattern == 0) ? 0 : paletteIndex; // universal bg for transparent pattern
        frameIndexBuffer[scanline * 256 + x] = store;
        int colorIndex = palette.read(0x3F00 + ((pattern == 0) ? 0 : paletteIndex));
        frameBuffer[scanline * 256 + x] = palette.getArgb(colorIndex, regMASK);
        if ((debugBgSample || debugBgSampleAll) && debugBgSampleCount < debugBgSampleLimit) {
            // Log sample with enough context to reason about shift orientation
            System.out.printf(
                    "[BG-SAMPLE] frame=%d scan=%d cyc=%d x=%d fineX=%d tap=%d patLoSh=%04X patHiSh=%04X attrLoSh=%04X attrHiSh=%04X nt=%02X at=%02X bits={%d%d attr=%d} palIdx=%X store=%X\n",
                    frame, scanline, cycle, x, fineX, 15 - (fineX & 7),
                    patternLowShift & 0xFFFF, patternHighShift & 0xFFFF,
                    attributeLowShift & 0xFFFF, attributeHighShift & 0xFFFF,
                    ntLatch & 0xFF, atLatch & 0xFF,
                    bit1, bit0, attr, paletteIndex, store);
            debugBgSampleCount++;
        }
    }

    private void loadShiftRegisters() {
        int pl = patternLowLatch & 0xFF;
        int ph = patternHighLatch & 0xFF;
        // Insert new tile bytes into LOW 8 bits; existing bits keep shifting toward
        // bit15
        patternLowShift = (patternLowShift & 0xFF00) | pl;
        patternHighShift = (patternHighShift & 0xFF00) | ph;
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
        // Replicate attribute bits into low byte only (8-bit pattern) like hardware;
        // previous high byte continues shifting out while new tile occupies low bits.
        attributeLowShift = (attributeLowShift & 0xFF00) | (lowBit != 0 ? 0x00FF : 0x0000);
        attributeHighShift = (attributeHighShift & 0xFF00) | (highBit != 0 ? 0x00FF : 0x0000);
        // No pre-shift; fineX handled at sampling time.
    }

    private void shiftBackgroundRegisters() {
        patternLowShift = (patternLowShift << 1) & 0xFFFF;
        patternHighShift = (patternHighShift << 1) & 0xFFFF;
        attributeLowShift = (attributeLowShift << 1) & 0xFFFF;
        attributeHighShift = (attributeHighShift << 1) & 0xFFFF;
    }

    // (No priming helper in accurate pipeline mode)

    // priming hack removed – rely on pre-render line (if enabled early) or natural
    // 8-cycle pipeline delay

    // Placeholder memory space for pattern/nametables/palette until Bus integration
    // fleshed out.
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
            if (mt == MirrorType.VERTICAL) {
                physical = table & 0x01; // 0,1,0,1
            } else { // HORIZONTAL
                physical = (table >> 1); // 0,0,1,1
            }
            return nameTables[(physical * 0x0400) + index] & 0xFF;
        } else if (addr < 0x4000) {
            // Palette RAM $3F00-$3F1F mirrored every 32 bytes up to 0x3FFF
            return palette.read(addr);
        }
        return 0;
    }

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
            if (mt == MirrorType.VERTICAL) {
                physical = table & 0x01; // 0,1,0,1
            } else {
                physical = (table >> 1); // 0,0,1,1
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
                    System.out.printf(
                            "[PPU NT WR] addr=%04X val=%02X frame=%d scan=%d cyc=%d table=%d phys=%d index=%03X%n",
                            logicalBase, value & 0xFF, frame, scanline, cycle, table, physical, index);
                    nametableLogCount++;
                }
            }
            if (isAttr && (LOG_ATTR || attrRuntimeLog)) {
                if (!attrRuntimeLog || attrLogCount < attrLogLimit) {
                    System.out.printf("[PPU ATTR WR] addr=%04X val=%02X frame=%d scan=%d cyc=%d table=%d phys=%d%n",
                            logicalBase, value & 0xFF, frame, scanline, cycle, table, physical);
                    attrLogCount++;
                }
            }
        } else if (addr < 0x4000) {
            palette.write(addr, value);
        }
    }

    // Accessors for future tests/debug
    public int getScanline() {
        return scanline;
    }

    public int getCycle() {
        return cycle;
    }

    public boolean isInVBlank() {
        return (regSTATUS & 0x80) != 0;
    }

    public boolean isVisibleScanline() {
        return scanline >= 0 && scanline <= 239;
    }

    public boolean isPreRender() {
        return scanline == -1;
    }

    public boolean isPostRender() {
        return scanline == 240;
    }

    public long getFrame() {
        return frame;
    }

    // --- Testing / debug accessors ---
    public int getBackgroundIndex(int x, int y) {
        if (x < 0 || x >= 256 || y < 0 || y >= 240)
            return 0;
        return frameIndexBuffer[y * 256 + x] & 0xFF;
    }

    public int[] getBackgroundIndexBufferCopy() {
        int[] copy = new int[frameIndexBuffer.length];
        System.arraycopy(frameIndexBuffer, 0, copy, 0, frameIndexBuffer.length);
        return copy;
    }

    // Testing accessors (safe read-only)
    public int getVramAddress() {
        return vramAddress & 0x7FFF;
    }

    public int getTempAddress() {
        return tempAddress & 0x7FFF;
    }

    public int getFineX() {
        return fineX & 0x7;
    }

    // TEST HELPERS (package-private)
    void setVramAddressForTest(int v) {
        this.vramAddress = v & 0x7FFF;
    }

    void setTempAddressForTest(int t) {
        this.tempAddress = t & 0x7FFF;
    }

    // Background test helpers
    void pokeNameTable(int offset, int value) {
        if (offset >= 0 && offset < nameTables.length)
            nameTables[offset] = (byte) value;
    }

    void pokePattern(int addr, int value) {
        if (addr >= 0 && addr < patternTables.length)
            patternTables[addr] = (byte) value;
    }

    int getPatternLowShift() {
        return patternLowShift & 0xFFFF;
    }

    int getPatternHighShift() {
        return patternHighShift & 0xFFFF;
    }

    int getAttributeLowShift() {
        return attributeLowShift & 0xFFFF;
    }

    int getAttributeHighShift() {
        return attributeHighShift & 0xFFFF;
    }

    int getNtLatch() {
        return ntLatch & 0xFF;
    }

    int getAtLatch() {
        return atLatch & 0xFF;
    }

    int getPixel(int x, int y) {
        if (x < 0 || x >= 256 || y < 0 || y >= 240)
            return 0;
        return frameIndexBuffer[y * 256 + x];
    }

    int[] getFrameBufferRef() { // returns ARGB buffer
        return frameBuffer;
    }

    /** Public accessor for rendering layer (returns direct reference). */
    public int[] getFrameBuffer() {
        return frameBuffer;
    }

    // loadChr removed: pattern fetches now always routed via mapper when attached.

    // TEST HELPERS for palette
    void pokePalette(int addr, int value) {
        palette.write(addr, value);
    }

    int readPalette(int addr) {
        return palette.read(addr);
    }

    // Raw status for deeper debug if needed
    public int getStatusRegister() {
        return regSTATUS & 0xFF;
    }

    public int getMaskRegister() {
        return regMASK & 0xFF;
    }

    // --- Debug / inspection helpers ---
    /**
     * Dump background raw palette indices (0..15) into a simple ASCII PGM-like
     * PPM (P3) file for quick inspection (grayscale mapping). Each index scaled to
     * 0..255 by *17.
     */
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
            System.err.println("PPU dumpBackgroundToPpm failed: " + e.getMessage());
        }
    }

    /** Print a 32x30 tile map of first scanline of each tile row (indices) */
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
        System.out.print(sb.toString());
    }

    // Tile matrix sampling mode (default "first")
    private String tileMatrixMode = "first";

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

    /**
     * Print histogram of background palette indices 0..15 for current frame buffer.
     */
    public void printBackgroundIndexHistogram() {
        int[] counts = new int[16];
        for (int i = 0; i < frameIndexBuffer.length; i++) {
            counts[frameIndexBuffer[i] & 0x0F]++;
        }
        System.out.println("--- Background index histogram (count) ---");
        for (int i = 0; i < 16; i++) {
            int c = counts[i];
            if (c > 0) {
                System.out.printf("%X: %d%n", i, c);
            }
        }
    }

    /**
     * Debug: imprime os IDs de tiles (bytes de nametable) de uma nametable lógica
     * (0..3). Espelha conforme mirroring ativo no mapper. Mostra 32x30 valores em
     * hex (duas casas) separados por espaço.
     */
    public void printNameTableTileIds(int logicalIndex) {
        if (logicalIndex < 0 || logicalIndex > 3)
            logicalIndex = 0;
        System.out.printf("--- NameTable %d tile IDs ---\n", logicalIndex);
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
                if (mt == MirrorType.VERTICAL) {
                    physical = table & 0x01; // 0,1,0,1
                } else { // HORIZONTAL
                    physical = (table >> 1); // 0,0,1,1
                }
                int value = nameTables[(physical * 0x0400) + index] & 0xFF;
                sb.append(String.format("%02X", value));
                if (col != 31)
                    sb.append(' ');
            }
            System.out.println(sb.toString());
        }
    }

    /**
     * Dump a pattern tile (0-255) of current background pattern table to stdout.
     */
    public void dumpPatternTile(int tile) {
        tile &= 0xFF;
        // Usa bit 3 (0x08) de PPUCTRL para seleção da pattern table de background (como
        // no pipeline)
        int base = ((regCTRL & 0x08) != 0 ? 0x1000 : 0x0000) + tile * 16; // background table select corrigido
        System.out.printf("--- Pattern tile %02X (base=%04X) ---\n", tile, base);
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
            System.out.println(bits.toString());
        }
    }

    // --- Debug helpers for background sampling investigation ---
    private boolean debugBgSample = false;
    private boolean debugBgSampleAll = false; // log mesmo se for muitos pixels (até limite)
    private int debugBgSampleLimit = 0;
    private int debugBgSampleCount = 0;
    private boolean simpleTiming = false; // modo experimental de timing simplificado
    // Runtime attribute logging
    private boolean attrRuntimeLog = false;
    private int attrLogLimit = 200;
    private int attrLogCount = 0;
    // Nametable runtime logging
    private boolean nametableRuntimeLog = false;
    private int nametableLogLimit = 200;
    private int nametableLogCount = 0;
    private int nametableBaselineFilter = -1; // se >=0 filtra esse valor

    public void enableBackgroundSampleDebug(int limit) {
        this.debugBgSample = true;
        this.debugBgSampleLimit = (limit <= 0 ? 50 : limit);
        this.debugBgSampleCount = 0;
        System.out.printf("[PPU] Background sample debug enabled (limit=%d)\n", this.debugBgSampleLimit);
    }

    public void enableBackgroundSampleDebugAll(int limit) {
        this.debugBgSampleAll = true;
        this.debugBgSample = false; // prevalece modo ALL
        this.debugBgSampleLimit = (limit <= 0 ? 200 : limit);
        this.debugBgSampleCount = 0;
        System.out.printf("[PPU] Background sample ALL debug enabled (limit=%d)\n", this.debugBgSampleLimit);
    }

    public void setSimpleTiming(boolean simple) {
        this.simpleTiming = simple;
        System.out.println("[PPU] simpleTiming=" + simple);
    }

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
        System.out.println("[PPU] testPatternMode=" + testPatternMode + " (prev=" + prev + ")");
    }

    public void enableAttributeRuntimeLog(int limit) {
        this.attrRuntimeLog = true;
        if (limit > 0)
            this.attrLogLimit = limit;
        this.attrLogCount = 0;
        System.out.printf("[PPU] Attribute runtime logging enabled (limit=%d)\n", attrLogLimit);
    }

    public void enableNametableRuntimeLog(int limit, int baselineFilter) {
        this.nametableRuntimeLog = true;
        if (limit > 0)
            this.nametableLogLimit = limit;
        this.nametableLogCount = 0;
        this.nametableBaselineFilter = baselineFilter;
        System.out.printf("[PPU] Nametable runtime logging enabled (limit=%d, baselineFilter=%s)\n", nametableLogLimit,
                baselineFilter >= 0 ? String.format("%02X", baselineFilter) : "NONE");
    }

    /**
     * Fallback: se nenhuma amostra foi registrada em tempo real, varre o frame e
     * imprime primeiras N não-zero.
     */
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
                    System.out.printf("[BG-SAMPLE-FALLBACK] frame=%d x=%d y=%d idx=%X\n", frame, x, y, idx);
                    printed++;
                }
            }
        }
        if (printed == 0) {
            System.out.println("[BG-SAMPLE-FALLBACK] Nenhum pixel não-zero encontrado neste frame.");
        }
    }

    /**
     * Estatísticas por coluna: conta pixels de background !=0 em cada coluna de 256
     * e por coluna de tile (32).
     */
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
        System.out.println("--- Background non-zero pixel counts per pixel column (only columns >0) ---");
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int x = 0; x < 256; x++) {
            if (pixelCounts[x] != 0) {
                sb.append(String.format("%d:%d ", x, pixelCounts[x]));
                shown++;
                if (shown % 16 == 0) {
                    System.out.println(sb.toString());
                    sb.setLength(0);
                }
            }
        }
        if (sb.length() > 0)
            System.out.println(sb.toString());
        if (shown == 0)
            System.out.println("(nenhuma coluna com pixels !=0)");
        System.out.println("--- Background non-zero pixel counts per tile column (0..31) ---");
        for (int t = 0; t < 32; t++) {
            int cnt = tileCounts[t];
            double pct = (cnt / 1920.0) * 100.0;
            System.out.printf("T%02d=%4d (%.1f%%)  %s%n", t, cnt, pct, (t < 4 ? "<- esquerda 32px" : ""));
        }
    }

    // Força habilitar background independentemente do valor escrito em $2001
    private boolean forceBgEnable = false;

    public void setForceBackgroundEnable(boolean enable) {
        this.forceBgEnable = enable;
        System.out.println("[PPU] forceBgEnable=" + enable);
    }

    public void enableNmiDebugLog(int limit) {
        this.debugNmiLog = true;
        if (limit > 0)
            this.debugNmiLogLimit = limit;
        this.debugNmiLogCount = 0;
        System.out.printf("[PPU] NMI debug log enabled (limit=%d)\n", debugNmiLogLimit);
    }

    // --- Minimal sprite system (evaluation + per-pixel overlay) ---
    private void evaluateSpritesForScanline() {
        spriteCountThisLine = 0;
        int sl = scanline;
        int spriteHeight = ((regCTRL & 0x20) != 0) ? 16 : 8; // CTRL bit5 selects 8x16
        for (int i = 0; i < 64 && spriteCountThisLine < 8; i++) {
            int base = i * 4;
            int y = oam[base] & 0xFF; // On real NES this is Y position minus 1; simplify: treat as direct
            int top = y;
            int bottom = y + spriteHeight - 1;
            if (sl >= top && sl <= bottom) {
                spriteIndices[spriteCountThisLine++] = i;
            }
        }
    }

    private void overlaySpritePixel() {
        if (spriteCountThisLine == 0)
            return;
        int xPixel = simpleTiming ? (cycle - 1) : (cycle - 9);
        if (xPixel < 0 || xPixel >= 256)
            return;
        int sl = scanline;
        int spriteHeight = ((regCTRL & 0x20) != 0) ? 16 : 8;
        // Respect left 8-pixel sprite clipping (PPUMASK bit2). If disabled and within
        // left region, skip sprite processing entirely for this pixel.
        if (xPixel < 8 && (regMASK & 0x04) == 0) {
            return;
        }
        // Capture original background index BEFORE any sprite overlays for sprite 0 hit
        // logic
        int bgOriginal = frameIndexBuffer[sl * 256 + xPixel] & 0x0F;
        for (int si = 0; si < spriteCountThisLine; si++) {
            int spriteIndex = spriteIndices[si];
            int base = spriteIndex * 4;
            int y = oam[base] & 0xFF;
            int tile = oam[base + 1] & 0xFF;
            int attr = oam[base + 2] & 0xFF; // bits: 76543210 (7 VFlip,6 HFlip,5 Priority,2-0 Palette)
            int x = oam[base + 3] & 0xFF;
            if (xPixel < x || xPixel >= x + 8)
                continue;
            int rowInSprite = sl - y;
            if (rowInSprite < 0 || rowInSprite >= spriteHeight)
                continue;
            boolean flipV = (attr & 0x80) != 0;
            boolean flipH = (attr & 0x40) != 0;
            int row = flipV ? (spriteHeight - 1 - rowInSprite) : rowInSprite;

            // --- Sprite pattern table selection ---
            // 8x8 mode: PPUCTRL bit4 selects base table (0: $0000, 1: $1000) and tile is
            // index
            // 8x16 mode: bit4 ignored; bit0 of tile selects table (0:$0000,1:$1000),
            // remaining 7 bits (tile & 0xFE) form base index for top half.
            int addrLo;
            int addrHi;
            if (spriteHeight == 16) { // 8x16
                int tableSelect = tile & 0x01; // pattern table chosen by bit0
                int baseTileIndex = tile & 0xFE; // even index for top half
                int half = row / 8; // 0 top, 1 bottom
                int tileRow = row & 0x7; // row inside the selected 8x8 tile
                int actualTile = baseTileIndex + half;
                int patternTableBase = tableSelect * 0x1000;
                addrLo = patternTableBase + actualTile * 16 + tileRow;
                addrHi = addrLo + 8;
            } else { // 8x8
                int patternTableBase = ((regCTRL & 0x10) != 0 ? 0x1000 : 0x0000);
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
                continue; // transparent
            int paletteGroup = attr & 0x03; // lower 2 bits select sprite palette group
            int paletteIndex = palette.read(0x3F10 + paletteGroup * 4 + pattern);
            int existingIndex = frameIndexBuffer[sl * 256 + xPixel] & 0x0F; // may have been modified by earlier sprites
            boolean bgTransparent = existingIndex == 0;
            boolean spritePriorityFront = (attr & 0x20) == 0; // 0 = in front of background
            // Sprite 0 hit detection (STATUS bit6): occurs when sprite 0 opaque pixel
            // overlaps
            // a non-transparent background pixel. Must also honor left clipping bits when
            // x<8.
            if (spriteIndex == 0 && pattern != 0 && bgOriginal != 0) {
                boolean allow = true;
                if (xPixel < 8) {
                    // Need both background left (bit1) and sprite left (bit2) enabled to register
                    // hit
                    if ((regMASK & 0x02) == 0 || (regMASK & 0x04) == 0) {
                        allow = false;
                    }
                }
                if (allow) {
                    regSTATUS |= 0x40; // set sprite 0 hit
                }
            }
            if (bgTransparent || spritePriorityFront) {
                frameIndexBuffer[sl * 256 + xPixel] = paletteIndex & 0x0F;
                frameBuffer[sl * 256 + xPixel] = palette.getArgb(paletteIndex, regMASK);
            }
            // Stop after first opaque sprite pixel (no back-to-front compositing yet)
            break;
        }
    }
}

package com.nesemu.mapper;

import com.nesemu.rom.INesRom;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.GENERAL;

/**
 * Minimal MMC5 (Mapper 5) implementation (EXROM boards) – phase 1.
 * Goal: Support common games that only rely on:
 * - PRG banking (most use PRG mode 3 – four 8KB windows)
 * - CHR banking (most use CHR mode 3 – eight 1KB banks)
 * - Basic mirroring via $5105 values corresponding to standard mirroring
 *
 * Unsupported (placeholders):
 * - Extended attribute mode / ExRAM as nametable / fill mode
 * - Split screen ($5200-$5202)
 * - IRQ / scanline counter ($5203/$5204)
 * - Multiplier ($5205/$5206)
 * - MMC5A extra registers ($5207+)
 * - Audio extensions
 * - PRG RAM protection / advanced RAM mapping nuances
 * - Separate CHR sets for background vs sprites (we expose a unified view)
 *
 * Design simplifying assumptions:
 * - Treat all CHR as ROM; if CHR size is 0 allocate 8KB CHR RAM.
 * - Allocate 64KB of PRG RAM superset (covers all needed; write enabled when
 * protection sequence met or we allow always for now).
 * - Default power-on: PRG mode = 3, CHR mode = 3, banks initialized to last for
 * fixed region, others to 0.
 */
public class Mapper5 extends Mapper {

    // Configuration registers
    private int regPrgMode = 3; // $5100 low 2 bits
    private int regChrMode = 3; // $5101 low 2 bits
    private int regPrgRamProt1 = 0; // $5102
    private int regPrgRamProt2 = 0; // $5103
    
    // Extra RAM ($5C00-$5FFF) – 1KB
    private int regExRamMode = 0; // $5104 (ignored for now except for value storage)
    private int regNtMapping = 0x50; // $5105 default horizontal example; store raw
    private int regFillTile = 0; // $5106 (not yet used in PPU path)
    private int regFillAttr = 0; // $5107 (2-bit attr) (not yet used)

    // PRG bank registers ($5113-$5117)
    private int regPrgBank6000 = 0; // $5113 (RAM only bank) – we map within prgRam
    private int regPrgBank8000 = 0; // $5114
    private int regPrgBankA000 = 0; // $5115
    private int regPrgBankC000 = 0; // $5116
    private int regPrgBankE000 = 0xFF; // $5117 – power-on often $FF (last bank). Upper bits ignored based on size.

    // CHR bank registers ($5120-$512B) – we keep full array 12 though only first 8
    // used for sprites; next 4 for BG.
    private final int[] regChrBanks = new int[12];
    
    // CHR upper bits ($5130) – 2 bits appended to each CHR bank number when using
    private int regChrUpper = 0; // $5130 upper bits (ignored for <=256KB CHR)

    // Precomputed for bounds checking / potential future use
    private final int prg8kBanks; // number of 8KB units in PRG
    private final int chr1kBanks; // number of 1KB units in CHR (or RAM)

    // logging helpers
    private boolean logBanks = true;
    private int logLimit = 500;
    private int logCount = 0;

    // Vertical/horizontal nametable mirroring from header (fallback)
    private final boolean verticalFromHeader;

    // Multiplier ($5205/$5206)
    private int multA = 0;
    private int multB = 0;

    // IRQ ($5203/$5204)
    private int irqTarget = 0;
    private boolean irqEnabled = false;
    private boolean irqPending = false;
    private boolean inFrame = false;

    // ExRAM latch for Mode 1 (Extended Attribute)
    private int lastExRamByte = 0;

    /**
     * Creates a Mapper 5 (MMC5) instance from the given iNES ROM.
     * @param rom
     */
    public Mapper5(INesRom rom) {
        this.exRam = new byte[1024];
        this.prg = rom.getPrgRom();
        this.chr = rom.getChrRom();
        this.chrRam = (chr.length == 0) ? new byte[0x2000] : null; // 8KB CHR RAM allocated
        this.prgRam = new byte[64 * 1024]; // 64KB PRG RAM (Max MMC5 size)
        this.prg8kBanks = prg.length / 0x2000; // 8KB units
        int chrLen = (chrRam != null) ? chrRam.length : chr.length;
        this.chr1kBanks = chrLen / 0x0400; // 1KB units
        this.verticalFromHeader = rom.getHeader().isVerticalMirroring();
        // Initialize CHR bank registers sequentially
        for (int i = 0; i < regChrBanks.length; i++) {
            regChrBanks[i] = i & 0xFF;
        }
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        
        // DEBUG: Allow reading back write-only configuration registers ($51xx)
        // This helps the Memory Viewer show the current state of the mapper.
        if (address >= 0x5100 && address <= 0x5130) {
            switch (address) {
                case 0x5100: return regPrgMode;
                case 0x5101: return regChrMode;
                case 0x5102: return regPrgRamProt1;
                case 0x5103: return regPrgRamProt2;
                case 0x5104: return regExRamMode;
                case 0x5105: return regNtMapping;
                case 0x5106: return regFillTile;
                case 0x5107: return regFillAttr;
                case 0x5113: return regPrgBank6000;
                case 0x5114: return regPrgBank8000;
                case 0x5115: return regPrgBankA000;
                case 0x5116: return regPrgBankC000;
                case 0x5117: return regPrgBankE000;
                case 0x5130: return regChrUpper;
                default:
                    if (address >= 0x5120 && address <= 0x512B) {
                        return regChrBanks[address - 0x5120];
                    }
                    return 0;
            }
        }

        // MMC5 Registers read
        if (address >= 0x5200 && address <= 0x5206) {
            switch (address) {
                case 0x5204: // IRQ Status
                    int status = (irqPending ? 0x80 : 0) | (inFrame ? 0x40 : 0);
                    irqPending = false; // Reading acknowledges IRQ
                    return status;
                case 0x5205: // Multiplier Low
                    return (multA * multB) & 0xFF;
                case 0x5206: // Multiplier High
                    return ((multA * multB) >> 8) & 0xFF;
            }
            return 0;
        }
        // ExRAM ($5C00-$5FFF) - CPU read access
        if (address >= 0x5C00 && address <= 0x5FFF) {
            return exRam[address - 0x5C00] & 0xFF;
        }
        if (address >= 0x6000 && address <= 0x7FFF) {
            // 8KB PRG RAM window ($6000) uses regPrgBank6000
            // $5113 bits 0-2 select the 8KB bank (0-7) from the 64KB PRG RAM
            int base = (regPrgBank6000 & 0x07) * 0x2000;
            return prgRam[base + (address - 0x6000)] & 0xFF;
        }
        if (address < 0x8000)
            return 0; // open bus area we ignore (APU etc handled elsewhere)
        return prgReadMapped(address);
    }

    @Override
    public void cpuWrite(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;
        // PRG RAM region
        if (address >= 0x6000 && address <= 0x7FFF) {
            // Write to selected PRG RAM bank
            int base = (regPrgBank6000 & 0x07) * 0x2000;
            prgRam[base + (address - 0x6000)] = (byte) value;
            return;
        }
        // Control/config registers
        if (address >= 0x5100 && address <= 0x5107) {
            switch (address) {
                case 0x5100 -> regPrgMode = value & 0x03;
                case 0x5101 -> regChrMode = value & 0x03;
                case 0x5102 -> regPrgRamProt1 = value & 0x03;
                case 0x5103 -> regPrgRamProt2 = value & 0x03;
                case 0x5104 -> regExRamMode = value & 0x03; // ignored
                case 0x5105 -> regNtMapping = value & 0xFF; // store raw
                case 0x5106 -> regFillTile = value & 0xFF;
                case 0x5107 -> regFillAttr = value & 0x03;
            }
            trace("[M5 CFG %04X]=%02X", address, value);
            return;
        }
        if (address >= 0x5113 && address <= 0x5117) {
            switch (address) {
                case 0x5113 -> regPrgBank6000 = value & 0x7F; // RAM only
                case 0x5114 -> regPrgBank8000 = value & 0xFF;
                case 0x5115 -> regPrgBankA000 = value & 0xFF;
                case 0x5116 -> regPrgBankC000 = value & 0xFF;
                case 0x5117 -> regPrgBankE000 = value & 0xFF;
            }
            trace("[M5 PRG %04X]=%02X mode=%d", address, value, regPrgMode);
            return;
        }
        if (address >= 0x5120 && address <= 0x512B) {
            int idx = address - 0x5120;
            if (idx >= 0 && idx < regChrBanks.length) {
                regChrBanks[idx] = value & 0xFF; // upper bits appended separately when using >256KB CHR
                trace("[M5 CHR%02d]=%02X", idx, value);
            }
            return;
        }
        if (address == 0x5130) {
            regChrUpper = value & 0x03; // 2 bits
            trace("[M5 CHRUP]=%02X", value);
            return;
        }
        // Multiplier & IRQ registers
        if (address >= 0x5200 && address <= 0x5206) {
            switch (address) {
                case 0x5203: // IRQ Scanline target
                    irqTarget = value & 0xFF;
                    break;
                case 0x5204: // IRQ Enable
                    irqEnabled = (value & 0x80) != 0;
                    break;
                case 0x5205: // Multiplier A
                    multA = value & 0xFF;
                    break;
                case 0x5206: // Multiplier B
                    multB = value & 0xFF;
                    break;
            }
            return;
        }
        // ExRAM ($5C00-$5FFF) - CPU write access
        if (address >= 0x5C00 && address <= 0x5FFF) {
            exRam[address - 0x5C00] = (byte) value;
            return;
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        if (address < 0x2000) { // CHR
            return chrRead(address) & 0xFF;
        }
        // Nametables handled in PPU using getMirrorType(); advanced mapping ($5105) not
        // implemented
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        if (address < 0x2000 && chrRam != null) {
            // Simplify write mapping: direct index ignoring banking (adequate for CHR RAM
            // homebrew)
            int idx = address & (chrRam.length - 1);
            chrRam[idx] = (byte) (value & 0xFF);
        }
    }

    @Override
    public MirrorType getMirrorType() {
        // Interpret $5105 only for standard cases; fallback to header
        // Horizontal example: $50 -> bits: DDCCBBAA = 0101 0000 => A=0 B=0 C=1 D=0
        // (approx). We'll just detect known patterns.
        int v = regNtMapping & 0xFF;
        return switch (v) {
            case 0x50 -> MirrorType.HORIZONTAL; // documented example
            case 0x44 -> MirrorType.VERTICAL; // documented example
            case 0x00 -> MirrorType.SINGLE0;
            case 0x55 -> MirrorType.SINGLE1; // treat as single1 (CIRAM 1) – somewhat arbitrary
            default -> verticalFromHeader ? MirrorType.VERTICAL : MirrorType.HORIZONTAL;
        };
    }

    /**
     * Enables logging of PRG and CHR bank changes to debug log.
     * @param limit
     */
    public void enableBankLogging(int limit) {
        this.logBanks = true;
        if (limit > 0)
            this.logLimit = limit;
    }

    @Override
    public void onScanline(int scanline) {
        // MMC5 IRQ logic approximation: fires when scanline matches target.
        // Also maintains the "In Frame" flag ($5204 bit 6).
        if (scanline >= 0 && scanline <= 239) {
            inFrame = true;
            if (scanline == irqTarget) {
                irqPending = true;
                if (irqEnabled && irqCallback != null) {
                    irqCallback.run();
                }
            }
        } else {
            inFrame = false;
            irqPending = false; // Reset pending flag at end of frame to prevent stuck IRQ
        }
    }

    @Override
    public int ppuReadNametable(int address, byte[] ciram) {
        int val = 0;
        
        // Decode MMC5 Nametable Mapping ($5105)
        // Format: DD CC BB AA (2 bits per quadrant)
        // 00=CIRAM0, 01=CIRAM1, 10=ExRAM, 11=FillMode
        int ntIndex = (address >> 10) & 0x03; // 0..3 ($2000,$2400,$2800,$2C00)
        int mode = (regNtMapping >> (ntIndex * 2)) & 0x03;

        switch (mode) {
            case 0: // CIRAM 0
                val = ciram[address & 0x03FF] & 0xFF;
                break;
            case 1: // CIRAM 1
                val = ciram[0x0400 + (address & 0x03FF)] & 0xFF;
                break;
            case 2: // ExRAM (as Nametable)
                val = exRam[address & 0x03FF] & 0xFF;
                break;
            case 3: // Fill Mode
                val = regFillTile & 0xFF;
                break;
        }
        
        // In ExRAM Mode 1, we latch the ExRAM value corresponding to this tile.
        // ExRAM is 1KB, indexed by the nametable offset (0-3FF).
        // CRITICAL: Only latch on NameTable fetches ($000-$3BF), NOT Attribute fetches ($3C0-$3FF).
        // Latching on attributes would corrupt the state for the subsequent pattern fetch.
        if (regExRamMode == 1 && (address & 0x03FF) < 0x03C0) {
            // Note: When using ExRAM as Nametable (mode 2 above), the latch logic is tricky.
            // But usually Mode 1 (Ex Attributes) is combined with CIRAM nametables.
            // We use the logical address offset 0-3FF to index ExRAM for the attribute data.
            // Even if the nametable data came from CIRAM, the attribute/bank data comes from ExRAM[offset].
            int offset = address & 0x03FF;
            lastExRamByte = exRam[offset] & 0xFF;
        }
        return val;
    }

    @Override
    public int adjustAttribute(int coarseX, int coarseY, int attributeAddress, int currentValue) {
        if (regExRamMode == 1) {
            // In Mode 1, attributes come from ExRAM (latched during NT read).
            // Format: bits 7-6 = palette index.
            // We replicate this palette to all 2-bit slots so the PPU's shift logic picks the right one.
            int pal = (lastExRamByte >> 6) & 0x03;
            return (pal << 6) | (pal << 4) | (pal << 2) | pal;
        }
        return currentValue;
    }

    /**
     * Reads a byte from CHR space with current banking applied.
     * @param address
     * @return
     */
    private int chrRead(int address) {
        int mode = regChrMode & 0x03;
        boolean sprite = (getChrReadMode() == ChrReadMode.SPRITE);

        // ExRAM Mode 1: Background tiles use ExRAM for banking (overrides standard BG banking)
        if (!sprite && regExRamMode == 1) {
            // ExRAM byte bits 0-5 select 4KB bank.
            int bank4k = lastExRamByte & 0x3F;
            // Upper 2 bits from $5130 (bits 0-1 become bits 6-7 of 4KB index)
            int finalBank4k = ((regChrUpper & 0x03) << 6) | bank4k;
            return readChrLinear(finalBank4k * 0x1000 + (address & 0x0FFF));
        }

        int bankVal = 0;
        int offset = 0;
        int mask = 0xFF; // Mask to align 1KB index to mode size

        if (sprite) {
            // --- Sprites ($5120-$5127) ---
            switch (mode) {
                case 0 -> { // 8KB: Uses $5127 (last reg)
                    bankVal = regChrBanks[7];
                    mask = 0xF8; // Ignore low 3 bits (align to 8KB)
                    offset = address & 0x1FFF;
                }
                case 1 -> { // 4KB: Uses $5123, $5127
                    int idx = (address < 0x1000) ? 3 : 7;
                    bankVal = regChrBanks[idx];
                    mask = 0xFC; // Ignore low 2 bits (align to 4KB)
                    offset = address & 0x0FFF;
                }
                case 2 -> { // 2KB: Uses $5121, $5123, $5125, $5127
                    int sub = (address >> 11) & 0x03; // 0..3
                    bankVal = regChrBanks[sub * 2 + 1];
                    mask = 0xFE; // Ignore low 1 bit (align to 2KB)
                    offset = address & 0x07FF;
                }
                case 3 -> { // 1KB: $5120..$5127
                    int sub = (address >> 10) & 0x07; // 0..7
                    bankVal = regChrBanks[sub];
                    mask = 0xFF;
                    offset = address & 0x03FF;
                }
            }
        } else {
            // --- Background ($5128-$512B) ---
            switch (mode) {
                case 0 -> { // 8KB: Uses $512B
                    bankVal = regChrBanks[11];
                    mask = 0xF8;
                    offset = address & 0x1FFF;
                }
                case 1 -> { // 4KB: Uses $512B (only one 4KB bank for BG in this mode usually, or aliased)
                    // MMC5 BG Mode 1 is weird, but usually follows the pattern.
                    // Assuming standard mapping: $512B for 0-FFF, $512B for 1000-1FFF?
                    // Actually standard is $512B for both or split. Let's use last reg.
                    int idx = (address < 0x1000) ? 11 : 11; 
                    bankVal = regChrBanks[idx];
                    mask = 0xFC;
                    offset = address & 0x0FFF;
                }
                case 2 -> { // 2KB: Uses $5129, $512B
                    int sub = (address >> 11) & 0x03;
                    bankVal = regChrBanks[8 + sub]; // 8,9,10,11? No, usually 9, 11.
                    // Simplified: just use the register corresponding to the slot.
                    mask = 0xFE;
                    offset = address & 0x07FF;
                }
                case 3 -> { // 1KB: $5128..$512B
                    int sub = (address >> 10) & 0x03; // 0..3 (maps to 0000,0400,0800,0C00 relative to table)
                    bankVal = regChrBanks[8 + sub];
                    mask = 0xFF;
                    offset = address & 0x03FF;
                }
            }
        }

        // Combine with upper bits from $5130 (which apply to the bank index)
        // AND apply the mask to align the bank index to the block size
        int finalBank = ((regChrUpper & 0x03) << 8) | (bankVal & mask);

        // Convert 1KB bank index to linear address (MMC5 registers are always 1KB units)
        return readChrLinear(finalBank * 0x0400 + offset);
    }

    /**
     * Reads a byte from CHR space using a linear address, applying modulo for out-of-bounds.
     * @param linear
     * @return
     */
    private int readChrLinear(int linear) {
        int chrLen = (chrRam != null) ? chrRam.length : chr.length;
        if (chrLen == 0) return 0;
        // Optimization: Use bitwise AND for power-of-2 sizes (standard for ROMs)
        // Modulo (%) is very slow in hot paths (PPU fetch).
        if ((chrLen & (chrLen - 1)) == 0) {
            linear &= (chrLen - 1);
        } else {
            linear %= chrLen;
        }
        return (chrRam != null ? chrRam[linear] : chr[linear]) & 0xFF;
    }

    /**
     * Reads a byte from PRG space with current banking applied.
     * @param address
     * @return
     */
    private int prgReadMapped(int address) {
        int off = address & 0xFFFF;
        // bankSize concept not needed after refactor (mapping handled per mode)
        int value = 0xFF;
        int prgIndex;
        switch (regPrgMode & 3) {
            case 0 -> { // 32KB mapped by regPrgBankE000 (bits 6..2 used)
                int bank32 = (regPrgBankE000 & 0x7C) >> 2; // ignore low 2 bits
                int base = (bank32 * 0x8000) % Math.max(prg.length, 1);
                prgIndex = base + (off - 0x8000);
                if (prgIndex >= 0 && prgIndex < prg.length)
                    value = prg[prgIndex] & 0xFF;
            }
            case 1 -> { // $8000-$BFFF 16KB via regPrgBank8000; $C000-$FFFF 16KB via regPrgBankE000
                if (off < 0xC000) {
                    // FIX: Mode 1 uses $5115 (Reg B) for the first 16KB bank, not $5114
                    int bank16 = (regPrgBankA000 & 0x7E) >> 1; 
                    int base = (bank16 * 0x4000) % Math.max(prg.length, 1);
                    prgIndex = base + (off - 0x8000);
                } else {
                    int bank16 = (regPrgBankE000 & 0x7E) >> 1;
                    int base = (bank16 * 0x4000) % Math.max(prg.length, 1);
                    prgIndex = base + (off - 0xC000);
                }
                if (prgIndex >= 0 && prgIndex < prg.length)
                    value = prg[prgIndex] & 0xFF;
            }
            case 2 -> { // Mode 2: 16KB ($8000) + 8KB ($C000) + 8KB ($E000)
                if (off < 0xC000) {
                    // $8000-$BFFF: 16KB via $5115 (Reg B)
                    int bank16 = (regPrgBankA000 & 0x7E) >> 1;
                    int base = (bank16 * 0x4000) % Math.max(prg.length, 1);
                    prgIndex = base + (off - 0x8000);
                } else if (off < 0xE000) {
                    // $C000-$DFFF: 8KB via $5116 (Reg C)
                    int bank8 = regPrgBankC000 & 0x7F;
                    int base = (bank8 * 0x2000) % Math.max(prg.length, 1);
                    prgIndex = base + (off - 0xC000);
                } else {
                    // $E000-$FFFF: 8KB via $5117 (Reg E)
                    int bank8 = regPrgBankE000 & 0x7F;
                    int base = (bank8 * 0x2000) % Math.max(prg.length, 1);
                    prgIndex = base + (off - 0xE000);
                }
                if (prgIndex >= 0 && prgIndex < prg.length)
                    value = prg[prgIndex] & 0xFF;
            }
            default -> { // mode 3: four 8KB windows 8000,A000,C000,E000 each
                int region = (off - 0x8000) / 0x2000; // 0..3
                int bankReg = switch (region) {
                    case 0 -> regPrgBank8000;
                    case 1 -> regPrgBankA000;
                    case 2 -> regPrgBankC000;
                    case 3 -> regPrgBankE000; // fixed last usually
                    default -> regPrgBankE000;
                };
                int bank8 = bankReg & 0x7F; // 7 bits
                int base = (bank8 * 0x2000) % Math.max(prg.length, 1);
                prgIndex = base + ((off - 0x8000) & 0x1FFF);
                if (prgIndex >= 0 && prgIndex < prg.length)
                    value = prg[prgIndex] & 0xFF;
            }
        }
        return value & 0xFF;
    }

    /**
     * Indicates whether PRG RAM writes are currently enabled based on the
     * protection register sequence.
     * @return
     */
    public boolean prgRamWriteEnabled() {
        // Proper enable requires prot1=2 prot2=1. For now allow always if both non-zero
        // OR simple match.
        if (regPrgRamProt1 == 2 && regPrgRamProt2 == 1)
            return true;
        // Fallback lenient: many homebrew may not set sequence exactly in early tests.
        return false; // keep strict to surface issues if needed
    }

    /**
     * Helper to log a trace message if bank logging is enabled and limit not yet reached.
     * @param fmt
     * @param args
     */
    private void trace(String fmt, Object... args) {
        if (logBanks && logCount < logLimit) {
            Log.debug(GENERAL, fmt, args);
            logCount++;
        }
    }

    //-------------------- Accessors and helpers --------------------
    public int getRegExRamMode() {
        return regExRamMode;
    }

    public int getRegFillTile() {
        return regFillTile;
    }

    public int getRegFillAttr() {
        return regFillAttr;
    }

    public int getRegChrUpper() {
        return regChrUpper;
    }
    public int getPrg8kBanks() {
        return prg8kBanks;
    }
    public int getChr1kBanks() {
        return chr1kBanks;
    }
    public int getRegPrgBank6000() {
        return regPrgBank6000;
    }
}

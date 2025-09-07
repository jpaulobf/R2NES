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
    private boolean logBanks = false;
    private int logLimit = 64;
    private int logCount = 0;

    // Vertical/horizontal nametable mirroring from header (fallback)
    private final boolean verticalFromHeader;

    /**
     * Creates a Mapper 5 (MMC5) instance from the given iNES ROM.
     * @param rom
     */
    public Mapper5(INesRom rom) {
        this.exRam = new byte[1024];
        this.prg = rom.getPrgRom();
        this.chr = rom.getChrRom();
        this.chrRam = (chr.length == 0) ? new byte[0x2000] : null; // 8KB CHR RAM allocated
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
        if (address >= 0x6000 && address <= 0x7FFF) {
            // 8KB PRG RAM window ($6000) uses regPrgBank6000 low bits (we ignore bank for
            // now - single 8K)
            int bank = regPrgBank6000 & 0x0F; // but we flatten onto prgRam (64K) 8KB pages
            int base = (bank % (prgRam.length / 0x2000)) * 0x2000;
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
            if (prgRamWriteEnabled()) {
                int bank = regPrgBank6000 & 0x0F;
                int base = (bank % (prgRam.length / 0x2000)) * 0x2000;
                prgRam[base + (address - 0x6000)] = (byte) value;
            }
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
        // Writes to multiplier / IRQ etc ignored for now
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
        } else if (address >= 0x5C00 && address <= 0x5FFF) {
            int ex = address - 0x5C00;
            if (ex >= 0 && ex < exRam.length) {
                exRam[ex] = (byte) (value & 0xFF);
            }
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

    /**
     * Reads a byte from CHR space with current banking applied.
     * @param address
     * @return
     */
    private int chrRead(int address) {
        int mode = regChrMode & 0x03;
        int bankIndex;
        int offsetInBank;
        switch (mode) {
            case 0 -> { // 8KB: use regChrBanks[0] as 8KB index (ignore low bits?)
                int bank8 = (regChrBanks[0]) & 0xFF; // treat sequential 8KB; 8KB = 8 *1KB
                bankIndex = bank8 * 0x2000;
                offsetInBank = address & 0x1FFF;
            }
            case 1 -> { // 4KB banks: 0/1
                if (address < 0x1000) {
                    int bank4 = regChrBanks[0] & 0xFF;
                    bankIndex = bank4 * 0x1000;
                    offsetInBank = address & 0x0FFF;
                } else {
                    int bank4 = regChrBanks[1] & 0xFF;
                    bankIndex = bank4 * 0x1000;
                    offsetInBank = (address - 0x1000) & 0x0FFF;
                }
            }
            case 2 -> { // 2KB banks: 0..3
                int region = (address >> 11) & 0x03; // 0..3
                int bank2 = regChrBanks[region] & 0xFF;
                bankIndex = bank2 * 0x0800;
                offsetInBank = address & 0x07FF;
            }
            default -> { // 1KB banks (mode 3) – 8 regions
                int region = (address >> 10) & 0x07; // 0..7
                int bank1 = regChrBanks[region] & 0xFF;
                bankIndex = bank1 * 0x0400;
                offsetInBank = address & 0x03FF;
            }
        }
        int linear = bankIndex + offsetInBank;
        int chrLen = (chrRam != null) ? chrRam.length : chr.length;
        if (chrLen == 0)
            return 0;
        linear %= chrLen; // wrap safety
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
                    int bank16 = (regPrgBank8000 & 0x7E) >> 1; // ignore bit0
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
            case 2 -> { // Simplified: treat like mode1 for now (common games use 3)
                int bank16 = (regPrgBank8000 & 0x7E) >> 1;
                int base = (bank16 * 0x4000) % Math.max(prg.length, 1);
                int local = (off - 0x8000) & 0x3FFF;
                prgIndex = base + local;
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
    private boolean prgRamWriteEnabled() {
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
}

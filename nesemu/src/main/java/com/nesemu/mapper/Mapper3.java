package com.nesemu.mapper;

import com.nesemu.rom.INesRom;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;

/**
 * CNROM (Mapper 3)
 * - Fixed PRG like NROM (16KB mirrored or 32KB direct)
 * - CHR bank switching: write to $8000-$FFFF selects 8KB CHR bank (lower bits)
 */
public class Mapper3 implements Mapper {
    private final byte[] prg;
    private final byte[] chr; // multiple 8KB banks sequentially
    private final int prgPageCount; // 16KB
    private final int chrPageCount; // 8KB
    private int chrBank; // currently selected bank (0..chrPageCount-1)
    private final boolean verticalMirroring; // from header bit

    // Debug/logging
    private boolean bankLogEnabled = false;
    private int bankLogLimit = 32;
    private int bankLogCount = 0;

    // Optional bus conflict simulation flag
    // If true, cpuWrite value is ANDed with underlying PRG ROM byte (common
    // behaviour
    // on discrete boards without proper decoding) before selecting bank.
    private boolean simulateBusConflicts = false;

    public Mapper3(INesRom rom) {
        this.prg = rom.getPrgRom();
        this.chr = rom.getChrRom();
        this.prgPageCount = rom.getHeader().getPrgRomPages();
        this.chrPageCount = Math.max(1, rom.getHeader().getChrRomPages());
        this.chrBank = 0;
        this.verticalMirroring = rom.getHeader().isVerticalMirroring();
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address < 0x8000)
            return 0;
        int offset = address - 0x8000;
        if (prgPageCount == 1) {
            offset &= 0x3FFF; // mirror 16KB twice
        } else {
            offset &= 0x7FFF; // 32KB
        }
        if (offset >= prg.length)
            return 0;
        return prg[offset] & 0xFF;
    }

    @Override
    public void cpuWrite(int address, int value) {
        if ((address & 0x8000) != 0) {
            int raw = value & 0xFF;
            if (simulateBusConflicts) {
                // Derive PRG ROM byte at this address (mirrored like cpuRead) and AND it
                int off = address - 0x8000;
                if (prgPageCount == 1)
                    off &= 0x3FFF;
                else
                    off &= 0x7FFF;
                if (off >= 0 && off < prg.length) {
                    raw &= prg[off] & 0xFF;
                }
            }
            int prev = chrBank;
            chrBank = raw;
            if (chrPageCount > 0)
                chrBank %= chrPageCount;
            if (bankLogEnabled && bankLogCount < bankLogLimit && prev != chrBank) {
                Log.debug(GENERAL, "[M3 CHR BANK] writeVal=%02X resolved=%02X newBank=%d", value & 0xFF, raw & 0xFF,
                        chrBank);
                bankLogCount++;
            }
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            int bankOffset = chrBank * 0x2000;
            int idx = bankOffset + (address & 0x1FFF);
            if (idx < chr.length)
                return chr[idx] & 0xFF;
            return 0;
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        // CNROM has CHR ROM (no writes); ignore.
    }

    @Override
    public MirrorType getMirrorType() {
        return verticalMirroring ? MirrorType.VERTICAL : MirrorType.HORIZONTAL;
    }

    // --- Debug / configuration helpers ---
    public void enableBankLogging(int limit) {
        this.bankLogEnabled = true;
        if (limit > 0)
            this.bankLogLimit = limit;
    }

    public void setSimulateBusConflicts(boolean enable) {
        this.simulateBusConflicts = enable;
    }

    // Test accessor
    public int getChrBank() {
        return chrBank;
    }
}

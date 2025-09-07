package com.nesemu.mapper;

import com.nesemu.rom.INesRom;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;

/**
 * UNROM (Mapper 2)
 * PRG:
 * - Switchable 16KB bank at $8000-$BFFF (select via write in $8000-$FFFF)
 * - Fixed last 16KB bank at $C000-$FFFF (points to highest PRG page)
 * CHR:
 * - Typically 8KB CHR RAM (header CHR=0). If CHR ROM present (rare for UNROM),
 * we expose it read-only.
 * Mirroring determined by header.
 *
 * Bank select value: we mod by (prgPageCount - 1) since last bank is fixed.
 * Optional bus conflict simulation: AND written value with PRG ROM byte at
 * address.
 */
public class Mapper2 extends Mapper {

    // PRG ROM data
    private final int prgPageCount; // in 16KB units
    private int bankSelect; // 0..(prgPageCount-2)

    // Vertically mirrored if header says so
    private final boolean verticalMirroring;

    // Debug/logging
    private boolean bankLogEnabled = false;
    private int bankLogLimit = 32;
    private int bankLogCount = 0;
    private boolean simulateBusConflicts = false;

    /**
     * Creates a new Mapper 2 instance.
     * @param rom
     */
    public Mapper2(INesRom rom) {
        this.prg = rom.getPrgRom();
        this.chr = rom.getChrRom();
        this.prgPageCount = rom.getHeader().getPrgRomPages();
        if (prgPageCount < 2) {
            throw new IllegalArgumentException("Mapper2 requer pelo menos 2 páginas PRG (32KB)");
        }
        this.bankSelect = 0;
        this.verticalMirroring = rom.getHeader().isVerticalMirroring();
        this.chrRam = (chr.length == 0) ? new byte[0x2000] : null; // 8KB CHR RAM
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address < 0x8000)
            return 0;
        int offset = address - 0x8000;
        if (offset < 0x4000) { // switchable
            int bank = bankSelect; // 16KB bank index
            int base = bank * 0x4000;
            int idx = base + (offset & 0x3FFF);
            if (idx >= 0 && idx < prg.length)
                return prg[idx] & 0xFF;
            return 0;
        } else { // fixed last bank
            int base = (prgPageCount - 1) * 0x4000;
            int idx = base + (offset & 0x3FFF);
            if (idx >= 0 && idx < prg.length)
                return prg[idx] & 0xFF;
            return 0;
        }
    }

    @Override
    public void cpuWrite(int address, int value) {
        if ((address & 0x8000) != 0) {
            int raw = value & 0xFF;
            if (simulateBusConflicts) {
                int off = address - 0x8000;
                // Determine underlying PRG byte (mirroring logic like read):
                int byteVal;
                if (off < 0x4000) {
                    int base = bankSelect * 0x4000;
                    int idx = base + (off & 0x3FFF);
                    byteVal = (idx < prg.length) ? (prg[idx] & 0xFF) : 0xFF;
                } else {
                    int base = (prgPageCount - 1) * 0x4000;
                    int idx = base + (off & 0x3FFF);
                    byteVal = (idx < prg.length) ? (prg[idx] & 0xFF) : 0xFF;
                }
                raw &= byteVal;
            }
            int prev = bankSelect;
            int selectable = prgPageCount - 1; // last is fixed
            bankSelect = selectable == 0 ? 0 : (raw % selectable);
            if (bankLogEnabled && bankLogCount < bankLogLimit && prev != bankSelect) {
                Log.debug(GENERAL, "[M2 PRG BANK] write=%02X new=%d", value & 0xFF, bankSelect);
                bankLogCount++;
            }
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            if (chr.length > 0) {
                if (address < chr.length)
                    return chr[address] & 0xFF;
                return 0;
            } else {
                return chrRam[address & 0x1FFF] & 0xFF;
            }
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        if (address < 0x2000 && chrRam != null) {
            chrRam[address & 0x1FFF] = (byte) (value & 0xFF);
        }
    }

    @Override
    public MirrorType getMirrorType() {
        return verticalMirroring ? MirrorType.VERTICAL : MirrorType.HORIZONTAL;
    }

    /**
     * Enables logging of PRG bank changes to debug log.
     * @param limit
     */
    public void enableBankLogging(int limit) {
        this.bankLogEnabled = true;
        if (limit > 0)
            this.bankLogLimit = limit;
    }

    /**
     * Disables logging of PRG bank changes.
     */
    public void setSimulateBusConflicts(boolean enable) {
        this.simulateBusConflicts = enable;
    }

    /**
     * Enables or disables bus conflict simulation.
     * @param enable
     */
    public int getPrgBank() {
        return bankSelect;
    }
}

package com.nesemu.mapper;

import com.nesemu.rom.INesRom;

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

    public Mapper3(INesRom rom) {
        this.prg = rom.getPrgRom();
        this.chr = rom.getChrRom();
        this.prgPageCount = rom.getHeader().getPrgRomPages();
        this.chrPageCount = Math.max(1, rom.getHeader().getChrRomPages());
        this.chrBank = 0;
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
            chrBank = value & 0xFF; // mask below
            if (chrPageCount > 0)
                chrBank %= chrPageCount;
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
}

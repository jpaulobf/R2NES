package com.nesemu.mapper;

import com.nesemu.rom.INesRom;

public class Mapper7 extends Mapper {

    private int prgBank = 0;
    private int mirroring = 0; // 0 = Single Screen A, 1 = Single Screen B

    public Mapper7(INesRom rom) {
        this.prg = rom.getPrgRom();
        this.chr = rom.getChrRom();
        // AxROM uses CHR RAM usually (8KB) if no CHR ROM present.
        this.chrRam = (chr.length == 0) ? new byte[0x2000] : null;
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address < 0x8000) return 0;
        
        // 32KB bank at $8000
        int offset = address - 0x8000;
        int bankBase = prgBank * 0x8000;
        int idx = (bankBase + offset) % prg.length;
        return prg[idx] & 0xFF;
    }

    @Override
    public void cpuWrite(int address, int value) {
        address &= 0xFFFF;
        if (address < 0x8000) return;

        // Write to $8000-$FFFF:
        // 7  bit  0
        // ---- ----
        // ...M .PPP
        //    |  |||
        //    |  +++- Select 32 KB PRG ROM bank for CPU $8000-$FFFF
        //    +------ Select 1 KB VRAM page for all 4 nametables
        
        value &= 0xFF;
        prgBank = value & 0x07;
        mirroring = (value & 0x10) >> 4;
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            if (chrRam != null) {
                return chrRam[address] & 0xFF;
            }
            if (chr.length > 0) {
                return chr[address % chr.length] & 0xFF;
            }
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        if (address < 0x2000 && chrRam != null) {
            chrRam[address] = (byte) value;
        }
    }

    @Override
    public MirrorType getMirrorType() {
        return mirroring == 0 ? MirrorType.SINGLE0 : MirrorType.SINGLE1;
    }
}
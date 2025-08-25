package com.nesemu.rom;

import java.util.Arrays;

/**
 * Loaded iNES cartridge (PRG-ROM + CHR-ROM + header).
 */
public class INesRom {
    private final INesHeader header;
    private final byte[] prgRom; // multiples of 16KB
    private final byte[] chrRom; // multiples of 8KB (0 if CHR RAM)
    private final byte[] trainer; // 512 bytes if present

    public INesRom(INesHeader header, byte[] prgRom, byte[] chrRom, byte[] trainer) {
        this.header = header;
        this.prgRom = prgRom; // already copied by loader
        this.chrRom = chrRom;
        this.trainer = trainer;
    }

    public INesHeader getHeader() {
        return header;
    }

    public byte[] getPrgRom() {
        return Arrays.copyOf(prgRom, prgRom.length);
    }

    public byte[] getChrRom() {
        return Arrays.copyOf(chrRom, chrRom.length);
    }

    public byte[] getTrainer() {
        return trainer == null ? null : Arrays.copyOf(trainer, trainer.length);
    }

    /**
     * Builds a 32KB PRG-ROM image for $8000-$FFFF (mirrors if only 16KB present).
     */
    public int[] buildPrgRom32k() {
        int pages = header.getPrgRomPages();
        if (pages <= 0)
            throw new IllegalStateException("Invalid PRG page count");
        if (pages == 1) {
            int[] out = new int[0x8000];
            // copy 16KB into $8000-$BFFF and mirror at $C000-$FFFF
            for (int i = 0; i < 0x4000; i++) {
                int v = prgRom[i] & 0xFF;
                out[i] = v;
                out[i + 0x4000] = v;
            }
            return out;
        } else {
            // first 2 pages (32KB) – extra ignored for simple mapper 0
            // use first 2 pages (32KB) – extra ignored for simple mapper 0
            int[] out = new int[0x8000];
            int copy = Math.min(prgRom.length, 0x8000);
            for (int i = 0; i < copy; i++)
                out[i] = prgRom[i] & 0xFF;
            if (copy < 0x8000) {
                for (int i = copy; i < 0x8000; i++)
                    out[i] = 0;
            }
            return out;
        }
    }
}

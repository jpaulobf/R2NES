package com.nesemu.rom;

import java.util.Arrays;

/**
 * iNES header (16 bytes).
 * Reference: https://www.nesdev.org/wiki/INES
 */
public class INesHeader {
    public static final int HEADER_SIZE = 16;

    private final int prgRomPages;   // número de páginas de 16KB
    private final int chrRomPages;   // número de páginas de 8KB
    private final int flags6;
    private final int flags7;
    private final int mapper;        // computed mapper number (lower mappers only here)
    private final boolean hasTrainer;
    private final boolean batteryBacked;
    private final boolean verticalMirroring; // true = vertical, false = horizontal
    private final boolean nes2;      // formato NES 2.0?

    private final byte[] raw;

    private INesHeader(byte[] raw, int prgRomPages, int chrRomPages, int flags6, int flags7,
                        boolean hasTrainer, boolean batteryBacked, boolean verticalMirroring, boolean nes2) {
        this.raw = Arrays.copyOf(raw, raw.length);
        this.prgRomPages = prgRomPages;
        this.chrRomPages = chrRomPages;
        this.flags6 = flags6;
        this.flags7 = flags7;
        this.hasTrainer = hasTrainer;
        this.batteryBacked = batteryBacked;
        this.verticalMirroring = verticalMirroring;
        this.nes2 = nes2;
        this.mapper = ((flags7 & 0xF0) | (flags6 >> 4)) & 0xFF;
    }

    public static INesHeader parse(byte[] header) {
        if (header.length < HEADER_SIZE)
            throw new IllegalArgumentException("Header muito pequeno");
        if (header[0] != 'N' || header[1] != 'E' || header[2] != 'S' || header[3] != 0x1A)
            throw new IllegalArgumentException("Arquivo não possui assinatura NES\u001A");
        int prgPages = header[4] & 0xFF;
        int chrPages = header[5] & 0xFF;
        int f6 = header[6] & 0xFF;
        int f7 = header[7] & 0xFF;
        boolean trainer = (f6 & 0x04) != 0;
        boolean battery = (f6 & 0x02) != 0;
        boolean mirroring = (f6 & 0x01) != 0; // 0=horizontal,1=vertical
        boolean nes2 = ( (f7 & 0x0C) == 0x08 );
    return new INesHeader(header, prgPages, chrPages, f6, f7, trainer, battery, mirroring, nes2);
    }

    public int getPrgRomPages() { return prgRomPages; }
    public int getChrRomPages() { return chrRomPages; }
    public int getMapper() { return mapper; }
    public boolean hasTrainer() { return hasTrainer; }
    public boolean isBatteryBacked() { return batteryBacked; }
    public boolean isVerticalMirroring() { return verticalMirroring; }
    public boolean isNes2() { return nes2; }
    public byte[] getRaw() { return Arrays.copyOf(raw, raw.length); }
    public int getFlags6() { return flags6; }
    public int getFlags7() { return flags7; }
}

package com.nesemu.rom;

import java.util.Arrays;

/**
 * iNES header (16 bytes).
 * Reference: https://www.nesdev.org/wiki/INES
 */
public class INesHeader {

    // Size of iNES header in bytes
    public static final int HEADER_SIZE = 16;
    private final int prgRomPages; // number of 16KB PRG-ROM pages
    private final int chrRomPages; // number of 8KB CHR-ROM pages
    private final int flags6;
    private final int flags7;
    private final int mapper; // computed mapper number (lower mappers only here)
    private final boolean hasTrainer;
    private final boolean batteryBacked;
    private final boolean verticalMirroring; // true = vertical, false = horizontal
    private final boolean nes2; // NES 2.0 format?
    private final byte[] raw;

    /**
     * Private constructor; use static parse() method.
     * @param raw
     * @param prgRomPages
     * @param chrRomPages
     * @param flags6
     * @param flags7
     * @param hasTrainer
     * @param batteryBacked
     * @param verticalMirroring
     * @param nes2
     */
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

    /**
     * Parses raw header bytes into INesHeader instance.
     * @param header
     * @return
     */
    public static INesHeader parse(byte[] header) {
        if (header.length < HEADER_SIZE)
            throw new IllegalArgumentException("Header too small");
        if (header[0] != 'N' || header[1] != 'E' || header[2] != 'S' || header[3] != 0x1A)
            throw new IllegalArgumentException("File missing NES signature NES\\u001A");
        int prgPages = header[4] & 0xFF;
        int chrPages = header[5] & 0xFF;
        int f6 = header[6] & 0xFF;
        int f7 = header[7] & 0xFF;
        boolean trainer = (f6 & 0x04) != 0;
        boolean battery = (f6 & 0x02) != 0;
        boolean mirroring = (f6 & 0x01) != 0; // 0=horizontal,1=vertical
        boolean nes2 = ((f7 & 0x0C) == 0x08);
        return new INesHeader(header, prgPages, chrPages, f6, f7, trainer, battery, mirroring, nes2);
    }

    /**
     * Gets the number of 16KB PRG ROM pages.
     * @return
     */
    public int getPrgRomPages() {
        return prgRomPages;
    }

    /**
     * Gets the number of 8KB CHR ROM pages.
     * @return
     */
    public int getChrRomPages() {
        return chrRomPages;
    }

    /**
     * Gets the mapper number (0-255).
     * @return
     */
    public int getMapper() {
        return mapper;
    }

    /**
     * Indicates if the ROM has a 512-byte trainer at $7000-$71FF.
     * @return
     */
    public boolean hasTrainer() {
        return hasTrainer;
    }

    /**
     * Indicates if the cartridge has battery-backed PRG RAM.
     * @return
     */
    public boolean isBatteryBacked() {
        return batteryBacked;
    }

    /**
     * Indicates if mirroring is vertical (true) or horizontal (false).
     * @return
     */
    public boolean isVerticalMirroring() {
        return verticalMirroring;
    }

    /**
     * Indicates if the header is in NES 2.0 format.
     * @return
     */
    public boolean isNes2() {
        return nes2;
    }

    /**
     * Gets a copy of the raw header bytes.
     * @return
    */
    public byte[] getRaw() {
        return Arrays.copyOf(raw, raw.length);
    }

    /**
     * Gets flags 6 byte.
     * @return
     */
    public int getFlags6() {
        return flags6;
    }

    /**
     * Gets flags 7 byte.
     * @return
     */
    public int getFlags7() {
        return flags7;
    }
}

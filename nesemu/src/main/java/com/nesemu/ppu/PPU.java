package com.nesemu.ppu;

/**
 * Interface for the NES Picture Processing Unit (PPU).
 * This interface defines methods for resetting the PPU,
 * clocking the PPU, and handling the rendering process.
 */
public interface PPU {
    void reset();
    void clock();
}

package com.nesemu.ppu;

/**
 * Interface for the NES Picture Processing Unit (PPU).
 * This interface defines methods for resetting the PPU,
 * clocking the PPU, and handling the rendering process.
 */
public interface PPU {
    void reset();

    void clock();

    default int[] getFrameBuffer() {
        return new int[0];
    }

    // Optional inspection hooks (default no-op / zero for simple implementations)
    default int getScanline() {
        return 0;
    }

    default int getCycle() {
        return 0;
    }

    default long getFrame() {
        return 0L;
    }

    default int getStatusRegister() {
        return 0;
    }

    default int getMaskRegister() {
        return 0;
    }

    default int getVramAddress() {
        return 0;
    }

    default int getFineX() {
        return 0;
    }
}

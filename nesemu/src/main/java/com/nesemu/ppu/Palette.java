package com.nesemu.ppu;

/**
 * NES master palette + palette RAM logic (32 bytes) with mirroring rules.
 * Provides mapping from PPU palette indices (0..63) to ARGB values.
 * Implements $3F00-$3F1F palette RAM addressing/mirroring.
 */
public class Palette {

    // 64-color master palette (approximate NTSC; values in ARGB 0xFFRRGGBB)
    // Source: widely used canonical NES palette approximation.
    public static final int[] NES_PALETTE = new int[] {
            0xFF545454, 0xFF001E74, 0xFF081090, 0xFF300088, 0xFF440064, 0xFF5C0030, 0xFF540400, 0xFF3C1800,
            0xFF202A00, 0xFF083A00, 0xFF004000, 0xFF003C00, 0xFF00323C, 0xFF000000, 0xFF000000, 0xFF000000,
            0xFF989698, 0xFF084CC4, 0xFF3032EC, 0xFF5C1EE4, 0xFF8814B0, 0xFFA01464, 0xFF982220, 0xFF783C00,
            0xFF545A00, 0xFF287200, 0xFF087C00, 0xFF007628, 0xFF006678, 0xFF000000, 0xFF000000, 0xFF000000,
            0xFFECEEEC, 0xFF4C9AEC, 0xFF787CEC, 0xFFB062EC, 0xFFE454EC, 0xFFEC58B4, 0xFFEC6A64, 0xFFD48820,
            0xFFA0AA00, 0xFF74C400, 0xFF4CD020, 0xFF38CC6C, 0xFF38B4CC, 0xFF3C3C3C, 0xFF000000, 0xFF000000,
            0xFFECEEEC, 0xFFA8CCF0, 0xFFBCBCEC, 0xFFD4B2F0, 0xFFE4A8F0, 0xFFE4A8C8, 0xFFE4B0A4, 0xFFE4C490,
            0xFFCCD278, 0xFFB4DE78, 0xFFA8E290, 0xFF98E2B4, 0xFFA0D6E4, 0xFFA0A2A0, 0xFF000000, 0xFF000000 };

    // 32 bytes palette RAM (background + sprite). We'll store raw 6-bit color
    // indices.
    private final int[] paletteRam = new int[32];

    // Write to palette RAM with mirroring handling.
    public void write(int addr, int value) {
        int index = decodeAddress(addr);
        paletteRam[index] = value & 0x3F; // 6-bit color index (0..63)
        // Handle mirrors of universal background color ($3F00, $3F04, $3F08, $3F0C)
        if (index == 0 || index == 4 || index == 8 || index == 12) {
            // Mirror writes to the corresponding sprite mirror entries (0x10,14,18,1C) &
            // vice-versa
            int mirror = index + 16;
            paletteRam[mirror] = paletteRam[index];
        } else if (index == 16 || index == 20 || index == 24 || index == 28) {
            int base = index - 16;
            paletteRam[base] = paletteRam[index];
        }
    }

    // Read from palette RAM (after mirroring normalization) returns 6-bit color
    // index
    public int read(int addr) {
        int index = decodeAddress(addr);
        return paletteRam[index] & 0x3F;
    }

    // Decode $3F00-$3FFF palette address to 0..31 with mirroring rules
    private int decodeAddress(int addr) {
        addr &= 0x3FFF;
        addr = 0x3F00 | (addr & 0x1F); // fold into 32-byte window
        int index = addr & 0x1F;
        // Addresses 3F10/14/18/1C mirror 3F00/04/08/0C
        if ((index & 0x13) == 0x10) { // bits: xxxx1x0000 with mask picking 0x10 and 0x00/0x04/0x08/0x0C pattern
            index &= ~0x10; // clear bit4 -> mirror to lower half
        }
        return index;
    }

    // Get ARGB color with optional grayscale/emphasis (mask bits passed in)
    public int getArgb(int paletteColorIndex, int mask /* PPUMASK */) {
        paletteColorIndex &= 0x3F;
        int rgb = NES_PALETTE[paletteColorIndex];
        // Apply emphasis first (hardware modifies DAC output before grayscale
        // simplification)
        int emph = (mask >> 5) & 0x07; // bits R,G,B emphasis
        if (emph != 0) {
            final float[][] mul = new float[][] {
                    { 1.00f, 1.00f, 1.00f }, // 000
                    { 1.10f, 0.85f, 0.85f }, // R
                    { 0.90f, 1.10f, 0.85f }, // G
                    { 1.05f, 1.05f, 0.80f }, // R+G
                    { 0.90f, 0.85f, 1.10f }, // B
                    { 1.05f, 0.80f, 1.05f }, // R+B
                    { 0.80f, 1.05f, 1.05f }, // G+B
                    { 0.95f, 0.95f, 0.95f } // R+G+B
            };
            float[] m = mul[emph];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            r = Math.min(255, Math.round(r * m[0]));
            g = Math.min(255, Math.round(g * m[1]));
            b = Math.min(255, Math.round(b * m[2]));
            rgb = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        // Grayscale after emphasis
        if ((mask & 0x01) != 0) {
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int y = (r * 30 + g * 59 + b * 11) / 100;
            rgb = 0xFF000000 | (y << 16) | (y << 8) | y;
        }
        return rgb;
    }

    public int getUniversalBackgroundColor() {
        return paletteRam[0] & 0x3F;
    }

    // Testing helpers
    int debugReadRaw(int index) {
        return paletteRam[index & 0x1F];
    }

    void debugWriteRaw(int index, int value) {
        paletteRam[index & 0x1F] = value & 0x3F;
    }
}

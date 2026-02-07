package com.nesemu.ppu;

/**
 * NES PPU register bit masks (PPUCTRL $2000, PPUMASK $2001, PPUSTATUS $2002).
 * Centralized to avoid magic numbers scattered across implementation & tests.
 */
public final class PpuRegs {

    /**
     * Private constructor to prevent instantiation
     */
    private PpuRegs() {
    }

    // PPUCTRL ($2000)
    public static final int CTRL_NAMETABLE0 = 0x01; // base nametable select bit 0
    public static final int CTRL_NAMETABLE1 = 0x02; // base nametable select bit 1
    public static final int CTRL_VRAM_INC_32 = 0x04; // 1=add 32, 0=add 1
    public static final int CTRL_SPR_TABLE = 0x08; // sprite pattern table (8x8) 0:$0000 1:$1000
    public static final int CTRL_BG_TABLE = 0x10; // background pattern table 0:$0000 1:$1000
    public static final int CTRL_SPR_SIZE_8x16 = 0x20; // 1=8x16 sprites
    public static final int CTRL_MASTER_SLAVE = 0x40; // unused (test mode)
    public static final int CTRL_NMI_ENABLE = 0x80; // generate NMI at vblank start

    // PPUMASK ($2001)
    public static final int MASK_GREYSCALE = 0x01;
    public static final int MASK_BG_LEFT = 0x02; // show background in leftmost 8 pixels
    public static final int MASK_SPR_LEFT = 0x04; // show sprites in leftmost 8 pixels
    public static final int MASK_BG_ENABLE = 0x08; // show background
    public static final int MASK_SPR_ENABLE = 0x10; // show sprites
    public static final int MASK_EMPH_RED = 0x20;
    public static final int MASK_EMPH_GREEN = 0x40;
    public static final int MASK_EMPH_BLUE = 0x80;

    // PPUSTATUS ($2002)
    public static final int STATUS_SPR_OVERFLOW = 0x20;
    public static final int STATUS_SPR0_HIT = 0x40;
    public static final int STATUS_VBLANK = 0x80;
}

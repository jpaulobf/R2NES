package com.nesemu.cpu;

/**
 * Enum representing the various addressing modes used in the NES CPU.
 */
public enum AddressingMode {
    IMMEDIATE,
    ZERO_PAGE,
    ZERO_PAGE_X,
    ZERO_PAGE_Y,
    ABSOLUTE,
    ABSOLUTE_X,
    ABSOLUTE_Y,
    INDIRECT,
    INDIRECT_X,
    INDIRECT_Y,
    ACCUMULATOR,
    IMPLIED,
    RELATIVE
}

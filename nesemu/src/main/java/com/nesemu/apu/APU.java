package com.nesemu.apu;

/**
 * Interface for the Audio Processing Unit (APU) of the NES.
 * This interface defines the basic operations that an APU should implement.
 */
public interface APU {
    void reset();
    void clock();
}
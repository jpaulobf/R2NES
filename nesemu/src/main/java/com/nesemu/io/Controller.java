package com.nesemu.io;

/**
 * Interface representing a controller for the NES emulator.
 * This interface defines methods for reading input from the controller
 * and writing output to it.
 */
public interface Controller {
    int read();
    void write(int value);
}

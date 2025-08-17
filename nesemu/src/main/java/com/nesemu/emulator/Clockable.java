package com.nesemu.emulator;

/**
 * Interface for clockable components in the NES emulator.
 * Defines a method for clocking the component,
 * which is typically called to update the state of the component.
 */
public interface Clockable {
    void clock();
}

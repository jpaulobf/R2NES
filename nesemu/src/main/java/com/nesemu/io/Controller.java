package com.nesemu.io;

import com.nesemu.input.ControllerButton;

/**
 * Interface representing a controller for the NES emulator.
 * This interface defines methods for reading input from the controller
 * and writing output to it.
 */
public interface Controller {
    /**
     * Read next bit of controller shift register (typical NES sequence A,B,Select,Start,Up,Down,Left,Right).
     * Implementations usually shift on each read after a strobe phase.
     * @return bit (0 or 1) in LSB; upper bits should be 0.
     */
    int read();

    /**
     * Write strobe/control value; when bit 0 set then latch current button states; when cleared begin shifting on reads.
     * @param value raw byte (only bit0 normally relevant)
     */
    void write(int value);

    /** 
     * Update a key token (from GUI) 
     */
    void setKeyTokenState(String token, boolean down);

    /** 
     * Programmatic set of a logical button (test convenience). 
     */
    void setLogical(ControllerButton btn, boolean down);

    /**
     * Returns true if logical button currently active (live state, not latched).
     */
    boolean isPressed(ControllerButton b);

    /**
     * Build a compact string of currently pressed NES buttons
     * (A,B,Sel,Start,U,D,L,R).
     */
    String pressedButtonsString();

    /**
     * Get current read bit index (0..8).
     * @return
     */
    int getReadBitIndexDebug();

    /**
     * Get current latched value (for debugging).
     * @return
     */
    int getLatchedValueDebug();

    /**
     * Get latched bits as string (for debugging).
     * @return
     */
    String getLatchedBitsString();
}

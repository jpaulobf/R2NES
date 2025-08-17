package com.nesemu.io;

/**
 * Class representing the NES controller.
 * This class implements the Controller interface and manages the state of the NES joypad.
 * It will handle reading input from the joypad and writing output to it.
 * The implementation will include methods for updating the joypad state,
 * reading button presses, and managing the controller's internal state.
 */
public class NesController implements Controller {
    // TODO: implementação dos botões e leitura
    @Override
    public int read() { return 0; }
    @Override
    public void write(int value) {}
}
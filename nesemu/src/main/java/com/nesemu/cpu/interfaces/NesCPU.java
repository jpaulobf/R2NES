package com.nesemu.cpu.interfaces;

import com.nesemu.emulator.Clockable;

/**
 * Interface for the CPU of the NES emulator.
 * This interface defines the basic operations that a CPU should implement.
 */
public interface NesCPU extends Clockable {
    void reset();
    void clock();
    void nmi();
    void irq();


    public int getA();

    public int getX();

    public int getY();

    public int getSP();

    public int getPC();

    public int getLastInstrPC();

    public boolean isInstructionBoundary();

    public int getStatusByte();

    public void forceState(int pc, int a, int x, int y, int p, int sp);

    public void stepInstruction();

    

}
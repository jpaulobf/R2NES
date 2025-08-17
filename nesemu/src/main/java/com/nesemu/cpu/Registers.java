package com.nesemu.cpu;

/**
 * Class representing the registers of the NES CPU.
 * Contains the 8-bit accumulator (A), index registers (X, Y),
 * stack pointer (SP), program counter (PC), and processor status flags (P).
 * This class is used to manage the state of the CPU during execution.
 * Each register is represented as an integer, with the processor status flags
 * stored in a single byte.
  */
public class Registers {
    public int A;    // 8-bit accumulator
    public int X;    // 8-bit index X
    public int Y;    // 8-bit index Y
    public int SP;   // 8-bit stack pointer
    public int PC;   // 16-bit program counter
    public int P;    // 8-bit processor status flags
}

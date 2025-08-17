package com.nesemu.cpu.interfaces;

public interface iCPU {
    void reset();
    void clock();
    void nmi();
    void irq();
}
package com.nesemu.input;

/** NES controller logical buttons (bit order matches standard shift order). */
public enum ControllerButton {
    A, B, SELECT, START, UP, DOWN, LEFT, RIGHT;

    /**
     * Return bit index in standard NES read sequence
     * (A,B,Select,Start,Up,Down,Left,Right).
     */
    public int bitIndex() {
        return switch (this) {
            case A -> 0;
            case B -> 1;
            case SELECT -> 2;
            case START -> 3;
            case UP -> 4;
            case DOWN -> 5;
            case LEFT -> 6;
            case RIGHT -> 7;
        };
    }
}

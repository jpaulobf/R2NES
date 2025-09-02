package com.nesemu.io;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.nesemu.input.ControllerButton;
import com.nesemu.input.ControllerConfig;

/**
 * Class representing the NES controller.
 * This class implements the Controller interface and manages the state of the
 * NES joypad.
 * It will handle reading input from the joypad and writing output to it.
 * The implementation will include methods for updating the joypad state,
 * reading button presses, and managing the controller's internal state.
 */
public class NesController implements Controller {
    private final ControllerConfig config;
    // Live key token pressed state
    private final Set<String> pressed = ConcurrentHashMap.newKeySet();
    // Latched shift register (8 bits A..Right)
    private int shiftRegister = 0;
    private boolean strobe = false;
    // Direct logical button overrides (for programmatic tests)
    private final Map<ControllerButton, Boolean> logicalState = new EnumMap<>(ControllerButton.class);

    public NesController(ControllerConfig config) {
        this.config = config;
    }

    /** Update a key token (from GUI) */
    public void setKeyTokenState(String token, boolean down) {
        if (token == null)
            return;
        token = token.toLowerCase();
        if (down)
            pressed.add(token);
        else
            pressed.remove(token);
        if (strobe)
            latch();
    }

    /** Programmatic set of a logical button (test convenience). */
    public void setLogical(ControllerButton btn, boolean down) {
        logicalState.put(btn, down);
        if (strobe)
            latch();
    }

    private boolean isButtonActive(ControllerButton btn) {
        // If logical override present, use it; else check any token pressed
        Boolean ov = logicalState.get(btn);
        if (ov != null)
            return ov.booleanValue();
        for (String token : config.getTokens(btn)) {
            if (pressed.contains(token))
                return true;
        }
        return false;
    }

    private void latch() {
        int v = 0;
        for (ControllerButton b : ControllerButton.values()) {
            if (isButtonActive(b))
                v |= (1 << b.bitIndex());
        }
        shiftRegister = v;
    }

    @Override
    public void write(int value) {
        boolean newStrobe = (value & 1) != 0;
        if (newStrobe) {
            // While high, continually latch
            strobe = true;
            latch();
        } else if (strobe && !newStrobe) {
            // Transition 1->0 locks current state, then shifting begins on reads
            strobe = false;
        }
    }

    @Override
    public int read() {
        int ret = shiftRegister & 1;
        if (!strobe) {
            // Shift only after strobe released
            shiftRegister = ((shiftRegister >> 1) | 0x80); // fill with 1s after 8 bits (hardware behavior
                                                           // approximation)
        }
        return ret;
    }

    /**
     * Returns true if logical button currently active (live state, not latched).
     */
    public boolean isPressed(ControllerButton b) {
        return isButtonActive(b);
    }

    /**
     * Build a compact string of currently pressed NES buttons
     * (A,B,Sel,Start,U,D,L,R).
     */
    public String pressedButtonsString() {
        StringBuilder sb = new StringBuilder();
        appendIf(sb, ControllerButton.A, "A");
        appendIf(sb, ControllerButton.B, "B");
        appendIf(sb, ControllerButton.SELECT, "Select");
        appendIf(sb, ControllerButton.START, "Start");
        appendIf(sb, ControllerButton.UP, "Up");
        appendIf(sb, ControllerButton.DOWN, "Down");
        appendIf(sb, ControllerButton.LEFT, "Left");
        appendIf(sb, ControllerButton.RIGHT, "Right");
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private void appendIf(StringBuilder sb, ControllerButton b, String label) {
        if (isPressed(b)) {
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(label);
        }
    }
}

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

    // Configuration mapping buttons to key tokens
    private final ControllerConfig config;

    // Live key token pressed state
    private final Set<String> pressed = ConcurrentHashMap.newKeySet();
    private boolean strobe = false; // current strobe line (bit0 of last write)
    private int readBitIndex = 0; // 0..8 (#bits already returned in current snapshot)
    private int latchedValue = 0; // snapshot of buttons (A..Right bits 0..7)

    // Direct logical button overrides (for programmatic tests)
    private final Map<ControllerButton, Boolean> logicalState = new EnumMap<>(ControllerButton.class);

    // Turbo support
    private boolean turboFast = false; // false=15Hz (ON2/OFF2), true=30Hz (ON1/OFF1)
    private long frameCounter = 0; // increments each frame

    /**
     * Constructor for NesController.
     * 
     * @param config
     */
    public NesController(ControllerConfig config) {
        this.config = config;
    }

    @Override
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

    @Override
    public void setLogical(ControllerButton btn, boolean down) {
        logicalState.put(btn, down);
        if (strobe)
            latch();
    }

    @Override
    public void write(int value) {
        boolean newStrobe = (value & 1) != 0;
        if (newStrobe) {
            // Strobe high: keep re-latching continuously; reads should always return A bit.
            if (!strobe) {
                // rising edge resets index
                readBitIndex = 0;
            }
            strobe = true;
            latch();
        } else {
            if (strobe) {
                // Falling edge: latch once and prepare to shift bits out
                latch();
                readBitIndex = 0;
            }
            strobe = false;
        }
    }

    @Override
    public int read() {
        if (strobe) {
            // While strobe high, always return current A (bit0) ignoring index
            latch(); // hardware re-latches each read effectively
            return latchedValue & 1;
        }
        int ret;
        if (readBitIndex < 8) {
            ret = (latchedValue >> readBitIndex) & 1;
            readBitIndex++;
        } else {
            // After 8 reads hardware returns 1
            ret = 1;
        }
        return ret;
    }

    @Override
    public boolean isPressed(ControllerButton b) {
        return isButtonActive(b);
    }

    @Override
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

    @Override
    public int getReadBitIndexDebug() {
        return readBitIndex;
    }

    @Override
    public int getLatchedValueDebug() {
        return latchedValue;
    }

    @Override
    public String getLatchedBitsString() {
        int v = latchedValue;
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++)
            sb.append(((v >> i) & 1)); // LSB first A..Right
        return sb.toString();
    }

    /**
     * Clear any logical override (back to token/key state).
     */
    private boolean isButtonActive(ControllerButton btn) {
        // If logical override present, use it (overrides turbo too)
        Boolean ov = logicalState.get(btn);
        if (ov != null)
            return ov.booleanValue();
        // Turbo applies only to A/B when at least one turbo token is pressed
        if ((btn == ControllerButton.A || btn == ControllerButton.B) && hasTurboTokenPressed(btn)) {
            return computeTurboOn();
        }
        // Normal tokens
        for (String token : config.getTokens(btn)) {
            if (pressed.contains(token))
                return true;
        }
        return false;
    }

    /**
     * Latch current button states into latchedValue.
     */
    private void latch() {
        int v = 0;
        for (ControllerButton b : ControllerButton.values()) {
            if (isButtonActive(b))
                v |= (1 << b.bitIndex());
        }
        latchedValue = v;
    }

    private boolean hasTurboTokenPressed(ControllerButton btn) {
        for (String tok : config.getTurboTokens(btn)) {
            if (pressed.contains(tok))
                return true;
        }
        return false;
    }

    private boolean computeTurboOn() {
        if (turboFast) {
            // 30Hz: ON 1 frame, OFF 1 frame (even frames ON)
            return (frameCounter & 1L) == 0L;
        } else {
            // 15Hz: ON 2 frames, OFF 2 frames (pattern length 4)
            long m = frameCounter & 3L; // modulo 4
            return (m == 0L || m == 1L);
        }
    }

    /** Enable/disable fast turbo (30Hz vs 15Hz). */
    public void setTurboFast(boolean fast) {
        this.turboFast = fast;
    }

    @Override
    public void onFrameAdvance() {
        frameCounter++;
    }

    /**
     * Helper to append button label if pressed.
     * 
     * @param sb
     * @param b
     * @param label
     */
    private void appendIf(StringBuilder sb, ControllerButton b, String label) {
        if (isPressed(b)) {
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(label);
        }
    }
}

package com.nesemu.input;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.util.EnumMap;
import java.util.Map;

import org.lwjgl.glfw.GLFWGamepadState;

import com.nesemu.io.NesController;

/**
 * Minimal GLFW-based gamepad poller using LWJGL 3.
 * - Creates a hidden GLFW window (required to init GLFW on some platforms).
 * - Polls the first present gamepad and maps common buttons/axes to NES buttons.
 * - Intended as an optional helper; caller controls lifecycle via start/stop.
 */
public class GamepadPoller implements Runnable {

    // GLFW state
    private final NesController controller;
    private final Thread thread;
    private volatile boolean running = false;
    private long window = NULL;
    private boolean glfwInitialized = false;

    // Simple mapping state
    private final Map<ControllerButton, Boolean> lastState = new EnumMap<>(ControllerButton.class);

    // Helpers to convert axis value to button state    
    private int axisPos(float v) { return v > 0.5f ? 1 : 0; }
    private int axisNeg(float v) { return v < -0.5f ? 1 : 0; }

    /**
     * Create a new poller for given controller.
     * @param controller
     */
    public GamepadPoller(NesController controller) {
        this.controller = controller;
        this.thread = new Thread(this, "NES-Gamepad");
        this.thread.setDaemon(true);
    }

    /**
     * Start polling thread (no-op if already running).
     */
    public synchronized void start() {
        if (running) return;
        if (!glfwInit()) {
            System.err.println("GLFW init failed: gamepad disabled");
            return;
        }
        glfwInitialized = true;
        // Create a tiny hidden window (some drivers require a window/context present)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        window = glfwCreateWindow(32, 32, "", NULL, NULL);
        if (window == NULL) {
            System.err.println("Failed to create hidden GLFW window");
            if (glfwInitialized) glfwTerminate();
            return;
        }
        running = true;
        thread.start();
    }

    /**
     * Stop polling thread (no-op if not running).
     */
    public synchronized void stop() {
        running = false;
        try { thread.join(200); } catch (InterruptedException ignore) {}
    if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }
    if (glfwInitialized) glfwTerminate();
    glfwInitialized = false;
    }

    @Override
    public void run() {
        while (running) {
        // Let GLFW process events (required on some platforms)
        glfwPollEvents();

        // Pick first present joystick/gamepad
            int jid = -1;
            for (int i = GLFW_JOYSTICK_1; i <= GLFW_JOYSTICK_LAST; i++) {
                if (glfwJoystickIsGamepad(i)) { jid = i; break; }
            }
            if (jid != -1) {
                GLFWGamepadState state = GLFWGamepadState.create();
                if (glfwGetGamepadState(jid, state)) {
                    // Map buttons
            setButton(ControllerButton.A, state.buttons(GLFW_GAMEPAD_BUTTON_A));
            setButton(ControllerButton.B, state.buttons(GLFW_GAMEPAD_BUTTON_X));
                    setButton(ControllerButton.START, state.buttons(GLFW_GAMEPAD_BUTTON_START));
                    setButton(ControllerButton.SELECT, state.buttons(GLFW_GAMEPAD_BUTTON_BACK));
                    // D-pad (prefer dpad; fallback to left stick if needed)
                    setButton(ControllerButton.UP,   state.buttons(GLFW_GAMEPAD_BUTTON_DPAD_UP)   |
                                                     axisNeg(state.axes(GLFW_GAMEPAD_AXIS_LEFT_Y)));
                    setButton(ControllerButton.DOWN, state.buttons(GLFW_GAMEPAD_BUTTON_DPAD_DOWN) |
                                                     axisPos(state.axes(GLFW_GAMEPAD_AXIS_LEFT_Y)));
                    setButton(ControllerButton.LEFT, state.buttons(GLFW_GAMEPAD_BUTTON_DPAD_LEFT) |
                                                     axisNeg(state.axes(GLFW_GAMEPAD_AXIS_LEFT_X)));
                    setButton(ControllerButton.RIGHT,state.buttons(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT)|
                                                     axisPos(state.axes(GLFW_GAMEPAD_AXIS_LEFT_X)));
                }
            }
            try { Thread.sleep(8); } catch (InterruptedException ignore) {}
        }
    }

    /**
     * Convert axis value to "positive" button state (1 if > 0.5, else 0).
     * @param btn
     * @param downInt
     */
    private void setButton(ControllerButton btn, int downInt) {
        boolean down = downInt != 0;
        Boolean prev = lastState.put(btn, down);
        if (prev == null || prev.booleanValue() != down) {
            controller.setLogical(btn, down);
        }
    }
}
package com.nesemu.gui;

import java.awt.event.KeyEvent;

/**
 * Utility class for converting KeyEvent instances to string tokens
 */
public final class KeyTokens {

    /**
     * Private constructor to prevent instantiation
     */
    private KeyTokens() {
    }

    /**
     * Convert a KeyEvent to its corresponding string token.
     * @param e
     * @return
     */
    public static String from(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.VK_UP:
                return "up";
            case KeyEvent.VK_DOWN:
                return "down";
            case KeyEvent.VK_LEFT:
                return "left";
            case KeyEvent.VK_RIGHT:
                return "right";
            case KeyEvent.VK_ENTER:
                return "enter";
            case KeyEvent.VK_BACK_SPACE:
                return "backspace";
            case KeyEvent.VK_SPACE:
                return "space";
            case KeyEvent.VK_ESCAPE:
                return "escape";
            case KeyEvent.VK_PAUSE:
                return "pause";
            case KeyEvent.VK_TAB:
                return "tab";
            case KeyEvent.VK_F1:
                return "f1";
            case KeyEvent.VK_F2:
                return "f2";
            case KeyEvent.VK_F3:
                return "f3";
            case KeyEvent.VK_F4:
                return "f4";
            case KeyEvent.VK_F5:
                return "f5";
            case KeyEvent.VK_F6:
                return "f6";
            case KeyEvent.VK_F7:
                return "f7";
            case KeyEvent.VK_F8:
                return "f8";
            case KeyEvent.VK_F9:
                return "f9";
            case KeyEvent.VK_F10:
                return "f10";
            case KeyEvent.VK_F11:
                return "f11";
            case KeyEvent.VK_F12:
                return "f12";
            case KeyEvent.VK_CONTROL: {
                int loc = e.getKeyLocation();
                if (loc == KeyEvent.KEY_LOCATION_LEFT)
                    return "lcontrol";
                if (loc == KeyEvent.KEY_LOCATION_RIGHT)
                    return "rcontrol";
                return "control";
            }
            case KeyEvent.VK_SHIFT: {
                int loc = e.getKeyLocation();
                if (loc == KeyEvent.KEY_LOCATION_LEFT)
                    return "lshift";
                if (loc == KeyEvent.KEY_LOCATION_RIGHT)
                    return "rshift";
                return "shift";
            }
            default:
                char ch = e.getKeyChar();
                if (Character.isLetterOrDigit(ch))
                    return String.valueOf(Character.toLowerCase(ch));
                return null;
        }
    }
}

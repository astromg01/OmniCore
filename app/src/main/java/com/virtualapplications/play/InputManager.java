package com.virtualapplications.play;

/**
 * Minimal OmniCore ABI declaration for the pinned Play! input JNI surface.
 * No Play! Android UI code is embedded here.
 */
public final class InputManager {
    static {
        System.loadLibrary("Play");
    }

    private InputManager() {}

    public static native void setButtonState(int controlId, boolean pressed);
    public static native void setAxisState(int controlId, float value);
}

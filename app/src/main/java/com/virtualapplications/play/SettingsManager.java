package com.virtualapplications.play;

/** Minimal OmniCore ABI declaration for Play!'s persisted native preferences. */
public final class SettingsManager {
    static {
        System.loadLibrary("Play");
    }

    private SettingsManager() {}

    public static native void save();
    public static native void registerPreferenceBoolean(String name, boolean defaultValue);
    public static native boolean getPreferenceBoolean(String name);
    public static native void setPreferenceBoolean(String name, boolean value);
    public static native int getPreferenceInteger(String name);
    public static native void setPreferenceInteger(String name, int value);
}

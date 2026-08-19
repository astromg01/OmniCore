package com.virtualapplications.play;

/** Minimal OmniCore ABI declaration for Play!'s existing runtime counters. */
public final class StatsManager {
    static {
        System.loadLibrary("Play");
    }

    private StatsManager() {}

    public static native int getFrames();
    public static native int getDrawCalls();
    public static native void clearStats();
    public static native boolean isProfiling();
    public static native String getProfilingInfo();
}

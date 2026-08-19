package com.virtualapplications.play;

import android.content.ContentResolver;
import android.content.res.AssetManager;
import android.view.Surface;

/**
 * Minimal ABI shim for the pinned Play! Android JNI backend.
 *
 * OmniCore deliberately does not embed Play!'s Android UI. Only the native
 * lifecycle entry points required by the isolated PS2 adapter are exposed.
 */
public final class NativeInterop {
    static {
        System.loadLibrary("Play");
    }

    private NativeInterop() {}

    public static native void setFilesDirPath(String dirPath);
    public static native void setCacheDirPath(String dirPath);
    public static native void setContentResolver(ContentResolver contentResolver);
    public static native void setAssetManager(AssetManager assetManager);
    public static native void createVirtualMachine();
    public static native boolean isVirtualMachineCreated();
    public static native void resumeVirtualMachine();
    public static native void pauseVirtualMachine();
    public static native void loadState(int slot);
    public static native void saveState(int slot);
    public static native void loadElf(String selectedFilePath);
    public static native void bootDiskImage(String selectedFilePath);
    public static native void setupGsHandler(Surface surface);
    public static native void notifyPreferencesChanged();
}

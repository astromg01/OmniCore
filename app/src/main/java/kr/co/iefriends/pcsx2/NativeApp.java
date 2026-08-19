package kr.co.iefriends.pcsx2;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * Minimal OmniCore Java ABI shim for the pinned ARMSX2/PCSX2 Android emucore.
 *
 * The native core exports JNI symbols against this historical package/class
 * name. OmniCore intentionally implements only the stable lifecycle, surface,
 * input, BIOS/settings and telemetry pieces required by PS2Backend; the ARMSX2
 * application UI is not embedded.
 */
public final class NativeApp {
    private NativeApp() {}

    private static final String TAG = "OmniCorePCSX2";
    private static volatile Context appContext;
    public static volatile boolean hasNoNativeBinary = true;
    public static volatile String nativeLoadError = "";
    public static volatile String selectedNativeLibrary = "";
    public static volatile long selectedPageSize = 4096L;

    static {
        selectedPageSize = runtimePageSize();
        selectedNativeLibrary = selectedPageSize >= 16384L ? "emucore_16k" : "emucore_4k";
        try {
            System.loadLibrary(selectedNativeLibrary);
            hasNoNativeBinary = false;
            nativeLoadError = "";
            Log.i(TAG, "PCSX2 native core loaded: " + selectedNativeLibrary
                    + " pageSize=" + selectedPageSize
                    + " abis=" + Arrays.toString(Build.SUPPORTED_ABIS));
        } catch (Throwable error) {
            hasNoNativeBinary = true;
            String message = error.getMessage();
            nativeLoadError = error.getClass().getSimpleName()
                    + ((message == null || message.isEmpty()) ? "" : ": " + message);
            Log.e(TAG, "PCSX2 native core load failed: library=" + selectedNativeLibrary
                    + " pageSize=" + selectedPageSize
                    + " abis=" + Arrays.toString(Build.SUPPORTED_ABIS)
                    + " error=" + nativeLoadError, error);
            System.err.println("OMNICORE_PCSX2_LOAD_FAILED library=" + selectedNativeLibrary
                    + " pageSize=" + selectedPageSize
                    + " abis=" + Arrays.toString(Build.SUPPORTED_ABIS)
                    + " error=" + nativeLoadError);
        }
    }

    private static long runtimePageSize() {
        try {
            long value = Os.sysconf(OsConstants._SC_PAGESIZE);
            return value > 0 ? value : 4096L;
        } catch (Throwable ignored) {
            return 4096L;
        }
    }

    private static String selectNativeLibraryName() {
        return runtimePageSize() >= 16384L ? "emucore_16k" : "emucore_4k";
    }

    public static String nativeLoadDiagnostic() {
        if (!hasNoNativeBinary) {
            return "loaded " + selectedNativeLibrary + " (pageSize=" + selectedPageSize + ")";
        }
        return "failed " + selectedNativeLibrary + " (pageSize=" + selectedPageSize + "): "
                + (nativeLoadError.isEmpty() ? "unknown linker error" : nativeLoadError);
    }

    public static void bindContext(Context context) {
        appContext = context.getApplicationContext();
    }

    public static native void initialize(String dataPath, String biosFolder, int apiVersion);
    public static native void setSetting(String section, String key, String type, String value);
    public static native void commitSettings();
    public static native void setAdpfEnabled(boolean enabled);
    public static native void setAffinityMode(int mode);

    public static native void onNativeSurfaceCreated();
    public static native void onNativeSurfaceChanged(Surface surface, int width, int height);
    public static native void onNativeSurfaceDestroyed();
    public static native void setDisplayRefreshRate(float hz);

    public static native boolean runVMThread(String path);
    public static native void pause();
    public static native void resume();
    public static native void shutdown();
    public static native boolean hasActiveVM();

    public static native void renderAuto();
    public static native void renderOpenGL();
    public static native void renderVulkan();
    public static native void renderUpscalemultiplier(float value);
    public static native void speedhackLimitermode(int value);

    public static native void setPadButton(int index, int range, boolean pressed);
    public static native void resetKeyStatus();

    public static native float getFPS();
    public static native boolean saveStateToSlot(int slot);
    public static native boolean loadStateFromSlot(int slot);

    // Methods below are callbacks/fallbacks looked up from native code during init.
    // They deliberately stay tiny so the emulator core remains behind PS2Backend.
    public static void vmSetPaused(boolean paused) {
        // OmniCore owns lifecycle state; this callback only satisfies the core ABI.
    }

    public static void onPadRumble(int pad, int largeMotor, int smallMotor) {
        // Controller rumble will be wired after the first PCSX2 device baseline.
    }

    public static void playSound(String path) {
        // RetroAchievements/UI sounds are outside the PS2 foundation scope.
    }

    public static int openContentUri(String uriString) {
        Context context = appContext;
        if (context == null || uriString == null || uriString.isEmpty()) return -1;
        try {
            ContentResolver resolver = context.getContentResolver();
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(Uri.parse(uriString), "r");
            return pfd != null ? pfd.detachFd() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static boolean createDirectoryPath(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            File dir = new File(path);
            return dir.isDirectory() || (dir.mkdirs() && dir.isDirectory());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean createFilePath(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            File file = new File(path);
            if (file.isFile()) return true;
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            return file.createNewFile() || file.isFile();
        } catch (IOException ignored) {
            return false;
        }
    }
}

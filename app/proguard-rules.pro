# OmniCore optimized device-test rules.
#
# Native libraries resolve many Java/Kotlin entry points by their exact JNI
# class/method names. R8 may optimize the UI freely, but emulator ABI boundaries
# must keep stable names and signatures.

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep class kr.co.iefriends.pcsx2.NativeApp { *; }
-keep class org.libsdl.app.** { *; }
-keep class com.virtualapplications.play.** { *; }
-keep class com.omnicore.emulator.core.** { *; }

# Activities/services are also referenced by Android manifests and system
# callbacks. The Android Gradle plugin normally seeds these automatically; this
# explicit rule keeps device-test shrinking conservative.
-keep class com.omnicore.emulator.MainActivity { *; }
-keep class com.omnicore.emulator.emulation.**Activity { *; }

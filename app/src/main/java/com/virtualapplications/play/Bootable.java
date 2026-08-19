package com.virtualapplications.play;

/**
 * JNI layout shim required by Play!'s JNI_OnLoad.
 * Field names and JVM types intentionally match the pinned backend ABI.
 */
public final class Bootable {
    String path;
    String discId;
    public String title;
    String coverUrl;
    int lastBootedTime;
    public String overview;

    public Bootable() {}
}

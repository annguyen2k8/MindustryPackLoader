package packloader.core;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import packloader.model.PackEntry;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static packloader.util.Localization.getLocalizedText;

/**
 * Handles Android Share and Open-with stream integration, and documents the
 * Android platform boundary constraints.
 *
 * <p><b>Android Platform Limitation (Architectural Blocker):</b><br>
 * Android system-wide "Share" ({@code ACTION_SEND}, {@code ACTION_SEND_MULTIPLE}) and "Open-with"
 * ({@code ACTION_VIEW}) choosers are resolved by Android OS {@code PackageManager} at intent dispatch time.
 * Under Android OS security architecture, an application can ONLY appear in the system chooser if its
 * {@code AndroidManifest.xml} declares static {@code <intent-filter>} tags for the target MIME types or
 * URI schemes at install time.
 *
 * <p>A mod distributed as a dynamic DEX/JAR inside Mindustry cannot inject or register an Activity intent-filter
 * into the host package manifest at runtime. Therefore, receiving external {@code ACTION_SEND} or
 * {@code ACTION_VIEW} intents from other Android apps requires an upstream modification to Mindustry's
 * {@code android/AndroidManifest.xml} and {@code AndroidLauncher.java}.
 *
 * <p>This class provides safe, bounded streaming ingestion for Android ContentProvider / SAF streams
 * without inflating large files into heap RAM.
 */
public final class AndroidShareHandler{
    public static final String MANIFEST_BLOCKER_EXPLANATION =
        "Android ACTION_SEND and ACTION_VIEW require static <intent-filter> entries in the host APK's " +
        "AndroidManifest.xml. Dynamic mod JARs cannot register Activity intent filters at runtime.";

    private static final int BUFFER_SIZE = 8192;

    private AndroidShareHandler(){}

    /**
     * Safely reads an incoming shared stream (e.g. from an Android ContentProvider or SAF URI)
     * into a temporary cached file in chunks, strictly enforcing a maximum byte limit to protect
     * against heap out-of-memory errors and decompression bombs.
     *
     * @param in the source input stream (caller is responsible for closing, or it will be consumed here)
     * @param targetFileName desired sanitized filename
     * @param maxBytes maximum permitted bytes
     * @return the materialized temporary {@link Fi} ready for scanner ingestion
     * @throws IOException if an I/O error occurs or if the stream exceeds maxBytes
     */
    public static Fi resolveSharedStream(InputStream in, String targetFileName, long maxBytes) throws IOException{
        if(in == null){
            throw new IllegalArgumentException("Input stream cannot be null");
        }
        if(targetFileName == null || targetFileName.trim().isEmpty()){
            targetFileName = "shared_import.tmp";
        }

        Fi tempDir = Core.files != null ?
            Core.files.cache("packloader_shared") :
            new Fi(new java.io.File(System.getProperty("java.io.tmpdir"), "packloader_shared"));

        tempDir.mkdirs();

        // Unique filename to avoid collision
        String sanitized = PackEntry.sanitizeName(targetFileName);
        String prefix = Long.toString(System.currentTimeMillis()) + "_" + Math.round(Math.random() * 10000) + "_";
        Fi dest = tempDir.child(prefix + sanitized);

        long totalWritten = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];

        try(BufferedInputStream bin = new BufferedInputStream(in);
            OutputStream out = dest.write(false, BUFFER_SIZE)){
            int read;
            while((read = bin.read(buffer)) != -1){
                totalWritten += read;
                if(maxBytes > 0 && totalWritten > maxBytes){
                    dest.delete();
                    throw new IOException(getLocalizedText("packloader.error.zip-too-large", maxBytes / (1024 * 1024)));
                }
                out.write(buffer, 0, read);
            }
            out.flush();
        }catch(Throwable t){
            if(dest.exists()) dest.delete();
            if(t instanceof SecurityException){
                Log.err("[PackLoader] Permission denied reading shared stream: @", targetFileName);
                throw new IOException(getLocalizedText("packloader.android.share.permission-denied", targetFileName), t);
            }
            if(t instanceof IOException) throw (IOException)t;
            throw new IOException("Failed to ingest shared stream: " + t.getMessage(), t);
        }

        return dest;
    }

    /** Cleans up any cached temporary files from previous shared stream ingestions. */
    public static void cleanupCache(){
        try{
            Fi tempDir = Core.files != null ?
                Core.files.cache("packloader_shared") :
                new Fi(new java.io.File(System.getProperty("java.io.tmpdir"), "packloader_shared"));
            if(tempDir.exists()){
                tempDir.deleteDirectory();
            }
        }catch(Throwable ignored){}
    }
}

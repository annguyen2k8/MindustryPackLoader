package packloader.util;

import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Strings;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Platform-specific storage root discovery, display names, and Android SAF
 * virtual URI path resolution.
 *
 * <p>Windows volume labels are queried from the OS through Swing's
 * {@code FileSystemView}. The reference is reflective so Android builds never
 * need to resolve a desktop-only class.</p>
 */
public final class StorageSources{
    private static final String WINDOWS = "windows";
    private static final String PRIMARY_TAG = "primary:";
    private static final String RAW_TAG = "raw:";

    private StorageSources(){}

    /** Returns whether the game is running on Windows. */
    public static boolean isWindows(){
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains(WINDOWS);
    }

    /**
     * Detects whether a path string originated from an Android Storage Access Framework (SAF)
     * virtual URI rather than a standard POSIX filesystem path.
     */
    public static boolean isVirtualSafPath(String path){
        if(path == null || path.isEmpty()) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith("/document/")
            || lower.startsWith("/tree/")
            || lower.startsWith("content://")
            || lower.contains("primary:")
            || lower.contains("primary%3a")
            || lower.contains("raw:")
            || lower.contains("raw%3a");
    }

    /**
     * Checks whether a physical file can actually be opened and read via direct POSIX FileInputStream.
     * On Android 11+ with Scoped Storage, File.exists() and File.canRead() can return true while
     * opening an actual FileInputStream fails with EACCES (Permission denied).
     *
     * @param fi file handle to verify
     * @return true if the file exists and a direct InputStream can actually be opened, false otherwise
     */
    public static boolean canReadDirectly(Fi fi){
        if(fi == null) return false;
        try{
            File f = fi.file();
            if(f == null || !f.exists()) return false;
            if(f.isDirectory()) return f.canRead();
            try(java.io.InputStream in = new java.io.FileInputStream(f)){
                return true;
            }
        }catch(Throwable ignored){
            return false;
        }
    }

    /**
     * Checks whether a physical file path can actually be opened and read via direct POSIX FileInputStream.
     */
    public static boolean canReadDirectly(String path){
        if(path == null || path.trim().isEmpty()) return false;
        try{
            Fi fi = Core.files != null ? Core.files.absolute(path) : new Fi(path);
            return canReadDirectly(fi);
        }catch(Throwable ignored){
            return false;
        }
    }

    /**
     * Resolves a path (which may be a direct filesystem path, an Android SAF virtual document path,
     * or a percent-encoded content URI) into a physical {@link Fi} that exists on disk and can
     * actually be read directly via POSIX streaming.
     *
     * @param path file path or SAF virtual URI string
     * @return existing and readable {@link Fi} if found on disk, or null if resolution fails
     */
    public static Fi resolveFi(String path){
        if(path == null || path.trim().isEmpty()) return null;
        String clean = path.trim();

        // 1. Direct check: if already a valid local file that can actually be read directly, return immediately
        try{
            Fi direct = Core.files != null ? Core.files.absolute(clean) : new Fi(clean);
            if(canReadDirectly(direct)){
                return direct;
            }
        }catch(Throwable ignored){}

        // 2. Decode percent-encoding (e.g. %3A -> :, %2F -> /)
        String decoded = clean;
        if(decoded.contains("%")){
            try{
                decoded = java.net.URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            }catch(Throwable ignored){}
        }

        // 3. Strip file:// scheme if present
        if(decoded.startsWith("file://")){
            decoded = decoded.substring(7);
        }

        // 4. Re-check direct path after URL decoding
        try{
            Fi directDecoded = Core.files != null ? Core.files.absolute(decoded) : new Fi(decoded);
            if(canReadDirectly(directDecoded)){
                return directDecoded;
            }
        }catch(Throwable ignored){}

        // 5. Check raw: prefix (e.g. raw:/storage/emulated/0/... or /document/raw:/storage/...)
        int rawIdx = decoded.indexOf(RAW_TAG);
        if(rawIdx != -1){
            String rawPath = decoded.substring(rawIdx + RAW_TAG.length()).trim();
            try{
                Fi rawFi = Core.files != null ? Core.files.absolute(rawPath) : new Fi(rawPath);
                if(canReadDirectly(rawFi)){
                    return rawFi;
                }
            }catch(Throwable ignored){}
        }

        // 6. Check primary: prefix (Android primary external storage)
        int primaryIdx = decoded.indexOf(PRIMARY_TAG);
        if(primaryIdx != -1){
            String rel = decoded.substring(primaryIdx + PRIMARY_TAG.length()).trim();
            while(rel.startsWith("/")) rel = rel.substring(1);
            while(rel.startsWith("\\")) rel = rel.substring(1);

            Seq<String> candidateRoots = getAndroidStorageRoots();
            for(String root : candidateRoots){
                if(root == null || root.isEmpty()) continue;
                try{
                    File f = rel.isEmpty() ? new File(root) : new File(root, rel);
                    Fi candidate = Core.files != null ? Core.files.absolute(f.getAbsolutePath()) : new Fi(f);
                    if(canReadDirectly(candidate)){
                        return candidate;
                    }
                    // Fallback: check inside Download/ subdirectory if rel doesn't already specify it
                    if(!rel.isEmpty() && !rel.toLowerCase(Locale.ROOT).startsWith("download")){
                        File dl = new File(new File(root, "Download"), rel);
                        Fi dlCandidate = Core.files != null ? Core.files.absolute(dl.getAbsolutePath()) : new Fi(dl);
                        if(canReadDirectly(dlCandidate)){
                            return dlCandidate;
                        }
                    }
                }catch(Throwable ignored){}
            }
        }

        // 7. Check secondary external storage (e.g. 1234-5678:path or /document/1234-5678:path)
        int colon = decoded.indexOf(':');
        if(colon > 0 && colon < decoded.length() - 1){
            String prefix = decoded.substring(0, colon);
            int slash = prefix.lastIndexOf('/');
            String volId = slash != -1 ? prefix.substring(slash + 1) : prefix;
            if(volId.matches("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")){
                String rel = decoded.substring(colon + 1).trim();
                while(rel.startsWith("/")) rel = rel.substring(1);
                String secRoot = "/storage/" + volId;
                try{
                    File f = rel.isEmpty() ? new File(secRoot) : new File(secRoot, rel);
                    Fi candidate = Core.files != null ? Core.files.absolute(f.getAbsolutePath()) : new Fi(f);
                    if(canReadDirectly(candidate)){
                        return candidate;
                    }
                }catch(Throwable ignored){}
            }
        }

        return null;
    }

    /**
     * Resolves a {@link Fi} instance, converting Android SAF virtual file handles into physical
     * filesystem {@link Fi} objects only when the underlying file exists on local storage AND
     * can actually be read directly via POSIX streaming.
     */
    public static Fi resolveFi(Fi fi){
        if(fi == null) return null;
        try{
            if(canReadDirectly(fi)){
                return fi;
            }
        }catch(Throwable ignored){}

        Fi resolved = resolveFi(fi.path());
        return (resolved != null && canReadDirectly(resolved)) ? resolved : fi;
    }

    /**
     * Resolves a path string, returning the physical absolute path if found, or the original
     * path if no physical file match could be confirmed.
     */
    public static String resolvePath(String path){
        Fi resolved = resolveFi(path);
        return (resolved != null && resolved.exists()) ? resolved.path() : path;
    }

    /**
     * Pure path translation from a virtual SAF URI to an Android physical path structure,
     * without requiring the destination file to already exist on disk.
     * Useful for prefilling paths or predictable testing.
     *
     * @param virtualPath virtual path string (e.g. {@code /document/primary:Download/pack.zip})
     * @param defaultStorageRoot storage root to use (e.g. {@code /storage/emulated/0})
     * @return translated filesystem path string
     */
    public static String translateVirtualPath(String virtualPath, String defaultStorageRoot){
        if(virtualPath == null || virtualPath.trim().isEmpty()) return "";
        String clean = virtualPath.trim();

        if(clean.contains("%")){
            try{
                clean = java.net.URLDecoder.decode(clean, StandardCharsets.UTF_8);
            }catch(Throwable ignored){}
        }

        if(clean.startsWith("file://")){
            clean = clean.substring(7);
        }

        int rawIdx = clean.indexOf(RAW_TAG);
        if(rawIdx != -1){
            return clean.substring(rawIdx + RAW_TAG.length()).trim();
        }

        int primaryIdx = clean.indexOf(PRIMARY_TAG);
        if(primaryIdx != -1){
            String rel = clean.substring(primaryIdx + PRIMARY_TAG.length()).trim();
            while(rel.startsWith("/")) rel = rel.substring(1);
            while(rel.startsWith("\\")) rel = rel.substring(1);

            String root = (defaultStorageRoot != null && !defaultStorageRoot.isEmpty()) ?
                defaultStorageRoot : "/storage/emulated/0";
            while(root.endsWith("/")) root = root.substring(0, root.length() - 1);
            while(root.endsWith("\\")) root = root.substring(0, root.length() - 1);

            return rel.isEmpty() ? root : root + "/" + rel;
        }

        int colon = clean.indexOf(':');
        if(colon > 0 && colon < clean.length() - 1){
            String prefix = clean.substring(0, colon);
            int slash = prefix.lastIndexOf('/');
            String volId = slash != -1 ? prefix.substring(slash + 1) : prefix;
            if(volId.matches("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")){
                String rel = clean.substring(colon + 1).trim();
                while(rel.startsWith("/")) rel = rel.substring(1);
                return rel.isEmpty() ? "/storage/" + volId : "/storage/" + volId + "/" + rel;
            }
        }

        return clean;
    }

    /**
     * Sanitizes a display name from a raw filename or SAF URI path, stripping virtual prefixes
     * like {@code primary:}, {@code document:}, or directory segments.
     *
     * @param name raw file or source name
     * @param path source path used as fallback if name is empty
     * @return human-readable sanitized filename (e.g. {@code mindustry-data-export.zip})
     */
    public static String sanitizeSourceName(String name, String path){
        String candidate = (name != null && !name.trim().isEmpty()) ? name.trim() :
            (path != null ? path.trim() : "");
        if(candidate.isEmpty()) return "unnamed";

        if(candidate.contains("%")){
            try{
                candidate = java.net.URLDecoder.decode(candidate, StandardCharsets.UTF_8);
            }catch(Throwable ignored){}
        }

        int colon = candidate.lastIndexOf(':');
        if(colon != -1 && colon < candidate.length() - 1){
            candidate = candidate.substring(colon + 1);
        }

        int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if(slash != -1 && slash < candidate.length() - 1){
            candidate = candidate.substring(slash + 1);
        }

        candidate = Strings.sanitizeFilename(candidate);
        return candidate.isEmpty() ? "unnamed" : candidate;
    }

    /** Returns candidate root paths for Android primary external storage. */
    public static Seq<String> getAndroidStorageRoots(){
        Seq<String> roots = new Seq<>();
        if(Core.files != null){
            try{
                String ext = Core.files.getExternalStoragePath();
                if(ext != null && !ext.isEmpty() && !roots.contains(ext)){
                    roots.add(ext);
                }
            }catch(Throwable ignored){}
        }

        String[] standardRoots = {"/storage/emulated/0", "/sdcard", "/storage/self/primary"};
        for(String r : standardRoots){
            if(!roots.contains(r)){
                roots.add(r);
            }
        }

        try{
            String env = System.getenv("EXTERNAL_STORAGE");
            if(env != null && !env.isEmpty() && !roots.contains(env)){
                roots.add(env);
            }
        }catch(Throwable ignored){}

        return roots;
    }

    /**
     * Returns existing Windows drive roots sorted by path, with display names
     * such as {@code Local Disk (C:)} or {@code DU LIEU (D:)}.
     *
     * @param fallbackLabel label used when Windows reports no volume name
     */
    public static Seq<StorageRoot> windowsRoots(String fallbackLabel){
        File[] files;
        try{
            files = File.listRoots();
        }catch(Throwable ignored){
            return new Seq<>();
        }

        return collectWindowsRoots(files, StorageSources::systemDisplayName, fallbackLabel);
    }

    static Seq<StorageRoot> collectWindowsRoots(File[] files, VolumeLabelProvider labelProvider, String fallbackLabel){
        Seq<StorageRoot> roots = new Seq<>();
        if(files == null) return roots;

        for(File file : files){
            if(file == null) continue;

            try{
                if(!file.exists() || !file.isDirectory()) continue;

                String path = file.getAbsolutePath();
                if(driveLetter(path).isEmpty()) continue;

                String label = null;
                if(labelProvider != null){
                    try{
                        label = labelProvider.displayName(file);
                    }catch(Throwable ignored){}
                }

                roots.add(new StorageRoot(path, formatDriveName(label, path, fallbackLabel)));
            }catch(Throwable ignored){}
        }

        roots.sort((a, b) -> a.path.compareToIgnoreCase(b.path));
        return roots;
    }

    /**
     * Formats a Windows drive name as {@code Volume (X:)} and uses
     * {@code fallbackName} when the OS provides no usable label.
     */
    public static String formatDriveName(String systemName, String rootPath, String fallbackName){
        String drive = driveLetter(rootPath);
        String name = systemName == null ? "" : systemName.trim();

        if(drive.isEmpty()){
            return !name.isEmpty() ? name : (fallbackName == null ? "" : fallbackName.trim());
        }

        if(name.isEmpty() || name.equalsIgnoreCase(rootPath) || name.equalsIgnoreCase(drive)
            || name.equalsIgnoreCase(drive + "\\") || name.equalsIgnoreCase(drive + "/")){
            name = fallbackName == null ? "" : fallbackName.trim();
        }
        if(name.isEmpty()) return drive;

        String suffix = "(" + drive + ")";
        return name.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT)) ? name : name + " " + suffix;
    }

    /** Extracts an uppercase drive letter such as {@code C:} from a Windows root path. */
    public static String driveLetter(String path){
        if(path == null || path.length() < 2 || path.charAt(1) != ':') return "";

        char letter = path.charAt(0);
        if(!Character.isLetter(letter)) return "";
        if(path.length() > 2 && path.charAt(2) != '\\' && path.charAt(2) != '/') return "";

        return Character.toUpperCase(letter) + ":";
    }

    private static String systemDisplayName(File root){
        try{
            Class<?> viewType = Class.forName("javax.swing.filechooser.FileSystemView");
            Object view = viewType.getMethod("getFileSystemView").invoke(null);
            Object name = viewType.getMethod("getSystemDisplayName", File.class).invoke(view, root);
            return name instanceof String ? (String)name : null;
        }catch(Throwable ignored){
            return null;
        }
    }

    public static final class StorageRoot{
        public final String path;
        public final String displayName;

        public StorageRoot(String path, String displayName){
            this.path = path;
            this.displayName = displayName;
        }
    }

    @FunctionalInterface
    interface VolumeLabelProvider{
        String displayName(File root);
    }
}

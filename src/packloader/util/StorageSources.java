package packloader.util;

import arc.struct.Seq;

import java.io.File;
import java.util.Locale;

/**
 * Platform-specific storage root discovery and display names.
 *
 * <p>Windows volume labels are queried from the OS through Swing's
 * {@code FileSystemView}. The reference is reflective so Android builds never
 * need to resolve a desktop-only class.</p>
 */
public final class StorageSources{
    private static final String WINDOWS = "windows";

    private StorageSources(){}

    /** Returns whether the game is running on Windows. */
    public static boolean isWindows(){
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains(WINDOWS);
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
                    }catch(Throwable ignored){
                        // FileSystemView/reflection can fail; keep the drive with fallback text.
                    }
                }

                roots.add(new StorageRoot(path, formatDriveName(label, path, fallbackLabel)));
            }catch(Throwable ignored){
                // Skip drives that cannot be queried; the browser remains usable.
            }
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

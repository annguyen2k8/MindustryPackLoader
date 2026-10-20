package packloader.core;

import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.io.MapIO;
import mindustry.maps.Map;
import packloader.model.PackEntry;
import packloader.model.PackType;

import java.io.*;
import java.util.Enumeration;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PackScanner{
    private final Fi mapDirectory;
    private final Fi schematicDirectory;

    public PackScanner(){
        this(Vars.customMapDirectory, Vars.schematicDirectory);
    }

    public PackScanner(Fi mapDirectory, Fi schematicDirectory){
        this.mapDirectory = mapDirectory;
        this.schematicDirectory = schematicDirectory;
    }

    /** Formats a byte count into a human-readable size string (e.g., 512 B, 12.4 KB, 1.5 MB). */
    public static String formatSize(long bytes){
        if(bytes < 1024) return bytes + " B";
        if(bytes < 1024 * 1024) return Strings.autoFixed(bytes / 1024f, 1) + " KB";
        return Strings.autoFixed(bytes / (1024f * 1024f), 1) + " MB";
    }

    /** Verifies that a ZIP entry path is safe and does not attempt Zip Slip traversal. */
    public static boolean isSafeZipEntry(String entryName){
        if(entryName == null) return false;
        String normalized = entryName.replace('\\', '/').trim();
        if(normalized.isEmpty()) return false;

        // Reject absolute paths
        if(normalized.startsWith("/")) return false;

        // Reject Windows drive letters
        if(normalized.length() >= 2 && Character.isLetter(normalized.charAt(0)) && normalized.charAt(1) == ':'){
            return false;
        }

        // Reject directory traversal
        String[] parts = normalized.split("/");
        for(String part : parts){
            if(part.equals("..")){
                return false;
            }
        }

        return true;
    }

    /** Checks if entry is an OS metadata entry that should be silently ignored. */
    public static boolean isIgnoredMetadata(String entryName){
        if(entryName == null) return true;
        String norm = entryName.replace('\\', '/').trim();
        return norm.startsWith("__MACOSX/") ||
               norm.contains("/__MACOSX/") ||
               norm.endsWith("/.DS_Store") ||
               norm.equals(".DS_Store") ||
               norm.startsWith("._") ||
               norm.contains("/._");
    }

    /** Detects whether a Fi is a ZIP archive, checking filename and magic bytes for SAF resilience. */
    public static boolean isZipFi(Fi fi){
        if(fi == null || fi.isDirectory()) return false;
        String name = fi.name().trim().toLowerCase();
        if(name.endsWith(".zip")) return true;

        // Check magic bytes in case Android SAF masked or altered the extension
        try(InputStream in = fi.read(4)){
            byte[] header = new byte[4];
            int read = in.read(header);
            if(read >= 4 && header[0] == 0x50 && header[1] == 0x4B &&
               ((header[2] == 0x03 && header[3] == 0x04) ||
                (header[2] == 0x05 && header[3] == 0x06) ||
                (header[2] == 0x07 && header[3] == 0x08))){
                return true;
            }
        }catch(Throwable ignored){}
        return false;
    }

    /** Detects whether a Fi is a supported PackType by checking extension, with magic bytes fallback. */
    public static PackType detectPackType(Fi fi){
        if(fi == null || fi.isDirectory()) return null;
        String name = fi.name().trim().toLowerCase();
        if(name.endsWith(".msch")) return PackType.SCHEMATIC;
        if(name.endsWith(".msav")) return PackType.MAP;

        // Check magic bytes for files with altered/hidden extension
        try(InputStream in = fi.read(16)){
            byte[] header = new byte[16];
            int read = in.read(header);
            if(read >= 4 && header[0] == 'm' && header[1] == 's' && header[2] == 'c' && header[3] == 'h'){
                return PackType.SCHEMATIC;
            }
        }catch(Throwable ignored){}

        try(InputStream in = fi.read(64);
            InflaterInputStream iis = new InflaterInputStream(in)){
            byte[] header = new byte[4];
            int read = iis.read(header);
            if(read >= 4 && header[0] == 'M' && header[1] == 'S' && header[2] == 'A' && header[3] == 'V'){
                return PackType.MAP;
            }
        }catch(Throwable ignored){}

        return null;
    }

    /** Checks whether a directory is restricted by Android Scoped Storage. */
    public static boolean isScopedStorageRestricted(Fi dir){
        return isScopedStorageRestricted(dir, Vars.mobile);
    }

    /** Overload allowing explicit isMobile check for unit testing. */
    public static boolean isScopedStorageRestricted(Fi dir, boolean isMobile){
        if(!isMobile || dir == null) return false;
        try{
            if(Vars.dataDirectory != null && Vars.dataDirectory.exists()){
                String dataPath = Vars.dataDirectory.file().getCanonicalPath();
                String dirPath = dir.file().getCanonicalPath();
                if(dirPath.startsWith(dataPath)) return false;
            }
        }catch(Throwable ignored){}

        String path = dir.path();
        return path.startsWith("/storage/") || path.startsWith("/sdcard/") || path.startsWith("/mnt/");
    }

    /** Resolves a virtual Android SAF Fi to a local POSIX-accessible ZIP file if needed. */
    public static Fi resolveZipFi(Fi zipFile) throws IOException{
        if(zipFile == null) throw new FileNotFoundException("ZIP file is null");

        // If local file exists and is directly readable by java.util.zip.ZipFile
        try{
            if(zipFile.file() != null && zipFile.file().exists() && zipFile.file().isFile() && zipFile.file().canRead()){
                return zipFile;
            }
        }catch(Throwable ignored){}

        // On Android SAF or virtual Fi, materialize the stream to a temporary POSIX cache file
        Fi tmpDir = (Vars.tmpDirectory != null && Vars.tmpDirectory.exists()) ?
            Vars.tmpDirectory : Fi.tempDirectory("packload_cache");
        if(!tmpDir.exists()) tmpDir.mkdirs();

        Fi cached = tmpDir.child("saf_zip_" + System.currentTimeMillis() + "_" + Math.abs(zipFile.hashCode()) + ".zip");
        try(InputStream in = zipFile.read(16384);
            OutputStream out = cached.write(false, 16384)){
            byte[] buf = new byte[16384];
            int len;
            long total = 0;
            while((len = in.read(buf)) > 0){
                out.write(buf, 0, len);
                total += len;
            }
            out.flush();
            if(total == 0){
                cached.delete();
                throw new IOException("ZIP source is empty (0 bytes read)");
            }
        }catch(Throwable t){
            if(cached.exists()) cached.delete();
            if(t instanceof IOException io) throw io;
            throw new IOException("Failed to materialize ZIP from SAF: " + t.getMessage(), t);
        }

        return cached;
    }

    /** Scans a ZIP archive and extracts all valid map and schematic entries. */
    public Seq<PackEntry> scanZip(Fi zipFile) throws IOException{
        return scanZip(zipFile, null);
    }

    /** Scans a ZIP archive filtering for specific PackType (MAP, SCHEMATIC, or null for all). */
    public Seq<PackEntry> scanZip(Fi zipFile, PackType targetType) throws IOException{
        if(zipFile == null){
            throw new FileNotFoundException(Core.bundle != null ? Core.bundle.format("packloader.error.notfound", "null") : "Not found: null");
        }

        Fi resolvedZip = resolveZipFi(zipFile);
        File file = resolvedZip.file();

        Seq<PackEntry> entries = new Seq<>();

        try(ZipFile zf = new ZipFile(file)){
            Enumeration<? extends ZipEntry> zipEntries = zf.entries();
            while(zipEntries.hasMoreElements()){
                ZipEntry ze = zipEntries.nextElement();
                if(ze.isDirectory()) continue;

                String entryName = ze.getName();
                if(isIgnoredMetadata(entryName)) continue;

                if(!isSafeZipEntry(entryName)){
                    Log.warn("[PackLoader] " + (Core.bundle != null ? Core.bundle.format("packloader.error.zipslip", entryName) : "Zip Slip detected: " + entryName));
                    continue;
                }

                PackType type = PackType.fromFileName(entryName);
                if(type == null) continue; // Unsupported file type (e.g. .txt, .png)
                if(targetType != null && type != targetType) continue; // Filter by target type

                String rawFileName = extractFileName(entryName);
                long entrySize = ze.getSize() >= 0 ? ze.getSize() : 0;
                PackEntry entry = new PackEntry(type, entryName, rawFileName, entrySize);
                entry.zipFile = resolvedZip;
                entry.zipEntryName = entryName;

                // Validate and inspect content
                validateAndInspectEntry(entry);
                checkDuplicate(entry);

                entries.add(entry);
            }
        }catch(java.util.zip.ZipException ze){
            throw new IOException(Core.bundle != null ? Core.bundle.format("packloader.error.zip-corrupt", ze.getMessage()) : "Corrupt ZIP archive: " + ze.getMessage(), ze);
        }

        return entries;
    }

    /** Recursively scans a directory for map and schematic files. */
    public Seq<PackEntry> scanDirectory(Fi dir) throws IOException{
        return scanDirectory(dir, null);
    }

    /** Recursively scans a directory filtering for specific PackType (MAP, SCHEMATIC, or null for all). */
    public Seq<PackEntry> scanDirectory(Fi dir, PackType targetType) throws IOException{
        if(dir == null || !dir.exists() || !dir.isDirectory()){
            throw new FileNotFoundException(Core.bundle != null ? Core.bundle.format("packloader.error.directory-notfound", (dir == null ? "null" : dir.path())) : "Directory not found");
        }

        Fi[] testList;
        try{
            testList = dir.list();
        }catch(Throwable t){
            throw new IOException(Core.bundle != null ? Core.bundle.format("packloader.error.permission-denied", dir.path()) : "Permission denied: " + dir.path(), t);
        }
        if(testList == null){
            throw new IOException(Core.bundle != null ? Core.bundle.format("packloader.error.permission-denied", dir.path()) : "Cannot list directory (permission denied): " + dir.path());
        }

        Seq<PackEntry> entries = new Seq<>();
        scanDirectoryRecursive(dir, dir, entries, targetType);
        return entries;
    }

    private void scanDirectoryRecursive(Fi root, Fi current, Seq<PackEntry> entries, PackType targetType){
        Fi[] children;
        try{
            children = current.list();
        }catch(Throwable t){
            Log.warn("[PackLoader] Cannot list directory at: @ (@)", current.path(), t.getMessage());
            return;
        }

        if(children == null) return;

        for(Fi child : children){
            try{
                if(child.isDirectory()){
                    scanDirectoryRecursive(root, child, entries, targetType);
                }else{
                    PackEntry entry = createEntryIfSupported(child, makeRelative(root, child), targetType);
                    if(entry != null){
                        entries.add(entry);
                    }else if(isZipFi(child)){
                        try{
                            entries.addAll(scanZip(child, targetType));
                        }catch(Throwable e){
                            Log.err("[PackLoader] Error reading nested zip: @", child.path(), e);
                        }
                    }
                }
            }catch(Throwable t){
                Log.err("[PackLoader] Error scanning file: @", child.path(), t);
            }
        }
    }

    /** Scans a selection of arbitrary files (e.g. from SAF or file chooser). */
    public Seq<PackEntry> scanFiles(Fi... files) throws IOException{
        return scanFiles(null, files);
    }

    /** Scans a selection of arbitrary files with target type filtering. */
    public Seq<PackEntry> scanFiles(PackType targetType, Fi... files) throws IOException{
        Seq<PackEntry> entries = new Seq<>();
        if(files == null) return entries;

        for(Fi file : files){
            if(file == null) continue;

            if(file.isDirectory()){
                entries.addAll(scanDirectory(file, targetType));
            }else if(isZipFi(file)){
                entries.addAll(scanZip(file, targetType));
            }else{
                PackEntry entry = createEntryIfSupported(file, file.name(), targetType);
                if(entry != null){
                    entries.add(entry);
                }
            }
        }

        return entries;
    }

    private PackEntry createEntryIfSupported(Fi file, String path, PackType targetType){
        String fileName = file.name();
        if(isIgnoredMetadata(fileName)) return null;

        PackType type = PackType.fromFileName(fileName);
        if(type == null && !isZipFi(file)){
            type = detectPackType(file);
        }
        if(type == null || (targetType != null && type != targetType)){
            return null;
        }

        String safeName = fileName;
        if(!safeName.toLowerCase().endsWith("." + type.extension)){
            safeName = safeName + "." + type.extension;
        }

        PackEntry entry = new PackEntry(type, path, safeName, file.length());
        entry.sourceFile = file;
        validateAndInspectEntry(entry);
        checkDuplicate(entry);
        return entry;
    }

    /** Validates file structure and extracts display name metadata. */
    public void validateAndInspectEntry(PackEntry entry){
        try{
            if(entry.type == PackType.SCHEMATIC){
                validateSchematic(entry);
            }else if(entry.type == PackType.MAP){
                validateMap(entry);
            }
        }catch(Throwable t){
            entry.isValid = false;
            String msg = (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            entry.errorReason = Core.bundle != null ? Core.bundle.format("packloader.error.validation", msg) : "Validation failed: " + msg;
        }
    }

    private void validateSchematic(PackEntry entry) throws IOException{
        try(InputStream in = entry.openStream()){
            byte[] header = new byte[4];
            int read = in.read(header);
            if(read < 4 || header[0] != 'm' || header[1] != 's' || header[2] != 'c' || header[3] != 'h'){
                entry.isValid = false;
                entry.errorReason = Core.bundle != null ? Core.bundle.get("packloader.error.invalid-schematic") : "Invalid schematic header";
                return;
            }

            int ver = in.read();
            if(ver > 1){
                entry.isValid = false;
                entry.errorReason = Core.bundle != null ? Core.bundle.format("packloader.error.invalid-schematic-ver", ver) : "Unsupported schematic version: " + ver;
                return;
            }

            // Attempt to read schematic metadata using official parser
            try(InputStream schemIn = entry.openStream()){
                Schematic s = Schematics.read(schemIn);
                if(s != null && s.name() != null && !s.name().trim().isEmpty()){
                    entry.displayName = s.name();
                }
            }catch(Throwable ignored){
                // If full schematic body fails to deserialize, header was at least valid
            }
        }
    }

    private void validateMap(PackEntry entry) throws IOException{
        try(InputStream in = entry.openStream();
            InflaterInputStream iis = new InflaterInputStream(in)){
            byte[] header = new byte[4];
            int read = iis.read(header);
            if(read < 4 || header[0] != 'M' || header[1] != 'S' || header[2] != 'A' || header[3] != 'V'){
                entry.isValid = false;
                entry.errorReason = Core.bundle != null ? Core.bundle.get("packloader.error.invalid-map") : "Invalid map header";
                return;
            }

            // If sourceFile is available, try reading map tags through MapIO
            if(entry.sourceFile != null){
                try{
                    Map map = MapIO.createMap(entry.sourceFile, true);
                    if(map != null && map.name() != null && !map.name().trim().isEmpty()){
                        entry.displayName = map.name();
                    }
                }catch(Throwable ignored){}
            }
        }catch(Throwable t){
            entry.isValid = false;
            entry.errorReason = Core.bundle != null ? Core.bundle.format("packloader.error.uncompress-map", t.getMessage()) : "Cannot decompress map: " + t.getMessage();
        }
    }

    /** Checks whether the entry collides with an existing file or item in the game. */
    public void checkDuplicate(PackEntry entry){
        Fi targetDir = (entry.type == PackType.MAP) ? mapDirectory : schematicDirectory;
        if(targetDir == null) return;

        // 1. Check direct file collision in target directory
        Fi targetFile = targetDir.child(entry.sanitizedFileName);
        if(targetFile.exists()){
            entry.isDuplicate = true;
            entry.duplicateReason = Core.bundle != null ? Core.bundle.format("packloader.duplicate.file", targetFile.name()) : "File already exists: " + targetFile.name();
            return;
        }

        // 2. Check collision with loaded game content by filename only (avoid false duplicates from common display names)
        try{
            if(entry.type == PackType.MAP && Vars.maps != null){
                Map existing = Vars.maps.all().find(m ->
                    (m.file != null && m.file.name().equalsIgnoreCase(entry.sanitizedFileName))
                );
                if(existing != null){
                    entry.isDuplicate = true;
                    entry.duplicateReason = Core.bundle != null ? Core.bundle.format("packloader.duplicate.map", existing.name()) : "Duplicate map: " + existing.name();
                    return;
                }
            }else if(entry.type == PackType.SCHEMATIC && Vars.schematics != null){
                Schematic existing = Vars.schematics.all().find(s ->
                    (s.file != null && s.file.name().equalsIgnoreCase(entry.sanitizedFileName))
                );
                if(existing != null){
                    entry.isDuplicate = true;
                    entry.duplicateReason = Core.bundle != null ? Core.bundle.format("packloader.duplicate.schematic", existing.name()) : "Duplicate schematic: " + existing.name();
                    return;
                }
            }
        }catch(Throwable ignored){
            // Vars.maps or Vars.schematics might be null in tests
        }
    }

    private static String extractFileName(String path){
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if(lastSlash >= 0 && lastSlash < path.length() - 1){
            return path.substring(lastSlash + 1);
        }
        return path;
    }

    private static String makeRelative(Fi root, Fi child){
        try{
            String rootPath = root.file().getCanonicalPath();
            String childPath = child.file().getCanonicalPath();
            if(childPath.startsWith(rootPath)){
                String rel = childPath.substring(rootPath.length());
                if(rel.startsWith(File.separator)) rel = rel.substring(1);
                return rel.replace('\\', '/');
            }
        }catch(Throwable ignored){}
        return child.name();
    }
}

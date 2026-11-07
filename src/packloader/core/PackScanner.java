package packloader.core;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.maps.Map;
import packloader.model.PackEntry;
import packloader.model.PackType;
import packloader.util.MainThread;

import java.io.*;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static packloader.util.Localization.getLocalizedText;

public class PackScanner{
    public static final long MAX_ZIP_ARCHIVE_BYTES = 512L * 1024 * 1024;
    public static final long MAX_ZIP_TOTAL_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;
    public static final int MAX_ZIP_ENTRIES = 20_000;
    public static final int MAX_SCAN_DEPTH = 64;

    private static final String SAF_TEMP_PREFIX = "saf_zip_";
    private static final int MAX_UNTRACKED_SAF_ZIPS = 8;
    private static final long MAX_UNTRACKED_SAF_ZIP_BYTES = 512L * 1024 * 1024;
    private static final java.util.concurrent.atomic.AtomicLong TEMP_COUNTER =
        new java.util.concurrent.atomic.AtomicLong();

    private final Fi mapDirectory;
    private final Fi schematicDirectory;
    private final ContentHashIndex hashIndex;
    private final Object tempLock = new Object();
    private final Seq<Fi> temporaryZips = new Seq<>();
    private final Seq<String> warnings = new Seq<>();

    /** Receives throttled-safe progress information from a background scan. */
    public interface ScanListener{
        void onProgress(String currentFile, int discovered, int total);

        /** Receives non-fatal problems for this specific scan. */
        default void onWarning(String message){
        }

        default boolean isCancelled(){
            return false;
        }
    }

    /** Thrown when a caller cancels a scan through {@link ScanListener#isCancelled()}. */
    public static class ScanCancelledException extends IOException{
        public ScanCancelledException(){
            super("Scan cancelled");
        }
    }

    public PackScanner(){
        this(Vars.customMapDirectory, Vars.schematicDirectory);
    }

    public PackScanner(Fi mapDirectory, Fi schematicDirectory){
        this.mapDirectory = mapDirectory;
        this.schematicDirectory = schematicDirectory;
        this.hashIndex = new ContentHashIndex(mapDirectory, schematicDirectory);
    }

    public ContentHashIndex getHashIndex(){
        return hashIndex;
    }

    /** Clears warnings collected by previous scans. Call before starting a new scan session. */
    public void clearWarnings(){
        warnings.clear();
    }

    /** Returns a snapshot of non-fatal problems encountered while scanning. */
    public Seq<String> getWarnings(){
        return new Seq<>(warnings);
    }

    private void warn(ScanListener listener, String message){
        Log.warn("[PackLoader] @", message);
        if(listener != null){
            // A scan listener owns the warnings for its own scan, so a stale scan cannot
            // leak messages into a newer scan's summary.
            listener.onWarning(message);
        }else{
            warnings.add(message);
        }
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

    private ResolvedZip resolveZip(Fi zipFile) throws IOException{
        if(zipFile == null) throw new FileNotFoundException("ZIP file is null");

        // If local file exists and is directly readable by java.util.zip.ZipFile
        try{
            if(zipFile.file() != null && zipFile.file().exists() && zipFile.file().isFile() && zipFile.file().canRead()){
                return new ResolvedZip(zipFile, null);
            }
        }catch(Throwable ignored){}

        // On Android SAF or virtual Fi, materialize the stream to a temporary POSIX cache file
        Fi tmpDir = (Vars.tmpDirectory != null && Vars.tmpDirectory.exists()) ?
            Vars.tmpDirectory : Fi.tempDirectory("packload_cache");
        if(!tmpDir.exists()) tmpDir.mkdirs();

        pruneUntrackedSafTemps(tmpDir);
        Fi cached = tmpDir.child(SAF_TEMP_PREFIX + System.currentTimeMillis() + "_" +
            TEMP_COUNTER.incrementAndGet() + "_" + Math.abs(zipFile.hashCode()) + ".zip");
        try(InputStream in = zipFile.read(16384);
            OutputStream out = cached.write(false, 16384)){
            byte[] buf = new byte[16384];
            int len;
            long total = 0;
            while((len = in.read(buf)) > 0){
                total += len;
                if(total > MAX_ZIP_ARCHIVE_BYTES){
                    throw new IOException(getLocalizedText("packloader.error.zip-too-large",
                        MAX_ZIP_ARCHIVE_BYTES / (1024 * 1024)));
                }
                out.write(buf, 0, len);
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

        synchronized(tempLock){
            temporaryZips.add(cached);
        }
        return new ResolvedZip(cached, cached);
    }

    /** Scans a ZIP archive and extracts all valid map and schematic entries. */
    public Seq<PackEntry> scanZip(Fi zipFile) throws IOException{
        return scanZip(zipFile, null, null);
    }

    /** Scans a ZIP archive filtering for specific PackType (MAP, SCHEMATIC, or null for all). */
    public Seq<PackEntry> scanZip(Fi zipFile, PackType targetType) throws IOException{
        return scanZip(zipFile, targetType, null);
    }

    /** Scans a ZIP archive with progress reporting and cooperative cancellation. */
    public Seq<PackEntry> scanZip(Fi zipFile, PackType targetType, ScanListener listener) throws IOException{
        if(zipFile == null){
            throw new FileNotFoundException(getLocalizedText("packloader.error.notfound", "null"));
        }

        long scanStartTime = System.currentTimeMillis();
        ResolvedZip resolved = resolveZip(zipFile);
        File file = resolved.zip.file();
        Seq<PackEntry> entries = new Seq<>();

        // Reject oversized local archives before ZipFile parses the central directory. SAF
        // sources are already bounded while materializing, but a local path was not.
        if(file.length() > MAX_ZIP_ARCHIVE_BYTES){
            discardTemp(resolved.temporary);
            throw new IOException(getLocalizedText("packloader.error.zip-too-large",
                MAX_ZIP_ARCHIVE_BYTES / (1024 * 1024)));
        }

        // Cache existing filesystem names to avoid repeated stat syscalls during candidate checks
        Set<String> existingMapFiles = getExistingFileNames(mapDirectory);
        Set<String> existingSchemFiles = getExistingFileNames(schematicDirectory);

        try(ZipFile zf = new ZipFile(file)){
            Seq<ZipEntry> candidates = new Seq<>();
            Set<String> names = new HashSet<>();
            long totalUncompressed = 0;
            int entryCount = 0;
            Enumeration<? extends ZipEntry> zipEntries = zf.entries();
            while(zipEntries.hasMoreElements()){
                ZipEntry ze = zipEntries.nextElement();
                entryCount++;
                if(entryCount > MAX_ZIP_ENTRIES){
                    throw new IOException(getLocalizedText("packloader.error.zip-too-many-entries", MAX_ZIP_ENTRIES));
                }
                if(ze.isDirectory()) continue;

                String entryName = ze.getName();
                if(isIgnoredMetadata(entryName)) continue;

                if(!isSafeZipEntry(entryName)){
                    warn(listener, getLocalizedText("packloader.error.zipslip", entryName));
                    continue;
                }

                String duplicateKey = entryName.replace('\\', '/').toLowerCase(Locale.ROOT);
                if(!names.add(duplicateKey)){
                    warn(listener, getLocalizedText("packloader.scan.warning.duplicate-entry", entryName));
                    continue;
                }

                long entrySize = ze.getSize();
                if(entrySize < 0){
                    warn(listener, getLocalizedText("packloader.scan.warning.unknown-size", entryName));
                    continue;
                }
                if(entrySize > MAX_ZIP_TOTAL_UNCOMPRESSED_BYTES - totalUncompressed){
                    throw new IOException(getLocalizedText("packloader.error.zip-uncompressed-too-large",
                        MAX_ZIP_TOTAL_UNCOMPRESSED_BYTES / (1024 * 1024)));
                }
                totalUncompressed += entrySize;

                PackType type = PackType.fromFileName(entryName);
                if(type == null) continue; // Unsupported file type (e.g. .txt, .png)
                if(targetType != null && type != targetType) continue; // Filter by target type
                candidates.add(ze);
            }

            int total = candidates.size;
            for(ZipEntry ze : candidates){
                checkCancelled(listener);
                String entryName = ze.getName();
                String rawFileName = extractFileName(entryName);
                long entrySize = ze.getSize() >= 0 ? ze.getSize() : 0;
                PackEntry entry = new PackEntry(PackType.fromFileName(entryName), entryName, rawFileName, entrySize);
                entry.zipFile = resolved.zip;
                entry.zipEntryName = entryName;
                entry.temporaryZipFile = resolved.temporary;

                if(listener != null){
                    listener.onProgress(entryName, entries.size + 1, total);
                }

                if(entrySize > entry.getMaxAllowedBytes()){
                    entry.isValid = false;
                    entry.errorReason = getLocalizedText("packloader.error.zipbomb", entry.getMaxAllowedBytes() / (1024 * 1024));
                }else{
                    // Reuse open ZipFile stream directly instead of reopening ZipFile for each entry
                    try(InputStream in = new BufferedInputStream(zf.getInputStream(ze), 16384)){
                        validateAndInspectEntry(entry, in);
                    }catch(Throwable t){
                        handleValidationError(entry, t);
                    }
                }
                checkDuplicate(entry, entry.type == PackType.MAP ? existingMapFiles : existingSchemFiles);

                entries.add(entry);
            }
        }catch(ScanCancelledException e){
            discardTemp(resolved.temporary);
            throw e;
        }catch(ZipException e){
            // Preserve the exact exception so the UI can identify a corrupt archive.
            discardTemp(resolved.temporary);
            throw e;
        }catch(IOException | RuntimeException e){
            discardTemp(resolved.temporary);
            throw e;
        }

        if(entries.isEmpty()){
            discardTemp(resolved.temporary);
        }

        long scanElapsed = System.currentTimeMillis() - scanStartTime;
        Log.info("[PackLoader] ZIP scan completed: @ entries found in @ ms (@ ms/entry)",
            entries.size, scanElapsed, entries.isEmpty() ? 0 : scanElapsed / Math.max(1, entries.size));
        checkContentDuplicates(entries);
        return entries;
    }

    /** Recursively scans a directory for map and schematic files. */
    public Seq<PackEntry> scanDirectory(Fi dir) throws IOException{
        return scanDirectory(dir, null, null);
    }

    /** Recursively scans a directory filtering for specific PackType (MAP, SCHEMATIC, or null for all). */
    public Seq<PackEntry> scanDirectory(Fi dir, PackType targetType) throws IOException{
        return scanDirectory(dir, targetType, null);
    }

    /** Recursively scans a directory with progress reporting and cooperative cancellation. */
    public Seq<PackEntry> scanDirectory(Fi dir, PackType targetType, ScanListener listener) throws IOException{
        if(dir == null || !dir.exists() || !dir.isDirectory()){
            throw new FileNotFoundException(getLocalizedText("packloader.error.directory-notfound", (dir == null ? "null" : dir.path())));
        }

        Fi[] testList;
        try{
            testList = dir.list();
        }catch(Throwable t){
            throw new IOException(getLocalizedText("packloader.error.permission-denied", dir.path()), t);
        }
        if(testList == null){
            throw new IOException(getLocalizedText("packloader.error.permission-denied", dir.path()));
        }

        Seq<PackEntry> entries = new Seq<>();
        long startTime = System.currentTimeMillis();
        try{
            scanDirectoryRecursive(dir, dir, entries, targetType, listener, 0, new HashSet<>());
            long elapsed = System.currentTimeMillis() - startTime;
            Log.info("[PackLoader] Directory scan completed: @ entries found in @ ms", entries.size, elapsed);
            checkContentDuplicates(entries);
            return entries;
        }catch(Throwable t){
            // Entries from already-scanned nested ZIPs own materialized SAF temp files;
            // release them when the overall scan does not return a result.
            cleanupTemporaryZips(entries);
            throw t;
        }
    }

    private void scanDirectoryRecursive(Fi root, Fi current, Seq<PackEntry> entries, PackType targetType,
                                        ScanListener listener, int depth, Set<String> visited) throws IOException{
        checkCancelled(listener);
        if(depth > MAX_SCAN_DEPTH){
            warn(listener, getLocalizedText("packloader.scan.warning.depth", current.path(), MAX_SCAN_DEPTH));
            return;
        }

        String canonical;
        try{
            canonical = current.file().getCanonicalPath();
        }catch(Throwable t){
            canonical = current.path();
        }
        if(!visited.add(canonical)){
            warn(listener, getLocalizedText("packloader.scan.warning.cycle", current.path()));
            return;
        }

        Fi[] children;
        try{
            children = current.list();
        }catch(Throwable t){
            warn(listener, getLocalizedText("packloader.scan.warning.unreadable-directory", current.path()));
            return;
        }

        if(children == null){
            warn(listener, getLocalizedText("packloader.scan.warning.unreadable-directory", current.path()));
            return;
        }

        for(Fi child : children){
            checkCancelled(listener);
            try{
                if(isLinkOrJunction(child)){
                    warn(listener, getLocalizedText("packloader.scan.warning.link-skipped", child.path()));
                    continue;
                }

                if(child.isDirectory()){
                    scanDirectoryRecursive(root, child, entries, targetType, listener, depth + 1, visited);
                }else{
                    PackEntry entry = createEntryIfSupported(child, makeRelative(root, child), targetType);
                    if(entry != null){
                        entries.add(entry);
                        if(listener != null){
                            listener.onProgress(child.name(), entries.size, 0);
                        }
                    }else if(isZipFi(child)){
                        try{
                            Seq<PackEntry> nested = scanZip(child, targetType, nestedListener(listener, child, entries.size));
                            entries.addAll(nested);
                            if(listener != null){
                                listener.onProgress(child.name(), entries.size, 0);
                            }
                        }catch(ScanCancelledException e){
                            throw e;
                        }catch(Throwable e){
                            warn(listener, getLocalizedText("packloader.scan.warning.nested-zip", child.path(), safeMessage(e)));
                        }
                    }
                }
            }catch(ScanCancelledException e){
                throw e;
            }catch(Throwable t){
                warn(listener, getLocalizedText("packloader.scan.warning.unreadable-file", child.path(), safeMessage(t)));
            }
        }
    }

    private static boolean isLinkOrJunction(Fi file){
        try{
            BasicFileAttributes attributes = java.nio.file.Files.readAttributes(
                file.file().toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // On Windows, junctions are reported as "other" rather than symbolic links.
            return attributes.isSymbolicLink() || attributes.isOther();
        }catch(Throwable ignored){
            return false;
        }
    }

    private ScanListener nestedListener(ScanListener outer, Fi zip, int alreadyFound){
        if(outer == null) return null;
        return new ScanListener(){
            @Override
            public void onProgress(String currentFile, int discovered, int total){
                outer.onProgress(getLocalizedText("packloader.scan.source.nested", zip.name(), currentFile),
                    alreadyFound + discovered, 0);
            }

            @Override
            public void onWarning(String message){
                outer.onWarning(message);
            }

            @Override
            public boolean isCancelled(){
                return outer.isCancelled();
            }
        };
    }

    private static String safeMessage(Throwable t){
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? t.getClass().getSimpleName() : message;
    }

    /** Scans a selection of arbitrary files (e.g. from SAF or file chooser). */
    public Seq<PackEntry> scanFiles(Fi... files) throws IOException{
        return scanFiles(null, null, files);
    }

    /** Scans a selection of arbitrary files with target type filtering. */
    public Seq<PackEntry> scanFiles(PackType targetType, Fi... files) throws IOException{
        return scanFiles(targetType, null, files);
    }

    /** Scans a selection of arbitrary files with progress reporting and cooperative cancellation. */
    public Seq<PackEntry> scanFiles(PackType targetType, ScanListener listener, Fi... files) throws IOException{
        Seq<PackEntry> entries = new Seq<>();
        if(files == null) return entries;

        long startTime = System.currentTimeMillis();
        try{
            for(Fi file : files){
                checkCancelled(listener);
                if(file == null) continue;

                if(file.isDirectory()){
                    entries.addAll(scanDirectory(file, targetType, listener));
                }else if(isZipFi(file)){
                    entries.addAll(scanZip(file, targetType, listener));
                }else{
                    PackEntry entry = createEntryIfSupported(file, file.name(), targetType);
                    if(entry != null){
                        entries.add(entry);
                        if(listener != null){
                            listener.onProgress(file.name(), entries.size, 0);
                        }
                    }
                }
            }
            long elapsed = System.currentTimeMillis() - startTime;
            Log.info("[PackLoader] File scan completed: @ entries found in @ ms", entries.size, elapsed);
            checkContentDuplicates(entries);
            return entries;
        }catch(Throwable t){
            cleanupTemporaryZips(entries);
            throw t;
        }
    }

    public PackEntry scanFile(Fi file) throws IOException{
        return scanFile(file, null);
    }

    public PackEntry scanFile(Fi file, PackType targetType) throws IOException{
        Seq<PackEntry> res = scanFiles(targetType, file);
        return res.isEmpty() ? null : res.first();
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

    /** Validates file structure and extracts display name metadata using the entry stream. */
    public void validateAndInspectEntry(PackEntry entry){
        try(InputStream in = new BufferedInputStream(entry.openStream(), 16384)){
            validateAndInspectEntry(entry, in);
        }catch(Throwable t){
            handleValidationError(entry, t);
        }
    }

    /** Validates file structure and extracts metadata using an already-open stream. */
    public void validateAndInspectEntry(PackEntry entry, InputStream in){
        entry.isValid = true;
        entry.errorReason = "";
        try{
            if(entry.type == PackType.SCHEMATIC){
                validateSchematic(entry, in);
            }else if(entry.type == PackType.MAP){
                validateMap(entry, in);
            }
        }catch(Throwable t){
            handleValidationError(entry, t);
        }
    }

    private void handleValidationError(PackEntry entry, Throwable t){
        entry.isValid = false;
        String msg = (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        String lower = msg.toLowerCase(Locale.ROOT);
        if(entry.type == PackType.MAP){
            entry.errorReason = (lower.contains("header") || lower.contains("msav")) ?
                getLocalizedText("packloader.error.invalid-map") :
                getLocalizedText("packloader.error.uncompress-map", msg);
        }else{
            entry.errorReason = lower.contains("header") ?
                getLocalizedText("packloader.error.invalid-schematic") :
                getLocalizedText("packloader.error.validation", msg);
        }
    }

    private void validateSchematic(PackEntry entry, InputStream in) throws IOException{
        Schematic s = PackFormat.readSchematic(in);
        entry.schematic = s;
        // Schematic.name() falls back to "unknown"; only use an explicit metadata name.
        if(s != null && s.tags.containsKey("name") && !s.name().trim().isEmpty()){
            entry.displayName = s.name();
        }
    }

    private void validateMap(PackEntry entry, InputStream in) throws IOException{
        PackFormat.MapInfo info = PackFormat.validateMap(in);
        entry.mapInfo = info;
        entry.displayName = info.name;
    }

    /**
     * Refreshes duplicate badges against the live game registries. This must be called
     * on the main thread because {@code Vars.maps}/{@code Vars.schematics} are game state.
     * Uses in-memory hash maps to evaluate O(N) entries in O(1) per lookup.
     */
    public void checkGameDuplicates(Seq<PackEntry> entries){
        if(entries == null) return;
        if(!MainThread.isMainThread()) return;

        // Pre-index existing game maps in O(M) time to prevent O(N * M) main-thread lockups
        java.util.Map<String, Map> mapsByName = new HashMap<>();
        java.util.Map<String, Map> mapsByFile = new HashMap<>();
        try{
            if(Vars.maps != null && Vars.maps.all() != null){
                for(Map m : Vars.maps.all()){
                    if(m.name() != null) mapsByName.put(m.name().toLowerCase(Locale.ROOT), m);
                    if(m.file != null) mapsByFile.put(m.file.name().toLowerCase(Locale.ROOT), m);
                }
            }
        }catch(Throwable ignored){}

        // Pre-index existing schematics
        java.util.Map<String, Schematic> schemsByName = new HashMap<>();
        java.util.Map<String, Schematic> schemsByFile = new HashMap<>();
        try{
            if(Vars.schematics != null && Vars.schematics.all() != null){
                for(Schematic s : Vars.schematics.all()){
                    if(s.name() != null) schemsByName.put(s.name().toLowerCase(Locale.ROOT), s);
                    if(s.file != null) schemsByFile.put(s.file.name().toLowerCase(Locale.ROOT), s);
                }
            }
        }catch(Throwable ignored){}

        for(PackEntry entry : entries){
            if(entry.isDuplicate) continue;
            try{
                if(entry.type == PackType.MAP && !mapsByName.isEmpty()){
                    String nameKey = entry.displayName.toLowerCase(Locale.ROOT);
                    String fileKey = entry.sanitizedFileName.toLowerCase(Locale.ROOT);
                    Map existing = mapsByName.get(nameKey);
                    if(existing == null) existing = mapsByFile.get(fileKey);
                    if(existing != null){
                        entry.isDuplicate = true;
                        entry.duplicateReason = getLocalizedText("packloader.duplicate.map", existing.name());
                    }
                }else if(entry.type == PackType.SCHEMATIC && !schemsByName.isEmpty()){
                    String nameKey = entry.displayName.toLowerCase(Locale.ROOT);
                    String fileKey = entry.sanitizedFileName.toLowerCase(Locale.ROOT);
                    Schematic existing = schemsByName.get(nameKey);
                    if(existing == null) existing = schemsByFile.get(fileKey);
                    if(existing != null){
                        entry.isDuplicate = true;
                        entry.duplicateReason = getLocalizedText("packloader.duplicate.schematic", existing.name());
                    }
                }
            }catch(Throwable ignored){}
        }
    }

    /**
     * Inspects entries for identical content hashes (SHA-256).
     * Distinguishes content duplicates from name conflicts without modifying name conflict flags.
     * Evaluates both existing game library files and intra-batch/multi-source entries.
     */
    public void checkContentDuplicates(Seq<PackEntry> entries){
        if(entries == null || entries.isEmpty()) return;

        hashIndex.refresh();

        java.util.Map<String, PackEntry> batchMaps = new HashMap<>();
        java.util.Map<String, PackEntry> batchSchems = new HashMap<>();

        for(PackEntry entry : entries){
            entry.isContentDuplicate = false;
            entry.isIntraBatchContentDuplicate = false;
            entry.contentDuplicateMatch = "";
            entry.contentDuplicateReason = "";

            if(!entry.isValid || entry.size <= 0) continue;

            String hash = entry.getOrComputeSha256();
            if(hash.isEmpty()) continue;

            // 1. Check against existing files in game library
            ContentHashIndex.FileRecord existing = hashIndex.findMatch(hash, entry.type);
            if(existing != null){
                entry.isContentDuplicate = true;
                entry.isIntraBatchContentDuplicate = false;
                entry.contentDuplicateMatch = existing.name;
                entry.contentDuplicateReason = getLocalizedText("packloader.duplicate.content", existing.name);
                continue;
            }

            // 2. Check against earlier entries in this batch/queue
            java.util.Map<String, PackEntry> batchSeen = (entry.type == PackType.MAP) ? batchMaps : batchSchems;
            PackEntry earlier = batchSeen.get(hash);
            if(earlier != null){
                entry.isContentDuplicate = true;
                entry.isIntraBatchContentDuplicate = true;
                entry.contentDuplicateMatch = earlier.sanitizedFileName;
                entry.contentDuplicateReason = getLocalizedText("packloader.duplicate.content.batch", earlier.sanitizedFileName);
            }else{
                batchSeen.put(hash, entry);
            }
        }
    }

    /** Returns a set of lowercase file names existing in the specified directory. */
    public Set<String> getExistingFileNames(Fi directory){
        Set<String> set = new HashSet<>();
        if(directory != null && directory.exists() && directory.isDirectory()){
            try{
                Fi[] list = directory.list();
                if(list != null){
                    for(Fi f : list){
                        set.add(f.name().toLowerCase(Locale.ROOT));
                    }
                }
            }catch(Throwable ignored){}
        }
        return set;
    }

    /** Checks whether the entry collides with an existing file in the target directory. */
    public void checkDuplicate(PackEntry entry){
        checkDuplicate(entry, null);
    }

    /** Checks collision using a cached set of existing file names if available, falling back to disk. */
    public void checkDuplicate(PackEntry entry, Set<String> existingFileNames){
        if(existingFileNames != null){
            if(existingFileNames.contains(entry.sanitizedFileName.toLowerCase(Locale.ROOT))){
                entry.isDuplicate = true;
                entry.duplicateReason = getLocalizedText("packloader.duplicate.file", entry.sanitizedFileName);
            }
            return;
        }

        Fi targetDir = (entry.type == PackType.MAP) ? mapDirectory : schematicDirectory;
        if(targetDir == null) return;

        Fi targetFile = targetDir.child(entry.sanitizedFileName);
        if(targetFile.exists()){
            entry.isDuplicate = true;
            entry.duplicateReason = getLocalizedText("packloader.duplicate.file", targetFile.name());
        }
    }

    /** Deletes materialized SAF ZIPs referenced by the supplied entries. */
    public void cleanupTemporaryZips(Seq<PackEntry> entries){
        if(entries == null) return;
        HashMap<String, Fi> temps = new HashMap<>();
        for(PackEntry entry : entries){
            if(entry.temporaryZipFile == null) continue;
            String path;
            try{
                path = entry.temporaryZipFile.absolutePath();
            }catch(Throwable t){
                path = entry.temporaryZipFile.path();
            }
            temps.put(path, entry.temporaryZipFile);
            entry.temporaryZipFile = null;
        }

        for(Entry<String, Fi> temp : temps.entrySet()){
            deleteTemp(temp.getValue());
        }
    }

    /** Deletes every SAF ZIP materialized by this scanner. */
    public void cleanupAllTemporaryZips(){
        Seq<Fi> copy;
        synchronized(tempLock){
            copy = new Seq<>(temporaryZips);
            temporaryZips.clear();
        }
        for(Fi temp : copy){
            try{
                if(temp.exists()) temp.delete();
            }catch(Throwable ignored){}
        }
    }

    private void checkCancelled(ScanListener listener) throws ScanCancelledException{
        if(listener != null && listener.isCancelled()){
            throw new ScanCancelledException();
        }
    }

    private void discardTemp(Fi temp){
        if(temp == null) return;
        deleteTemp(temp);
    }

    private void deleteTemp(Fi temp){
        if(temp == null) return;
        synchronized(tempLock){
            for(int i = temporaryZips.size - 1; i >= 0; i--){
                if(samePath(temporaryZips.get(i), temp)){
                    temporaryZips.remove(i);
                }
            }
        }
        try{
            if(temp.exists()) temp.delete();
        }catch(Throwable ignored){}
    }

    private static boolean samePath(Fi a, Fi b){
        if(a == null || b == null) return false;
        try{
            return a.absolutePath().equals(b.absolutePath());
        }catch(Throwable t){
            return a.path().equals(b.path());
        }
    }

    private void pruneUntrackedSafTemps(Fi tmpDir){
        Fi[] files;
        try{
            files = tmpDir.list();
        }catch(Throwable t){
            return;
        }
        if(files == null || files.length == 0) return;

        Seq<Fi> untracked = new Seq<>();
        long total = 0;
        for(Fi file : files){
            try{
                if(file.isDirectory() || !file.name().startsWith(SAF_TEMP_PREFIX)) continue;
                if(isTracked(file)) continue;
                untracked.add(file);
                total += file.length();
            }catch(Throwable ignored){}
        }
        if(untracked.isEmpty()) return;

        untracked.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int count = untracked.size;
        for(Fi file : untracked){
            if(count <= MAX_UNTRACKED_SAF_ZIPS && total <= MAX_UNTRACKED_SAF_ZIP_BYTES) break;
            long length = file.length();
            try{
                if(file.delete()){
                    count--;
                    total -= length;
                }
            }catch(Throwable ignored){}
        }
    }

    private boolean isTracked(Fi file){
        synchronized(tempLock){
            for(Fi temp : temporaryZips){
                if(samePath(temp, file)) return true;
            }
        }
        return false;
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

    private static final class ResolvedZip{
        final Fi zip;
        final Fi temporary;

        ResolvedZip(Fi zip, Fi temporary){
            this.zip = zip;
            this.temporary = temporary;
        }
    }
}

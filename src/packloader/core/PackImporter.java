package packloader.core;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.maps.Map;
import mindustry.world.blocks.storage.CoreBlock;
import packloader.model.DuplicatePolicy;
import packloader.model.ImportItemResult;
import packloader.model.ImportReport;
import packloader.model.PackEntry;
import packloader.model.PackType;
import packloader.util.MainThread;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

import static packloader.util.Localization.getLocalizedText;

public class PackImporter{
    private final Fi mapDirectory;
    private final Fi schematicDirectory;

    /** Receives progress snapshots from the import worker. Callbacks are posted to the main thread. */
    public interface ImportListener{
        void onProgress(ImportProgress progress);

        default boolean isCancelled(){
            return false;
        }
    }

    /** Immutable progress snapshot; counters always describe the current import run. */
    public static final class ImportProgress{
        public final String currentFile;
        public final int processed;
        public final int total;
        public final int success;
        public final int failed;
        public final int skipped;
        public final int overwritten;

        public ImportProgress(String currentFile, int processed, int total, int success, int failed, int skipped, int overwritten){
            this.currentFile = currentFile == null ? "" : currentFile;
            this.processed = processed;
            this.total = total;
            this.success = success;
            this.failed = failed;
            this.skipped = skipped;
            this.overwritten = overwritten;
        }
    }

    public PackImporter(){
        this(Vars.customMapDirectory, Vars.schematicDirectory);
    }

    public PackImporter(Fi mapDirectory, Fi schematicDirectory){
        this.mapDirectory = mapDirectory;
        this.schematicDirectory = schematicDirectory;
    }

    /**
     * Imports all selected entries using the specified duplicate conflict policy.
     * File parsing and staging run on the calling (worker) thread; game registry mutations
     * are dispatched to the main thread through {@link MainThread}.
     */
    public ImportReport importEntries(Seq<PackEntry> entries, DuplicatePolicy policy, ImportListener listener){
        return importEntries(entries, policy, policy, listener);
    }

    /**
     * Imports all selected entries using independent per-type conflict policy presets for Maps and Schematics.
     */
    public ImportReport importEntries(Seq<PackEntry> entries, DuplicatePolicy mapPolicy, DuplicatePolicy schemPolicy,
                                      ImportListener listener){
        ImportReport report = new ImportReport();
        long startTime = System.currentTimeMillis();

        if(entries == null || entries.isEmpty()){
            report.elapsedMillis = 0;
            return report;
        }

        Seq<PackEntry> toImport = entries.select(e -> e.selected);
        report.totalSelected = toImport.size;
        if(toImport.isEmpty()){
            report.elapsedMillis = 0;
            return report;
        }

        boolean anyMapsImported = false;
        boolean anySchematicsImported = false;
        Fi stagingRoot = createStagingRoot();

        try{
            for(int i = 0; i < toImport.size; i++){
                if(listener != null && listener.isCancelled()){
                    report.cancelled = true;
                    break;
                }

                PackEntry entry = toImport.get(i);
                notifyProgress(listener, entry, toImport.size, report);

                if(!entry.isValid){
                    report.addResult(ImportItemResult.failed(entry,
                        entry.errorReason == null || entry.errorReason.isEmpty() ?
                            getLocalizedText("packloader.error.validation", "invalid file") : entry.errorReason));
                    notifyProgress(listener, entry, toImport.size, report);
                    continue;
                }

                DuplicatePolicy policy = entry.type == PackType.MAP ? mapPolicy : schemPolicy;
                if(policy == null) policy = DuplicatePolicy.SKIP;

                boolean imported = false;
                if(entry.type == PackType.MAP && isNativeMapTarget()){
                    imported = importNativeMap(entry, policy, report, stagingRoot, i);
                    anyMapsImported |= imported && report.results.peek().status != ImportItemResult.Status.FAILED;
                }else if(entry.type == PackType.SCHEMATIC && isNativeSchematicTarget()){
                    imported = importNativeSchematic(entry, policy, report, stagingRoot, i);
                    anySchematicsImported |= imported && report.results.peek().status != ImportItemResult.Status.FAILED;
                }else{
                    imported = importFallback(entry, policy, report);
                    if(imported && report.results.peek().status != ImportItemResult.Status.FAILED &&
                       report.results.peek().status != ImportItemResult.Status.SKIPPED){
                        if(entry.type == PackType.MAP) anyMapsImported = true;
                        else anySchematicsImported = true;
                    }
                }

                notifyProgress(listener, entry, toImport.size, report);
            }
        }finally{
            try{
                if(stagingRoot.exists()) stagingRoot.deleteDirectory();
            }catch(Throwable ignored){}
        }

        if(listener != null && !report.cancelled){
            notifyProgress(listener, null, toImport.size, report);
        }

        refreshGameSubsystems(anyMapsImported, anySchematicsImported);

        report.elapsedMillis = System.currentTimeMillis() - startTime;
        return report;
    }

    // ---------------------------------------------------------------------
    // Native map/schematic imports (main-thread game registry access)
    // ---------------------------------------------------------------------

    private boolean importNativeMap(PackEntry entry, DuplicatePolicy policy, ImportReport report, Fi stagingRoot, int index){
        Fi entryDir = prepareEntryDir(stagingRoot, index);
        Fi staged = entryDir.child("work.tmp");
        try{
            entry.copyTo(staged);
            if(staged.length() == 0){
                throw new IOException(getLocalizedText("packloader.error.empty-file"));
            }

            PackFormat.MapInfo info = PackFormat.validateMap(staged.read());
            String expectedName = info.name;
            String baseName = mapBaseName(info.name);

            NativeResult conflict = MainThread.call(() -> findMapConflict(info.name));
            Map overwriteTarget = null;

            if(conflict != null && conflict.map != null){
                if(policy == DuplicatePolicy.SKIP){
                    report.addResult(ImportItemResult.skipped(entry,
                        getLocalizedText("packloader.result.msg.skipped", info.name)));
                    return false;
                }else if(policy == DuplicatePolicy.OVERWRITE){
                    if(!canReplaceMap(conflict.map)){
                        report.addResult(ImportItemResult.failed(entry,
                            getLocalizedText("packloader.error.protected-map", info.name)));
                        return false;
                    }
                    overwriteTarget = conflict.map;
                }else if(policy == DuplicatePolicy.RENAME){
                    expectedName = MainThread.call(() -> findUniqueMapName(info.name));
                    Fi renamed = entryDir.child("renamed.tmp");
                    try(InputStream in = staged.read();
                        OutputStream out = renamed.write(false, 8192)){
                        PackFormat.renameMap(in, out, expectedName);
                    }
                    staged.delete();
                    staged = renamed;
                    PackFormat.MapInfo renamedInfo = PackFormat.validateMap(staged.read());
                    baseName = mapBaseName(renamedInfo.name);
                }
            }

            // Maps.importMap requires a "name" tag in map metadata; savegames and exported saves
            // often only contain "mapname". Ensure "name" is injected before game import.
            if(!info.hasNameTag && "work.tmp".equals(staged.name())){
                Fi normalized = entryDir.child("normalized.tmp");
                try(InputStream in = staged.read();
                    OutputStream out = normalized.write(false, 8192)){
                    PackFormat.renameMap(in, out, expectedName);
                }
                staged.delete();
                staged = normalized;
            }

            // Maps.importMap uses file.name() as the destination base and appends .msav.
            // Stage a file whose name is exactly the sanitized base name (no extension).
            Fi importFile = entryDir.child(baseName);
            staged.moveTo(importFile);

            Map target = overwriteTarget;
            String verifyName = expectedName;
            Fi importedFile = MainThread.call(() -> {
                Map imported = null;
                try{
                    imported = Vars.maps.importMap(importFile);
                    if(imported == null || imported.file == null || !imported.file.exists() || imported.file.length() <= 0){
                        throw new IOException("Map file was not written");
                    }
                    if(!isWithinDirectory(Vars.customMapDirectory, imported.file)){
                        throw new IOException("Map was written outside the game map directory");
                    }
                    if(imported.name() == null || !imported.name().equalsIgnoreCase(verifyName)){
                        throw new IOException("Imported map name did not match the requested name");
                    }

                    // Validate the file the game actually wrote, not just the staged copy.
                    PackFormat.validateMap(imported.file.read());

                    if(target != null){
                        // Import-first: only remove the old custom map after the new file is
                        // verified. If the old file cannot be removed, fail the import and
                        // restore the registry entry instead of reporting a false overwrite.
                        removeMapForOverwrite(target);
                    }
                    return imported.file;
                }catch(Throwable t){
                    if(imported != null){
                        try{
                            Vars.maps.removeMap(imported);
                        }catch(Throwable ignored){}
                    }
                    if(t instanceof IOException io) throw new RuntimeException(io);
                    throw t instanceof RuntimeException re ? re : new RuntimeException(t);
                }
            });

            if(target != null){
                report.addResult(ImportItemResult.overwritten(entry, importedFile));
            }else{
                report.addResult(ImportItemResult.success(entry, importedFile));
            }
            return true;
        }catch(Throwable t){
            recordFailure(report, entry, "Native Map import error", t);
            return false;
        }finally{
            try{
                if(entryDir.exists()) entryDir.deleteDirectory();
            }catch(Throwable ignored){}
        }
    }

    private boolean importNativeSchematic(PackEntry entry, DuplicatePolicy policy, ImportReport report, Fi stagingRoot, int index){
        Fi entryDir = prepareEntryDir(stagingRoot, index);
        Fi staged = entryDir.child("work.tmp");
        try{
            entry.copyTo(staged);
            if(staged.length() == 0){
                throw new IOException(getLocalizedText("packloader.error.empty-file"));
            }

            // Read through an InputStream: Schematics.read(Fi) sets s.file to the staged
            // temp file, which would make the post-write verification meaningless.
            Schematic schematic = PackFormat.readSchematic(staged.read());
            if(!schematic.tags.containsKey("name") || schematic.name().trim().isEmpty()){
                // Schematics.read(Fi) derives a missing name from the file name. Do the
                // same here so a valid nameless file can still be imported natively.
                schematic.tags.put("name", PackEntry.extractBaseName(entry.sanitizedFileName));
            }
            String originalName = schematic.name();

            NativeResult result = MainThread.call(() ->
                addNativeSchematic(entry, schematic, originalName, policy));

            switch(result.status){
                case SKIPPED -> report.addResult(ImportItemResult.skipped(entry,
                    getLocalizedText("packloader.result.msg.skipped", originalName)));
                case FAILED -> report.addResult(ImportItemResult.failed(entry,
                    result.message == null ? getLocalizedText("packloader.result.msg.failed", originalName) : result.message));
                case OVERWRITTEN -> report.addResult(ImportItemResult.overwritten(entry, result.destination));
                default -> report.addResult(ImportItemResult.success(entry, result.destination));
            }
            return result.status != ImportItemResult.Status.FAILED && result.status != ImportItemResult.Status.SKIPPED;
        }catch(Throwable t){
            recordFailure(report, entry, "Native Schematic import error", t);
            return false;
        }finally{
            try{
                if(entryDir.exists()) entryDir.deleteDirectory();
            }catch(Throwable ignored){}
        }
    }

    private NativeResult addNativeSchematic(PackEntry entry, Schematic schematic, String originalName, DuplicatePolicy policy){
        if(Vars.schematics == null){
            return NativeResult.failed(getLocalizedText("packloader.error.target-unavailable"));
        }

        Schematic conflict = findSchematicConflict(originalName);
        Schematic overwriteTarget = null;

        if(conflict != null){
            if(policy == DuplicatePolicy.SKIP){
                return NativeResult.skipped(originalName);
            }else if(policy == DuplicatePolicy.OVERWRITE){
                if(!canReplaceSchematic(conflict)){
                    return NativeResult.failed(getLocalizedText("packloader.error.protected-schematic", originalName));
                }
                overwriteTarget = conflict;
            }else if(policy == DuplicatePolicy.RENAME){
                schematic.tags.put("name", findUniqueSchematicName(originalName));
            }
        }

        try{
            String expectedName = schematic.name();
            Vars.schematics.add(schematic);

            // H2: SUCCESS is only reported after the destination file really exists and
            // contains a schematic that can be read back from disk.
            if(schematic.file == null || !schematic.file.exists() || schematic.file.length() <= 0){
                throw new IOException("Schematic file was not written");
            }
            if(!isWithinDirectory(schematicDirectory, schematic.file) ||
               !isWithinDirectory(Vars.schematicDirectory, schematic.file)){
                throw new IOException("Schematic was written outside the game directory");
            }

            Schematic written = PackFormat.readSchematic(schematic.file.file());
            if(written.name() == null || !written.name().equals(expectedName)){
                throw new IOException("Written schematic could not be read back correctly");
            }
        }catch(Throwable t){
            rollbackNativeSchematic(schematic);
            Log.err("[PackLoader] Native schematic write failed for '@'.", originalName, t);
            return NativeResult.failed(getLocalizedText("packloader.result.msg.failed",
                t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }

        if(overwriteTarget != null){
            // Snapshot loadout membership before removal so a failed delete can restore the
            // old schematic to the same derived caches.
            Seq<CoreBlock> loadoutBlocks = snapshotSchematicLoadouts(overwriteTarget);
            try{
                removeSchematicForOverwrite(overwriteTarget);
            }catch(Throwable t){
                restoreSchematicRegistration(overwriteTarget, loadoutBlocks);
                rollbackNativeSchematic(schematic);
                Log.err("[PackLoader] Imported schematic, but could not remove the old file.", t);
                return NativeResult.failed(getLocalizedText("packloader.result.msg.failed",
                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
            return NativeResult.overwritten(schematic.file);
        }
        return NativeResult.success(schematic.file);
    }

    /** Removes the old map and verifies its file is gone; restores the registry on failure. */
    private static void removeMapForOverwrite(Map target) throws IOException{
        try{
            Vars.maps.removeMap(target);
        }catch(Throwable t){
            restoreMapRegistration(target);
            throw new IOException("Could not remove the existing map file", t);
        }

        if(target.file != null && target.file.exists()){
            restoreMapRegistration(target);
            throw new IOException("Could not remove the existing map file");
        }
    }

    private static void restoreMapRegistration(Map map){
        if(map == null || Vars.maps == null) return;
        try{
            Seq<Map> all = Vars.maps.all();
            if(!all.contains(map, true)){
                all.add(map);
                all.sort();
            }
        }catch(Throwable t){
            Log.err("[PackLoader] Could not restore the previous map registration.", t);
        }
    }

    /** Removes the old schematic and verifies its file is gone before reporting overwrite. */
    private static void removeSchematicForOverwrite(Schematic target) throws IOException{
        try{
            Vars.schematics.remove(target);
        }catch(Throwable t){
            throw new IOException("Could not remove the existing schematic file", t);
        }

        if(target.file != null && target.file.exists()){
            throw new IOException("Could not remove the existing schematic file");
        }
    }

    private static Seq<CoreBlock> snapshotSchematicLoadouts(Schematic schematic){
        Seq<CoreBlock> blocks = new Seq<>();
        try{
            Vars.schematics.getLoadouts().each((block, list) -> {
                if(list.contains(schematic, true)) blocks.add(block);
            });
        }catch(Throwable ignored){
        }
        return blocks;
    }

    private static void restoreSchematicRegistration(Schematic schematic, Seq<CoreBlock> loadoutBlocks){
        if(schematic == null || Vars.schematics == null) return;
        try{
            if(!Vars.schematics.all().contains(schematic, true)){
                Vars.schematics.all().add(schematic);
                Vars.schematics.all().sort();
            }
            if(loadoutBlocks != null){
                for(CoreBlock block : loadoutBlocks){
                    Seq<Schematic> list = Vars.schematics.getLoadouts().get(block);
                    if(list != null && !list.contains(schematic, true)){
                        list.add(schematic);
                    }
                }
            }
        }catch(Throwable t){
            Log.err("[PackLoader] Could not restore the previous schematic registration.", t);
        }
    }

    // ---------------------------------------------------------------------
    // Fallback (headless tests / custom target directories)
    // ---------------------------------------------------------------------

    private boolean importFallback(PackEntry entry, DuplicatePolicy policy, ImportReport report){
        Fi targetDir = (entry.type == PackType.MAP) ? mapDirectory : schematicDirectory;
        if(targetDir == null){
            report.addResult(ImportItemResult.failed(entry, getLocalizedText("packloader.error.target-unavailable")));
            return false;
        }

        if(!targetDir.exists()){
            targetDir.mkdirs();
        }

        try{
            String targetFileName = entry.sanitizedFileName;
            Fi targetFile = targetDir.child(targetFileName);
            if(!isWithinDirectory(targetDir, targetFile)){
                report.addResult(ImportItemResult.failed(entry, getLocalizedText("packloader.error.path-escape")));
                return false;
            }

            boolean fileExists = targetFile.exists();
            // Duplicate badges are scan-time snapshots. A file that disappeared since the
            // scan must not force a needless SKIP or RENAME; use the live filesystem state.
            boolean isConflict = fileExists;

            if(isConflict){
                if(policy == DuplicatePolicy.SKIP){
                    report.addResult(ImportItemResult.skipped(entry,
                        getLocalizedText("packloader.result.msg.skipped", targetFileName)));
                    return true;
                }else if(policy == DuplicatePolicy.RENAME){
                    String baseName = PackEntry.extractBaseName(entry.sanitizedFileName);
                    targetFile = findUniqueFile(targetDir, baseName, entry.type.extension);
                    fileExists = targetFile.exists();
                }
                // For OVERWRITE, targetFile remains as is.
            }

            boolean isOverwrite = fileExists && policy == DuplicatePolicy.OVERWRITE;
            safeExtract(entry, targetFile, isOverwrite);

            if(isOverwrite){
                report.addResult(ImportItemResult.overwritten(entry, targetFile));
            }else{
                report.addResult(ImportItemResult.success(entry, targetFile));
            }
            return true;
        }catch(Throwable t){
            recordFailure(report, entry, "Import error", t);
            return false;
        }
    }

    /**
     * Stages a copy next to the destination, validates it, backs up any existing file,
     * and only then moves the staged file into place. On failure the old file is restored
     * when the destination is still free; if another writer recreated it, that file is left
     * untouched and the original is preserved next to it as a backup for manual recovery.
     */
    private void safeExtract(PackEntry entry, Fi targetFile, boolean overwrite) throws IOException{
        Fi tempFile = targetFile.parent().child(targetFile.name() + "." + System.nanoTime() + ".packload_tmp");
        Fi backupFile = null;
        boolean movedOld = false;

        try{
            entry.copyTo(tempFile);
            if(tempFile.length() == 0){
                throw new IOException(getLocalizedText("packloader.error.empty-file"));
            }
            validateStagedEntry(entry, tempFile);

            if(overwrite && targetFile.exists()){
                backupFile = targetFile.parent().child(targetFile.name() + "." + System.nanoTime() + ".packload_backup");
                moveFile(targetFile, backupFile);
                movedOld = true;
            }

            moveStagedToTarget(tempFile, targetFile);

            if(!targetFile.exists() || targetFile.length() == 0){
                throw new IOException(getLocalizedText("packloader.error.empty-file"));
            }

            if(backupFile != null && backupFile.exists()){
                try{
                    backupFile.delete();
                }catch(Throwable t){
                    Log.err("[PackLoader] Could not delete backup file '@'.", backupFile.path(), t);
                }
            }
        }catch(Throwable t){
            if(movedOld && backupFile != null && backupFile.exists()){
                try{
                    if(targetFile.exists()){
                        // A new file raced into the destination after the original was
                        // backed up. Never delete it; keep the original backup intact for
                        // manual recovery instead of destroying somebody else's write.
                        Log.err("[PackLoader] Rollback skipped because '@' was recreated; original preserved at '@'.",
                            targetFile.path(), backupFile.path());
                    }else{
                        moveFile(backupFile, targetFile);
                    }
                }catch(Throwable restoreError){
                    t.addSuppressed(restoreError);
                    Log.err("[PackLoader] Rollback failed; original file remains at '@'.", backupFile.path(), restoreError);
                }
            }

            try{
                if(tempFile.exists()) tempFile.delete();
            }catch(Throwable ignored){}

            if(t instanceof IOException io) throw io;
            throw new IOException(t.getMessage(), t);
        }
    }

    /** Package-private for rollback tests; never overwrites an existing destination. */
    void moveStagedToTarget(Fi staged, Fi target) throws IOException{
        moveFile(staged, target);
    }

    private static void moveFile(Fi source, Fi target) throws IOException{
        // Deliberately no REPLACE_EXISTING and no ATOMIC_MOVE: ATOMIC_MOVE may replace an
        // existing target on some platforms, which would silently destroy a racing file.
        Files.move(source.file().toPath(), target.file().toPath());
    }

    private void validateStagedEntry(PackEntry entry, Fi staged) throws IOException{
        try(InputStream in = staged.read()){
            if(entry.type == PackType.MAP){
                PackFormat.validateMap(in);
            }else{
                PackFormat.readSchematic(in);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Fi createStagingRoot(){
        try{
            Fi tmp = Vars.tmpDirectory;
            if(tmp != null && tmp.exists()){
                Fi root = tmp.child("packloader_import_" + System.nanoTime());
                if(root.mkdirs() || root.exists()) return root;
            }
        }catch(Throwable ignored){}
        return Fi.tempDirectory("packloader_import");
    }

    private static Fi prepareEntryDir(Fi stagingRoot, int index){
        Fi dir = stagingRoot.child("entry_" + index + "_" + System.nanoTime());
        dir.mkdirs();
        return dir;
    }

    private static String mapBaseName(String name){
        String clean = Strings.sanitizeFilename(name == null ? "" : name.trim());
        if(clean.toLowerCase().endsWith(".msav")){
            clean = clean.substring(0, clean.length() - 5);
        }
        clean = clean.trim();
        if(clean.isEmpty()) clean = "map";
        return clean;
    }

    private static NativeResult findMapConflict(String mapName){
        if(Vars.maps == null || mapName == null) return NativeResult.success(null);
        Map conflict = Vars.maps.all().find(m -> m.name() != null && m.name().equalsIgnoreCase(mapName));
        return conflict == null ? NativeResult.success(null) : NativeResult.conflict(conflict);
    }

    private static Schematic findSchematicConflict(String schematicName){
        if(Vars.schematics == null || schematicName == null) return null;
        return Vars.schematics.all().find(s -> s.name() != null && s.name().equalsIgnoreCase(schematicName));
    }

    private static boolean canReplaceSchematic(Schematic conflict){
        if(conflict == null || conflict.mod != null || conflict.file == null ||
           !conflict.file.exists() || conflict.file.length() <= 0) return false;
        Fi directory = Vars.schematicDirectory;
        if(directory == null) return false;
        return isWithinDirectory(directory, conflict.file);
    }

    private static boolean canReplaceMap(Map conflict){
        if(conflict == null || !conflict.custom || conflict.workshop || conflict.mod != null ||
           conflict.file == null || !conflict.file.exists() || conflict.file.length() <= 0){
            return false;
        }
        Fi directory = Vars.customMapDirectory;
        if(directory == null) return false;
        return isWithinDirectory(directory, conflict.file);
    }

    private void rollbackNativeSchematic(Schematic schematic){
        if(schematic == null) return;

        boolean inside = schematic.file != null &&
            isWithinDirectory(schematicDirectory, schematic.file) &&
            isWithinDirectory(Vars.schematicDirectory, schematic.file);
        if(inside){
            try{
                if(schematic.file.exists()) schematic.file.delete();
            }catch(Throwable ignored){}
        }

        // Use the game's removal path so the schematic is also dropped from loadout and
        // preview caches. Clear the file first: remove() would delete an outside file that
        // PackLoader must preserve when a write went to the wrong location.
        schematic.file = null;
        try{
            Vars.schematics.remove(schematic);
        }catch(Throwable t){
            Log.err("[PackLoader] Could not remove the failed schematic registration.", t);
            unregisterSchematic(schematic);
        }
    }

    private static void unregisterSchematic(Schematic schematic){
        if(schematic == null || Vars.schematics == null) return;
        try{
            Vars.schematics.all().remove(schematic, true);
        }catch(Throwable ignored){}
        try{
            Vars.schematics.getLoadouts().each((block, list) -> list.remove(schematic, true));
        }catch(Throwable t){
            Log.err("[PackLoader] Could not remove the failed schematic from loadout caches.", t);
        }
    }

    private boolean isNativeMapTarget(){
        return Vars.maps != null && mapDirectory != null && Vars.customMapDirectory != null &&
            samePath(mapDirectory, Vars.customMapDirectory);
    }

    private boolean isNativeSchematicTarget(){
        return Vars.schematics != null && schematicDirectory != null && Vars.schematicDirectory != null &&
            samePath(schematicDirectory, Vars.schematicDirectory);
    }

    private static boolean samePath(Fi a, Fi b){
        if(a == null || b == null) return false;
        if(a == b) return true;
        try{
            return a.file().getCanonicalFile().equals(b.file().getCanonicalFile());
        }catch(Throwable t){
            try{
                return a.file().getAbsoluteFile().equals(b.file().getAbsoluteFile());
            }catch(Throwable ignored){
                return a.path().equals(b.path());
            }
        }
    }

    private void notifyProgress(ImportListener listener, PackEntry entry, int total, ImportReport report){
        if(listener == null) return;
        ImportProgress progress = new ImportProgress(
            entry == null ? "" : entry.sanitizedFileName,
            report.processed(),
            total,
            report.totalSuccess,
            report.totalFailed,
            report.totalSkipped,
            report.totalOverwritten
        );
        MainThread.post(() -> listener.onProgress(progress));
    }

    public static Fi findUniqueFile(Fi dir, String baseName, String extension){
        String clean = Strings.sanitizeFilename(baseName);
        if(clean.isEmpty()) clean = "unnamed";

        Fi target = dir.child(clean + "." + extension);
        int index = 1;
        while(target.exists()){
            target = dir.child(clean + "_" + index + "." + extension);
            index++;
        }
        return target;
    }

    public static String findUniqueSchematicName(String baseName, java.util.Set<String> extraTakenNames){
        int index = 1;
        String name = baseName + " " + index;
        while(isSchematicNameTaken(name) || containsIgnoreCase(extraTakenNames, name)){
            index++;
            name = baseName + " " + index;
        }
        return name;
    }

    public static String findUniqueSchematicName(String baseName){
        return findUniqueSchematicName(baseName, null);
    }

    public static String findUniqueMapName(String baseName, java.util.Set<String> extraTakenNames){
        int index = 1;
        String name = baseName + "_" + index;
        while(isMapNameTaken(name) || containsIgnoreCase(extraTakenNames, name)){
            index++;
            name = baseName + "_" + index;
        }
        return name;
    }

    public static String findUniqueMapName(String baseName){
        return findUniqueMapName(baseName, null);
    }

    public static boolean containsIgnoreCase(java.util.Set<String> set, String target){
        if(set == null || target == null || set.isEmpty()) return false;
        for(String s : set){
            if(s != null && s.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    public static boolean isSchematicNameTaken(String name){
        if(Vars.schematics == null) return false;
        for(Schematic sc : Vars.schematics.all()){
            if(sc.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static boolean isMapNameTaken(String name){
        if(Vars.maps == null) return false;
        for(Map map : Vars.maps.all()){
            if(map.name() != null && map.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static boolean isWithinDirectory(Fi parent, Fi child){
        if(parent == null || child == null) return false;
        try{
            String parentPath = parent.file().getCanonicalPath();
            String childPath = child.file().getCanonicalPath();
            return childPath.startsWith(parentPath + File.separator) || childPath.equals(parentPath);
        }catch(IOException e){
            return false;
        }
    }

    /** Safely refreshes Mindustry UI without destroying map textures or existing instances. */
    public void refreshGameSubsystems(boolean reloadMaps, boolean reloadSchematics){
        Runnable refreshAction = () -> {
            try{
                if(reloadMaps && Vars.maps != null){
                    try{
                        Vars.maps.loadPreviews();
                    }catch(Throwable ignored){}

                    if(Vars.ui != null){
                        if(Vars.ui.maps != null) invokeSetup(Vars.ui.maps);
                        if(Vars.ui.custom != null) invokeSetup(Vars.ui.custom);
                    }
                    Log.info("[PackLoader] Mindustry custom maps UI refreshed safely.");
                }
            }catch(Throwable t){
                Log.err("[PackLoader] Error refreshing Maps UI:", t);
            }

            try{
                if(reloadSchematics && Vars.schematics != null){
                    if(Vars.ui != null && Vars.ui.schematics != null){
                        invokeSetup(Vars.ui.schematics);
                    }
                    Log.info("[PackLoader] PackLoader schematics UI refreshed safely.");
                }
            }catch(Throwable t){
                Log.err("[PackLoader] Error refreshing Schematics UI:", t);
            }
        };

        MainThread.post(refreshAction);
    }

    private void recordFailure(ImportReport report, PackEntry entry, String logPrefix, Throwable t){
        Log.err("[PackLoader] " + logPrefix + " for '@':", entry.sourcePath, t);
        Throwable cause = t;
        while(cause instanceof RuntimeException && cause.getCause() != null && !(cause.getCause() instanceof RuntimeException)){
            cause = cause.getCause();
        }
        String msg = (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        report.addResult(ImportItemResult.failed(entry, getLocalizedText("packloader.result.msg.failed", msg)));
    }

    private static void invokeSetup(Object dialog){
        if(dialog == null) return;
        Class<?> clazz = dialog.getClass();
        while(clazz != null && clazz != Object.class){
            try{
                java.lang.reflect.Method m = clazz.getDeclaredMethod("setup");
                m.setAccessible(true);
                m.invoke(dialog);
                return;
            }catch(NoSuchMethodException e){
                clazz = clazz.getSuperclass();
            }catch(Throwable ignored){
                return;
            }
        }
    }

    private static final class NativeResult{
        final ImportItemResult.Status status;
        final Fi destination;
        final String message;
        final Map map;

        private NativeResult(ImportItemResult.Status status, Fi destination, String message, Map map){
            this.status = status;
            this.destination = destination;
            this.message = message;
            this.map = map;
        }

        static NativeResult success(Fi destination){
            return new NativeResult(ImportItemResult.Status.SUCCESS, destination, null, null);
        }

        static NativeResult overwritten(Fi destination){
            return new NativeResult(ImportItemResult.Status.OVERWRITTEN, destination, null, null);
        }

        static NativeResult skipped(String name){
            return new NativeResult(ImportItemResult.Status.SKIPPED, null, name, null);
        }

        static NativeResult failed(String message){
            return new NativeResult(ImportItemResult.Status.FAILED, null, message, null);
        }

        static NativeResult conflict(Map map){
            return new NativeResult(ImportItemResult.Status.SUCCESS, null, null, map);
        }
    }
}

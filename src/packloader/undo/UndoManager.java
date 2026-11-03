package packloader.undo;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.maps.Map;
import packloader.core.PackFormat;
import packloader.core.PackImporter;
import packloader.model.PackEntry;
import packloader.model.PackType;
import packloader.util.MainThread;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Manages persistent import transaction manifests, pre-write backup creation,
 * pre-undo safety verification (tamper/modification detection), and in-place atomic rollback.
 */
public class UndoManager{
    public static final int MAX_RETAINED_TRANSACTIONS = 5;
    public static final long MAX_TOTAL_BACKUP_BYTES = 50 * 1024 * 1024L; // 50MB

    public final Fi backupRoot;
    public final Fi mapDirectory;
    public final Fi schematicDirectory;

    public interface UndoListener{
        void onProgress(int current, int total, String currentFile);
    }

    public static class BackupRecord{
        public final String fileName;
        public final String sha256;
        public final long size;

        public BackupRecord(String fileName, String sha256, long size){
            this.fileName = fileName;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    public static class UndoResult{
        public final ImportTransaction transaction;
        public final int deletedCount;
        public final int restoredCount;
        public final int conflictCount;
        public final Seq<String> details = new Seq<>();
        public final boolean success;

        public UndoResult(ImportTransaction transaction, int deletedCount, int restoredCount, int conflictCount, boolean success){
            this.transaction = transaction;
            this.deletedCount = deletedCount;
            this.restoredCount = restoredCount;
            this.conflictCount = conflictCount;
            this.success = success;
        }
    }

    public UndoManager(){
        this(defaultBackupRoot(), Vars.customMapDirectory, Vars.schematicDirectory);
    }

    public UndoManager(Fi backupRoot, Fi mapDirectory, Fi schematicDirectory){
        this.backupRoot = backupRoot != null ? backupRoot : defaultBackupRoot();
        this.mapDirectory = mapDirectory != null ? mapDirectory : Vars.customMapDirectory;
        this.schematicDirectory = schematicDirectory != null ? schematicDirectory : Vars.schematicDirectory;

        try{
            if(!this.backupRoot.exists()){
                this.backupRoot.mkdirs();
            }
        }catch(Throwable ignored){}
    }

    public static Fi defaultBackupRoot(){
        if(Vars.dataDirectory != null && Vars.dataDirectory.exists()){
            return Vars.dataDirectory.child("packloader_backups");
        }
        return new Fi("packloader_backups");
    }

    public Fi getTxDir(ImportTransaction tx){
        return backupRoot.child(tx.id);
    }

    // ---------------------------------------------------------------------
    // Transaction Lifecycle & Pre-Write Backups
    // ---------------------------------------------------------------------

    public ImportTransaction createTransaction(String sourceName){
        String id = "tx_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
        ImportTransaction tx = new ImportTransaction(id, System.currentTimeMillis(), sourceName);
        Fi txDir = getTxDir(tx);
        txDir.mkdirs();
        return tx;
    }

    /**
     * Safely creates a durable backup copy of an existing file before it is replaced.
     * Validates path boundaries, destination existence, and format integrity.
     */
    public BackupRecord backupFile(ImportTransaction tx, int entryIndex, Fi existingFile, PackType type) throws IOException{
        if(existingFile == null || !existingFile.exists()){
            throw new IOException("Cannot backup non-existent file: " + (existingFile != null ? existingFile.name() : "null"));
        }

        Fi managedDir = type == PackType.MAP ? mapDirectory : schematicDirectory;
        if(managedDir != null && !PackImporter.isWithinDirectory(managedDir, existingFile)){
            throw new IOException("Security violation: file is outside managed directory: " + existingFile.path());
        }

        Fi txDir = getTxDir(tx);
        if(!txDir.exists()) txDir.mkdirs();

        String backupName = "backup_" + entryIndex + "_" + existingFile.name();
        Fi backupFile = txDir.child(backupName);

        // Copy file
        existingFile.copyTo(backupFile);

        if(!backupFile.exists() || backupFile.length() <= 0){
            throw new IOException("Backup write failed or file is empty: " + backupName);
        }

        // Validate format
        if(type == PackType.MAP){
            try(InputStream in = backupFile.read()){
                PackFormat.validateMap(in);
            }
        }else if(type == PackType.SCHEMATIC){
            PackFormat.readSchematic(backupFile.file());
        }

        String sha256 = computeSha256(backupFile);
        long size = backupFile.length();
        tx.totalBackupBytes += size;

        return new BackupRecord(backupName, sha256, size);
    }

    public void recordEntry(ImportTransaction tx, PackEntry entry, TransactionAction action,
                            Fi destFile, BackupRecord backup){
        recordEntry(tx, entry, action, destFile,
            backup != null ? backup.fileName : null,
            backup != null ? backup.sha256 : null,
            backup != null ? backup.size : 0L);
    }

    public void recordEntry(ImportTransaction tx, PackEntry entry, TransactionAction action,
                            Fi destFile, String backupFileName, String backupSha256, long backupSize){
        String destPath = destFile != null ? destFile.file().getAbsolutePath() : "";
        long impSize = (destFile != null && destFile.exists()) ? destFile.length() : 0L;
        String impSha = (destFile != null && destFile.exists()) ? computeSha256(destFile) : "";

        TransactionEntry te = new TransactionEntry(
            entry.rawFileName,
            entry.displayName,
            entry.type,
            action,
            destPath,
            backupFileName,
            impSize,
            impSha,
            backupSize,
            backupSha256,
            false
        );
        tx.entries.add(te);
    }

    public void commitTransaction(ImportTransaction tx){
        if(tx == null) return;
        Fi txDir = getTxDir(tx);
        if(!txDir.exists()) txDir.mkdirs();

        Fi manifestTmp = txDir.child("manifest.json.tmp");
        Fi manifest = txDir.child("manifest.json");

        try{
            manifestTmp.writeString(tx.toJson().toString());
            manifestTmp.moveTo(manifest);
        }catch(Throwable t){
            Log.err("[PackLoader] Failed to commit transaction manifest '@': @", tx.id, t.getMessage());
        }

        cleanOldBackups();
    }

    public void rollbackPartialTransaction(ImportTransaction tx){
        if(tx == null) return;
        Log.info("[PackLoader] Rolling back partial transaction: @", tx.id);

        for(int i = tx.entries.size - 1; i >= 0; i--){
            TransactionEntry te = tx.entries.get(i);
            if(te.action == TransactionAction.SKIP) continue;

            Fi destFile = te.destinationPath.isEmpty() ? null : new Fi(te.destinationPath);
            if(te.action == TransactionAction.NEW || te.action == TransactionAction.RENAME){
                if(destFile != null && destFile.exists()){
                    try{
                        destFile.delete();
                    }catch(Throwable ignored){}
                }
            }else if(te.action == TransactionAction.OVERWRITE){
                Fi backupFile = getTxDir(tx).child(te.backupFileName);
                if(destFile != null && backupFile.exists()){
                    try{
                        backupFile.copyTo(destFile);
                    }catch(Throwable ignored){}
                }
            }
        }

        try{
            Fi txDir = getTxDir(tx);
            if(txDir.exists()) txDir.deleteDirectory();
        }catch(Throwable ignored){}
    }

    // ---------------------------------------------------------------------
    // Querying & Verification
    // ---------------------------------------------------------------------

    public boolean hasActiveTransaction(){
        return getLatestActiveTransaction() != null;
    }

    public ImportTransaction getLatestTransaction(){
        return getLatestActiveTransaction();
    }

    public ImportTransaction getLatestActiveTransaction(){
        if(!backupRoot.exists()) return null;
        Fi[] dirs = backupRoot.list();
        if(dirs == null || dirs.length == 0) return null;

        // Sort descending by directory name / timestamp
        Arrays.sort(dirs, (a, b) -> b.name().compareTo(a.name()));

        for(Fi dir : dirs){
            if(!dir.isDirectory() || !dir.name().startsWith("tx_")) continue;
            Fi manifest = dir.child("manifest.json");
            if(!manifest.exists()) continue;

            try{
                Jval json = Jval.read(manifest.readString());
                ImportTransaction tx = ImportTransaction.fromJson(json);
                if(tx != null && !tx.undone && tx.getActiveItemCount() > 0){
                    return tx;
                }
            }catch(Throwable ignored){}
        }
        return null;
    }

    /**
     * Verifies whether the transaction can be safely undone without damaging user edits.
     * Evaluates file existence, path safety, post-import modifications, and backup integrity.
     */
    public UndoVerification verifyUndo(ImportTransaction tx){
        UndoVerification ver = new UndoVerification(tx);
        if(tx == null) return ver;

        Fi txDir = getTxDir(tx);

        for(TransactionEntry te : tx.entries){
            if(te.action == TransactionAction.SKIP){
                ver.add(new UndoVerification.VerifiedEntry(te, UndoVerification.EntryStatus.SKIP, "Skipped"));
                continue;
            }

            if(te.undone){
                ver.add(new UndoVerification.VerifiedEntry(te, UndoVerification.EntryStatus.ALREADY_REMOVED, "Already undone"));
                continue;
            }

            Fi destFile = te.destinationPath.isEmpty() ? null : new Fi(te.destinationPath);
            Fi managedDir = te.type == PackType.MAP ? mapDirectory : schematicDirectory;

            // Security check: path traversal prevention
            if(destFile == null || (managedDir != null && !PackImporter.isWithinDirectory(managedDir, destFile))){
                ver.add(new UndoVerification.VerifiedEntry(te, UndoVerification.EntryStatus.SECURITY_PATH_ESCAPE, "File path escapes managed directory"));
                continue;
            }

            if(te.action == TransactionAction.NEW || te.action == TransactionAction.RENAME){
                if(!destFile.exists()){
                    ver.add(new UndoVerification.VerifiedEntry(te, UndoVerification.EntryStatus.ALREADY_REMOVED, "File was removed"));
                }else{
                    String currentSha = computeSha256(destFile);
                    if(te.importedSha256.isEmpty() || currentSha.equalsIgnoreCase(te.importedSha256)){
                        ver.add(new UndoVerification.VerifiedEntry(te, UndoVerification.EntryStatus.CAN_DELETE, "Ready to remove"));
                    }else{
                        ver.add(new UndoVerification.VerifiedEntry(te, UndoVerification.EntryStatus.CONFLICT_MODIFIED, "File was modified after import"));
                    }
                }
            }else if(te.action == TransactionAction.OVERWRITE){
                Fi backupFile = txDir.child(te.backupFileName);
                if(!backupFile.exists() || backupFile.length() <= 0){
                    ver.add(new UndoVerification.VerifiedEntry(te, UndoVerification.EntryStatus.CONFLICT_BACKUP_MISSING, "Backup file missing"));
                    continue;
                }

                String backupSha = computeSha256(backupFile);
                if(!te.backupSha256.isEmpty() && !backupSha.equalsIgnoreCase(te.backupSha256)){
                    ver.add(new UndoVerification.VerifiedEntry(te, UndoVerification.EntryStatus.CONFLICT_BACKUP_CORRUPTED, "Backup file corrupted"));
                    continue;
                }

                if(destFile.exists()){
                    String currentSha = computeSha256(destFile);
                    if(te.importedSha256.isEmpty() || currentSha.equalsIgnoreCase(te.importedSha256)){
                        ver.add(new UndoVerification.VerifiedEntry(te, UndoVerification.EntryStatus.CAN_RESTORE, "Ready to restore backup"));
                    }else{
                        ver.add(new UndoVerification.VerifiedEntry(te, UndoVerification.EntryStatus.CONFLICT_MODIFIED, "File was modified after import"));
                    }
                }else{
                    // Dest file was deleted, but backup exists: safe to restore
                    ver.add(new UndoVerification.VerifiedEntry(te, UndoVerification.EntryStatus.CAN_RESTORE, "Ready to restore backup"));
                }
            }
        }

        return ver;
    }

    // ---------------------------------------------------------------------
    // Execution
    // ---------------------------------------------------------------------

    public UndoResult executeUndo(ImportTransaction tx, UndoListener listener){
        UndoVerification ver = verifyUndo(tx);
        if(!ver.canUndoAny()){
            return new UndoResult(tx, 0, 0, ver.conflictCount, false);
        }

        int deleted = 0;
        int restored = 0;
        boolean anyMapsChanged = false;
        boolean anySchematicsChanged = false;

        Fi txDir = getTxDir(tx);
        int total = ver.verifiedEntries.size;

        for(int i = 0; i < total; i++){
            UndoVerification.VerifiedEntry ve = ver.verifiedEntries.get(i);
            if(listener != null){
                listener.onProgress(i + 1, total, ve.entry.originalFileName);
            }

            if(!ve.isActionable()) continue;

            Fi destFile = new Fi(ve.entry.destinationPath);

            try{
                if(ve.status == UndoVerification.EntryStatus.CAN_DELETE){
                    deleteImportedFile(destFile, ve.entry.type);
                    ve.entry.undone = true;
                    deleted++;
                    if(ve.entry.type == PackType.MAP) anyMapsChanged = true;
                    else anySchematicsChanged = true;
                }else if(ve.status == UndoVerification.EntryStatus.CAN_RESTORE){
                    Fi backupFile = txDir.child(ve.entry.backupFileName);
                    restoreBackupFile(backupFile, destFile, ve.entry.type);
                    ve.entry.undone = true;
                    restored++;
                    if(ve.entry.type == PackType.MAP) anyMapsChanged = true;
                    else anySchematicsChanged = true;
                }
            }catch(Throwable t){
                Log.err("[PackLoader] Undo failed for entry '@': @", ve.entry.originalFileName, t.getMessage());
            }
        }

        // Refresh game registries
        refreshGameSubsystems(anyMapsChanged, anySchematicsChanged);

        // Update transaction manifest state
        boolean allActionableUndone = true;
        for(UndoVerification.VerifiedEntry ve : ver.verifiedEntries){
            if(ve.isActionable() && !ve.entry.undone){
                allActionableUndone = false;
                break;
            }
        }

        if(allActionableUndone && !ver.hasConflicts()){
            tx.undone = true;
        }

        // Save updated manifest
        try{
            Fi manifest = txDir.child("manifest.json");
            manifest.writeString(tx.toJson().toString());
        }catch(Throwable ignored){}

        return new UndoResult(tx, deleted, restored, ver.conflictCount, deleted + restored > 0);
    }

    private void deleteImportedFile(Fi destFile, PackType type){
        if(destFile == null || !destFile.exists()) return;

        if(type == PackType.MAP && Vars.maps != null){
            MainThread.run(() -> {
                try{
                    Map map = Vars.maps.all().find(m -> m.file != null && m.file.equals(destFile));
                    if(map != null){
                        Vars.maps.removeMap(map);
                    }
                }catch(Throwable ignored){}
            });
        }else if(type == PackType.SCHEMATIC && Vars.schematics != null){
            MainThread.run(() -> {
                try{
                    Schematic sc = Vars.schematics.all().find(s -> s.file != null && s.file.equals(destFile));
                    if(sc != null){
                        Vars.schematics.remove(sc);
                    }
                }catch(Throwable ignored){}
            });
        }

        // Ensure physical file is deleted
        if(destFile.exists()){
            destFile.delete();
        }
    }

    private void restoreBackupFile(Fi backupFile, Fi destFile, PackType type) throws IOException{
        if(backupFile == null || !backupFile.exists()){
            throw new IOException("Backup file missing");
        }

        // Stage restored file atomically
        Fi tempFile = destFile.parent().child(destFile.name() + ".restore.tmp");
        try{
            backupFile.copyTo(tempFile);

            if(type == PackType.MAP){
                try(InputStream in = tempFile.read()){
                    PackFormat.validateMap(in);
                }
            }else if(type == PackType.SCHEMATIC){
                PackFormat.readSchematic(tempFile.file());
            }

            tempFile.moveTo(destFile);

            // Register into game subsystems
            if(type == PackType.MAP && Vars.maps != null){
                MainThread.run(() -> {
                    try{
                        Vars.maps.reload();
                    }catch(Throwable ignored){}
                });
            }else if(type == PackType.SCHEMATIC && Vars.schematics != null){
                MainThread.run(() -> {
                    try{
                        Schematic s = PackFormat.readSchematic(destFile.file());
                        s.file = destFile;
                        if(!Vars.schematics.all().contains(s, true)){
                            Vars.schematics.all().add(s);
                            Vars.schematics.all().sort();
                        }
                    }catch(Throwable ignored){}
                });
            }
        }finally{
            if(tempFile.exists()) tempFile.delete();
        }
    }

    private void refreshGameSubsystems(boolean reloadMaps, boolean reloadSchematics){
        MainThread.run(() -> {
            try{
                if(reloadMaps && Vars.maps != null){
                    Vars.maps.reload();
                }
                if(reloadSchematics && Vars.schematics != null){
                    // Native schematic reload
                    Vars.schematics.all().sort();
                }
            }catch(Throwable ignored){}
        });
    }

    // ---------------------------------------------------------------------
    // Cleanup & Quotas
    // ---------------------------------------------------------------------

    public void cleanOldBackups(){
        if(!backupRoot.exists()) return;
        Fi[] dirs = backupRoot.list();
        if(dirs == null || dirs.length == 0) return;

        ImportTransaction activeTx = getLatestActiveTransaction();
        String activeTxId = activeTx != null ? activeTx.id : "";

        // Sort ascending by timestamp (oldest first)
        Arrays.sort(dirs, Comparator.comparing(Fi::name));

        Seq<Fi> candidates = new Seq<>();
        long totalBytes = 0L;

        for(Fi dir : dirs){
            if(!dir.isDirectory() || !dir.name().startsWith("tx_")) continue;
            // Never prune active transaction
            if(dir.name().equals(activeTxId)) continue;

            candidates.add(dir);
            totalBytes += getDirSize(dir);
        }

        // Delete oldest candidates exceeding limits
        int totalTxCount = candidates.size + (activeTx != null ? 1 : 0);
        int excessCount = totalTxCount - MAX_RETAINED_TRANSACTIONS;
        for(Fi dir : candidates){
            boolean shouldDelete = excessCount > 0 || totalBytes > MAX_TOTAL_BACKUP_BYTES;
            if(shouldDelete){
                long dirSize = getDirSize(dir);
                try{
                    dir.deleteDirectory();
                    totalBytes -= dirSize;
                    excessCount--;
                }catch(Throwable ignored){}
            }
        }
    }

    private static long getDirSize(Fi dir){
        if(dir == null || !dir.exists()) return 0L;
        long total = 0L;
        Fi[] list = dir.list();
        if(list != null){
            for(Fi f : list){
                if(f.isDirectory()) total += getDirSize(f);
                else total += f.length();
            }
        }
        return total;
    }

    // ---------------------------------------------------------------------
    // Utility Helpers
    // ---------------------------------------------------------------------

    public static String computeSha256(Fi file){
        if(file == null || !file.exists() || file.isDirectory()) return "";
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try(InputStream in = file.read()){
                byte[] buf = new byte[8192];
                int n;
                while((n = in.read(buf)) != -1){
                    digest.update(buf, 0, n);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for(byte b : hash){
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }catch(Throwable t){
            return "";
        }
    }
}

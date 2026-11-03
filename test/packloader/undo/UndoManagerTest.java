package packloader.undo;

import arc.files.Fi;
import arc.struct.Seq;
import mindustry.Vars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;
import packloader.core.PackImporter;
import packloader.core.PackScanner;
import packloader.model.DuplicatePolicy;
import packloader.model.ImportReport;
import packloader.model.PackEntry;
import packloader.model.PackType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class UndoManagerTest{
    private File tempDir;
    private Fi mapDir;
    private Fi schemDir;
    private Fi backupDir;
    private PackScanner scanner;
    private PackImporter importer;
    private Fi originalCustomMapDirectory;
    private Fi originalSchematicDirectory;

    @BeforeEach
    public void setup() throws IOException{
        TestHelper.initBundle(Locale.ENGLISH);
        tempDir = Files.createTempDirectory("packloader_test_undo").toFile();
        mapDir = new Fi(new File(tempDir, "maps"));
        schemDir = new Fi(new File(tempDir, "schematics"));
        backupDir = new Fi(new File(tempDir, "packloader_backups"));
        mapDir.mkdirs();
        schemDir.mkdirs();
        backupDir.mkdirs();

        originalCustomMapDirectory = Vars.customMapDirectory;
        originalSchematicDirectory = Vars.schematicDirectory;
        Vars.customMapDirectory = mapDir;
        Vars.schematicDirectory = schemDir;

        scanner = new PackScanner(mapDir, schemDir);
        importer = new PackImporter(mapDir, schemDir, backupDir);
    }

    @AfterEach
    public void teardown(){
        Vars.customMapDirectory = originalCustomMapDirectory;
        Vars.schematicDirectory = originalSchematicDirectory;

        if(tempDir != null && tempDir.exists()){
            new Fi(tempDir).deleteDirectory();
        }
    }

    @Test
    public void testUndoNewFilesDeletesThem() throws IOException{
        byte[] mapBytes = TestHelper.createValidMapBytes("Test Map New");
        byte[] schemBytes = TestHelper.createValidSchematicBytes("Test Schem New");

        Fi mapInput = new Fi(new File(tempDir, "input_map.msav"));
        Fi schemInput = new Fi(new File(tempDir, "input_schem.msch"));
        mapInput.writeBytes(mapBytes);
        schemInput.writeBytes(schemBytes);

        Seq<PackEntry> entries = scanner.scanFiles(mapInput, schemInput);
        assertEquals(2, entries.size);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE, "TestSource", null);
        assertEquals(2, report.totalSuccess);
        assertNotNull(report.transaction);

        ImportTransaction tx = report.transaction;
        assertEquals(2, tx.entries.size);
        assertEquals(TransactionAction.NEW, tx.entries.get(0).action);
        assertEquals(TransactionAction.NEW, tx.entries.get(1).action);

        Fi createdMap = new Fi(new File(tx.entries.get(0).destinationPath));
        Fi createdSchem = new Fi(new File(tx.entries.get(1).destinationPath));
        assertTrue(createdMap.exists());
        assertTrue(createdSchem.exists());

        // Verify pre-undo analysis
        UndoVerification ver = importer.undoManager.verifyUndo(tx);
        assertTrue(ver.canUndoAny());
        assertEquals(2, ver.canDeleteCount);
        assertEquals(0, ver.canRestoreCount);
        assertEquals(0, ver.conflictCount);

        // Execute Undo
        UndoManager.UndoResult result = importer.undoManager.executeUndo(tx, null);
        assertTrue(result.success);
        assertEquals(2, result.deletedCount);
        assertEquals(0, result.restoredCount);
        assertEquals(0, result.conflictCount);

        // Verified: newly imported files deleted
        assertFalse(createdMap.exists());
        assertFalse(createdSchem.exists());
        assertTrue(tx.undone);
        assertFalse(importer.undoManager.hasActiveTransaction());
    }

    @Test
    public void testUndoOverwriteRestoresOriginalFiles() throws IOException{
        byte[] originalMapBytes = TestHelper.createValidMapBytes("Original Map v1");
        byte[] originalSchemBytes = TestHelper.createValidSchematicBytes("Original Schem v1");

        Fi liveMap = mapDir.child("game_map.msav");
        Fi liveSchem = schemDir.child("game_schem.msch");
        liveMap.writeBytes(originalMapBytes);
        liveSchem.writeBytes(originalSchemBytes);

        byte[] newMapBytes = TestHelper.createValidMapBytes("Replaced Map v2");
        byte[] newSchemBytes = TestHelper.createValidSchematicBytes("Replaced Schem v2");

        // Use same file name to trigger duplicate detection
        File inputDir = new File(tempDir, "incoming");
        inputDir.mkdirs();
        Fi mapInput = new Fi(new File(inputDir, "game_map.msav"));
        Fi schemInput = new Fi(new File(inputDir, "game_schem.msch"));
        mapInput.writeBytes(newMapBytes);
        schemInput.writeBytes(newSchemBytes);

        Seq<PackEntry> entries = scanner.scanFiles(mapInput, schemInput);
        assertEquals(2, entries.size);
        assertTrue(entries.get(0).isDuplicate);
        assertTrue(entries.get(1).isDuplicate);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE, "OverwriteSource", null);
        assertEquals(2, report.totalOverwritten);
        assertTrue(report.hasSuccess());
        ImportTransaction tx = report.transaction;
        assertNotNull(tx);

        // Destination files should now contain v2 bytes
        assertTrue(Arrays.equals(newMapBytes, liveMap.readBytes()));
        assertTrue(Arrays.equals(newSchemBytes, liveSchem.readBytes()));

        // Verify pre-undo
        UndoVerification ver = importer.undoManager.verifyUndo(tx);
        assertTrue(ver.canUndoAny());
        assertEquals(0, ver.canDeleteCount);
        assertEquals(2, ver.canRestoreCount);
        assertEquals(0, ver.conflictCount);

        // Execute Undo
        UndoManager.UndoResult result = importer.undoManager.executeUndo(tx, null);
        assertTrue(result.success);
        assertEquals(2, result.restoredCount);
        assertEquals(0, result.conflictCount);

        // Destination files should be restored to exact original v1 bytes
        assertTrue(Arrays.equals(originalMapBytes, liveMap.readBytes()));
        assertTrue(Arrays.equals(originalSchemBytes, liveSchem.readBytes()));
    }

    @Test
    public void testUndoRenameDeletesRenamedFilesOnly() throws IOException{
        byte[] originalMapBytes = TestHelper.createValidMapBytes("Sector 42");
        Fi liveMap = mapDir.child("Sector 42.msav");
        liveMap.writeBytes(originalMapBytes);

        byte[] newMapBytes = TestHelper.createValidMapBytes("Sector 42");
        File inputDir = new File(tempDir, "incoming_rename");
        inputDir.mkdirs();
        Fi mapInput = new Fi(new File(inputDir, "Sector 42.msav"));
        mapInput.writeBytes(newMapBytes);

        Seq<PackEntry> entries = scanner.scanFiles(mapInput);
        assertEquals(1, entries.size);
        assertTrue(entries.first().isDuplicate);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.RENAME, DuplicatePolicy.RENAME, "RenameSource", null);
        assertEquals(1, report.totalSuccess);
        ImportTransaction tx = report.transaction;
        assertNotNull(tx);

        assertEquals(TransactionAction.RENAME, tx.entries.get(0).action);
        Fi renamedFile = new Fi(new File(tx.entries.get(0).destinationPath));
        assertTrue(renamedFile.exists());
        assertNotEquals(liveMap.path(), renamedFile.path());
        assertTrue(liveMap.exists());

        // Undo
        UndoManager.UndoResult result = importer.undoManager.executeUndo(tx, null);
        assertTrue(result.success);
        assertEquals(1, result.deletedCount);

        // Renamed file deleted, original preserved untouched
        assertFalse(renamedFile.exists());
        assertTrue(liveMap.exists());
        assertTrue(Arrays.equals(originalMapBytes, liveMap.readBytes()));
    }

    @Test
    public void testUndoSkipDoesNothing() throws IOException{
        byte[] originalMapBytes = TestHelper.createValidMapBytes("Skip Me");
        Fi liveMap = mapDir.child("Skip Me.msav");
        liveMap.writeBytes(originalMapBytes);

        File inputDir = new File(tempDir, "incoming_skip");
        inputDir.mkdirs();
        Fi mapInput = new Fi(new File(inputDir, "Skip Me.msav"));
        mapInput.writeBytes(TestHelper.createValidMapBytes("Skip Me"));

        Seq<PackEntry> entries = scanner.scanFiles(mapInput);
        assertEquals(1, entries.size);
        assertTrue(entries.first().isDuplicate);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP, "SkipSource", null);
        assertEquals(1, report.totalSkipped);
        ImportTransaction tx = report.transaction;
        assertNotNull(tx);
        assertEquals(TransactionAction.SKIP, tx.entries.get(0).action);

        UndoVerification ver = importer.undoManager.verifyUndo(tx);
        assertFalse(ver.canUndoAny());
        assertEquals(0, ver.canDeleteCount);
        assertEquals(0, ver.canRestoreCount);
    }

    @Test
    public void testMixedBatchUndo() throws IOException{
        // 1. Existing map to overwrite
        Fi existingMap = mapDir.child("old_map.msav");
        byte[] oldMapBytes = TestHelper.createValidMapBytes("Old Map");
        existingMap.writeBytes(oldMapBytes);

        // 2. Existing schematic to skip
        Fi existingSchem = schemDir.child("old_schem.msch");
        byte[] oldSchemBytes = TestHelper.createValidSchematicBytes("Old Schem");
        existingSchem.writeBytes(oldSchemBytes);

        // Inputs
        File inputDir = new File(tempDir, "mixed_input");
        inputDir.mkdirs();

        Fi mapNew = new Fi(new File(inputDir, "new_map.msav"));
        mapNew.writeBytes(TestHelper.createValidMapBytes("Brand New Map"));

        Fi mapOverwrite = new Fi(new File(inputDir, "old_map.msav"));
        byte[] replaceMapBytes = TestHelper.createValidMapBytes("Old Map Replaced");
        mapOverwrite.writeBytes(replaceMapBytes);

        Fi schemSkip = new Fi(new File(inputDir, "old_schem.msch"));
        schemSkip.writeBytes(TestHelper.createValidSchematicBytes("Old Schem Skipped"));

        Seq<PackEntry> entries = scanner.scanFiles(mapNew, mapOverwrite, schemSkip);
        assertEquals(3, entries.size);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.OVERWRITE, DuplicatePolicy.SKIP, "MixedSource", null);
        ImportTransaction tx = report.transaction;
        assertNotNull(tx);

        UndoVerification ver = importer.undoManager.verifyUndo(tx);
        assertEquals(1, ver.canDeleteCount); // new_map NEW
        assertEquals(1, ver.canRestoreCount); // old_map OVERWRITE
        assertEquals(0, ver.conflictCount);

        UndoManager.UndoResult result = importer.undoManager.executeUndo(tx, null);
        assertTrue(result.success);
        assertEquals(1, result.deletedCount);
        assertEquals(1, result.restoredCount);

        // Verify state
        Fi createdMap = new Fi(new File(tx.entries.get(0).destinationPath));
        assertFalse(createdMap.exists());
        assertTrue(Arrays.equals(oldMapBytes, existingMap.readBytes()));
        assertTrue(Arrays.equals(oldSchemBytes, existingSchem.readBytes()));
    }

    @Test
    public void testUndoPersistsAcrossSimulatedGameRestart() throws IOException{
        Fi mapInput = new Fi(new File(tempDir, "persisted_map.msav"));
        mapInput.writeBytes(TestHelper.createValidMapBytes("Persist Map"));

        Seq<PackEntry> entries = scanner.scanFiles(mapInput);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE, "RestartSource", null);
        assertNotNull(report.transaction);
        String createdPath = report.transaction.entries.get(0).destinationPath;
        assertTrue(new Fi(new File(createdPath)).exists());

        // Simulate game restart: create a new UndoManager instance pointing to the same backup directory
        UndoManager restartedUndoManager = new UndoManager(backupDir, mapDir, schemDir);
        assertTrue(restartedUndoManager.hasActiveTransaction());

        ImportTransaction reloadedTx = restartedUndoManager.getLatestActiveTransaction();
        assertNotNull(reloadedTx);
        assertEquals(report.transaction.id, reloadedTx.id);
        assertEquals("RestartSource", reloadedTx.sourceName);

        UndoVerification ver = restartedUndoManager.verifyUndo(reloadedTx);
        assertTrue(ver.canUndoAny());
        assertEquals(1, ver.canDeleteCount);

        UndoManager.UndoResult result = restartedUndoManager.executeUndo(reloadedTx, null);
        assertTrue(result.success);
        assertEquals(1, result.deletedCount);
        assertFalse(new Fi(new File(createdPath)).exists());
        assertFalse(restartedUndoManager.hasActiveTransaction());
    }

    @Test
    public void testConflictWhenFileModifiedAfterImport() throws IOException{
        Fi mapInput = new Fi(new File(tempDir, "user_edited_map.msav"));
        mapInput.writeBytes(TestHelper.createValidMapBytes("User Edit Map"));

        Seq<PackEntry> entries = scanner.scanFiles(mapInput);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE, "UserEditSource", null);
        ImportTransaction tx = report.transaction;
        assertNotNull(tx);

        Fi liveFile = new Fi(new File(tx.entries.get(0).destinationPath));
        assertTrue(liveFile.exists());

        // Simulate user editing the file in-game after import
        byte[] editedBytes = TestHelper.createValidMapBytes("User Edited Post-Import Content");
        liveFile.writeBytes(editedBytes);

        // Verification must detect the modification conflict
        UndoVerification ver = importer.undoManager.verifyUndo(tx);
        assertEquals(1, ver.conflictCount);
        assertEquals(0, ver.canDeleteCount);
        assertEquals(UndoVerification.EntryStatus.CONFLICT_MODIFIED, ver.verifiedEntries.get(0).status);
        assertFalse(ver.canUndoAny());

        // Execute Undo must protect user data: do NOT delete or touch the modified file
        UndoManager.UndoResult result = importer.undoManager.executeUndo(tx, null);
        assertEquals(0, result.deletedCount);
        assertEquals(1, result.conflictCount);
        assertTrue(liveFile.exists());
        assertTrue(Arrays.equals(editedBytes, liveFile.readBytes()));
    }

    @Test
    public void testConflictWhenBackupCorruptedOrMissing() throws IOException{
        Fi existing = mapDir.child("corrupt_test.msav");
        existing.writeBytes(TestHelper.createValidMapBytes("Corrupt Test v1"));

        File inputDir = new File(tempDir, "incoming_corrupt");
        inputDir.mkdirs();
        Fi input = new Fi(new File(inputDir, "corrupt_test.msav"));
        input.writeBytes(TestHelper.createValidMapBytes("Corrupt Test v2"));

        Seq<PackEntry> entries = scanner.scanFiles(input);
        assertEquals(1, entries.size);
        assertTrue(entries.first().isDuplicate);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE, "CorruptSource", null);
        ImportTransaction tx = report.transaction;
        assertNotNull(tx);

        // 1. Test Corrupted Backup: change the backup file bytes
        String backupName = tx.entries.get(0).backupFileName;
        Fi backupFile = importer.undoManager.getTxDir(tx).child(backupName);
        assertTrue(backupFile.exists());
        backupFile.writeBytes(new byte[]{1, 2, 3, 4, 5}); // corrupt it

        UndoVerification verCorrupted = importer.undoManager.verifyUndo(tx);
        assertEquals(1, verCorrupted.conflictCount);
        assertEquals(UndoVerification.EntryStatus.CONFLICT_BACKUP_CORRUPTED, verCorrupted.verifiedEntries.get(0).status);

        // 2. Test Missing Backup: delete the backup file
        backupFile.delete();
        UndoVerification verMissing = importer.undoManager.verifyUndo(tx);
        assertEquals(1, verMissing.conflictCount);
        assertEquals(UndoVerification.EntryStatus.CONFLICT_BACKUP_MISSING, verMissing.verifiedEntries.get(0).status);

        // Execute undo must leave existing live file safe
        UndoManager.UndoResult result = importer.undoManager.executeUndo(tx, null);
        assertEquals(0, result.restoredCount);
        assertEquals(1, result.conflictCount);
        assertTrue(existing.exists());
    }

    @Test
    public void testConsecutiveTransactionsAndQuotaPruning() throws IOException{
        // Execute 7 consecutive imports
        for(int i = 1; i <= 7; i++){
            Fi input = new Fi(new File(tempDir, "multi_" + i + ".msav"));
            input.writeBytes(TestHelper.createValidMapBytes("Multi " + i));
            Seq<PackEntry> entries = scanner.scanFiles(input);
            importer.importEntries(entries, DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE, "Source_" + i, null);
            try{ Thread.sleep(25); }catch(InterruptedException ignored){}
        }

        // Check retained transaction directories: maximum 5 retained
        Fi[] txDirs = backupDir.list();
        int validTxDirs = 0;
        for(Fi d : txDirs){
            if(d.isDirectory() && d.name().startsWith("tx_")){
                validTxDirs++;
            }
        }
        assertTrue(validTxDirs <= UndoManager.MAX_RETAINED_TRANSACTIONS);

        // Latest transaction is the 7th import
        ImportTransaction latest = importer.undoManager.getLatestActiveTransaction();
        assertNotNull(latest);
        assertEquals("Source_7", latest.sourceName);

        // Undo latest transaction
        UndoManager.UndoResult result = importer.undoManager.executeUndo(latest, null);
        assertTrue(result.success);
        assertEquals(1, result.deletedCount);

        // Active transaction is now updated to the previous (6th) import!
        ImportTransaction nextActive = importer.undoManager.getLatestActiveTransaction();
        assertNotNull(nextActive);
        assertEquals("Source_6", nextActive.sourceName);
    }

    @Test
    public void testRollbackOnPartialTransactionFailure() throws IOException{
        Fi existing = mapDir.child("rollback_target.msav");
        byte[] originalBytes = TestHelper.createValidMapBytes("Rollback Original");
        existing.writeBytes(originalBytes);

        ImportTransaction tx = importer.undoManager.createTransaction("PartialSource");

        // Simulate 1 overwrite and 1 new write in progress
        UndoManager.BackupRecord backup = importer.undoManager.backupFile(tx, 0, existing, PackType.MAP);
        byte[] replacedBytes = TestHelper.createValidMapBytes("Rollback Replaced");
        existing.writeBytes(replacedBytes);
        PackEntry mapEntry = new PackEntry(PackType.MAP, existing.path(), "rollback_target.msav", replacedBytes.length);
        importer.undoManager.recordEntry(tx, mapEntry, TransactionAction.OVERWRITE, existing, backup);

        Fi createdNew = mapDir.child("new_created.msav");
        byte[] newBytes = TestHelper.createValidMapBytes("Newly Created");
        createdNew.writeBytes(newBytes);
        PackEntry newEntry = new PackEntry(PackType.MAP, createdNew.path(), "new_created.msav", newBytes.length);
        importer.undoManager.recordEntry(tx, newEntry, TransactionAction.NEW, createdNew, null);

        // Files are currently in partially mutated state
        assertTrue(Arrays.equals(replacedBytes, existing.readBytes()));
        assertTrue(createdNew.exists());

        // Rollback partial transaction (as happens on crash or unhandled failure)
        importer.undoManager.rollbackPartialTransaction(tx);

        // Verified: existing file restored from backup, newly created file deleted
        assertTrue(existing.exists());
        assertTrue(Arrays.equals(originalBytes, existing.readBytes()));
        assertFalse(createdNew.exists());

        // Verified: uncommitted transaction is NOT active
        assertFalse(importer.undoManager.hasActiveTransaction());
    }
}

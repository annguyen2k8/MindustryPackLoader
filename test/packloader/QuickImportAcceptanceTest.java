package packloader;

import arc.files.Fi;
import arc.struct.Seq;
import mindustry.Vars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.core.PackImporter;
import packloader.core.PackScanner;
import packloader.model.*;
import packloader.undo.ImportTransaction;
import packloader.util.PackLoaderSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class QuickImportAcceptanceTest{
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
        tempDir = Files.createTempDirectory("packloader_test_quick_import_acc").toFile();
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

        PackLoaderSettings.setQuickImport(true);
        PackLoaderSettings.setSkipIdenticalContent(true);
    }

    @AfterEach
    public void teardown(){
        Vars.customMapDirectory = originalCustomMapDirectory;
        Vars.schematicDirectory = originalSchematicDirectory;

        PackLoaderSettings.setQuickImport(false);
        PackLoaderSettings.setSkipIdenticalContent(false);

        if(tempDir != null && tempDir.exists()){
            new Fi(tempDir).deleteDirectory();
        }
    }

    @Test
    public void testOneTapQuickImportNewFilesAndUndo() throws IOException{
        File zip = new File(tempDir, "fresh_pack.zip");
        TestHelper.createTestZip(zip,
            new TestHelper.ZipEntryBuilder("map1.msav", TestHelper.createValidMapBytes("Map One")),
            new TestHelper.ZipEntryBuilder("map2.msav", TestHelper.createValidMapBytes("Map Two")),
            new TestHelper.ZipEntryBuilder("schem1.msch", TestHelper.createValidSchematicBytes("Schem One")),
            new TestHelper.ZipEntryBuilder("schem2.msch", TestHelper.createValidSchematicBytes("Schem Two"))
        );

        Seq<PackEntry> scanned = scanner.scanZip(new Fi(zip), null, null);
        assertEquals(4, scanned.size);

        scanner.checkGameDuplicates(scanned);
        scanner.checkContentDuplicates(scanned);

        int eligible = EntryFilter.selectEligibleForQuickImport(scanned, null,
            DuplicatePolicy.SKIP, DuplicatePolicy.SKIP);
        assertEquals(4, eligible, "All 4 fresh files must be eligible for Quick Import");

        Seq<PackEntry> toImport = EntryFilter.collectSelected(scanned, null, null, "");
        assertEquals(4, toImport.size);

        ImportReport report = importer.importEntries(toImport, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP,
            "fresh_pack.zip", null);
        assertEquals(4, report.totalSuccess);
        assertEquals(0, report.totalFailed);

        assertTrue(mapDir.child("map1.msav").exists());
        assertTrue(mapDir.child("map2.msav").exists());
        assertTrue(schemDir.child("schem1.msch").exists());
        assertTrue(schemDir.child("schem2.msch").exists());

        // Verify Undo transaction created by Quick Import can cleanly revert everything
        ImportTransaction tx = importer.undoManager.getLatestTransaction();
        assertNotNull(tx, "Quick import must record a transaction for Undo");
        assertEquals(4, tx.getActiveItemCount());

        var result = importer.undoManager.executeUndo(tx, null);
        assertTrue(result.success);
        assertEquals(4, result.deletedCount);

        assertFalse(mapDir.child("map1.msav").exists());
        assertFalse(mapDir.child("map2.msav").exists());
        assertFalse(schemDir.child("schem1.msch").exists());
        assertFalse(schemDir.child("schem2.msch").exists());

        scanner.cleanupTemporaryZips(scanned);
    }

    @Test
    public void testQuickImportWithPerTypePoliciesAndUndo() throws IOException{
        byte[] mapContentOld = TestHelper.createValidMapBytes("Old Map");
        byte[] mapContentNew = TestHelper.createValidMapBytes("Updated Map");
        byte[] schemContentOld = TestHelper.createValidSchematicBytes("Old Schem");
        byte[] schemContentNew = TestHelper.createValidSchematicBytes("Updated Schem");

        // Write existing files into game library
        mapDir.child("dup_map.msav").writeBytes(mapContentOld);
        schemDir.child("dup_schem.msch").writeBytes(schemContentOld);

        File zip = new File(tempDir, "mixed_policies.zip");
        TestHelper.createTestZip(zip,
            new TestHelper.ZipEntryBuilder("dup_map.msav", mapContentNew),
            new TestHelper.ZipEntryBuilder("dup_schem.msch", schemContentNew),
            new TestHelper.ZipEntryBuilder("fresh_map.msav", TestHelper.createValidMapBytes("Fresh Map")),
            new TestHelper.ZipEntryBuilder("fresh_schem.msch", TestHelper.createValidSchematicBytes("Fresh Schem"))
        );

        Seq<PackEntry> scanned = scanner.scanZip(new Fi(zip), null, null);
        assertEquals(4, scanned.size);

        scanner.checkGameDuplicates(scanned);
        scanner.checkContentDuplicates(scanned);

        // Map Policy = OVERWRITE (accepts duplicate), Schematic Policy = SKIP (rejects duplicate)
        DuplicatePolicy mapPolicy = DuplicatePolicy.OVERWRITE;
        DuplicatePolicy schemPolicy = DuplicatePolicy.SKIP;

        int eligible = EntryFilter.selectEligibleForQuickImport(scanned, null, mapPolicy, schemPolicy);
        assertEquals(3, eligible, "3 entries eligible: fresh_map, fresh_schem, and dup_map (under OVERWRITE)");

        Seq<PackEntry> toImport = EntryFilter.collectSelected(scanned, null, null, "");
        assertEquals(3, toImport.size);

        // Verify dup_schem was excluded by SKIP policy
        for(PackEntry e : toImport){
            assertNotEquals("dup_schem.msch", e.sanitizedFileName);
        }

        ImportReport report = importer.importEntries(toImport, mapPolicy, schemPolicy, "mixed_policies.zip", null);
        assertEquals(2, report.totalSuccess); // fresh_map, fresh_schem
        assertEquals(1, report.totalOverwritten); // dup_map
        assertEquals(0, report.totalFailed);

        // Verify dup_map was overwritten with mapContentNew
        assertArrayEquals(mapContentNew, mapDir.child("dup_map.msav").readBytes());

        // Verify dup_schem was untouched in library
        assertArrayEquals(schemContentOld, schemDir.child("dup_schem.msch").readBytes());

        // Verify Undo restores dup_map to mapContentOld and removes fresh files
        ImportTransaction tx = importer.undoManager.getLatestTransaction();
        assertNotNull(tx);
        var undoResult = importer.undoManager.executeUndo(tx, null);
        assertTrue(undoResult.success);
        assertEquals(2, undoResult.deletedCount);
        assertEquals(1, undoResult.restoredCount);

        assertArrayEquals(mapContentOld, mapDir.child("dup_map.msav").readBytes());
        assertFalse(mapDir.child("fresh_map.msav").exists());
        assertFalse(schemDir.child("fresh_schem.msch").exists());

        scanner.cleanupTemporaryZips(scanned);
    }

    @Test
    public void testQuickImportWithSkipIdenticalContentEnabled() throws IOException{
        PackLoaderSettings.setSkipIdenticalContent(true);

        byte[] mapBytes = TestHelper.createValidMapBytes("Base Map");
        mapDir.child("original.msav").writeBytes(mapBytes);

        File zip = new File(tempDir, "dup_content_pack.zip");
        TestHelper.createTestZip(zip,
            // Different filename, exact identical byte content as original.msav
            new TestHelper.ZipEntryBuilder("copy_of_original.msav", mapBytes),
            // Truly new map with different content
            new TestHelper.ZipEntryBuilder("brand_new.msav", TestHelper.createValidMapBytes("Brand New Map"))
        );

        Seq<PackEntry> scanned = scanner.scanZip(new Fi(zip), PackType.MAP, null);
        assertEquals(2, scanned.size);

        scanner.checkGameDuplicates(scanned);
        scanner.checkContentDuplicates(scanned);

        assertTrue(scanned.get(0).isContentDuplicate, "copy_of_original must be marked as content duplicate");
        assertFalse(scanned.get(0).isDuplicate, "copy_of_original has different name, so isDuplicate is false");
        assertFalse(scanned.get(1).isContentDuplicate);

        int eligible = EntryFilter.selectEligibleForQuickImport(scanned, PackType.MAP,
            DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE, true);
        assertEquals(1, eligible, "When skipIdenticalContent=true, content duplicate must be excluded even under OVERWRITE");

        Seq<PackEntry> toImport = EntryFilter.collectSelected(scanned, PackType.MAP, null, "");
        assertEquals(1, toImport.size);
        assertEquals("brand_new.msav", toImport.get(0).sanitizedFileName);

        ImportReport report = importer.importEntries(toImport, DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE,
            "dup_content_pack.zip", null);
        assertEquals(1, report.totalSuccess);

        assertTrue(mapDir.child("brand_new.msav").exists());
        assertFalse(mapDir.child("copy_of_original.msav").exists(), "Identical content file must not be imported");

        scanner.cleanupTemporaryZips(scanned);
    }

    @Test
    public void testQuickImportIntraBatchDuplicatesWithSkipIdentical() throws IOException{
        PackLoaderSettings.setSkipIdenticalContent(true);

        byte[] commonSchem = TestHelper.createValidSchematicBytes("Repeated Schem");

        File zip = new File(tempDir, "intra_pack.zip");
        TestHelper.createTestZip(zip,
            new TestHelper.ZipEntryBuilder("schem_first.msch", commonSchem),
            new TestHelper.ZipEntryBuilder("schem_second.msch", commonSchem)
        );

        Seq<PackEntry> scanned = scanner.scanZip(new Fi(zip), PackType.SCHEMATIC, null);
        assertEquals(2, scanned.size);

        scanner.checkGameDuplicates(scanned);
        scanner.checkContentDuplicates(scanned);

        assertFalse(scanned.get(0).isContentDuplicate);
        assertTrue(scanned.get(1).isContentDuplicate);
        assertTrue(scanned.get(1).isIntraBatchContentDuplicate);

        int eligible = EntryFilter.selectEligibleForQuickImport(scanned, PackType.SCHEMATIC,
            DuplicatePolicy.SKIP, DuplicatePolicy.SKIP, true);
        assertEquals(1, eligible, "Only the first unique entry should be eligible");

        Seq<PackEntry> toImport = EntryFilter.collectSelected(scanned, PackType.SCHEMATIC, null, "");
        assertEquals(1, toImport.size);
        assertEquals("schem_first.msch", toImport.get(0).sanitizedFileName);

        ImportReport report = importer.importEntries(toImport, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP,
            "intra_pack.zip", null);
        assertEquals(1, report.totalSuccess);

        assertTrue(schemDir.child("schem_first.msch").exists());
        assertFalse(schemDir.child("schem_second.msch").exists(), "Intra-batch duplicate must not be imported");

        scanner.cleanupTemporaryZips(scanned);
    }

    @Test
    public void testQuickImportAllSkippedProducesZeroEligibleWithoutWriting() throws IOException{
        byte[] mapBytes = TestHelper.createValidMapBytes("Same Map");
        mapDir.child("existing1.msav").writeBytes(mapBytes);
        mapDir.child("existing2.msav").writeBytes(mapBytes);

        File zip = new File(tempDir, "all_dups.zip");
        TestHelper.createTestZip(zip,
            new TestHelper.ZipEntryBuilder("existing1.msav", mapBytes),
            new TestHelper.ZipEntryBuilder("existing2.msav", mapBytes)
        );

        Seq<PackEntry> scanned = scanner.scanZip(new Fi(zip), PackType.MAP, null);
        assertEquals(2, scanned.size);

        scanner.checkGameDuplicates(scanned);
        scanner.checkContentDuplicates(scanned);

        int eligible = EntryFilter.selectEligibleForQuickImport(scanned, PackType.MAP,
            DuplicatePolicy.SKIP, DuplicatePolicy.SKIP);
        assertEquals(0, eligible, "All entries are duplicates under SKIP; 0 eligible");

        Seq<PackEntry> toImport = EntryFilter.collectSelected(scanned, PackType.MAP, null, "");
        assertTrue(toImport.isEmpty(), "Nothing should be marked to import");

        // Verify zero disk mutations
        assertEquals(2, mapDir.list().length);

        scanner.cleanupTemporaryZips(scanned);
    }

    @Test
    public void testQuickImportCorruptedFileHandling() throws IOException{
        File zip = new File(tempDir, "corrupted_pack.zip");
        TestHelper.createTestZip(zip,
            new TestHelper.ZipEntryBuilder("good_map.msav", TestHelper.createValidMapBytes("Good Map")),
            new TestHelper.ZipEntryBuilder("corrupt_map.msav", new byte[]{0x42, 0x43, 0x44}) // Invalid header
        );

        Seq<PackEntry> scanned = scanner.scanZip(new Fi(zip), PackType.MAP, null);
        assertEquals(2, scanned.size);

        scanner.checkGameDuplicates(scanned);
        scanner.checkContentDuplicates(scanned);

        assertTrue(scanned.get(0).isValid);
        assertFalse(scanned.get(1).isValid);

        int eligible = EntryFilter.selectEligibleForQuickImport(scanned, PackType.MAP,
            DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE);
        assertEquals(1, eligible, "Corrupt file must never be eligible, even under OVERWRITE");

        Seq<PackEntry> toImport = EntryFilter.collectSelected(scanned, PackType.MAP, null, "");
        assertEquals(1, toImport.size);
        assertEquals("good_map.msav", toImport.get(0).sanitizedFileName);

        ImportReport report = importer.importEntries(toImport, DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE,
            "corrupted_pack.zip", null);
        assertEquals(1, report.totalSuccess);
        assertEquals(0, report.totalFailed);

        assertTrue(mapDir.child("good_map.msav").exists());
        assertFalse(mapDir.child("corrupt_map.msav").exists());

        scanner.cleanupTemporaryZips(scanned);
    }

    @Test
    public void testQuickImportCancellationRollback() throws IOException{
        File zip = new File(tempDir, "cancel_pack.zip");
        TestHelper.createTestZip(zip,
            new TestHelper.ZipEntryBuilder("mapA.msav", TestHelper.createValidMapBytes("Map A")),
            new TestHelper.ZipEntryBuilder("mapB.msav", TestHelper.createValidMapBytes("Map B")),
            new TestHelper.ZipEntryBuilder("mapC.msav", TestHelper.createValidMapBytes("Map C"))
        );

        Seq<PackEntry> scanned = scanner.scanZip(new Fi(zip), PackType.MAP, null);
        assertEquals(3, scanned.size);

        scanner.checkGameDuplicates(scanned);
        scanner.checkContentDuplicates(scanned);

        int eligible = EntryFilter.selectEligibleForQuickImport(scanned, PackType.MAP,
            DuplicatePolicy.SKIP, DuplicatePolicy.SKIP);
        assertEquals(3, eligible);

        Seq<PackEntry> toImport = EntryFilter.collectSelected(scanned, PackType.MAP, null, "");
        assertEquals(3, toImport.size);

        // Cancel after 1 item is imported
        final int[] progressCount = {0};
        PackImporter.ImportListener cancellingListener = new PackImporter.ImportListener(){
            @Override
            public void onProgress(PackImporter.ImportProgress progress){
                progressCount[0]++;
            }

            @Override
            public boolean isCancelled(){
                return progressCount[0] >= 1;
            }
        };

        ImportReport report = importer.importEntries(toImport, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP,
            "cancel_pack.zip", cancellingListener);
        assertTrue(report.cancelled);
        assertEquals(1, report.totalSuccess);

        // Verify that the 1 imported map has an Undo transaction and can be reverted
        ImportTransaction tx = importer.undoManager.getLatestTransaction();
        assertNotNull(tx);
        assertEquals(1, tx.getActiveItemCount());

        var undoResult = importer.undoManager.executeUndo(tx, null);
        assertTrue(undoResult.success);
        assertEquals(1, undoResult.deletedCount);
        assertFalse(mapDir.child("mapA.msav").exists());

        scanner.cleanupTemporaryZips(scanned);
    }
}

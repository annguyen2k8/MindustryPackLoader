package packloader;

import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.struct.Seq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.core.PackImporter;
import packloader.core.PackScanner;
import packloader.model.DuplicatePolicy;
import packloader.model.EntryFilter;
import packloader.model.ImportReport;
import packloader.model.PackEntry;
import packloader.model.PackType;
import packloader.util.PackLoaderSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class MultiSourceQueueTest{
    private File tempDir;
    private Fi mapDir;
    private Fi schemDir;
    private PackScanner scanner;
    private PackImporter importer;

    @BeforeEach
    public void setup() throws IOException{
        TestHelper.initBundle(Locale.ENGLISH);
        Core.settings = new Settings();
        tempDir = Files.createTempDirectory("packloader_test_multisource").toFile();
        mapDir = new Fi(new File(tempDir, "maps"));
        schemDir = new Fi(new File(tempDir, "schematics"));
        mapDir.mkdirs();
        schemDir.mkdirs();
        scanner = new PackScanner(mapDir, schemDir);
        importer = new PackImporter(mapDir, schemDir);
    }

    @AfterEach
    public void teardown(){
        Core.settings = null;
        if(tempDir != null && tempDir.exists()){
            new Fi(tempDir).deleteDirectory();
        }
    }

    @Test
    public void testMultiSourceScanningAndMerging() throws IOException{
        File zip1 = new File(tempDir, "pack1.zip");
        File zip2 = new File(tempDir, "pack2.zip");

        byte[] map1 = TestHelper.createValidMapBytes("Alpha Map");
        byte[] map2 = TestHelper.createValidMapBytes("Beta Map");

        TestHelper.createTestZip(zip1, new TestHelper.ZipEntryBuilder("map1.msav", map1));
        TestHelper.createTestZip(zip2, new TestHelper.ZipEntryBuilder("map2.msav", map2));

        Seq<PackEntry> queue = new Seq<>();

        // Scan source 1
        Seq<PackEntry> scan1 = scanner.scanZip(new Fi(zip1), PackType.MAP, null);
        assertEquals(1, scan1.size);
        queue.addAll(scan1);

        // Scan source 2 and append
        Seq<PackEntry> scan2 = scanner.scanZip(new Fi(zip2), PackType.MAP, null);
        assertEquals(1, scan2.size);
        queue.addAll(scan2);

        assertEquals(2, queue.size);
        assertEquals("Alpha Map", queue.get(0).displayName);
        assertEquals("Beta Map", queue.get(1).displayName);

        // Import the entire queued batch
        ImportReport report = importer.importEntries(queue, DuplicatePolicy.SKIP, null);
        assertEquals(2, report.totalSuccess);
        assertEquals(0, report.totalFailed);

        assertTrue(mapDir.child("map1.msav").exists());
        assertTrue(mapDir.child("map2.msav").exists());

        // Cleanup temporary files across all sources in queue
        scanner.cleanupTemporaryZips(queue);
    }

    @Test
    public void testQuickImportSelectionFlow() throws IOException{
        PackLoaderSettings.setQuickImport(true);
        PackLoaderSettings.setDuplicatePolicy(DuplicatePolicy.SKIP);

        assertTrue(PackLoaderSettings.isQuickImport());
        assertEquals(DuplicatePolicy.SKIP, PackLoaderSettings.getDuplicatePolicy());

        // Existing map in directory
        mapDir.child("existing.msav").writeBytes(TestHelper.createValidMapBytes("Existing Map"));

        File zip = new File(tempDir, "incoming.zip");
        TestHelper.createTestZip(zip,
            new TestHelper.ZipEntryBuilder("existing.msav", TestHelper.createValidMapBytes("Existing Map")),
            new TestHelper.ZipEntryBuilder("new.msav", TestHelper.createValidMapBytes("Fresh Map"))
        );

        Seq<PackEntry> scanned = scanner.scanZip(new Fi(zip), PackType.MAP, null);
        assertEquals(2, scanned.size);

        // Run duplicate detection against game directory
        for(PackEntry e : scanned){
            scanner.checkDuplicate(e);
        }

        assertTrue(scanned.get(0).isDuplicate, "First entry must be marked duplicate");
        assertFalse(scanned.get(1).isDuplicate, "Second entry must be fresh");

        // Quick import with SKIP policy selects new items only
        EntryFilter.selectNewOnly(scanned, PackType.MAP, null, "");

        assertFalse(scanned.get(0).selected, "Duplicate entry must not be selected under SKIP");
        assertTrue(scanned.get(1).selected, "Fresh entry must be selected");

        Seq<PackEntry> toImport = EntryFilter.collectSelected(scanned, PackType.MAP, null, "");
        assertEquals(1, toImport.size);
        assertEquals("Fresh Map", toImport.get(0).displayName);

        ImportReport report = importer.importEntries(toImport, DuplicatePolicy.SKIP, null);
        assertEquals(1, report.totalSuccess);
        assertTrue(mapDir.child("new.msav").exists());

        scanner.cleanupTemporaryZips(scanned);
    }

    @Test
    public void testQuickImportFlowWithOverwriteAndRename() throws IOException{
        mapDir.child("existing.msav").writeBytes(TestHelper.createValidMapBytes("Old Version"));

        File zip = new File(tempDir, "update.zip");
        TestHelper.createTestZip(zip,
            new TestHelper.ZipEntryBuilder("existing.msav", TestHelper.createValidMapBytes("Updated Version")),
            new TestHelper.ZipEntryBuilder("bonus.msav", TestHelper.createValidMapBytes("Bonus Map"))
        );

        Seq<PackEntry> scanned = scanner.scanZip(new Fi(zip), PackType.MAP, null);
        for(PackEntry e : scanned) scanner.checkDuplicate(e);

        PackLoaderSettings.setQuickImport(true);
        PackLoaderSettings.setDuplicatePolicy(DuplicatePolicy.OVERWRITE);

        int eligible = EntryFilter.selectEligibleForQuickImport(scanned, PackType.MAP, PackLoaderSettings.getDuplicatePolicy());
        assertEquals(2, eligible, "OVERWRITE policy must auto-select all 2 valid entries");

        Seq<PackEntry> toImport = EntryFilter.collectSelected(scanned, PackType.MAP, null, "");
        assertEquals(2, toImport.size);

        ImportReport report = importer.importEntries(toImport, DuplicatePolicy.OVERWRITE, null);
        assertEquals(2, report.totalSuccess + report.totalOverwritten);
        assertEquals(1, report.totalOverwritten);

        scanner.cleanupTemporaryZips(scanned);
    }

    @Test
    public void testQuickImportFlowAllDuplicatesSkipProducesZeroEligible() throws IOException{
        mapDir.child("map_a.msav").writeBytes(TestHelper.createValidMapBytes("Map A"));
        mapDir.child("map_b.msav").writeBytes(TestHelper.createValidMapBytes("Map B"));

        File zip = new File(tempDir, "all_dups.zip");
        TestHelper.createTestZip(zip,
            new TestHelper.ZipEntryBuilder("map_a.msav", TestHelper.createValidMapBytes("Map A")),
            new TestHelper.ZipEntryBuilder("map_b.msav", TestHelper.createValidMapBytes("Map B"))
        );

        Seq<PackEntry> scanned = scanner.scanZip(new Fi(zip), PackType.MAP, null);
        for(PackEntry e : scanned) scanner.checkDuplicate(e);

        PackLoaderSettings.setQuickImport(true);
        PackLoaderSettings.setDuplicatePolicy(DuplicatePolicy.SKIP);

        int eligible = EntryFilter.selectEligibleForQuickImport(scanned, PackType.MAP, DuplicatePolicy.SKIP);
        assertEquals(0, eligible, "When all items are duplicates, SKIP must produce 0 eligible entries without auto-importing");

        Seq<PackEntry> toImport = EntryFilter.collectSelected(scanned, PackType.MAP, null, "");
        assertTrue(toImport.isEmpty(), "Nothing should be marked to import");

        scanner.cleanupTemporaryZips(scanned);
    }
}

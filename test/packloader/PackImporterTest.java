package packloader;

import arc.files.Fi;
import arc.struct.Seq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.core.PackImporter;
import packloader.core.PackScanner;
import packloader.model.DuplicatePolicy;
import packloader.model.ImportItemResult;
import packloader.model.ImportReport;
import packloader.model.PackEntry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class PackImporterTest{
    private File tempDir;
    private Fi mapDir;
    private Fi schemDir;
    private PackScanner scanner;
    private PackImporter importer;

    @BeforeEach
    public void setup() throws IOException{
        TestHelper.initBundle(java.util.Locale.ENGLISH);
        tempDir = Files.createTempDirectory("packloader_test_import").toFile();
        mapDir = new Fi(new File(tempDir, "maps"));
        schemDir = new Fi(new File(tempDir, "schematics"));
        mapDir.mkdirs();
        schemDir.mkdirs();

        scanner = new PackScanner(mapDir, schemDir);
        importer = new PackImporter(mapDir, schemDir);
    }

    @AfterEach
    public void teardown(){
        if(tempDir != null && tempDir.exists()){
            new Fi(tempDir).deleteDirectory();
        }
    }

    @Test
    public void testImportWithSkipPolicy() throws IOException{
        byte[] originalContent = TestHelper.createValidMapBytes("OriginalMap");
        byte[] newContent = TestHelper.createValidMapBytes("NewMapReplacement");

        // Write existing file
        Fi target = mapDir.child("conflict_map.msav");
        target.writeBytes(originalContent);

        // Prepare incoming file
        File inputDir = new File(tempDir, "incoming");
        inputDir.mkdirs();
        Fi incoming = new Fi(new File(inputDir, "conflict_map.msav"));
        incoming.writeBytes(newContent);

        Seq<PackEntry> entries = scanner.scanFiles(incoming);
        assertEquals(1, entries.size);
        assertTrue(entries.first().isDuplicate);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.SKIP, null);

        assertEquals(1, report.totalSelected);
        assertEquals(1, report.totalSkipped);
        assertEquals(0, report.totalSuccess);
        assertEquals(0, report.totalOverwritten);

        // Target content must NOT have changed
        assertArrayEquals(originalContent, target.readBytes());
    }

    @Test
    public void testImportWithOverwritePolicy() throws IOException{
        byte[] originalContent = TestHelper.createValidMapBytes("OriginalMap");
        byte[] newContent = TestHelper.createValidMapBytes("OverwrittenMap");

        Fi target = mapDir.child("conflict_map.msav");
        target.writeBytes(originalContent);

        File inputDir = new File(tempDir, "incoming");
        inputDir.mkdirs();
        Fi incoming = new Fi(new File(inputDir, "conflict_map.msav"));
        incoming.writeBytes(newContent);

        Seq<PackEntry> entries = scanner.scanFiles(incoming);
        ImportReport report = importer.importEntries(entries, DuplicatePolicy.OVERWRITE, null);

        assertEquals(1, report.totalSelected);
        assertEquals(1, report.totalOverwritten);
        assertEquals(0, report.totalSkipped);

        // Target file must contain new content
        assertArrayEquals(newContent, target.readBytes());
    }

    @Test
    public void testImportWithRenamePolicy() throws IOException{
        byte[] originalContent = TestHelper.createValidSchematicBytes("MyCoreSchem");
        byte[] newContent = TestHelper.createValidSchematicBytes("MyCoreSchem_V2");

        Fi target = schemDir.child("core_layout.msch");
        target.writeBytes(originalContent);

        File inputDir = new File(tempDir, "incoming");
        inputDir.mkdirs();
        Fi incoming = new Fi(new File(inputDir, "core_layout.msch"));
        incoming.writeBytes(newContent);

        Seq<PackEntry> entries = scanner.scanFiles(incoming);
        ImportReport report = importer.importEntries(entries, DuplicatePolicy.RENAME, null);

        assertEquals(1, report.totalSelected);
        assertEquals(1, report.totalSuccess);
        assertEquals(0, report.totalSkipped);
        assertEquals(0, report.totalOverwritten);

        // Both original and renamed files must exist
        assertTrue(target.exists());
        assertArrayEquals(originalContent, target.readBytes());

        Fi renamed = schemDir.child("core_layout_1.msch");
        assertTrue(renamed.exists(), "Expected auto-renamed file core_layout_1.msch to exist");
        assertArrayEquals(newContent, renamed.readBytes());
    }

    @Test
    public void testInvalidFileReportedAsFailedWithoutHalting() throws IOException{
        byte[] validBytes = TestHelper.createValidMapBytes("GoodMap");
        byte[] badBytes = new byte[]{1, 2, 3, 4};

        File inputDir = new File(tempDir, "mixed");
        inputDir.mkdirs();

        Fi good = new Fi(new File(inputDir, "good.msav"));
        good.writeBytes(validBytes);

        Fi bad = new Fi(new File(inputDir, "bad.msch"));
        bad.writeBytes(badBytes);

        Seq<PackEntry> entries = scanner.scanFiles(good, bad);
        assertEquals(2, entries.size);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.SKIP, null);

        assertEquals(2, report.totalSelected);
        assertEquals(1, report.totalSuccess);
        assertEquals(1, report.totalFailed);

        assertTrue(mapDir.child("good.msav").exists());
        assertFalse(schemDir.child("bad.msch").exists());

        ImportItemResult badRes = report.results.find(r -> r.entry.sanitizedFileName.equals("bad.msch"));
        assertNotNull(badRes);
        assertEquals(ImportItemResult.Status.FAILED, badRes.status);
    }

    @Test
    public void testEndToEndZipArchiveImport() throws IOException{
        File zipFile = new File(tempDir, "bundle.zip");

        byte[] mapBytes = TestHelper.createValidMapBytes("E2EMap");
        byte[] schemBytes = TestHelper.createValidSchematicBytes("E2ESchem");

        TestHelper.createTestZip(zipFile,
            new TestHelper.ZipEntryBuilder("maps/e2e_map.msav", mapBytes),
            new TestHelper.ZipEntryBuilder("schematics/e2e_schem.msch", schemBytes)
        );

        Seq<PackEntry> entries = scanner.scanZip(new Fi(zipFile));
        assertEquals(2, entries.size);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.SKIP, null);

        assertEquals(2, report.totalSuccess);
        assertEquals(0, report.totalFailed);

        Fi importedMap = mapDir.child("e2e_map.msav");
        Fi importedSchem = schemDir.child("e2e_schem.msch");

        assertTrue(importedMap.exists(), "e2e_map.msav should exist in maps directory");
        assertTrue(importedSchem.exists(), "e2e_schem.msch should exist in schematics directory");

        assertArrayEquals(mapBytes, importedMap.readBytes());
        assertArrayEquals(schemBytes, importedSchem.readBytes());

        // Ensure no temporary files remained
        assertEquals(0, mapDir.file().listFiles((dir, name) -> name.endsWith(".packload_tmp")).length);
        assertEquals(0, schemDir.file().listFiles((dir, name) -> name.endsWith(".packload_tmp")).length);
    }
}

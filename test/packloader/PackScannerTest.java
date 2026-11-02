package packloader;

import arc.files.Fi;
import arc.struct.Seq;
import mindustry.Vars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.core.PackScanner;
import packloader.model.PackEntry;
import packloader.model.PackType;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class PackScannerTest{
    private File tempDir;
    private Fi mapDir;
    private Fi schemDir;
    private PackScanner scanner;
    private Fi originalTmpDirectory;

    @BeforeEach
    public void setup() throws IOException{
        TestHelper.initBundle(java.util.Locale.ENGLISH);
        tempDir = Files.createTempDirectory("packloader_test_scan").toFile();
        mapDir = new Fi(new File(tempDir, "maps"));
        schemDir = new Fi(new File(tempDir, "schematics"));
        mapDir.mkdirs();
        schemDir.mkdirs();

        originalTmpDirectory = Vars.tmpDirectory;
        scanner = new PackScanner(mapDir, schemDir);
    }

    @AfterEach
    public void teardown(){
        Vars.tmpDirectory = originalTmpDirectory;

        if(tempDir != null && tempDir.exists()){
            new Fi(tempDir).deleteDirectory();
        }
    }

    @Test
    public void testSafeZipEntryPathTraversalProtection(){
        // Safe paths
        assertTrue(PackScanner.isSafeZipEntry("map.msav"));
        assertTrue(PackScanner.isSafeZipEntry("maps/map.msav"));
        assertTrue(PackScanner.isSafeZipEntry("nested/folder/schem.msch"));
        assertTrue(PackScanner.isSafeZipEntry("safe_file_123.msch"));

        // Unsafe paths (Zip Slip attacks)
        assertFalse(PackScanner.isSafeZipEntry("../evil.msav"));
        assertFalse(PackScanner.isSafeZipEntry("../../evil.msav"));
        assertFalse(PackScanner.isSafeZipEntry("sub/../../evil.msav"));
        assertFalse(PackScanner.isSafeZipEntry("/root/evil.msav"));
        assertFalse(PackScanner.isSafeZipEntry("/etc/passwd"));
        assertFalse(PackScanner.isSafeZipEntry("C:\\Windows\\System32\\calc.exe"));
        assertFalse(PackScanner.isSafeZipEntry("..\\evil.msav"));
        assertFalse(PackScanner.isSafeZipEntry(""));
        assertFalse(PackScanner.isSafeZipEntry(null));
    }

    @Test
    public void testAndroidSafVirtualPathSanitization(){
        assertEquals("cool_map.msav", PackEntry.sanitizeName("primary:Download/cool_map.msav"));
        assertEquals("base.msch", PackEntry.sanitizeName("raw:/storage/emulated/0/Download/base.msch"));
        assertEquals("test_map.msav", PackEntry.sanitizeName("document:1234:test_map.msav"));
        assertEquals("simple.msav", PackEntry.sanitizeName("simple.msav"));
        assertEquals("nested_schem.msch", PackEntry.sanitizeName("folder/nested_schem.msch"));
        assertEquals("windows_map.msav", PackEntry.sanitizeName("C:\\Downloads\\windows_map.msav"));
    }

    @Test
    public void testScanZipComprehensive() throws IOException{
        File zipFile = new File(tempDir, "sample_pack.zip");

        byte[] validSchemBytes = TestHelper.createValidSchematicBytes("MySchem");
        byte[] validMapBytes = TestHelper.createValidMapBytes("DesertFortress");
        byte[] corruptSchemBytes = new byte[]{0x00, 0x01, 0x02, 0x03}; // Bad header
        byte[] textFileBytes = "Some documentation text".getBytes();

        TestHelper.createTestZip(zipFile,
            new TestHelper.ZipEntryBuilder("root_schem.msch", validSchemBytes),
            new TestHelper.ZipEntryBuilder("maps/nested_map.msav", validMapBytes),
            new TestHelper.ZipEntryBuilder("broken.msch", corruptSchemBytes),
            new TestHelper.ZipEntryBuilder("readme.txt", textFileBytes),
            new TestHelper.ZipEntryBuilder("__MACOSX/._root_schem.msch", textFileBytes)
        );

        Seq<PackEntry> entries = scanner.scanZip(new Fi(zipFile));

        // Expect 3 entries (root_schem, nested_map, broken). Text and MacOS metadata must be ignored.
        assertEquals(3, entries.size);

        PackEntry schemEntry = entries.find(e -> e.sanitizedFileName.equals("root_schem.msch"));
        assertNotNull(schemEntry);
        assertEquals(PackType.SCHEMATIC, schemEntry.type);
        assertTrue(schemEntry.isValid);

        PackEntry mapEntry = entries.find(e -> e.sanitizedFileName.equals("nested_map.msav"));
        assertNotNull(mapEntry);
        assertEquals(PackType.MAP, mapEntry.type);
        assertTrue(mapEntry.isValid);

        PackEntry brokenEntry = entries.find(e -> e.sanitizedFileName.equals("broken.msch"));
        assertNotNull(brokenEntry);
        assertFalse(brokenEntry.isValid);
        assertTrue(brokenEntry.errorReason.contains("header"));
    }

    @Test
    public void testScanDirectoryRecursive() throws IOException{
        File sourceDir = new File(tempDir, "source_folder");
        File subDir = new File(sourceDir, "sub_dir");
        subDir.mkdirs();

        byte[] mapBytes = TestHelper.createValidMapBytes("TestMap");
        byte[] schemBytes = TestHelper.createValidSchematicBytes("TestSchem");

        new Fi(new File(sourceDir, "direct_map.msav")).writeBytes(mapBytes);
        new Fi(new File(subDir, "nested_schem.msch")).writeBytes(schemBytes);
        new Fi(new File(sourceDir, "notes.md")).writeBytes("Notes".getBytes());

        Seq<PackEntry> entries = scanner.scanDirectory(new Fi(sourceDir));

        assertEquals(2, entries.size);
        assertTrue(entries.contains(e -> e.sanitizedFileName.equals("direct_map.msav")));
        assertTrue(entries.contains(e -> e.sanitizedFileName.equals("nested_schem.msch")));
    }

    @Test
    public void testDuplicateDetectionInDirectory() throws IOException{
        // Place an existing map in mapDir
        Fi existing = mapDir.child("existing_map.msav");
        existing.writeBytes(TestHelper.createValidMapBytes("ExistingMap"));

        // Scan an entry with the same name
        File testDir = new File(tempDir, "input");
        testDir.mkdirs();
        Fi incoming = new Fi(new File(testDir, "existing_map.msav"));
        incoming.writeBytes(TestHelper.createValidMapBytes("ExistingMapNew"));

        Seq<PackEntry> entries = scanner.scanFiles(incoming);
        assertEquals(1, entries.size);

        PackEntry entry = entries.first();
        assertTrue(entry.isDuplicate);
        assertTrue(entry.duplicateReason.contains("existing_map.msav"));
    }

    @Test
    public void testZipEntryCountLimitIsEnforced() throws IOException{
        File zipFile = new File(tempDir, "too_many_entries.zip");
        try(ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile))){
            for(int i = 0; i <= PackScanner.MAX_ZIP_ENTRIES; i++){
                out.putNextEntry(new ZipEntry("file_" + i + ".txt"));
                out.closeEntry();
            }
        }

        IOException error = assertThrows(IOException.class, () -> scanner.scanZip(new Fi(zipFile)));
        assertTrue(error.getMessage().contains("20000") || error.getMessage().toLowerCase().contains("entries"),
            error.getMessage());
    }

    @Test
    public void testCorruptZipThrowsException(){
        File corruptZip = new File(tempDir, "corrupted.zip");
        new Fi(corruptZip).writeBytes(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

        IOException ex = assertThrows(IOException.class, () -> {
            scanner.scanZip(new Fi(corruptZip));
        });
        assertTrue(ex.getMessage().toLowerCase().contains("corrupt") || ex.getMessage().toLowerCase().contains("zip"));
    }

    @Test
    public void testScanZipUppercaseExtensionSpacesAndNestedPaths() throws IOException{
        File zipFile = new File(tempDir, "odd_names.zip");

        byte[] mapBytes = TestHelper.createValidMapBytes("OddMap");
        byte[] schemBytes = TestHelper.createValidSchematicBytes("OddSchem");

        TestHelper.createTestZip(zipFile,
            new TestHelper.ZipEntryBuilder("nested/my map.MSAV", mapBytes),
            new TestHelper.ZipEntryBuilder("schematics / big core .MSCH ", schemBytes),
            new TestHelper.ZipEntryBuilder("sub_folder/nested_map.msav", mapBytes),
            new TestHelper.ZipEntryBuilder("./dot_slash.msch", schemBytes)
        );

        Seq<PackEntry> entries = scanner.scanZip(new Fi(zipFile));
        assertEquals(4, entries.size, "Must find all 4 entries regardless of case, spaces, or dot-slash prefix");

        PackEntry map1 = entries.find(e -> e.sanitizedFileName.equalsIgnoreCase("my map.MSAV"));
        assertNotNull(map1);
        assertEquals(PackType.MAP, map1.type);

        PackEntry schem1 = entries.find(e -> e.sanitizedFileName.toLowerCase().contains("big core"));
        assertNotNull(schem1);
        assertEquals(PackType.SCHEMATIC, schem1.type);

        PackEntry nestedMap = entries.find(e -> e.sanitizedFileName.equalsIgnoreCase("nested_map.msav"));
        assertNotNull(nestedMap);

        PackEntry dotSchem = entries.find(e -> e.sanitizedFileName.equalsIgnoreCase("dot_slash.msch"));
        assertNotNull(dotSchem);
    }

    @Test
    public void testTargetTypeFilteringInZip() throws IOException{
        File zipFile = new File(tempDir, "mixed_pack.zip");

        byte[] mapBytes = TestHelper.createValidMapBytes("MapOnlyTest");
        byte[] schemBytes = TestHelper.createValidSchematicBytes("SchemOnlyTest");

        TestHelper.createTestZip(zipFile,
            new TestHelper.ZipEntryBuilder("maps/level1.msav", mapBytes),
            new TestHelper.ZipEntryBuilder("maps/level2.msav", mapBytes),
            new TestHelper.ZipEntryBuilder("schematics/base.msch", schemBytes)
        );

        // 1. Scan with MAP filter only
        Seq<PackEntry> mapsOnly = scanner.scanZip(new Fi(zipFile), PackType.MAP);
        assertEquals(2, mapsOnly.size);
        for(PackEntry e : mapsOnly){
            assertEquals(PackType.MAP, e.type);
        }

        // 2. Scan with SCHEMATIC filter only
        Seq<PackEntry> schemsOnly = scanner.scanZip(new Fi(zipFile), PackType.SCHEMATIC);
        assertEquals(1, schemsOnly.size);
        assertEquals(PackType.SCHEMATIC, schemsOnly.first().type);

        // 3. Scan with null filter (all)
        Seq<PackEntry> all = scanner.scanZip(new Fi(zipFile), null);
        assertEquals(3, all.size);
    }

    @Test
    public void testTargetTypeFilteringInDirectory() throws IOException{
        File root = new File(tempDir, "dir_mixed");
        File sub1 = new File(root, "sub_maps");
        File sub2 = new File(root, "sub_schems");
        sub1.mkdirs();
        sub2.mkdirs();

        byte[] mapBytes = TestHelper.createValidMapBytes("DirMap");
        byte[] schemBytes = TestHelper.createValidSchematicBytes("DirSchem");

        new Fi(new File(sub1, "map1.msav")).writeBytes(mapBytes);
        new Fi(new File(sub1, "map2.MSAV")).writeBytes(mapBytes);
        new Fi(new File(sub2, "schem1.msch")).writeBytes(schemBytes);

        // Map filter only
        Seq<PackEntry> mapsOnly = scanner.scanDirectory(new Fi(root), PackType.MAP);
        assertEquals(2, mapsOnly.size);
        for(PackEntry e : mapsOnly){
            assertEquals(PackType.MAP, e.type);
        }

        // Schematic filter only
        Seq<PackEntry> schemsOnly = scanner.scanDirectory(new Fi(root), PackType.SCHEMATIC);
        assertEquals(1, schemsOnly.size);
        assertEquals(PackType.SCHEMATIC, schemsOnly.first().type);
    }

    @Test
    public void testIsZipFiWithMagicBytesWithoutExtension() throws IOException{
        File zipFile = new File(tempDir, "document_10000123"); // Simulates Android SAF Uri without .zip

        byte[] mapBytes = TestHelper.createValidMapBytes("SafMap");
        TestHelper.createTestZip(zipFile,
            new TestHelper.ZipEntryBuilder("level.msav", mapBytes)
        );

        Fi fi = new Fi(zipFile);
        assertTrue(PackScanner.isZipFi(fi), "Must recognize ZIP by magic bytes even without .zip in name");

        // Scan via scanFiles should auto-detect and scan it as ZIP
        Seq<PackEntry> entries = scanner.scanFiles(PackType.MAP, fi);
        assertEquals(1, entries.size);
        assertEquals("level.msav", entries.first().sanitizedFileName);
    }

    @Test
    public void testTargetTypeDirectoryScanningSeparation() throws IOException{
        File folder = new File(tempDir, "mixed_folder");
        folder.mkdirs();
        new Fi(new File(folder, "cool_map.msav")).writeBytes(TestHelper.createValidMapBytes("CoolMap"));
        new Fi(new File(folder, "cool_schem.msch")).writeBytes(TestHelper.createValidSchematicBytes("CoolSchem"));

        // When scanning for schematics, must only find schematics
        Seq<PackEntry> schemsOnly = scanner.scanDirectory(new Fi(folder), PackType.SCHEMATIC);
        assertEquals(1, schemsOnly.size);
        assertEquals(PackType.SCHEMATIC, schemsOnly.first().type);
        assertEquals("cool_schem.msch", schemsOnly.first().sanitizedFileName);

        // When scanning for maps, must only find maps
        Seq<PackEntry> mapsOnly = scanner.scanDirectory(new Fi(folder), PackType.MAP);
        assertEquals(1, mapsOnly.size);
        assertEquals(PackType.MAP, mapsOnly.first().type);
        assertEquals("cool_map.msav", mapsOnly.first().sanitizedFileName);
    }

    @Test
    public void testDetectPackTypeByMagicBytes() throws IOException{
        File schemNoExt = new File(tempDir, "schem_without_extension");
        File mapNoExt = new File(tempDir, "map_without_extension");

        new Fi(schemNoExt).writeBytes(TestHelper.createValidSchematicBytes("MagicSchem"));
        new Fi(mapNoExt).writeBytes(TestHelper.createValidMapBytes("MagicMap"));

        assertEquals(PackType.SCHEMATIC, PackScanner.detectPackType(new Fi(schemNoExt)));
        assertEquals(PackType.MAP, PackScanner.detectPackType(new Fi(mapNoExt)));

        // Non-matching file
        File textFile = new File(tempDir, "notes.txt");
        new Fi(textFile).writeString("Hello world");
        assertNull(PackScanner.detectPackType(new Fi(textFile)));
    }

    @Test
    public void testIsScopedStorageRestricted(){
        Fi extDownload = new Fi(new File("/storage/emulated/0/Download"));
        Fi sdCard = new Fi(new File("/sdcard/Download"));
        Fi linuxHome = new Fi(new File("/home/user/Download"));

        // On desktop/non-mobile, never restricted
        assertFalse(PackScanner.isScopedStorageRestricted(extDownload, false));
        assertFalse(PackScanner.isScopedStorageRestricted(sdCard, false));
        assertFalse(PackScanner.isScopedStorageRestricted(linuxHome, false));

        // On mobile: external storage paths ARE restricted
        assertTrue(PackScanner.isScopedStorageRestricted(extDownload, true));
        assertTrue(PackScanner.isScopedStorageRestricted(sdCard, true));

        // Non-storage paths are not restricted
        assertFalse(PackScanner.isScopedStorageRestricted(linuxHome, true));
    }

    @Test
    public void testScanDirectoryWithMagicBytesDetection() throws IOException{
        File folder = new File(tempDir, "magic_folder");
        folder.mkdirs();

        // Create schematic with non-standard extension (e.g. download renamed to .bin)
        File renamedSchem = new File(folder, "reactor_download.bin");
        new Fi(renamedSchem).writeBytes(TestHelper.createValidSchematicBytes("ReactorCore"));

        Seq<PackEntry> entries = scanner.scanDirectory(new Fi(folder), PackType.SCHEMATIC);
        assertEquals(1, entries.size);
        assertEquals(PackType.SCHEMATIC, entries.first().type);
        assertTrue(entries.first().sanitizedFileName.endsWith(".msch"));
    }

    @Test
    public void testCorruptZipIsReportedAsZipException() throws IOException{
        File corruptZip = new File(tempDir, "corrupt_archive.zip");
        new Fi(corruptZip).writeBytes(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

        assertThrows(ZipException.class, () -> scanner.scanZip(new Fi(corruptZip)));
    }

    @Test
    public void testTruncatedAndMalformedFilesInsideZipAreInvalid() throws IOException{
        File zipFile = new File(tempDir, "malformed_pack.zip");
        byte[] validMap = TestHelper.createValidMapBytes("Valid Map");
        byte[] truncatedMap = TestHelper.createTruncatedMapBytes("Truncated Map");
        byte[] malformedMap = TestHelper.createMalformedMapContentBytes("Malformed Map");
        byte[] truncatedSchematic = TestHelper.createTruncatedSchematicBytes();

        TestHelper.createTestZip(zipFile,
            new TestHelper.ZipEntryBuilder("valid.msav", validMap),
            new TestHelper.ZipEntryBuilder("truncated.msav", truncatedMap),
            new TestHelper.ZipEntryBuilder("malformed.msav", malformedMap),
            new TestHelper.ZipEntryBuilder("truncated.msch", truncatedSchematic)
        );

        Seq<PackEntry> entries = scanner.scanZip(new Fi(zipFile));
        assertEquals(4, entries.size);

        PackEntry valid = entries.find(e -> e.sanitizedFileName.equals("valid.msav"));
        assertNotNull(valid);
        assertTrue(valid.isValid);

        PackEntry truncated = entries.find(e -> e.sanitizedFileName.equals("truncated.msav"));
        assertNotNull(truncated);
        assertFalse(truncated.isValid);
        assertFalse(truncated.errorReason.isEmpty());

        PackEntry malformed = entries.find(e -> e.sanitizedFileName.equals("malformed.msav"));
        assertNotNull(malformed);
        assertFalse(malformed.isValid);
        assertFalse(malformed.errorReason.isEmpty());

        PackEntry truncatedSchem = entries.find(e -> e.sanitizedFileName.equals("truncated.msch"));
        assertNotNull(truncatedSchem);
        assertFalse(truncatedSchem.isValid);
        assertFalse(truncatedSchem.errorReason.isEmpty());
    }

    @Test
    public void testZipScanProgressReportsCurrentFileAndKnownTotal() throws IOException{
        File zipFile = new File(tempDir, "progress.zip");
        TestHelper.createTestZip(zipFile,
            new TestHelper.ZipEntryBuilder("first.msav", TestHelper.createValidMapBytes("First")),
            new TestHelper.ZipEntryBuilder("second.msch", TestHelper.createValidSchematicBytes("Second"))
        );

        RecordingListener listener = new RecordingListener();
        Seq<PackEntry> entries = scanner.scanZip(new Fi(zipFile), null, listener);

        assertEquals(2, entries.size);
        assertEquals(2, listener.files.size());
        for(int total : listener.totals){
            assertEquals(2, total);
        }
        assertEquals(1, listener.discovered.get(0));
        assertEquals(2, listener.discovered.get(1));
        assertEquals("first.msav", listener.files.get(0));
        assertEquals("second.msch", listener.files.get(1));
    }

    @Test
    public void testDirectoryScanProgressUsesUnknownTotal() throws IOException{
        File folder = new File(tempDir, "progress_folder");
        folder.mkdirs();
        new Fi(new File(folder, "one.msav")).writeBytes(TestHelper.createValidMapBytes("One"));
        new Fi(new File(folder, "two.msch")).writeBytes(TestHelper.createValidSchematicBytes("Two"));

        RecordingListener listener = new RecordingListener();
        Seq<PackEntry> entries = scanner.scanDirectory(new Fi(folder), null, listener);

        assertEquals(2, entries.size);
        assertEquals(2, listener.files.size());
        for(int total : listener.totals){
            assertEquals(0, total, "Directory scans do not know the total ahead of time");
        }
        assertEquals(2, listener.discovered.get(listener.discovered.size() - 1));
    }

    @Test
    public void testDuplicateZipEntriesWarnOnlyTheOwningScan() throws IOException{
        File zipFile = new File(tempDir, "duplicates.zip");
        byte[] mapBytes = TestHelper.createValidMapBytes("Duplicate Map");
        try(java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
            new java.io.FileOutputStream(zipFile))){
            for(String name : new String[]{"same.msav", "SAME.MSAV"}){
                zos.putNextEntry(new java.util.zip.ZipEntry(name));
                zos.write(mapBytes);
                zos.closeEntry();
            }
        }

        RecordingListener first = new RecordingListener();
        Seq<PackEntry> entries = scanner.scanZip(new Fi(zipFile), null, first);

        assertEquals(1, entries.size, "Case-insensitive duplicate ZIP entries must be skipped");
        assertEquals(1, first.warnings.size);
        assertTrue(first.warnings.get(0).contains("Duplicate ZIP entry"));

        // The same archive still contains duplicates, so a new scan must produce its own
        // warning. Ownership means each listener only receives warnings from its own scan.
        RecordingListener second = new RecordingListener();
        scanner.scanZip(new Fi(zipFile), null, second);
        assertEquals(1, second.warnings.size,
            "A new scan must report its own duplicate warning");
        assertTrue(second.warnings.get(0).contains("Duplicate ZIP entry"));

        File cleanZip = new File(tempDir, "clean.zip");
        TestHelper.createTestZip(cleanZip,
            new TestHelper.ZipEntryBuilder("clean.msav", mapBytes));
        RecordingListener clean = new RecordingListener();
        scanner.scanZip(new Fi(cleanZip), null, clean);
        assertTrue(clean.warnings.isEmpty(),
            "Warnings from a previous scan must not leak into a new scan");
    }

    @Test
    public void testDirectoryDepthWarningIsDeliveredToListener() throws IOException{
        File root = new File(tempDir, "deep_folder");
        File current = root;
        for(int i = 0; i < PackScanner.MAX_SCAN_DEPTH + 2; i++){
            current = new File(current, "d");
        }
        current.mkdirs();

        RecordingListener listener = new RecordingListener();
        scanner.scanDirectory(new Fi(root), null, listener);

        assertTrue(listener.warnings.find(w -> w.contains("deeper")) != null,
            "Folder depth warnings must be delivered to the scan listener");
    }

    @Test
    public void testScanCancellationStopsZipScan() throws IOException{
        File zipFile = new File(tempDir, "cancel.zip");
        TestHelper.createTestZip(zipFile,
            new TestHelper.ZipEntryBuilder("one.msav", TestHelper.createValidMapBytes("One")),
            new TestHelper.ZipEntryBuilder("two.msav", TestHelper.createValidMapBytes("Two"))
        );

        RecordingListener listener = new RecordingListener();
        listener.cancelAfter = 1;

        assertThrows(PackScanner.ScanCancelledException.class,
            () -> scanner.scanZip(new Fi(zipFile), null, listener));
    }

    @Test
    public void testScanCancellationBeforeFirstFile() throws IOException{
        File input = new File(tempDir, "cancel_one.msav");
        new Fi(input).writeBytes(TestHelper.createValidMapBytes("Cancel"));

        RecordingListener listener = new RecordingListener();
        listener.cancelAfter = 0;

        assertThrows(PackScanner.ScanCancelledException.class,
            () -> scanner.scanFiles(PackType.MAP, listener, new Fi(input)));
    }

    @Test
    public void testMaterializedSafZipIsCleanedUpWithEntries() throws IOException{
        File tmpRoot = new File(tempDir, "saf_tmp");
        tmpRoot.mkdirs();
        Vars.tmpDirectory = new Fi(tmpRoot);

        byte[] zipBytes = createZipBytes(new TestHelper.ZipEntryBuilder(
            "only.msav", TestHelper.createValidMapBytes("Only Map")));
        VirtualZipFi virtual = new VirtualZipFi("saf_document", zipBytes);

        Seq<PackEntry> entries = scanner.scanZip(virtual);
        assertEquals(1, entries.size);

        Fi temporary = entries.first().temporaryZipFile;
        assertNotNull(temporary, "A virtual SAF ZIP must be materialized");
        assertTrue(temporary.exists());
        assertTrue(temporary.name().startsWith("saf_zip_"));

        scanner.cleanupTemporaryZips(entries);

        assertNull(entries.first().temporaryZipFile);
        assertFalse(temporary.exists(), "The temporary ZIP must be deleted");
    }

    @Test
    public void testUntrackedSafTempsArePrunedAndCapped() throws IOException{
        File tmpRoot = new File(tempDir, "saf_prune");
        tmpRoot.mkdirs();
        Fi tmpDir = new Fi(tmpRoot);
        Vars.tmpDirectory = tmpDir;

        for(int i = 0; i < 10; i++){
            Fi old = tmpDir.child("saf_zip_old_" + i + ".zip");
            old.writeBytes(new byte[16]);
            assertTrue(old.file().setLastModified(1000L + i * 1000L));
        }

        byte[] zipBytes = createZipBytes(new TestHelper.ZipEntryBuilder(
            "only.msch", TestHelper.createValidSchematicBytes("Only Schematic")));
        Seq<PackEntry> entries = scanner.scanZip(new VirtualZipFi("saf_document", zipBytes));
        assertEquals(1, entries.size);

        File[] remaining = tmpRoot.listFiles((dir, name) -> name.startsWith("saf_zip_"));
        assertNotNull(remaining);
        assertEquals(9, remaining.length, "8 pruned-cap untracked ZIPs plus the active one");
        assertFalse(new File(tmpRoot, "saf_zip_old_0.zip").exists());
        assertFalse(new File(tmpRoot, "saf_zip_old_1.zip").exists());

        scanner.cleanupTemporaryZips(entries);
        remaining = tmpRoot.listFiles((dir, name) -> name.startsWith("saf_zip_"));
        assertNotNull(remaining);
        assertEquals(8, remaining.length);
    }

    @Test
    public void testSchematicCachedInPackEntryDuringScan() throws IOException{
        byte[] schemData = TestHelper.createValidSchematicBytes("CachedReactor");
        File zipFile = new File(tempDir, "schem_cache.zip");
        TestHelper.createTestZip(zipFile,
            new TestHelper.ZipEntryBuilder("reactor.msch", schemData)
        );

        Seq<PackEntry> entries = scanner.scanZip(new Fi(zipFile), PackType.SCHEMATIC, null);
        assertEquals(1, entries.size);
        PackEntry entry = entries.first();

        assertTrue(entry.isValid);
        assertEquals("CachedReactor", entry.displayName);
        assertNotNull(entry.schematic, "PackScanner must cache parsed schematic on entry");
        assertEquals("CachedReactor", entry.schematic.name());
        assertEquals(2, entry.schematic.width);
        assertEquals(2, entry.schematic.height);
    }

    @Test
    public void testGetExistingFileNamesAndFastDuplicateCheck() throws IOException{
        Fi existing1 = schemDir.child("drill.msch");
        Fi existing2 = schemDir.child("Reactor_Base.msch");
        existing1.writeBytes(new byte[]{1, 2, 3});
        existing2.writeBytes(new byte[]{1, 2, 3});

        java.util.Set<String> existingNames = scanner.getExistingFileNames(schemDir);
        assertEquals(2, existingNames.size());
        assertTrue(existingNames.contains("drill.msch"));
        assertTrue(existingNames.contains("reactor_base.msch"));

        PackEntry entryDup = new PackEntry(PackType.SCHEMATIC, "drill.msch", "DRILL.msch", 100);
        PackEntry entryNew = new PackEntry(PackType.SCHEMATIC, "turret.msch", "turret.msch", 100);

        scanner.checkDuplicate(entryDup, existingNames);
        assertTrue(entryDup.isDuplicate);

        scanner.checkDuplicate(entryNew, existingNames);
        assertFalse(entryNew.isDuplicate);
    }

    private static byte[] createZipBytes(TestHelper.ZipEntryBuilder... builders) throws IOException{
        File zip = Files.createTempFile("packloader_test_zip", ".zip").toFile();
        try{
            TestHelper.createTestZip(zip, builders);
            return Files.readAllBytes(zip.toPath());
        }finally{
            if(zip.exists()) zip.delete();
        }
    }

    private static class RecordingListener implements PackScanner.ScanListener{
        final List<String> files = new ArrayList<>();
        final List<Integer> discovered = new ArrayList<>();
        final List<Integer> totals = new ArrayList<>();
        final Seq<String> warnings = new Seq<>();
        int cancelAfter = -1;

        @Override
        public void onProgress(String currentFile, int discoveredCount, int total){
            files.add(currentFile);
            discovered.add(discoveredCount);
            totals.add(total);
        }

        @Override
        public void onWarning(String message){
            warnings.add(message);
        }

        @Override
        public boolean isCancelled(){
            return cancelAfter >= 0 && files.size() >= cancelAfter;
        }
    }

    /** Simulates an Android SAF document whose bytes are only available through read(). */
    private static class VirtualZipFi extends Fi{
        private final String fileName;
        private final byte[] data;

        VirtualZipFi(String fileName, byte[] data){
            super(new File(System.getProperty("java.io.tmpdir"),
                "packloader_virtual_" + System.nanoTime()));
            this.fileName = fileName;
            this.data = data;
        }

        @Override
        public String name(){
            return fileName;
        }

        @Override
        public BufferedInputStream read(int bufferSize){
            return new BufferedInputStream(new ByteArrayInputStream(data));
        }
    }
}

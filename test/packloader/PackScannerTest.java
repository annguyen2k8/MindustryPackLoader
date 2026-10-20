package packloader;

import arc.files.Fi;
import arc.struct.Seq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.core.PackScanner;
import packloader.model.PackEntry;
import packloader.model.PackType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class PackScannerTest{
    private File tempDir;
    private Fi mapDir;
    private Fi schemDir;
    private PackScanner scanner;

    @BeforeEach
    public void setup() throws IOException{
        TestHelper.initBundle(java.util.Locale.ENGLISH);
        tempDir = Files.createTempDirectory("packloader_test_scan").toFile();
        mapDir = new Fi(new File(tempDir, "maps"));
        schemDir = new Fi(new File(tempDir, "schematics"));
        mapDir.mkdirs();
        schemDir.mkdirs();

        scanner = new PackScanner(mapDir, schemDir);
    }

    @AfterEach
    public void teardown(){
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
}

package packloader;

import arc.files.Fi;
import arc.struct.Seq;
import arc.struct.StringMap;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.maps.Map;
import mindustry.maps.Maps;
import mindustry.mod.Mods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.core.PackFormat;
import packloader.core.PackImporter;
import packloader.core.PackScanner;
import packloader.model.DuplicatePolicy;
import packloader.model.ImportItemResult;
import packloader.model.ImportReport;
import packloader.model.PackEntry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PackImporterTest{
    private File tempDir;
    private Fi mapDir;
    private Fi schemDir;
    private PackScanner scanner;
    private PackImporter importer;

    private Maps originalMaps;
    private Schematics originalSchematics;
    private Fi originalCustomMapDirectory;
    private Fi originalSchematicDirectory;
    private int inputCounter;

    @BeforeEach
    public void setup() throws IOException{
        TestHelper.initBundle(java.util.Locale.ENGLISH);
        tempDir = Files.createTempDirectory("packloader_test_import").toFile();
        mapDir = new Fi(new File(tempDir, "maps"));
        schemDir = new Fi(new File(tempDir, "schematics"));
        mapDir.mkdirs();
        schemDir.mkdirs();

        originalMaps = Vars.maps;
        originalSchematics = Vars.schematics;
        originalCustomMapDirectory = Vars.customMapDirectory;
        originalSchematicDirectory = Vars.schematicDirectory;

        scanner = new PackScanner(mapDir, schemDir);
        importer = new PackImporter(mapDir, schemDir);
    }

    @AfterEach
    public void teardown(){
        Vars.maps = originalMaps;
        Vars.schematics = originalSchematics;
        Vars.customMapDirectory = originalCustomMapDirectory;
        Vars.schematicDirectory = originalSchematicDirectory;

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

    // ---------------------------------------------------------------------
    // Native map imports (H1)
    // ---------------------------------------------------------------------

    @Test
    public void testNativeMapImportUsesMetadataNameAsDestination() throws IOException{
        FakeGame.FakeMaps maps = installMaps();
        byte[] mapBytes = TestHelper.createValidMapBytes("Fancy Map");
        PackEntry entry = scanSingle("downloaded.msav", mapBytes);

        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.SKIP, null);

        assertEquals(1, report.totalSuccess);
        assertEquals(0, report.totalFailed);

        Fi destination = mapDir.child("Fancy Map.msav");
        assertTrue(destination.exists(), "Destination must use the map metadata name");
        assertArrayEquals(mapBytes, destination.readBytes());
        assertFalse(mapDir.child("downloaded.msav").exists(), "The original download name must not be used");
        assertEquals(1, maps.importCalls);
        assertEquals(1, maps.all.size);
        assertEquals("Fancy Map", maps.all.first().name());
    }

    @Test
    public void testNativeMapImportWithOnlyMapNameTag() throws IOException{
        FakeGame.FakeMaps maps = installMaps();
        byte[] mapBytes = TestHelper.createMapWithMapNameOnlyBytes("Savegame Map");
        PackEntry entry = scanSingle("save_1.msav", mapBytes);

        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.SKIP, null);

        assertEquals(1, report.totalSuccess);
        assertEquals(0, report.totalFailed);

        Fi destination = mapDir.child("Savegame Map.msav");
        assertTrue(destination.exists(), "Destination must use the mapname metadata");
        assertEquals(1, maps.importCalls);
        assertEquals(1, maps.all.size);
        assertEquals("Savegame Map", maps.all.first().name());

        // Verify the saved file now has a valid "name" tag in its metadata
        PackFormat.MapInfo writtenInfo = PackFormat.validateMap(destination.read());
        assertEquals("Savegame Map", writtenInfo.name);
        assertTrue(writtenInfo.hasNameTag, "Native import must have injected the 'name' tag");
    }

    @Test
    public void testNativeMapSkipKeepsExistingCustomMap() throws IOException{
        FakeGame.FakeMaps maps = installMaps();
        Map existing = existingMap(maps, "Shared Name", true);
        byte[] originalBytes = existing.file.readBytes();

        PackEntry entry = scanSingle("incoming.msav", TestHelper.createValidMapBytes("Shared Name"));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.SKIP, null);

        assertEquals(1, report.totalSkipped);
        assertEquals(0, report.totalSuccess);
        assertEquals(0, maps.importCalls);
        assertArrayEquals(originalBytes, existing.file.readBytes());
        assertEquals(1, maps.all.size);
    }

    @Test
    public void testNativeMapOverwriteRefusesBuiltInMap() throws IOException{
        FakeGame.FakeMaps maps = installMaps();
        Map builtIn = existingMap(maps, "Maze", false);
        byte[] originalBytes = builtIn.file.readBytes();

        PackEntry entry = scanSingle("incoming.msav", TestHelper.createValidMapBytes("Maze", 13, 8, 8));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.OVERWRITE, null);

        assertEquals(1, report.totalFailed);
        assertEquals(0, report.totalOverwritten);
        assertEquals(0, maps.importCalls);
        assertArrayEquals(originalBytes, builtIn.file.readBytes());
        assertEquals(1, maps.all.size);
        assertTrue(report.results.first().message.toLowerCase().contains("built-in") ||
            report.results.first().message.toLowerCase().contains("cannot be overwritten"));
    }

    @Test
    public void testNativeMapOverwriteReplacesCustomMap() throws IOException{
        FakeGame.FakeMaps maps = installMaps();
        Map existing = existingMap(maps, "Shared Name", true);
        Fi oldFile = existing.file;
        byte[] incomingBytes = TestHelper.createValidMapBytes("Shared Name", 13, 8, 8);

        PackEntry entry = scanSingle("incoming.msav", incomingBytes);
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.OVERWRITE, null);

        assertEquals(1, report.totalOverwritten);
        assertEquals(0, report.totalFailed);
        assertEquals(1, maps.importCalls);
        assertFalse(oldFile.exists(), "The old custom map file must be removed");
        assertArrayEquals(incomingBytes, mapDir.child("Shared Name_1.msav").readBytes());
        assertEquals(1, maps.all.size);
        assertEquals("Shared Name", maps.all.first().name());
    }

    @Test
    public void testNativeMapOverwriteRemovalFailureRollsBackAndKeepsOldMap() throws IOException{
        FakeGame.FakeMaps maps = installMaps();
        Map existing = existingMap(maps, "Shared Name", true);
        Fi oldFile = existing.file;
        byte[] originalBytes = oldFile.readBytes();
        byte[] incomingBytes = TestHelper.createValidMapBytes("Shared Name", 13, 8, 8);
        maps.failNextRemove = true;

        PackEntry entry = scanSingle("incoming.msav", incomingBytes);
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.OVERWRITE, null);

        assertEquals(1, report.totalFailed);
        assertEquals(0, report.totalOverwritten);
        assertEquals(0, report.totalSuccess);
        assertTrue(oldFile.exists(), "A failed removal must not claim to have overwritten the old map");
        assertArrayEquals(originalBytes, oldFile.readBytes());
        assertEquals(1, maps.all.size);
        assertSame(existing, maps.all.first());
        assertFalse(mapDir.child("Shared Name_1.msav").exists(),
            "The newly imported map must be rolled back when the old file cannot be removed");
    }

    @Test
    public void testNativeMapRenameKeepsBothMaps() throws IOException{
        FakeGame.FakeMaps maps = installMaps();
        Map existing = existingMap(maps, "Shared Name", true);
        byte[] originalBytes = existing.file.readBytes();
        byte[] incomingBytes = TestHelper.createValidMapBytes("Shared Name", 13, 8, 8);

        PackEntry entry = scanSingle("incoming.msav", incomingBytes);
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.RENAME, null);

        assertEquals(1, report.totalSuccess);
        assertEquals(0, report.totalFailed);
        assertArrayEquals(originalBytes, existing.file.readBytes());

        Fi renamed = mapDir.child("Shared Name_1.msav");
        assertTrue(renamed.exists());
        PackFormat.MapInfo renamedInfo = PackFormat.validateMap(renamed.read());
        assertEquals("Shared Name_1", renamedInfo.name);
        assertEquals(8, renamedInfo.width);
        assertEquals(8, renamedInfo.height);
        assertEquals(2, maps.all.size);
        assertTrue(maps.all.contains(m -> m.name().equals("Shared Name_1")));
    }

    @Test
    public void testNativeMapWriteFailureIsReportedAsFailed() throws IOException{
        FakeGame.FakeMaps maps = installMaps();
        maps.failImport = true;

        PackEntry entry = scanSingle("incoming.msav", TestHelper.createValidMapBytes("Failed Map"));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.SKIP, null);

        assertEquals(1, report.totalFailed);
        assertEquals(0, report.totalSuccess);
        assertEquals(0, maps.all.size);
        assertFalse(mapDir.child("Failed Map.msav").exists());
        assertTrue(report.results.first().message.contains("simulated map write failure"));
    }

    // ---------------------------------------------------------------------
    // Native schematic imports (H2)
    // ---------------------------------------------------------------------

    @Test
    public void testNativeSchematicImportWritesFileBeforeSuccess() throws IOException{
        FakeGame.FakeSchematics schematics = installSchematics();
        PackEntry entry = scanSingle("reactor.msch", TestHelper.createValidSchematicBytes("Reactor Core"));

        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.SKIP, null);

        assertEquals(1, report.totalSuccess);
        assertEquals(0, report.totalFailed);
        assertEquals(1, schematics.all.size);

        Schematic imported = schematics.all.first();
        assertEquals("Reactor Core", imported.name());
        assertNotNull(imported.file);
        assertTrue(imported.file.exists());
        assertTrue(imported.file.length() > 0);
        assertTrue(PackImporter.isWithinDirectory(schemDir, imported.file));
    }

    @Test
    public void testNativeSchematicWithoutNameUsesFileName() throws IOException{
        FakeGame.FakeSchematics schematics = installSchematics();
        PackEntry entry = scanSingle("Nameless Core.msch", TestHelper.createNamelessSchematicBytes());
        assertEquals("Nameless Core", entry.displayName,
            "A nameless schematic should use its file name instead of 'unknown'");

        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.SKIP, null);

        assertEquals(1, report.totalSuccess, report.results.first().message);
        assertEquals(0, report.totalFailed);
        assertEquals(1, schematics.all.size);
        assertEquals("Nameless Core", schematics.all.first().name());
        assertTrue(schemDir.child("Nameless Core.msch").exists());
    }

    @Test
    public void testNativeSchematicWriteFailureRemovesLoadoutEntry() throws IOException{
        FakeGame.FakeSchematics schematics = installSchematics();
        schematics.failWrites = true;

        PackEntry entry = scanSingle("reactor.msch", TestHelper.createValidSchematicBytes("Reactor Core"));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.SKIP, null);

        assertEquals(1, report.totalFailed);
        assertTrue(schematics.loadoutCache.isEmpty(),
            "A failed write must not leave the schematic in the loadout cache");
    }

    @Test
    public void testNativeSchematicWriteFailureDoesNotReportSuccess() throws IOException{
        FakeGame.FakeSchematics schematics = installSchematics();
        schematics.failWrites = true;

        PackEntry entry = scanSingle("reactor.msch", TestHelper.createValidSchematicBytes("Reactor Core"));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.SKIP, null);

        assertEquals(1, report.totalFailed);
        assertEquals(0, report.totalSuccess);
        assertEquals(0, schematics.all.size, "A failed write must be rolled back from the registry");
        assertFalse(schemDir.child("Reactor Core.msch").exists());
    }

    @Test
    public void testNativeSchematicWrittenOutsideDirectoryIsRejected() throws IOException{
        FakeGame.FakeSchematics schematics = installSchematics();
        schematics.outsideFile = new Fi(new File(tempDir, "outside.msch"));

        PackEntry entry = scanSingle("reactor.msch", TestHelper.createValidSchematicBytes("Reactor Core"));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.SKIP, null);

        assertEquals(1, report.totalFailed);
        assertEquals(0, report.totalSuccess);
        assertEquals(0, schematics.all.size);
        assertTrue(schematics.loadoutCache.isEmpty(),
            "Rejecting an outside write must also clean the loadout cache");
        assertTrue(schematics.outsideFile.exists(),
            "Rollback must not delete a file outside PackLoader's schematic directory");
    }

    @Test
    public void testNativeSchematicSkipKeepsExistingSchematic() throws IOException{
        FakeGame.FakeSchematics schematics = installSchematics();
        Schematic existing = existingSchematic(schematics, "Reactor Core", true, false);
        byte[] originalBytes = existing.file.readBytes();

        PackEntry entry = scanSingle("reactor.msch", TestHelper.createValidSchematicBytes("Reactor Core"));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.SKIP, null);

        assertEquals(1, report.totalSkipped);
        assertEquals(0, report.totalSuccess);
        assertEquals(1, schematics.all.size);
        assertArrayEquals(originalBytes, existing.file.readBytes());
    }

    @Test
    public void testNativeSchematicOverwriteReplacesCustomSchematic() throws IOException{
        FakeGame.FakeSchematics schematics = installSchematics();
        Schematic existing = existingSchematic(schematics, "Reactor Core", true, false);
        Fi oldFile = existing.file;

        PackEntry entry = scanSingle("reactor.msch", TestHelper.createValidSchematicBytes("Reactor Core"));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.OVERWRITE, null);

        assertEquals(1, report.totalOverwritten);
        assertEquals(0, report.totalFailed);
        assertFalse(oldFile.exists(), "The old custom schematic file must be removed");
        assertEquals(1, schematics.all.size);
        assertTrue(schematics.all.first().file.exists());
        assertTrue(PackImporter.isWithinDirectory(schemDir, schematics.all.first().file));
    }

    @Test
    public void testNativeSchematicOverwriteRemovalFailureRollsBackAndKeepsOldSchematic() throws IOException{
        FakeGame.FakeSchematics schematics = installSchematics();
        Schematic existing = existingSchematic(schematics, "Reactor Core", true, false);
        Fi oldFile = existing.file;
        byte[] originalBytes = oldFile.readBytes();
        schematics.failNextRemove = true;

        PackEntry entry = scanSingle("reactor.msch", TestHelper.createValidSchematicBytes("Reactor Core"));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.OVERWRITE, null);

        assertEquals(1, report.totalFailed);
        assertEquals(0, report.totalOverwritten);
        assertEquals(0, report.totalSuccess);
        assertTrue(oldFile.exists(), "A failed removal must not claim to have overwritten the old schematic");
        assertArrayEquals(originalBytes, oldFile.readBytes());
        assertEquals(1, schematics.all.size);
        assertSame(existing, schematics.all.first());
        assertFalse(schemDir.child("Reactor Core_1.msch").exists(),
            "The newly imported schematic must be rolled back when the old file cannot be removed");
    }

    @Test
    public void testNativeSchematicOverwriteRefusesModSchematic() throws IOException{
        FakeGame.FakeSchematics schematics = installSchematics();
        Schematic existing = existingSchematic(schematics, "Mod Core", true, true);
        byte[] originalBytes = existing.file.readBytes();

        PackEntry entry = scanSingle("reactor.msch", TestHelper.createValidSchematicBytes("Mod Core"));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.OVERWRITE, null);

        assertEquals(1, report.totalFailed);
        assertEquals(0, report.totalOverwritten);
        assertEquals(1, schematics.all.size);
        assertArrayEquals(originalBytes, existing.file.readBytes());
    }

    @Test
    public void testNativeSchematicOverwriteRefusesSchematicWithoutFile() throws IOException{
        FakeGame.FakeSchematics schematics = installSchematics();
        existingSchematic(schematics, "Lost Core", false, false);

        PackEntry entry = scanSingle("reactor.msch", TestHelper.createValidSchematicBytes("Lost Core"));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.OVERWRITE, null);

        assertEquals(1, report.totalFailed);
        assertEquals(0, report.totalOverwritten);
        assertEquals(1, schematics.all.size);
    }

    @Test
    public void testNativeSchematicRenameKeepsBothSchematics() throws IOException{
        FakeGame.FakeSchematics schematics = installSchematics();
        Schematic existing = existingSchematic(schematics, "Reactor Core", true, false);
        byte[] originalBytes = existing.file.readBytes();

        PackEntry entry = scanSingle("reactor.msch", TestHelper.createValidSchematicBytes("Reactor Core"));
        ImportReport report = importer.importEntries(seqOf(entry), DuplicatePolicy.RENAME, null);

        assertEquals(1, report.totalSuccess);
        assertEquals(0, report.totalFailed);
        assertArrayEquals(originalBytes, existing.file.readBytes());
        assertEquals(2, schematics.all.size);
        assertTrue(schematics.all.contains(s -> s.name().equals("Reactor Core 1")));
        assertTrue(schemDir.child("Reactor Core 1.msch").exists());
    }

    // ---------------------------------------------------------------------
    // Progress and cancellation
    // ---------------------------------------------------------------------

    @Test
    public void testImportProgressCountersAreFreshOnRetry() throws IOException{
        File inputDir = new File(tempDir, "progress");
        inputDir.mkdirs();
        Fi map = new Fi(new File(inputDir, "progress_map.msav"));
        Fi schem = new Fi(new File(inputDir, "progress_schem.msch"));
        map.writeBytes(TestHelper.createValidMapBytes("Progress Map"));
        schem.writeBytes(TestHelper.createValidSchematicBytes("Progress Schem"));

        Seq<PackEntry> entries = scanner.scanFiles(map, schem);
        assertEquals(2, entries.size);

        List<PackImporter.ImportProgress> firstRun = new ArrayList<>();
        ImportReport first = importer.importEntries(entries, DuplicatePolicy.SKIP,
            firstRun::add);

        assertEquals(2, first.totalSuccess);
        assertProgress(firstRun, 2, 2, 2, 0, 0);

        List<PackImporter.ImportProgress> secondRun = new ArrayList<>();
        ImportReport second = importer.importEntries(entries, DuplicatePolicy.SKIP,
            secondRun::add);

        assertEquals(2, second.totalSkipped);
        assertProgress(secondRun, 2, 2, 0, 0, 2);
    }

    @Test
    public void testCancellationStopsAfterCurrentEntry() throws IOException{
        File inputDir = new File(tempDir, "cancel");
        inputDir.mkdirs();
        Fi first = new Fi(new File(inputDir, "cancel_one.msav"));
        Fi second = new Fi(new File(inputDir, "cancel_two.msav"));
        first.writeBytes(TestHelper.createValidMapBytes("Cancel One"));
        second.writeBytes(TestHelper.createValidMapBytes("Cancel Two"));

        Seq<PackEntry> entries = scanner.scanFiles(first, second);
        assertEquals(2, entries.size);

        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        ImportReport report = importer.importEntries(entries, DuplicatePolicy.SKIP,
            new PackImporter.ImportListener(){
                @Override
                public void onProgress(PackImporter.ImportProgress progress){
                    if(progress.processed == 0){
                        cancel.set(true);
                    }
                }

                @Override
                public boolean isCancelled(){
                    return cancel.get();
                }
            });

        assertTrue(report.cancelled);
        assertEquals(2, report.totalSelected);
        assertEquals(1, report.processed());
        assertTrue(mapDir.child("cancel_one.msav").exists());
        assertFalse(mapDir.child("cancel_two.msav").exists());
    }

    @Test
    public void testImportEntriesWithPerTypePolicies() throws IOException{
        byte[] originalMap = TestHelper.createValidMapBytes("OriginalMap");
        byte[] newMap = TestHelper.createValidMapBytes("NewMap");
        byte[] originalSchem = TestHelper.createValidSchematicBytes("OriginalSchem");
        byte[] newSchem = TestHelper.createValidSchematicBytes("NewSchem");

        Fi targetMap = mapDir.child("per_type_map.msav");
        targetMap.writeBytes(originalMap);

        Fi targetSchem = schemDir.child("per_type_schem.msch");
        targetSchem.writeBytes(originalSchem);

        File inputDir = new File(tempDir, "incoming_per_type");
        inputDir.mkdirs();
        Fi incomingMap = new Fi(new File(inputDir, "per_type_map.msav"));
        incomingMap.writeBytes(newMap);
        Fi incomingSchem = new Fi(new File(inputDir, "per_type_schem.msch"));
        incomingSchem.writeBytes(newSchem);

        Seq<PackEntry> entries = scanner.scanFiles(incomingMap, incomingSchem);
        assertEquals(2, entries.size);
        for(PackEntry e : entries){
            e.selected = true;
            assertTrue(e.isDuplicate, e.sanitizedFileName + " must be flagged as duplicate");
        }

        // Apply OVERWRITE for Maps and SKIP for Schematics
        ImportReport report = importer.importEntries(entries, DuplicatePolicy.OVERWRITE, DuplicatePolicy.SKIP, null);

        assertEquals(2, report.totalSelected);
        assertEquals(1, report.totalOverwritten, "Map must be overwritten");
        assertEquals(1, report.totalSkipped, "Schematic duplicate must be skipped");
        assertEquals(0, report.totalFailed);

        assertArrayEquals(newMap, targetMap.readBytes(), "Map file must contain overwritten bytes");
        assertArrayEquals(originalSchem, targetSchem.readBytes(), "Schematic file must remain unchanged");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private FakeGame.FakeMaps installMaps(){
        FakeGame.FakeMaps maps = new FakeGame.FakeMaps(mapDir);
        Vars.maps = maps;
        Vars.customMapDirectory = mapDir;
        Vars.schematicDirectory = schemDir;
        return maps;
    }

    private FakeGame.FakeSchematics installSchematics(){
        FakeGame.FakeSchematics schematics = new FakeGame.FakeSchematics(schemDir);
        Vars.schematics = schematics;
        Vars.customMapDirectory = mapDir;
        Vars.schematicDirectory = schemDir;
        return schematics;
    }

    private PackEntry scanSingle(String fileName, byte[] bytes) throws IOException{
        File inputDir = new File(tempDir, "incoming_" + (inputCounter++));
        inputDir.mkdirs();
        Fi incoming = new Fi(new File(inputDir, fileName));
        incoming.writeBytes(bytes);

        Seq<PackEntry> entries = scanner.scanFiles(incoming);
        assertEquals(1, entries.size);
        return entries.first();
    }

    private Map existingMap(FakeGame.FakeMaps maps, String name, boolean custom) throws IOException{
        Fi file = mapDir.child(name + ".msav");
        file.writeBytes(TestHelper.createValidMapBytes(name));

        StringMap tags = new StringMap();
        tags.put("name", name);
        Map map = new Map(file, 4, 4, tags, custom);
        maps.all.add(map);
        return map;
    }

    private Schematic existingSchematic(FakeGame.FakeSchematics schematics, String name,
                                        boolean writeFile, boolean mod) throws IOException{
        StringMap tags = new StringMap();
        tags.put("name", name);
        Schematic schematic = new Schematic(new Seq<>(), tags, 2, 2);

        if(writeFile){
            Fi file = schemDir.child(name + ".msch");
            file.writeBytes(TestHelper.createValidSchematicBytes(name));
            schematic.file = file;
        }
        if(mod){
            Mods.ModMeta meta = new Mods.ModMeta();
            meta.name = "test mod";
            schematic.mod = new Mods.LoadedMod(schemDir, schemDir, null, null, meta);
        }

        schematics.all.add(schematic);
        return schematic;
    }

    private static Seq<PackEntry> seqOf(PackEntry... entries){
        return Seq.with(entries);
    }

    private static void assertProgress(List<PackImporter.ImportProgress> snapshots,
                                       int expectedTotal, int expectedProcessed,
                                       int expectedSuccess, int expectedFailed, int expectedSkipped){
        assertFalse(snapshots.isEmpty(), "Import must report progress");
        int lastProcessed = -1;
        for(PackImporter.ImportProgress snapshot : snapshots){
            assertEquals(expectedTotal, snapshot.total);
            assertTrue(snapshot.processed >= lastProcessed, "Progress counters must not go backwards");
            lastProcessed = snapshot.processed;
        }

        PackImporter.ImportProgress last = snapshots.get(snapshots.size() - 1);
        assertEquals(expectedProcessed, last.processed);
        assertEquals(expectedSuccess, last.success);
        assertEquals(expectedFailed, last.failed);
        assertEquals(expectedSkipped, last.skipped);
    }
}

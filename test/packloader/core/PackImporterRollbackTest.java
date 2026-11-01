package packloader.core;

import arc.files.Fi;
import arc.struct.Seq;
import mindustry.Vars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;
import packloader.model.DuplicatePolicy;
import packloader.model.ImportReport;
import packloader.model.PackEntry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class PackImporterRollbackTest{
    private File tempDir;
    private Fi mapDir;
    private Fi schemDir;
    private Fi originalCustomMapDirectory;
    private Fi originalSchematicDirectory;

    @BeforeEach
    public void setup() throws IOException{
        TestHelper.initBundle(Locale.ENGLISH);
        tempDir = Files.createTempDirectory("packloader_test_rollback").toFile();
        mapDir = new Fi(new File(tempDir, "maps"));
        schemDir = new Fi(new File(tempDir, "schematics"));
        mapDir.mkdirs();
        schemDir.mkdirs();

        originalCustomMapDirectory = Vars.customMapDirectory;
        originalSchematicDirectory = Vars.schematicDirectory;
        Vars.customMapDirectory = null;
        Vars.schematicDirectory = null;
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
    public void testFailedOverwriteRestoresTheOriginalFile() throws IOException{
        PackScanner scanner = new PackScanner(mapDir, schemDir);
        FailMoveImporter importer = new FailMoveImporter(mapDir, schemDir);

        byte[] originalBytes = TestHelper.createValidMapBytes("Original Map");
        Fi target = mapDir.child("replace_map.msav");
        target.writeBytes(originalBytes);

        File inputDir = new File(tempDir, "incoming");
        inputDir.mkdirs();
        Fi incoming = new Fi(new File(inputDir, "replace_map.msav"));
        incoming.writeBytes(TestHelper.createValidMapBytes("Replacement Map"));

        Seq<PackEntry> entries = scanner.scanFiles(incoming);
        assertEquals(1, entries.size);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.OVERWRITE, null);

        assertEquals(1, report.totalFailed);
        assertEquals(0, report.totalOverwritten);
        assertTrue(target.exists(), "The original file must still exist after rollback");
        assertArrayEquals(originalBytes, target.readBytes());
        assertNoStagingFiles(mapDir);
    }

    @Test
    public void testMoveRaceNeverOverwritesANewlyCreatedDestination() throws IOException{
        PackScanner scanner = new PackScanner(mapDir, schemDir);
        RaceImporter importer = new RaceImporter(mapDir, schemDir);

        File inputDir = new File(tempDir, "incoming");
        inputDir.mkdirs();
        Fi incoming = new Fi(new File(inputDir, "race_map.msav"));
        incoming.writeBytes(TestHelper.createValidMapBytes("Race Map"));

        Seq<PackEntry> entries = scanner.scanFiles(incoming);
        assertEquals(1, entries.size);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.RENAME, null);

        assertEquals(1, report.totalFailed);
        assertEquals(0, report.totalSuccess);

        Fi target = mapDir.child("race_map.msav");
        assertTrue(target.exists(), "The racing file must be preserved");
        assertEquals("sentinel", target.readString());
        assertNoStagingFiles(mapDir);
    }

    @Test
    public void testOverwriteRacePreservesRacingFileAndOriginalBackup() throws IOException{
        PackScanner scanner = new PackScanner(mapDir, schemDir);
        RaceImporter importer = new RaceImporter(mapDir, schemDir);

        byte[] originalBytes = TestHelper.createValidMapBytes("Original Map");
        Fi target = mapDir.child("replace_map.msav");
        target.writeBytes(originalBytes);

        File inputDir = new File(tempDir, "incoming_race_overwrite");
        inputDir.mkdirs();
        Fi incoming = new Fi(new File(inputDir, "replace_map.msav"));
        incoming.writeBytes(TestHelper.createValidMapBytes("Replacement Map"));

        Seq<PackEntry> entries = scanner.scanFiles(incoming);
        assertEquals(1, entries.size);

        ImportReport report = importer.importEntries(entries, DuplicatePolicy.OVERWRITE, null);

        assertEquals(1, report.totalFailed);
        assertEquals(0, report.totalOverwritten);
        assertTrue(target.exists(), "A file that raced into the destination must be preserved");
        assertEquals("sentinel", target.readString());

        Fi backup = findBackup(mapDir);
        assertNotNull(backup, "The original file must be kept for recovery");
        assertArrayEquals(originalBytes, backup.readBytes());
    }

    private static void assertNoStagingFiles(Fi directory){
        File[] files = directory.file().listFiles((dir, name) ->
            name.endsWith(".packload_tmp") || name.endsWith(".packload_backup"));
        assertNotNull(files);
        assertEquals(0, files.length, "Staging and backup files must be cleaned up");
    }

    private static Fi findBackup(Fi directory){
        File[] files = directory.file().listFiles((dir, name) -> name.endsWith(".packload_backup"));
        assertNotNull(files);
        assertEquals(1, files.length, "Exactly one original backup is expected");
        return new Fi(files[0]);
    }

    /** Forces the staged-to-target move to fail after the old file has been backed up. */
    private static class FailMoveImporter extends PackImporter{
        FailMoveImporter(Fi mapDirectory, Fi schematicDirectory){
            super(mapDirectory, schematicDirectory);
        }

        @Override
        void moveStagedToTarget(Fi staged, Fi target) throws IOException{
            throw new IOException("simulated move failure");
        }
    }

    /** Creates the destination immediately before the real non-replacing move. */
    private static class RaceImporter extends PackImporter{
        RaceImporter(Fi mapDirectory, Fi schematicDirectory){
            super(mapDirectory, schematicDirectory);
        }

        @Override
        void moveStagedToTarget(Fi staged, Fi target) throws IOException{
            target.writeString("sentinel");
            super.moveStagedToTarget(staged, target);
        }
    }
}

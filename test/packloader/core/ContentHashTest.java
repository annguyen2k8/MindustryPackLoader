package packloader.core;

import arc.files.Fi;
import arc.struct.Seq;
import mindustry.Vars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;
import packloader.model.*;
import packloader.util.PackLoaderSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class ContentHashTest{
    private File tempDir;
    private Fi mapDir;
    private Fi schemDir;
    private PackScanner scanner;
    private Fi originalCustomMapDirectory;
    private Fi originalSchematicDirectory;

    @BeforeEach
    public void setup() throws IOException{
        TestHelper.initBundle(Locale.ENGLISH);
        tempDir = Files.createTempDirectory("packloader_test_hash").toFile();
        mapDir = new Fi(new File(tempDir, "maps"));
        schemDir = new Fi(new File(tempDir, "schematics"));
        mapDir.mkdirs();
        schemDir.mkdirs();

        originalCustomMapDirectory = Vars.customMapDirectory;
        originalSchematicDirectory = Vars.schematicDirectory;
        Vars.customMapDirectory = mapDir;
        Vars.schematicDirectory = schemDir;

        scanner = new PackScanner(mapDir, schemDir);
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
    public void testContentHashComputation() throws Exception{
        byte[] data = "Hello Mindustry Content Hash!".getBytes();
        String computed = ContentHash.computeSha256(data);
        assertNotNull(computed);
        assertEquals(64, computed.length());

        // Compare against standard MessageDigest
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for(byte b : digest){
            sb.append(String.format("%02x", b));
        }
        assertEquals(sb.toString(), computed);
    }

    @Test
    public void testIdenticalContentDifferentNames() throws IOException{
        byte[] mapBytes = TestHelper.createValidMapBytes("Common Map");

        // Existing file in library
        Fi existing = mapDir.child("original.msav");
        existing.writeBytes(mapBytes);

        // Incoming file with completely different name
        Fi incomingDir = new Fi(new File(tempDir, "incoming"));
        incomingDir.mkdirs();
        Fi incoming = incomingDir.child("unique_name_same_data.msav");
        incoming.writeBytes(mapBytes);

        Seq<PackEntry> entries = scanner.scanDirectory(incomingDir, PackType.MAP);
        assertEquals(1, entries.size);

        PackEntry entry = entries.first();
        assertFalse(entry.isDuplicate, "Filename does not collide, so name duplicate must be false");
        assertTrue(entry.isContentDuplicate, "Content is identical to library file, so content duplicate must be true");
        assertEquals("original.msav", entry.contentDuplicateMatch);
    }

    @Test
    public void testDifferentContentIdenticalNames() throws IOException{
        byte[] mapBytesA = TestHelper.createValidMapBytes("Map Alpha");
        byte[] mapBytesB = TestHelper.createValidMapBytes("Map Beta");

        // Existing file in library named sector.msav
        Fi existing = mapDir.child("sector.msav");
        existing.writeBytes(mapBytesA);

        // Incoming file named sector.msav with different content
        Fi incomingDir = new Fi(new File(tempDir, "incoming2"));
        incomingDir.mkdirs();
        Fi incoming = incomingDir.child("sector.msav");
        incoming.writeBytes(mapBytesB);

        Seq<PackEntry> entries = scanner.scanDirectory(incomingDir, PackType.MAP);
        assertEquals(1, entries.size);

        PackEntry entry = entries.first();
        assertTrue(entry.isDuplicate, "Name collides with existing file, so name duplicate must be true");
        assertFalse(entry.isContentDuplicate, "Content is different, so content duplicate must be false");
    }

    @Test
    public void testIntraBatchContentDuplicate() throws IOException{
        byte[] schemBytes = TestHelper.createValidSchematicBytes("Batch Item");

        Fi incomingDir = new Fi(new File(tempDir, "batch_in"));
        incomingDir.mkdirs();
        Fi file1 = incomingDir.child("item1.msch");
        Fi file2 = incomingDir.child("item2.msch");
        file1.writeBytes(schemBytes);
        file2.writeBytes(schemBytes);

        Seq<PackEntry> entries = scanner.scanDirectory(incomingDir, PackType.SCHEMATIC);
        assertEquals(2, entries.size);

        PackEntry first = entries.get(0);
        PackEntry second = entries.get(1);

        assertFalse(first.isContentDuplicate, "First item in batch should not be flagged duplicate");
        assertTrue(second.isContentDuplicate, "Second item with identical content must be flagged duplicate");
        assertTrue(second.isIntraBatchContentDuplicate);
        assertEquals(first.sanitizedFileName, second.contentDuplicateMatch);
    }

    @Test
    public void testMultiSourceQueueDuplicates() throws IOException{
        byte[] mapBytes = TestHelper.createValidMapBytes("Multi Queue Map");

        Fi dir1 = new Fi(new File(tempDir, "source1"));
        Fi dir2 = new Fi(new File(tempDir, "source2"));
        dir1.mkdirs();
        dir2.mkdirs();

        Fi file1 = dir1.child("alpha.msav");
        Fi file2 = dir2.child("beta.msav");
        file1.writeBytes(mapBytes);
        file2.writeBytes(mapBytes);

        Seq<PackEntry> entries = scanner.scanFiles(PackType.MAP, dir1, dir2);
        assertEquals(2, entries.size);

        PackEntry first = entries.get(0);
        PackEntry second = entries.get(1);

        assertFalse(first.isContentDuplicate);
        assertTrue(second.isContentDuplicate);
        assertEquals(first.sanitizedFileName, second.contentDuplicateMatch);
    }

    @Test
    public void testTypeIsolation() throws IOException{
        // Even if bytes are identical, Maps and Schematics must never match each other
        byte[] commonBytes = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};

        Fi mapFile = mapDir.child("test.msav");
        mapFile.writeBytes(commonBytes);

        ContentHashIndex index = new ContentHashIndex(mapDir, schemDir);
        index.refresh();

        // Querying for Schematic type with identical bytes must return null
        Fi schemQuery = new Fi(new File(tempDir, "query.msch"));
        schemQuery.writeBytes(commonBytes);

        ContentHashIndex.FileRecord match = index.findMatch(schemQuery, PackType.SCHEMATIC);
        assertNull(match, "Maps and Schematics must have isolated hash spaces and never match");
    }

    @Test
    public void testMetadataCachingAndStaleCachePrevention() throws IOException{
        ContentHashIndex index = new ContentHashIndex(mapDir, schemDir);

        byte[] bytesA = TestHelper.createValidMapBytes("Version A");
        byte[] bytesB = TestHelper.createValidMapBytes("Version B");

        Fi target = mapDir.child("evolving.msav");
        target.writeBytes(bytesA);
        index.refresh();

        Fi queryA = new Fi(new File(tempDir, "queryA.msav"));
        queryA.writeBytes(bytesA);
        Fi queryB = new Fi(new File(tempDir, "queryB.msav"));
        queryB.writeBytes(bytesB);

        ContentHashIndex.FileRecord matchA = index.findMatch(queryA, PackType.MAP);
        assertNotNull(matchA);
        assertEquals("evolving.msav", matchA.name);
        assertNull(index.findMatch(queryB, PackType.MAP));

        // Now modify evolving.msav to have bytesB
        target.writeBytes(bytesB);
        target.file().setLastModified(System.currentTimeMillis() + 2000L);
        index.refresh();

        // findMatch dynamically invalidates stale cache when size/lastModified change
        ContentHashIndex.FileRecord matchB = index.findMatch(queryB, PackType.MAP);
        assertNotNull(matchB, "Cache must update to fresh content B");
        assertEquals("evolving.msav", matchB.name);
        assertNull(index.findMatch(queryA, PackType.MAP), "Old content A must no longer match evolving.msav");
    }

    @Test
    public void testZeroDiskSideEffectsDuringScan() throws IOException{
        byte[] mapBytes = TestHelper.createValidMapBytes("Safe Map");
        Fi existing = mapDir.child("safe.msav");
        existing.writeBytes(mapBytes);

        int mapDirCountBefore = mapDir.list().length;
        int schemDirCountBefore = schemDir.list().length;

        Fi incomingDir = new Fi(new File(tempDir, "safe_incoming"));
        incomingDir.mkdirs();
        Fi incoming = incomingDir.child("duplicate_safe.msav");
        incoming.writeBytes(mapBytes);

        Seq<PackEntry> entries = scanner.scanDirectory(incomingDir, PackType.MAP);
        assertEquals(1, entries.size);
        assertTrue(entries.first().isContentDuplicate);

        // Verify library was not touched
        assertEquals(mapDirCountBefore, mapDir.list().length);
        assertEquals(schemDirCountBefore, schemDir.list().length);
        assertTrue(existing.exists());
        assertEquals(mapBytes.length, existing.length());
    }

    @Test
    public void testEmptyAndMalformedFiles() throws IOException{
        Fi incomingDir = new Fi(new File(tempDir, "empty_malformed"));
        incomingDir.mkdirs();

        Fi emptyFile = incomingDir.child("empty.msch");
        emptyFile.writeBytes(new byte[0]);

        Fi malformedFile = incomingDir.child("malformed.msav");
        malformedFile.writeBytes(new byte[]{0x42, 0x43, 0x44});

        assertDoesNotThrow(() -> {
            Seq<PackEntry> entries = scanner.scanDirectory(incomingDir, null);
            for(PackEntry entry : entries){
                assertNotNull(entry.getOrComputeSha256());
            }
        });
    }

    @Test
    public void testQuickImportWithSkipIdenticalContent() throws IOException{
        byte[] mapBytes1 = TestHelper.createValidMapBytes("Map 1");
        byte[] mapBytes2 = TestHelper.createValidMapBytes("Map 2");

        // map1 is already in the library
        mapDir.child("map1.msav").writeBytes(mapBytes1);

        Fi incomingDir = new Fi(new File(tempDir, "quick_in"));
        incomingDir.mkdirs();
        Fi file1 = incomingDir.child("dup_content.msav");
        Fi file2 = incomingDir.child("unique_content.msav");
        file1.writeBytes(mapBytes1);
        file2.writeBytes(mapBytes2);

        Seq<PackEntry> entries = scanner.scanDirectory(incomingDir, PackType.MAP);
        assertEquals(2, entries.size);

        // Test with skipIdenticalContent = false
        int eligibleWithoutSkip = EntryFilter.selectEligibleForQuickImport(entries, PackType.MAP,
            DuplicatePolicy.SKIP, DuplicatePolicy.SKIP, false);
        assertEquals(2, eligibleWithoutSkip, "Both have unique filenames, so without skipIdenticalContent both are eligible");

        // Test with skipIdenticalContent = true
        int eligibleWithSkip = EntryFilter.selectEligibleForQuickImport(entries, PackType.MAP,
            DuplicatePolicy.SKIP, DuplicatePolicy.SKIP, true);
        assertEquals(1, eligibleWithSkip, "With skipIdenticalContent=true, content duplicate must be excluded");
    }

    @Test
    public void testDryRunReflectsContentDuplicates() throws IOException{
        byte[] mapBytes = TestHelper.createValidMapBytes("DryRun Map");
        mapDir.child("existing.msav").writeBytes(mapBytes);

        Fi incomingDir = new Fi(new File(tempDir, "dry_in"));
        incomingDir.mkdirs();
        Fi incoming = incomingDir.child("different_name.msav");
        incoming.writeBytes(mapBytes);

        Seq<PackEntry> entries = scanner.scanDirectory(incomingDir, PackType.MAP);
        assertEquals(1, entries.size);
        PackEntry entry = entries.first();
        entry.selected = true;

        // When skipIdenticalContent = false:
        DryRunPlan planNoSkip = DryRunPlan.calculate(entries, PackType.MAP, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP, false);
        assertEquals(1, planNoSkip.contentDuplicateCount);
        assertEquals(DryRunAction.NEW, planNoSkip.items.first().action);
        assertTrue(planNoSkip.items.first().isContentDuplicate);

        // When skipIdenticalContent = true:
        DryRunPlan planWithSkip = DryRunPlan.calculate(entries, PackType.MAP, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP, true);
        assertEquals(1, planWithSkip.contentDuplicateCount);
        assertEquals(DryRunAction.SKIP, planWithSkip.items.first().action);
    }
}

package packloader;

import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.game.Schematic;
import mindustry.world.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.core.PackFormat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class PackFormatTest{

    @BeforeEach
    public void setup(){
        TestHelper.initBundle(Locale.ENGLISH);
    }

    @Test
    public void testValidMapMetadataIsRead() throws IOException{
        PackFormat.MapInfo info = PackFormat.validateMap(new ByteArrayInputStream(
            TestHelper.createValidMapBytes("Canyon Siege", 13, 128, 96)));

        assertEquals("Canyon Siege", info.name);
        assertEquals(128, info.width);
        assertEquals(96, info.height);
        assertEquals(13, info.version);
        assertTrue(info.hasNameTag);
    }

    @Test
    public void testMapWithOnlyMapNameMetadataIsAccepted() throws IOException{
        PackFormat.MapInfo info = PackFormat.validateMap(new ByteArrayInputStream(
            TestHelper.createMapWithMapNameOnlyBytes("Exported Campaign Save")));

        assertEquals("Exported Campaign Save", info.name);
        assertEquals(4, info.width);
        assertEquals(4, info.height);
        assertEquals(13, info.version);
        assertFalse(info.hasNameTag);
    }

    @Test
    public void testLegacyBlockFallbackResolution() throws IOException{
        ContentLoader prevContent = Vars.content;
        try{
            Vars.content = new ContentLoader();
            new Block("steam-generator"){
                {
                    update = true;
                    destructible = true;
                }
            };

            PackFormat.MapInfo info = PackFormat.validateMap(new ByteArrayInputStream(
                TestHelper.createLegacyMapWithFallbackBlock("Legacy Power", "turbine-generator")));

            assertEquals("Legacy Power", info.name);
            assertEquals(3, info.version);
        }finally{
            Vars.content = prevContent;
        }
    }

    @Test
    public void testLegacyAndV7MapsUseTheirRealTrailingChunkCounts() throws IOException{
        // v4-v6 maps end after the entities chunk (one trailing chunk in this fixture).
        assertDoesNotThrow(() -> PackFormat.validateMap(new ByteArrayInputStream(
            TestHelper.createMapBytes("Legacy", 4, 8, 8, new byte[]{0}, 1))));

        // v7 adds custom chunks but still has no markers chunk.
        assertDoesNotThrow(() -> PackFormat.validateMap(new ByteArrayInputStream(
            TestHelper.createMapBytes("V7", 7, 8, 8, new byte[]{0}, 2))));
    }

    @Test
    public void testTruncatedMapIsRejected() throws IOException{
        byte[] truncated = TestHelper.createTruncatedMapBytes("Truncated");

        IOException error = assertThrows(IOException.class,
            () -> PackFormat.validateMap(new ByteArrayInputStream(truncated)));
        assertTrue(error.getMessage().toLowerCase().contains("truncated"), error.getMessage());
    }

    @Test
    public void testV7MapMissingCustomChunkIsRejected() throws IOException{
        byte[] truncated = TestHelper.createMapBytes("V7 Truncated", 7, 8, 8, new byte[]{0}, 1);

        assertThrows(IOException.class,
            () -> PackFormat.validateMap(new ByteArrayInputStream(truncated)));
    }

    @Test
    public void testMalformedContentHeaderIsRejected() throws IOException{
        byte[] malformed = TestHelper.createMalformedMapContentBytes("Bad Content");

        assertThrows(IOException.class,
            () -> PackFormat.validateMap(new ByteArrayInputStream(malformed)));
    }

    @Test
    public void testMapHeaderAndDimensionsAreValidated() throws IOException{
        assertThrows(IOException.class,
            () -> PackFormat.validateMap(new ByteArrayInputStream(new byte[]{'N', 'O', 'P', 'E'})));

        byte[] badDimensions = TestHelper.createMapBytes("Bad Size", 13, 0, 8, new byte[]{0}, 3);
        assertThrows(IOException.class,
            () -> PackFormat.validateMap(new ByteArrayInputStream(badDimensions)));
    }

    @Test
    public void testDenseTeamPlansAndVec2ConfigsAreAccepted() throws IOException{
        assertDoesNotThrow(() -> PackFormat.validateMap(new ByteArrayInputStream(
            TestHelper.createMapWithDenseTeamPlans("Dense Plans", 10))));

        assertDoesNotThrow(() -> PackFormat.validateMap(new ByteArrayInputStream(
            TestHelper.createMapWithVec2TeamPlan("Vec2 Config"))));
    }

    @Test
    public void testMapWithExtraTrailingChunkIsRejected() throws IOException{
        byte[] map = TestHelper.createMapWithExtraTrailingChunk("Extra Chunk");

        IOException error = assertThrows(IOException.class,
            () -> PackFormat.validateMap(new ByteArrayInputStream(map)));
        assertTrue(error.getMessage().toLowerCase().contains("trailing"), error.getMessage());
    }

    @Test
    public void testLegacyAndModernTileEntityLayoutsAreAccepted() throws IOException{
        for(int version = 1; version <= 3; version++){
            byte[] map = TestHelper.createValidMapBytes("Legacy " + version, version, 4, 4);
            assertDoesNotThrow(() -> PackFormat.validateMap(new ByteArrayInputStream(map)),
                "Legacy map version " + version + " must be accepted");
        }

        for(int version : new int[]{4, 7, 9, 10, 13}){
            byte[] map = TestHelper.createMapWithTileDataAndEntities("Layout " + version, version);
            assertDoesNotThrow(() -> PackFormat.validateMap(new ByteArrayInputStream(map)),
                "Map version " + version + " tile/entity layout must be accepted");
        }
    }

    @Test
    public void testTruncatedTileAndEntityRegionsAreRejected() throws IOException{
        assertThrows(IOException.class, () -> PackFormat.validateMap(
            new ByteArrayInputStream(TestHelper.createMapWithTruncatedTiles("Truncated Tiles"))));
        assertThrows(IOException.class, () -> PackFormat.validateMap(
            new ByteArrayInputStream(TestHelper.createMapWithTruncatedEntities("Truncated Entities"))));
    }

    @Test
    public void testRenameMapChangesOnlyTheMetadataName() throws IOException{
        byte[] original = TestHelper.createValidMapBytes("Old Name", 13, 32, 16);
        ByteArrayOutputStream renamed = new ByteArrayOutputStream();

        PackFormat.renameMap(new ByteArrayInputStream(original), renamed, "New Name");

        PackFormat.MapInfo info = PackFormat.validateMap(
            new ByteArrayInputStream(renamed.toByteArray()));
        assertEquals("New Name", info.name);
        assertEquals(32, info.width);
        assertEquals(16, info.height);
        assertEquals(13, info.version);
    }

    @Test
    public void testMapWithTrailingCompressedDataIsRejected() throws IOException{
        IOException error = assertThrows(IOException.class, () -> PackFormat.validateMap(
            new ByteArrayInputStream(TestHelper.createMapWithTrailingCompressedData("Junk Map"))));
        assertTrue(error.getMessage().toLowerCase().contains("compressed"),
            error.getMessage());
    }

    @Test
    public void testValidSchematicIsRead() throws IOException{
        Schematic schematic = PackFormat.readSchematic(new ByteArrayInputStream(
            TestHelper.createValidSchematicBytes("Reactor Core")));

        assertEquals("Reactor Core", schematic.name());
        assertNull(schematic.file, "Reading through an InputStream must not attach a file");
    }

    @Test
    public void testTruncatedSchematicIsRejected(){
        assertThrows(IOException.class, () -> PackFormat.readSchematic(
            new ByteArrayInputStream(TestHelper.createTruncatedSchematicBytes())));
    }

    @Test
    public void testSchematicTrailingDataAndInvalidBlockIndexAreRejected() throws IOException{
        IOException trailing = assertThrows(IOException.class, () -> PackFormat.readSchematic(
            new ByteArrayInputStream(TestHelper.createSchematicWithTrailingData("Trailing"))));
        assertTrue(trailing.getMessage().toLowerCase().contains("trailing"), trailing.getMessage());

        IOException block = assertThrows(IOException.class, () -> PackFormat.readSchematic(
            new ByteArrayInputStream(TestHelper.createSchematicWithInvalidBlockIndex("Bad Block"))));
        assertTrue(block.getMessage().toLowerCase().contains("block"), block.getMessage());
    }

    @Test
    public void testSchematicTrailingCompressedDataIsRejected() throws IOException{
        IOException error = assertThrows(IOException.class, () -> PackFormat.readSchematic(
            new ByteArrayInputStream(TestHelper.createSchematicWithTrailingCompressedData("Junk"))));
        assertTrue(error.getMessage().toLowerCase().contains("compressed"),
            error.getMessage());
    }

    @Test
    public void testSchematicSignedBlockIndexIsRejected() throws IOException{
        byte[] schematic = TestHelper.createSchematicWithTile("Signed Index", 129, 0x80, new byte[]{0});

        IOException error = assertThrows(IOException.class, () -> PackFormat.readSchematic(
            new ByteArrayInputStream(schematic)));
        assertTrue(error.getMessage().toLowerCase().contains("invalid block index"), error.getMessage());
    }

    @Test
    public void testSchematicNestedTypeIoArraysAreRejected() throws IOException{
        // object array -> point2 array; TypeIO forbids nested arrays.
        byte[] config = new byte[]{22, 0, 0, 0, 1, 8, 1, 0, 0, 0, 0};

        IOException error = assertThrows(IOException.class, () -> PackFormat.readSchematic(
            new ByteArrayInputStream(TestHelper.createSchematicWithConfig("Nested Arrays", config))));
        assertTrue(error.getMessage().contains("Nested TypeIO arrays"), error.getMessage());
    }

    @Test
    public void testSchematicTypeIoArrayCapsAreEnforced() throws IOException{
        // object array of 201 nulls; TypeIO caps non-safe arrays at 200.
        byte[] config = new byte[5 + 201];
        config[0] = 22;
        config[4] = (byte)201;

        IOException error = assertThrows(IOException.class, () -> PackFormat.readSchematic(
            new ByteArrayInputStream(TestHelper.createSchematicWithConfig("Large Array", config))));
        assertTrue(error.getMessage().contains("TypeIO object array"), error.getMessage());
    }

    @Test
    public void testSchematicHeaderIsRequired(){
        assertThrows(IOException.class, () -> PackFormat.readSchematic(
            new ByteArrayInputStream(new byte[]{'n', 'o', 'p', 'e', 1})));
    }
}

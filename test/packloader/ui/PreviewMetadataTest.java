package packloader.ui;

import arc.Core;
import arc.files.Fi;
import arc.mock.MockApplication;
import arc.mock.MockGL20;
import arc.mock.MockGraphics;
import arc.mock.MockSettings;
import arc.scene.Scene;
import mindustry.game.Schematic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;
import packloader.core.PackFormat;
import packloader.model.PackEntry;
import packloader.model.PackType;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class PreviewMetadataTest{
    private arc.Application prevApp;
    private arc.graphics.GL20 prevGl;
    private arc.graphics.GL20 prevGl20;
    private arc.Graphics prevGraphics;
    private arc.scene.Scene prevScene;
    private arc.Settings prevSettings;
    private File tempDir;

    @BeforeEach
    public void setup() throws Exception{
        prevApp = Core.app;
        prevGl = Core.gl;
        prevGl20 = Core.gl20;
        prevGraphics = Core.graphics;
        prevScene = Core.scene;
        prevSettings = Core.settings;

        Core.app = new MockApplication();
        Core.gl = Core.gl20 = new MockGL20();
        Core.graphics = new MockGraphics();
        Core.scene = new Scene();
        Core.settings = new MockSettings();

        tempDir = Files.createTempDirectory("packloader-preview-test").toFile();
    }

    @AfterEach
    public void teardown(){
        Core.app = prevApp;
        Core.gl = prevGl;
        Core.gl20 = prevGl20;
        Core.graphics = prevGraphics;
        Core.scene = prevScene;
        Core.settings = prevSettings;

        PreviewManager.dispose();
        if(tempDir != null && tempDir.exists()){
            for(File f : tempDir.listFiles()){
                f.delete();
            }
            tempDir.delete();
        }
    }

    @Test
    public void testMapMetadataExtraction() throws IOException{
        byte[] bytes = TestHelper.createMapWithMetadataBytes("Desert Outpost", "Alice", "A competitive 1v1 desert map.", 60, 80);
        PackFormat.MapInfo info = PackFormat.readMap(new ByteArrayInputStream(bytes));

        assertNotNull(info);
        assertEquals("Desert Outpost", info.name);
        assertEquals("Alice", info.author);
        assertEquals("A competitive 1v1 desert map.", info.description);
        assertEquals(60, info.width);
        assertEquals(80, info.height);
        assertEquals(13, info.version);
        assertTrue(info.hasNameTag);
        assertNotNull(info.tags);
        assertEquals("160", info.tags.get("build"));
    }

    @Test
    public void testMapMetadataDefaultEmptyFields() throws IOException{
        byte[] bytes = TestHelper.createValidMapBytes("SimpleMap", 13, 30, 40);
        PackFormat.MapInfo info = PackFormat.readMap(new ByteArrayInputStream(bytes));

        assertNotNull(info);
        assertEquals("SimpleMap", info.name);
        assertEquals("", info.author);
        assertEquals("", info.description);
        assertEquals(30, info.width);
        assertEquals(40, info.height);
    }

    @Test
    public void testSchematicMetadataExtraction() throws IOException{
        byte[] bytes = TestHelper.createSchematicWithMetadataBytes("Super Drill", "Produces titanium fast.", 12, 14);
        Schematic s = PackFormat.readSchematic(new ByteArrayInputStream(bytes));

        assertNotNull(s);
        assertEquals("Super Drill", s.name());
        assertEquals("Produces titanium fast.", s.description());
        assertEquals(12, s.width);
        assertEquals(14, s.height);
    }

    @Test
    public void testPackEntryDimensionsAndLazyMetadata() throws IOException{
        byte[] mapBytes = TestHelper.createMapWithMetadataBytes("Fortress", "Bob", "Defense mission", 100, 120);
        File mapFile = new File(tempDir, "fortress.msav");
        Files.write(mapFile.toPath(), mapBytes);

        PackEntry mapEntry = new PackEntry(PackType.MAP, mapFile.getAbsolutePath(), mapFile.getName(), mapBytes.length);
        mapEntry.sourceFile = new Fi(mapFile);

        assertEquals("100x120", mapEntry.getDimensionsString());
        assertNotNull(mapEntry.mapInfo);
        assertEquals("Bob", mapEntry.mapInfo.author);
        assertEquals("Defense mission", mapEntry.mapInfo.description);

        byte[] schemBytes = TestHelper.createSchematicWithMetadataBytes("Smelter Hub", "Power hungry", 8, 10);
        File schemFile = new File(tempDir, "smelter.msch");
        Files.write(schemFile.toPath(), schemBytes);

        PackEntry schemEntry = new PackEntry(PackType.SCHEMATIC, schemFile.getAbsolutePath(), schemFile.getName(), schemBytes.length);
        schemEntry.sourceFile = new Fi(schemFile);

        assertEquals("8x10", schemEntry.getDimensionsString());
        assertNotNull(schemEntry.schematic);
        assertEquals("Smelter Hub", schemEntry.schematic.name());
    }

    @Test
    public void testPreviewManagerKeyingAndSafeErrorHandling() throws IOException{
        PackEntry validEntry = new PackEntry(PackType.MAP, "C:/maps/test.msav", "test.msav", 500);
        validEntry.sha256 = "abc123def456";
        assertEquals("abc123def456", PreviewManager.entryKey(validEntry));

        PackEntry fallbackKeyEntry = new PackEntry(PackType.MAP, "C:/maps/test2.msav", "test2.msav", 500);
        assertEquals("C:/maps/test2.msav#test2.msav", PreviewManager.entryKey(fallbackKeyEntry));

        // Invalid or corrupt entry must safely return null without throwing exceptions
        PackEntry invalidEntry = new PackEntry(PackType.MAP, "C:/maps/corrupt.msav", "corrupt.msav", 100);
        invalidEntry.isValid = false;
        invalidEntry.cachedData = new byte[]{1, 2, 3, 4}; // Corrupt bytes

        AtomicBoolean callbackRan = new AtomicBoolean(false);
        PreviewManager.loadPreviewAsync(invalidEntry, tex -> {
            assertNull(tex, "Invalid entry preview must resolve to null");
            callbackRan.set(true);
        });
        assertTrue(callbackRan.get());

        // Null entry handling
        PreviewManager.loadPreviewAsync(null, tex -> assertNull(tex));
        assertFalse(PreviewManager.hasTexture(null));
        assertNull(PreviewManager.getCachedTexture(null));
    }

    @Test
    public void testNoSourceFileSideEffects() throws IOException{
        byte[] originalBytes = TestHelper.createMapWithMetadataBytes("UntouchedMap", "AuthorX", "DescX", 50, 50);
        File mapFile = new File(tempDir, "untouched.msav");
        Files.write(mapFile.toPath(), originalBytes);

        PackEntry entry = new PackEntry(PackType.MAP, mapFile.getAbsolutePath(), mapFile.getName(), originalBytes.length);
        entry.sourceFile = new Fi(mapFile);

        // Read dimensions & metadata
        String dims = entry.getDimensionsString();
        assertEquals("50x50", dims);

        // Verify source file bytes remain completely unchanged
        byte[] readBack = Files.readAllBytes(mapFile.toPath());
        assertArrayEquals(originalBytes, readBack, "Source map file must remain 100% byte-identical after metadata reading");
    }
}

package packloader.ui;

import arc.files.Fi;
import arc.struct.Seq;
import mindustry.Vars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;
import packloader.model.PackType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class FileDropHandlerTest{
    private File tempDir;
    private Fi mapDir;
    private Fi schemDir;
    private MockDropTarget mockDialog;
    private FileDropHandler handler;

    private static class MockDropTarget implements FileDropHandler.DropTarget{
        public PackType lastTargetType;
        public boolean lastAppend;
        public Seq<Fi> lastSources = new Seq<>();
        public boolean mockReviewMode = false;
        public PackType mockTargetType = null;

        @Override
        public void scanAsync(PackType targetType, boolean append, Fi... sources){
            this.lastTargetType = targetType;
            this.lastAppend = append;
            this.lastSources.clear();
            if(sources != null) this.lastSources.addAll(sources);
        }

        @Override
        public boolean isInReviewMode(){
            return mockReviewMode;
        }

        @Override
        public PackType getTargetType(){
            return mockTargetType;
        }
    }

    @BeforeEach
    public void setup() throws IOException{
        TestHelper.initBundle(Locale.ENGLISH);
        tempDir = Files.createTempDirectory("packloader_test_filedrop").toFile();
        mapDir = new Fi(new File(tempDir, "maps"));
        schemDir = new Fi(new File(tempDir, "schematics"));
        mapDir.mkdirs();
        schemDir.mkdirs();

        Vars.customMapDirectory = mapDir;
        Vars.schematicDirectory = schemDir;

        mockDialog = new MockDropTarget();
        handler = new FileDropHandler(mockDialog);
    }

    @AfterEach
    public void teardown(){
        if(tempDir != null && tempDir.exists()){
            new Fi(tempDir).deleteDirectory();
        }
    }

    @Test
    public void testIsSupportedExtensionsAndDirectories() throws IOException{
        Fi zip = new Fi(new File(tempDir, "test.zip"));
        zip.writeBytes(new byte[]{1, 2, 3});

        Fi map = new Fi(new File(tempDir, "test.msav"));
        map.writeBytes(new byte[]{1, 2, 3});

        Fi schem = new Fi(new File(tempDir, "test.msch"));
        schem.writeBytes(new byte[]{1, 2, 3});

        Fi dir = new Fi(new File(tempDir, "subfolder"));
        dir.mkdirs();

        Fi txt = new Fi(new File(tempDir, "readme.txt"));
        txt.writeString("hello");

        Fi nonExistent = new Fi(new File(tempDir, "missing.zip"));

        assertTrue(FileDropHandler.isSupported(zip), ".zip must be supported");
        assertTrue(FileDropHandler.isSupported(map), ".msav must be supported");
        assertTrue(FileDropHandler.isSupported(schem), ".msch must be supported");
        assertTrue(FileDropHandler.isSupported(dir), "Directories must be supported");

        assertFalse(FileDropHandler.isSupported(txt), ".txt must be rejected");
        assertFalse(FileDropHandler.isSupported(nonExistent), "Non-existent files must be rejected");
        assertFalse(FileDropHandler.isSupported(null), "Null must be rejected");
    }

    @Test
    public void testDropUnsupportedFilesAreIgnored(){
        Fi txt = new Fi(new File(tempDir, "ignored.png"));
        txt.writeString("fake image");

        handler.onFileDropped(txt);
        assertEquals(0, handler.getPendingDrops().size);
    }

    @Test
    public void testDropBatchingAndDeduplication(){
        Fi zip1 = new Fi(new File(tempDir, "pack1.zip"));
        Fi zip2 = new Fi(new File(tempDir, "pack2.zip"));
        zip1.writeBytes(new byte[]{1});
        zip2.writeBytes(new byte[]{2});

        // Drop zip1, zip2, and duplicate zip1 in the same drag operation
        handler.onFileDropped(zip1);
        handler.onFileDropped(zip2);
        handler.onFileDropped(zip1);

        assertEquals(2, handler.getPendingDrops().size, "Duplicate drop of zip1 must be suppressed");

        handler.flushPendingDrops();
        assertEquals(0, handler.getPendingDrops().size, "Flushed queue must be empty");

        assertEquals(2, mockDialog.lastSources.size);
        assertFalse(mockDialog.lastAppend);
        assertNull(mockDialog.lastTargetType, "ZIP archives should allow general pack type detection");
    }

    @Test
    public void testDropOnlyMapsDetectsMapType(){
        Fi map1 = new Fi(new File(tempDir, "m1.msav"));
        Fi map2 = new Fi(new File(tempDir, "m2.msav"));
        map1.writeBytes(new byte[]{1});
        map2.writeBytes(new byte[]{2});

        handler.onFileDropped(map1);
        handler.onFileDropped(map2);
        handler.flushPendingDrops();

        assertEquals(2, mockDialog.lastSources.size);
        assertEquals(PackType.MAP, mockDialog.lastTargetType, "Dropping only .msav files must detect MAP targetType");
    }

    @Test
    public void testDropOnlySchematicsDetectsSchematicType(){
        Fi s1 = new Fi(new File(tempDir, "s1.msch"));
        Fi s2 = new Fi(new File(tempDir, "s2.msch"));
        s1.writeBytes(new byte[]{1});
        s2.writeBytes(new byte[]{2});

        handler.onFileDropped(s1);
        handler.onFileDropped(s2);
        handler.flushPendingDrops();

        assertEquals(2, mockDialog.lastSources.size);
        assertEquals(PackType.SCHEMATIC, mockDialog.lastTargetType, "Dropping only .msch files must detect SCHEMATIC targetType");
    }

    @Test
    public void testDropWhileInReviewModeAppendsToQueue(){
        Fi zip = new Fi(new File(tempDir, "extra.zip"));
        zip.writeBytes(new byte[]{1});

        mockDialog.mockReviewMode = true;
        mockDialog.mockTargetType = PackType.MAP;

        handler.onFileDropped(zip);
        handler.flushPendingDrops();

        assertTrue(mockDialog.lastAppend, "Dropping a file while dialog is in review mode must append to the queue");
        assertEquals(PackType.MAP, mockDialog.lastTargetType);
    }
}

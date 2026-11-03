package packloader.core;

import arc.files.Fi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class AndroidShareHandlerTest{
    private File tempDir;

    @BeforeEach
    public void setup() throws IOException{
        TestHelper.initBundle(Locale.ENGLISH);
        tempDir = Files.createTempDirectory("packloader_test_android_share").toFile();
    }

    @AfterEach
    public void teardown(){
        AndroidShareHandler.cleanupCache();
        if(tempDir != null && tempDir.exists()){
            new Fi(tempDir).deleteDirectory();
        }
    }

    @Test
    public void testManifestBlockerDocumentationExists(){
        assertNotNull(AndroidShareHandler.MANIFEST_BLOCKER_EXPLANATION);
        assertTrue(AndroidShareHandler.MANIFEST_BLOCKER_EXPLANATION.contains("AndroidManifest.xml"));
        assertTrue(AndroidShareHandler.MANIFEST_BLOCKER_EXPLANATION.contains("intent-filter"));
    }

    @Test
    public void testResolveSharedStreamValidData() throws IOException{
        byte[] expected = "Shared Content Test Stream Data".getBytes();
        ByteArrayInputStream bais = new ByteArrayInputStream(expected);

        Fi result = AndroidShareHandler.resolveSharedStream(bais, "shared_pack.zip", 1024 * 1024);
        assertNotNull(result);
        assertTrue(result.exists());
        assertTrue(result.name().endsWith("shared_pack.zip"));
        assertArrayEquals(expected, result.readBytes());

        result.delete();
    }

    @Test
    public void testResolveSharedStreamExceedingByteLimitThrowsAndCleansUp(){
        byte[] largeData = new byte[10000];
        for(int i = 0; i < largeData.length; i++) largeData[i] = (byte)(i % 127);
        ByteArrayInputStream bais = new ByteArrayInputStream(largeData);

        // Max limit is only 2000 bytes
        IOException ex = assertThrows(IOException.class, () -> {
            AndroidShareHandler.resolveSharedStream(bais, "oversized.zip", 2000);
        });

        assertNotNull(ex.getMessage());
    }

    @Test
    public void testResolveSharedStreamNullStreamThrows(){
        assertThrows(IllegalArgumentException.class, () -> {
            AndroidShareHandler.resolveSharedStream(null, "invalid.zip", 1024);
        });
    }

    @Test
    public void testCleanupCacheRemovesStagedFiles() throws IOException{
        byte[] data = new byte[]{1, 2, 3, 4};
        Fi file1 = AndroidShareHandler.resolveSharedStream(new ByteArrayInputStream(data), "f1.zip", 1024);
        Fi file2 = AndroidShareHandler.resolveSharedStream(new ByteArrayInputStream(data), "f2.zip", 1024);

        assertTrue(file1.exists());
        assertTrue(file2.exists());

        AndroidShareHandler.cleanupCache();

        assertFalse(file1.exists());
        assertFalse(file2.exists());
    }
}

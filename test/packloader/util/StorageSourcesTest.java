package packloader.util;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

public class StorageSourcesTest{

    @Test
    public void testKeepsVolumeLabelAndDriveLetter(){
        assertEquals("Local Disk (C:)", StorageSources.formatDriveName("Local Disk (C:)", "C:\\", "Drive"));
        assertEquals("DU LIEU (D:)", StorageSources.formatDriveName("DU LIEU (D:)", "D:\\", "Drive"));
    }

    @Test
    public void testAddsDriveLetterToBareVolumeLabel(){
        assertEquals("Backup (G:)", StorageSources.formatDriveName("Backup", "G:\\", "Drive"));
    }

    @Test
    public void testUsesFallbackForMissingVolumeLabel(){
        assertEquals("Drive (E:)", StorageSources.formatDriveName(null, "E:\\", "Drive"));
        assertEquals("Drive (E:)", StorageSources.formatDriveName("   ", "E:\\", "Drive"));
        assertEquals("Drive (E:)", StorageSources.formatDriveName("E:\\", "E:\\", "Drive"));
        assertEquals("Drive (E:)", StorageSources.formatDriveName("E:", "E:\\", "Drive"));
        assertEquals("E:", StorageSources.formatDriveName(null, "E:\\", null));
        assertEquals("Ổ đĩa (E:)", StorageSources.formatDriveName(null, "E:\\", "Ổ đĩa"));
    }

    @Test
    public void testNormalizesDriveLetter(){
        assertEquals("Drive (C:)", StorageSources.formatDriveName(null, "c:\\", "Drive"));
    }

    @Test
    public void testLeavesNonDriveRootsUnformatted(){
        assertEquals("Storage", StorageSources.formatDriveName("Storage", "/", "Drive"));
        assertEquals("Drive", StorageSources.formatDriveName(null, "/", "Drive"));
    }

    @Test
    public void testDriveLetterExtraction(){
        assertEquals("C:", StorageSources.driveLetter("C:\\"));
        assertEquals("D:", StorageSources.driveLetter("d:/"));
        assertEquals("", StorageSources.driveLetter("C:relative"));
        assertEquals("", StorageSources.driveLetter("/sdcard"));
        assertEquals("", StorageSources.driveLetter("1:\\"));
        assertEquals("", StorageSources.driveLetter(null));
    }

    @Test
    public void testMultipleWindowsRootsAreSortedAndFormatted(){
        File c = fakeDrive("C:\\", true, true);
        File d = fakeDrive("D:\\", true, true);
        File e = fakeDrive("E:\\", true, true);

        var roots = StorageSources.collectWindowsRoots(new File[]{e, c, d}, root -> {
            String path = root.getAbsolutePath();
            if(path.equals("C:\\")) return "Local Disk (C:)";
            if(path.equals("D:\\")) return "";
            return "Data";
        }, "Drive");

        assertEquals(3, roots.size);
        assertEquals("C:\\", roots.get(0).path);
        assertEquals("Local Disk (C:)", roots.get(0).displayName);
        assertEquals("D:\\", roots.get(1).path);
        assertEquals("Drive (D:)", roots.get(1).displayName);
        assertEquals("E:\\", roots.get(2).path);
        assertEquals("Data (E:)", roots.get(2).displayName);
    }

    @Test
    public void testInaccessibleAndNonDriveRootsAreSkipped(){
        File accessible = fakeDrive("C:\\", true, true);
        File missing = fakeDrive("D:\\", false, true);
        File file = fakeDrive("E:\\", true, false);
        File unixRoot = fakeDrive("/", true, true);

        var roots = StorageSources.collectWindowsRoots(
            new File[]{null, missing, file, unixRoot, accessible}, root -> "Drive", "Drive");

        assertEquals(1, roots.size);
        assertEquals("C:\\", roots.get(0).path);
        assertEquals("Drive (C:)", roots.get(0).displayName);
    }

    @Test
    public void testLabelProviderFailureUsesFallback(){
        File c = fakeDrive("C:\\", true, true);

        var roots = StorageSources.collectWindowsRoots(new File[]{c}, root -> {
            throw new IllegalStateException("FileSystemView unavailable");
        }, "Drive");

        assertEquals(1, roots.size);
        assertEquals("Drive (C:)", roots.get(0).displayName);
    }

    @Test
    public void testEmptyRootListsAreSafe(){
        assertTrue(StorageSources.collectWindowsRoots(null, root -> "Drive", "Drive").isEmpty());
        assertTrue(StorageSources.collectWindowsRoots(new File[0], root -> "Drive", "Drive").isEmpty());
    }

    @Test
    public void testIsVirtualSafPath(){
        assertTrue(StorageSources.isVirtualSafPath("/document/primary:mindustry-data-export.zip"));
        assertTrue(StorageSources.isVirtualSafPath("/tree/primary:Download"));
        assertTrue(StorageSources.isVirtualSafPath("primary:mindustry-data-export.zip"));
        assertTrue(StorageSources.isVirtualSafPath("content://com.android.externalstorage.documents/document/primary%3Atest.zip"));
        assertTrue(StorageSources.isVirtualSafPath("/document/raw:/storage/emulated/0/Download/test.zip"));
        assertTrue(StorageSources.isVirtualSafPath("raw:/sdcard/pack.zip"));

        assertFalse(StorageSources.isVirtualSafPath("C:\\maps\\pack.zip"));
        assertFalse(StorageSources.isVirtualSafPath("/home/user/downloads/pack.zip"));
        assertFalse(StorageSources.isVirtualSafPath("/sdcard/Download/pack.zip"));
        assertFalse(StorageSources.isVirtualSafPath(null));
        assertFalse(StorageSources.isVirtualSafPath(""));
    }

    @Test
    public void testTranslateVirtualPath(){
        assertEquals("/storage/emulated/0/mindustry-data-export.zip",
            StorageSources.translateVirtualPath("/document/primary:mindustry-data-export.zip", "/storage/emulated/0"));

        assertEquals("/storage/emulated/0/Download/pack.zip",
            StorageSources.translateVirtualPath("/document/primary:Download/pack.zip", "/storage/emulated/0"));

        assertEquals("/sdcard/maps",
            StorageSources.translateVirtualPath("/tree/primary:maps", "/sdcard"));

        assertEquals("/storage/emulated/0/test.zip",
            StorageSources.translateVirtualPath("content://com.android.externalstorage.documents/document/primary%3Atest.zip", "/storage/emulated/0"));

        assertEquals("/storage/1234-5678/Download/maps.zip",
            StorageSources.translateVirtualPath("/document/1234-5678:Download/maps.zip", "/storage/emulated/0"));

        assertEquals("/storage/emulated/0/Download/foo.zip",
            StorageSources.translateVirtualPath("/document/raw:/storage/emulated/0/Download/foo.zip", "/storage/emulated/0"));

        assertEquals("C:\\maps\\pack.zip",
            StorageSources.translateVirtualPath("C:\\maps\\pack.zip", "/storage/emulated/0"));
    }

    @Test
    public void testSanitizeSourceName(){
        assertEquals("mindustry-data-export.zip",
            StorageSources.sanitizeSourceName("primary:mindustry-data-export.zip", "/document/primary:mindustry-data-export.zip"));

        assertEquals("pack.zip",
            StorageSources.sanitizeSourceName("primary:Download/pack.zip", "/document/primary:Download/pack.zip"));

        assertEquals("data.zip",
            StorageSources.sanitizeSourceName("primary%3Adata.zip", null));

        assertEquals("my_map.msav",
            StorageSources.sanitizeSourceName("my_map.msav", "/storage/emulated/0/my_map.msav"));

        assertEquals("pack.zip",
            StorageSources.sanitizeSourceName(null, "/document/primary:pack.zip"));

        assertEquals("unnamed",
            StorageSources.sanitizeSourceName(null, null));
    }

    @Test
    public void testCanReadDirectlyWithTempFile() throws Exception{
        File temp = File.createTempFile("test_read", ".tmp");
        temp.deleteOnExit();

        arc.files.Fi fi = new arc.files.Fi(temp);
        assertTrue(StorageSources.canReadDirectly(fi));
        assertTrue(StorageSources.canReadDirectly(temp.getAbsolutePath()));

        assertFalse(StorageSources.canReadDirectly((arc.files.Fi)null));
        assertFalse(StorageSources.canReadDirectly((String)null));
        assertFalse(StorageSources.canReadDirectly("/path/to/nonexistent/file.zip"));
    }

    private static File fakeDrive(String path, boolean exists, boolean directory){
        return new File(path){
            @Override
            public String getAbsolutePath(){
                return path;
            }

            @Override
            public boolean exists(){
                return exists;
            }

            @Override
            public boolean isDirectory(){
                return directory;
            }
        };
    }
}

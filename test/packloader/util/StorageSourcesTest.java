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

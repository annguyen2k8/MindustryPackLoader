package packloader;

import org.junit.jupiter.api.Test;
import packloader.ui.PackLoaderDialog;
import packloader.ui.UIUtils;

import static org.junit.jupiter.api.Assertions.*;

public class UIUtilsTest{

    @Test
    void testPadNullSafe(){
        assertNull(UIUtils.pad(null));
    }

    @Test
    void testTruncateFileName(){
        assertNull(PackLoaderDialog.truncateFileName(null, 20));
        assertEquals("short.msch", PackLoaderDialog.truncateFileName("short.msch", 20));

        // Long file name with extension
        String longName = "super_mega_ultra_complex_heavy_defense_v2_final.msch";
        String truncated = PackLoaderDialog.truncateFileName(longName, 25);
        assertTrue(truncated.length() <= 25);
        assertTrue(truncated.endsWith(".msch"));
        assertTrue(truncated.contains("..."));

        // Long file name without extension
        String longNoExt = "a_very_long_file_name_without_extension_here";
        String truncatedNoExt = PackLoaderDialog.truncateFileName(longNoExt, 20);
        assertTrue(truncatedNoExt.length() <= 20);
        assertTrue(truncatedNoExt.endsWith("..."));
    }
}

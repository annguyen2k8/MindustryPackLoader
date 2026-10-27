package packloader;

import arc.Core;
import arc.util.I18NBundle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.util.Localization;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class LocalizationTest{

    @BeforeEach
    public void setup(){
        TestHelper.initBundle(Locale.ENGLISH);
    }

    @AfterEach
    public void teardown(){
        Core.bundle = null;
    }

    @Test
    public void testEnglishBundle(){
        assertEquals("Scan Folder", Localization.getLocalizedText("packloader.source.scan-folder.schem"));
        assertEquals("Found: 5 maps, 2 schematics (7 files)", Localization.getLocalizedText("packloader.source.summary", 5, 2, 7));
        assertEquals("Close", Localization.getLocalizedText("packloader.action.close"));
    }

    @Test
    public void testVietnameseBundle(){
        TestHelper.initBundle(Locale.forLanguageTag("vi"));

        assertEquals("Quét thư mục", Localization.getLocalizedText("packloader.source.scan-folder.schem"));
        assertEquals("Tìm thấy: 10 bản đồ, 3 schematic (tổng cộng 13 tệp)", Localization.getLocalizedText("packloader.source.summary", 10, 3, 13));
        assertEquals("Đóng", Localization.getLocalizedText("packloader.action.close"));
    }

    @Test
    public void testLocaleMissingKeyFallsBackToEnglish(){
        I18NBundle bundle = I18NBundle.createEmptyBundle();
        bundle.getProperties().put("packloader.action.close", "Fermer");
        Core.bundle = bundle;

        // Current locale has priority over the English bundle.
        assertEquals("Fermer", Localization.getLocalizedText("packloader.action.close"));
        // Missing from the current locale, but present in English.
        assertEquals("Scan Folder", Localization.getLocalizedText("packloader.source.scan-folder.schem"));
    }

    @Test
    public void testMissingKeyEverywhereReturnsKey(){
        String key = "packloader.does.not.exist";

        assertEquals(key, Localization.getLocalizedText(key));
        assertEquals(key, Localization.getLocalizedText(key, "arg"));
    }

    @Test
    public void testNullKeyReturnsEmptyString(){
        assertEquals("", Localization.getLocalizedText(null));
        assertEquals("", Localization.getLocalizedText(null, "arg"));
    }

    @Test
    public void testNullBundleUsesEnglishFallback(){
        Core.bundle = null;

        assertEquals("Scan Folder", Localization.getLocalizedText("packloader.source.scan-folder.schem"));
        assertEquals("Found: 3 maps, 1 schematics (4 files)", Localization.getLocalizedText("packloader.source.summary", 3, 1, 4));
        assertEquals("packloader.does.not.exist", Localization.getLocalizedText("packloader.does.not.exist"));
    }

    @Test
    public void testBundleErrorFallsBackToEnglish(){
        Core.bundle = new I18NBundle(){
            @Override
            public boolean has(String key){
                throw new IllegalStateException("bundle unavailable");
            }
        };

        assertEquals("Scan Folder", Localization.getLocalizedText("packloader.source.scan-folder.schem"));
        assertEquals("Found: 5 maps, 2 schematics (7 files)", Localization.getLocalizedText("packloader.source.summary", 5, 2, 7));
        assertEquals("packloader.does.not.exist", Localization.getLocalizedText("packloader.does.not.exist"));
    }

    @Test
    public void testEmptyBundleUsesEnglishFallback(){
        Core.bundle = I18NBundle.createEmptyBundle();

        assertEquals("Scan Folder", Localization.getLocalizedText("packloader.source.scan-folder.schem"));
        assertEquals("packloader.does.not.exist", Localization.getLocalizedText("packloader.does.not.exist"));
    }
}

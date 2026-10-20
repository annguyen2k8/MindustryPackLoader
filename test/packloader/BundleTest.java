package packloader;

import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import arc.util.io.PropertiesUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

public class BundleTest{

    @BeforeEach
    public void setup(){
        TestHelper.initBundle(Locale.ENGLISH);
    }

    @AfterEach
    public void teardown(){
        Core.bundle = null;
    }

    @Test
    public void testBothBundlesExistAndAreParsable() throws Exception{
        Properties en = new Properties();
        try(InputStream in = getClass().getResourceAsStream("/bundles/bundle.properties")){
            assertNotNull(in, "assets/bundles/bundle.properties must exist on classpath");
            en.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        Properties vi = new Properties();
        try(InputStream in = getClass().getResourceAsStream("/bundles/bundle_vi.properties")){
            assertNotNull(in, "assets/bundles/bundle_vi.properties must exist on classpath");
            vi.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        assertFalse(en.isEmpty(), "English bundle must not be empty");
        assertFalse(vi.isEmpty(), "Vietnamese bundle must not be empty");

        // Verify key parity between en and vi
        for(String key : en.stringPropertyNames()){
            assertTrue(vi.containsKey(key), "Vietnamese bundle missing key: " + key);
        }
        for(String key : vi.stringPropertyNames()){
            assertTrue(en.containsKey(key), "English bundle missing key: " + key);
        }
    }

    @Test
    public void testCoreBundleEnglishLoadingAndPlaceholders(){
        TestHelper.initBundle(Locale.ENGLISH);

        assertNotNull(Core.bundle, "Core.bundle must be initialized");
        assertEquals("Mindustry PackLoader", Core.bundle.get("packloader.dialog.title"));
        assertEquals("Skip", Core.bundle.get("packloader.conflict.skip"));
        assertEquals("Overwrite", Core.bundle.get("packloader.conflict.overwrite"));
        assertEquals("Rename", Core.bundle.get("packloader.conflict.rename"));
        assertEquals("Import .zip", Core.bundle.get("packloader.button.import-zip"));
        assertEquals("Scan Folder", Core.bundle.get("packloader.action.scan-folder"));
        assertEquals("Select", Core.bundle.get("packloader.browser.select.folder"));
        assertEquals("No valid Mindustry Map (.msav) files found in the selected source.", Core.bundle.get("packloader.source.empty.maps"));

        // Placeholder formatting via Core.bundle.format
        String formatted = Core.bundle.format("packloader.source.summary", 5, 2, 7);
        assertEquals("Found: 5 maps, 2 schematics (7 files)", formatted);

        String fileDup = Core.bundle.format("packloader.duplicate.file", "test.msav");
        assertEquals("File already exists: test.msav", fileDup);
    }

    @Test
    public void testCoreBundleVietnameseLoadingAndPlaceholders(){
        TestHelper.initBundle(new Locale("vi"));

        assertNotNull(Core.bundle, "Core.bundle must be initialized for Vietnamese");
        assertEquals("Mindustry PackLoader", Core.bundle.get("packloader.dialog.title"));
        assertEquals("Bỏ qua", Core.bundle.get("packloader.conflict.skip"));
        assertEquals("Ghi đè", Core.bundle.get("packloader.conflict.overwrite"));
        assertEquals("Tự đổi tên", Core.bundle.get("packloader.conflict.rename"));
        assertEquals("Nhập .zip", Core.bundle.get("packloader.button.import-zip"));
        assertEquals("Quét thư mục", Core.bundle.get("packloader.action.scan-folder"));
        assertEquals("Chọn", Core.bundle.get("packloader.browser.select.folder"));
        assertEquals("Không tìm thấy tệp Bản đồ (.msav) hợp lệ trong nguồn đã chọn.", Core.bundle.get("packloader.source.empty.maps"));

        // Placeholder formatting
        String formatted = Core.bundle.format("packloader.source.summary", 10, 3, 13);
        assertEquals("Tìm thấy: 10 bản đồ, 3 schematic (tổng cộng 13 tệp)", formatted);

        String fileDup = Core.bundle.format("packloader.duplicate.file", "test.msav");
        assertEquals("Tệp đã tồn tại trong thư mục: test.msav", fileDup);
    }

    @Test
    public void testCoreBundleFallbackAndMissingKeys(){
        TestHelper.initBundle(new Locale("vi"));

        // When requesting key that doesn't exist, get(key, defaultVal) returns defaultVal
        String withDefault = Core.bundle.get("packloader.nonexistent.key.xyz", "DefaultFallback");
        assertEquals("DefaultFallback", withDefault);

        // has returns false for nonexistent keys
        assertFalse(Core.bundle.has("packloader.nonexistent.key.xyz"));
    }

    @Test
    public void testMindustryModBundleLoadingSimulation() throws Exception{
        // Simulate Mindustry's Mods.java loading mechanism:
        // Core.bundle is pre-existing, and mod properties are loaded into bundle.getProperties() via PropertiesUtils.load
        I18NBundle baseBundle = I18NBundle.createEmptyBundle();
        Core.bundle = baseBundle;

        Fi bundleFile = new Fi("assets/bundles/bundle.properties");
        assertTrue(bundleFile.exists(), "assets/bundles/bundle.properties must exist");

        // Simulate Mindustry: PropertiesUtils.load(bundle.getProperties(), file.reader())
        PropertiesUtils.load(baseBundle.getProperties(), bundleFile.reader("UTF-8"));

        assertTrue(Core.bundle.has("packloader.dialog.title"));
        assertEquals("Mindustry PackLoader", Core.bundle.get("packloader.dialog.title"));
        assertEquals("Skip", Core.bundle.get("packloader.conflict.skip"));
    }

    @Test
    public void testNoNestedBundlesPathInClasspathAndArtifact() throws Exception{
        // 1. Verify classpath resources: must be at /bundles/*, never at /bundles/bundles/*
        assertNotNull(getClass().getResource("/bundles/bundle.properties"), "/bundles/bundle.properties must exist on classpath");
        assertNotNull(getClass().getResource("/bundles/bundle_vi.properties"), "/bundles/bundle_vi.properties must exist on classpath");
        assertNull(getClass().getResource("/bundles/bundles/bundle.properties"), "Illegal nested /bundles/bundles/bundle.properties must not exist");
        assertNull(getClass().getResource("/bundles/bundles/bundle_vi.properties"), "Illegal nested /bundles/bundles/bundle_vi.properties must not exist");

        // 2. Verify built artifact JAR if present (produced by tasks.jar)
        File jarFile = new File("build/libs/MindustryPackLoaderDesktop.jar");
        if(jarFile.exists()){
            try(ZipFile zip = new ZipFile(jarFile)){
                ZipEntry defaultEntry = zip.getEntry("bundles/bundle.properties");
                ZipEntry viEntry = zip.getEntry("bundles/bundle_vi.properties");

                assertNotNull(defaultEntry, "JAR must contain 'bundles/bundle.properties'");
                assertNotNull(viEntry, "JAR must contain 'bundles/bundle_vi.properties'");

                // Verify that NO entry contains or starts with nested 'bundles/bundles/'
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while(entries.hasMoreElements()){
                    ZipEntry ze = entries.nextElement();
                    String name = ze.getName();
                    assertFalse(name.contains("bundles/bundles/"),
                        "JAR must not contain nested 'bundles/bundles/' path, found: " + name);
                    assertFalse(name.startsWith("bundles/bundles"),
                        "JAR must not start with 'bundles/bundles', found: " + name);
                }
            }
        }
    }

    @Test
    public void testModMetadataAndCompatibility() throws Exception{
        // 1. Verify mod.json and mod.hjson in project directory
        File modJson = new File("mod.json");
        File modHjson = new File("mod.hjson");
        assertTrue(modJson.exists(), "mod.json must exist in project root");
        assertTrue(modHjson.exists(), "mod.hjson must exist in project root");

        String jsonContent = java.nio.file.Files.readString(modJson.toPath());
        String hjsonContent = java.nio.file.Files.readString(modHjson.toPath());

        // 2. Verify main class entry point
        assertTrue(jsonContent.contains("\"main\": \"packloader.PackLoaderMod\""), "mod.json must declare packloader.PackLoaderMod");
        assertTrue(hjsonContent.contains("packloader.PackLoaderMod"), "mod.hjson must declare packloader.PackLoaderMod");

        // Verify that the entry point class is loadable
        Class<?> modClass = Class.forName("packloader.PackLoaderMod");
        assertNotNull(modClass, "PackLoaderMod class must exist");
        assertTrue(mindustry.mod.Mod.class.isAssignableFrom(modClass), "PackLoaderMod must extend mindustry.mod.Mod");

        // 3. Verify minGameVersion >= 154 (Mindustry v8 compatibility requirement)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\"']?minGameVersion[\"']?\\s*:\\s*[\"']?([0-9.]+)[\"']?").matcher(jsonContent);
        assertTrue(m.find(), "mod.json must contain minGameVersion");
        int minVer = Integer.parseInt(m.group(1).split("\\.")[0]);
        assertTrue(minVer >= 154, "mod.json minGameVersion (" + minVer + ") must be >= 154 to be compatible with Mindustry v8");

        java.util.regex.Matcher mH = java.util.regex.Pattern.compile("[\"']?minGameVersion[\"']?\\s*:\\s*[\"']?([0-9.]+)[\"']?").matcher(hjsonContent);
        assertTrue(mH.find(), "mod.hjson must contain minGameVersion");
        int minVerH = Integer.parseInt(mH.group(1).split("\\.")[0]);
        assertTrue(minVerH >= 154, "mod.hjson minGameVersion (" + minVerH + ") must be >= 154 to be compatible with Mindustry v8");

        // 4. Verify JAR archives if present
        File jarFile = new File("build/libs/MindustryPackLoaderDesktop.jar");
        if(jarFile.exists()){
            try(ZipFile zip = new ZipFile(jarFile)){
                assertNotNull(zip.getEntry("mod.json"), "Desktop JAR must contain mod.json at root");
                assertNotNull(zip.getEntry("mod.hjson"), "Desktop JAR must contain mod.hjson at root");
            }
        }

        File deployJar = new File("build/libs/MindustryPackLoader.jar");
        if(deployJar.exists()){
            try(ZipFile zip = new ZipFile(deployJar)){
                assertNotNull(zip.getEntry("mod.json"), "Deploy JAR must contain mod.json at root");
                assertNotNull(zip.getEntry("mod.hjson"), "Deploy JAR must contain mod.hjson at root");
                assertNotNull(zip.getEntry("classes.dex"), "Deploy JAR must contain classes.dex for Android runtime");
                assertNotNull(zip.getEntry("bundles/bundle.properties"), "Deploy JAR must contain default bundle");
                assertNotNull(zip.getEntry("bundles/bundle_vi.properties"), "Deploy JAR must contain Vietnamese bundle");
            }
        }
    }
}

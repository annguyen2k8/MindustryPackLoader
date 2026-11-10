package packloader.util;

import arc.Core;
import arc.Settings;
import arc.struct.Seq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.model.DuplicatePolicy;
import packloader.model.PackType;

import static org.junit.jupiter.api.Assertions.*;

public class RecentSourcesTest{

    @BeforeEach
    public void setup(){
        Core.settings = new Settings();
    }

    @AfterEach
    public void teardown(){
        Core.settings = null;
    }

    @Test
    public void testAddAndRetrieveRecentSources(){
        RecentSources.add("C:/maps/pack1.zip", "pack1.zip", PackType.MAP, false);
        RecentSources.add("C:/schems/base.msch", "base.msch", PackType.SCHEMATIC, false);

        Seq<RecentSources.SourceItem> list = RecentSources.load();
        assertEquals(2, list.size);

        // Most recent should be at index 0
        assertEquals("C:/schems/base.msch", list.get(0).path);
        assertEquals(PackType.SCHEMATIC, list.get(0).type);

        assertEquals("C:/maps/pack1.zip", list.get(1).path);
        assertEquals(PackType.MAP, list.get(1).type);
    }

    @Test
    public void testDeduplicationBumpsToFront(){
        RecentSources.add("path/a.zip", "a.zip", PackType.MAP, false);
        RecentSources.add("path/b.zip", "b.zip", PackType.MAP, false);
        RecentSources.add("path/a.zip", "a.zip", PackType.MAP, false);

        Seq<RecentSources.SourceItem> list = RecentSources.load();
        assertEquals(2, list.size);
        assertEquals("path/a.zip", list.get(0).path, "Re-added item must be moved to front");
        assertEquals("path/b.zip", list.get(1).path);
    }

    @Test
    public void testCapLimit(){
        for(int i = 0; i < 10; i++){
            RecentSources.add("path/file_" + i + ".zip", "file_" + i + ".zip", PackType.MAP, false);
        }

        Seq<RecentSources.SourceItem> list = RecentSources.load();
        assertEquals(RecentSources.MAX_RECENT, list.size);
        assertEquals("path/file_9.zip", list.get(0).path);
    }

    @Test
    public void testRemoveAndClear(){
        RecentSources.add("path/x.zip", "x.zip", PackType.MAP, false);
        RecentSources.add("path/y.zip", "y.zip", PackType.MAP, false);

        RecentSources.remove("path/x.zip");
        Seq<RecentSources.SourceItem> list = RecentSources.load();
        assertEquals(1, list.size);
        assertEquals("path/y.zip", list.get(0).path);

        RecentSources.clear();
        assertTrue(RecentSources.load().isEmpty());
    }

    @Test
    public void testDeduplicationDistinguishesType(){
        RecentSources.add("Downloads", "Downloads", PackType.SCHEMATIC, true);
        RecentSources.add("Downloads", "Downloads", PackType.MAP, true);

        Seq<RecentSources.SourceItem> list = RecentSources.load();
        assertEquals(2, list.size, "Same path with different types must coexist");
        assertEquals(PackType.MAP, list.get(0).type);
        assertEquals(PackType.SCHEMATIC, list.get(1).type);

        // Re-adding SCHEMATIC bumps it to front without removing MAP
        RecentSources.add("Downloads", "Downloads", PackType.SCHEMATIC, true);
        list = RecentSources.load();
        assertEquals(2, list.size);
        assertEquals(PackType.SCHEMATIC, list.get(0).type);
        assertEquals(PackType.MAP, list.get(1).type);
    }

    @Test
    public void testRemoveByType(){
        RecentSources.add("Downloads", "Downloads", PackType.SCHEMATIC, true);
        RecentSources.add("Downloads", "Downloads", PackType.MAP, true);

        // Remove only MAP entry
        RecentSources.remove("Downloads", PackType.MAP);
        Seq<RecentSources.SourceItem> list = RecentSources.load();
        assertEquals(1, list.size);
        assertEquals(PackType.SCHEMATIC, list.get(0).type);

        // Legacy remove(path) removes all entries matching path
        RecentSources.add("Downloads", "Downloads", PackType.MAP, true);
        assertEquals(2, RecentSources.load().size);
        RecentSources.remove("Downloads");
        assertTrue(RecentSources.load().isEmpty());
    }

    @Test
    public void testFilterByType(){
        RecentSources.add("maps.zip", "maps.zip", PackType.MAP, false);
        RecentSources.add("schems.zip", "schems.zip", PackType.SCHEMATIC, false);
        RecentSources.add("mixed.zip", "mixed.zip", null, false);

        Seq<RecentSources.SourceItem> maps = RecentSources.getForType(PackType.MAP);
        assertEquals(2, maps.size); // maps.zip and mixed.zip (null type matches all)

        Seq<RecentSources.SourceItem> schems = RecentSources.getForType(PackType.SCHEMATIC);
        assertEquals(2, schems.size); // schems.zip and mixed.zip
    }

    @Test
    public void testPackLoaderSettings(){
        assertFalse(PackLoaderSettings.isQuickImport());
        PackLoaderSettings.setQuickImport(true);
        assertTrue(PackLoaderSettings.isQuickImport());

        assertEquals(DuplicatePolicy.SKIP, PackLoaderSettings.getDuplicatePolicy());
        PackLoaderSettings.setDuplicatePolicy(DuplicatePolicy.RENAME);
        assertEquals(DuplicatePolicy.RENAME, PackLoaderSettings.getDuplicatePolicy());
    }

    @Test
    public void testPerTypeConflictPolicyPresets(){
        // Default policies must be SKIP
        assertEquals(DuplicatePolicy.SKIP, PackLoaderSettings.getDuplicatePolicy(PackType.MAP));
        assertEquals(DuplicatePolicy.SKIP, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC));

        // Setting Map policy does not affect Schematics
        PackLoaderSettings.setDuplicatePolicy(PackType.MAP, DuplicatePolicy.OVERWRITE);
        assertEquals(DuplicatePolicy.OVERWRITE, PackLoaderSettings.getDuplicatePolicy(PackType.MAP));
        assertEquals(DuplicatePolicy.SKIP, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC));

        // Setting Schematic policy does not affect Maps
        PackLoaderSettings.setDuplicatePolicy(PackType.SCHEMATIC, DuplicatePolicy.RENAME);
        assertEquals(DuplicatePolicy.OVERWRITE, PackLoaderSettings.getDuplicatePolicy(PackType.MAP));
        assertEquals(DuplicatePolicy.RENAME, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC));
    }

    @Test
    public void testLegacyPolicyMigrationAndFallback(){
        // Simulate legacy setting before per-type presets existed
        Core.settings.put(PackLoaderSettings.KEY_DUPLICATE_POLICY, DuplicatePolicy.OVERWRITE.name());

        // Without per-type keys, both should fall back to legacy setting
        assertEquals(DuplicatePolicy.OVERWRITE, PackLoaderSettings.getDuplicatePolicy(PackType.MAP));
        assertEquals(DuplicatePolicy.OVERWRITE, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC));

        // Explicitly setting one type overrides fallback for that type only
        PackLoaderSettings.setDuplicatePolicy(PackType.MAP, DuplicatePolicy.RENAME);
        assertEquals(DuplicatePolicy.RENAME, PackLoaderSettings.getDuplicatePolicy(PackType.MAP));
        assertEquals(DuplicatePolicy.OVERWRITE, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC));
    }

    @Test
    public void testVirtualSafNameSanitizedOnAdd(){
        RecentSources.add("/document/primary:mindustry-data-export.zip", "primary:mindustry-data-export.zip", PackType.SCHEMATIC, false);

        Seq<RecentSources.SourceItem> list = RecentSources.load();
        assertEquals(1, list.size);
        assertEquals("mindustry-data-export.zip", list.get(0).name, "Display name must be sanitized of primary: prefix");
        assertEquals(PackType.SCHEMATIC, list.get(0).type);
    }

    @Test
    public void testLegacyVirtualSafEntryMigrationOnLoad(){
        // Simulate an existing entry in settings that had primary: in both name and path (as in user screenshot)
        String legacyJson = "[{\"path\":\"/document/primary:mindustry-data-export.zip\",\"name\":\"primary:mindustry-data-export.zip\",\"type\":\"SCHEMATIC\",\"isDir\":false,\"lastUsed\":12345}]";
        Core.settings.put(RecentSources.SETTING_KEY, legacyJson);

        Seq<RecentSources.SourceItem> list = RecentSources.load();
        assertEquals(1, list.size);
        assertEquals("mindustry-data-export.zip", list.get(0).name, "Legacy virtual name must be sanitized automatically on load");
        assertEquals(PackType.SCHEMATIC, list.get(0).type);
    }
}

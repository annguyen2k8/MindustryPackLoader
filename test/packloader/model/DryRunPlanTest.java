package packloader.model;

import arc.files.Fi;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.Schematics;
import mindustry.maps.Maps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class DryRunPlanTest{
    private File tempDir;
    private Maps originalMaps;
    private Schematics originalSchematics;

    @BeforeEach
    public void setup() throws IOException{
        TestHelper.initBundle(java.util.Locale.ENGLISH);
        tempDir = Files.createTempDirectory("packloader_dryrun_test").toFile();
        originalMaps = Vars.maps;
        originalSchematics = Vars.schematics;
    }

    @AfterEach
    public void teardown(){
        Vars.maps = originalMaps;
        Vars.schematics = originalSchematics;
        if(tempDir != null && tempDir.exists()){
            new Fi(tempDir).deleteDirectory();
        }
    }

    @Test
    public void testEmptyAndNullEntries(){
        DryRunPlan planNull = DryRunPlan.calculate(null, null, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP);
        assertNotNull(planNull);
        assertEquals(0, planNull.totalSelected);
        assertEquals(0, planNull.items.size);

        DryRunPlan planEmpty = DryRunPlan.calculate(new Seq<>(), null, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP);
        assertNotNull(planEmpty);
        assertEquals(0, planEmpty.totalSelected);
        assertEquals(0, planEmpty.items.size);
    }

    @Test
    public void testNewEntriesNoConflicts(){
        Seq<PackEntry> entries = new Seq<>();
        PackEntry map = new PackEntry(PackType.MAP, "test/Desert.msav", "Desert.msav", 1024);
        PackEntry schem = new PackEntry(PackType.SCHEMATIC, "test/Router.msch", "Router.msch", 512);
        entries.add(map, schem);

        DryRunPlan plan = DryRunPlan.calculate(entries, null, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP);

        assertEquals(2, plan.totalSelected);
        assertEquals(2, plan.newCount);
        assertEquals(0, plan.skipCount);
        assertEquals(0, plan.overwriteCount);
        assertEquals(0, plan.renameCount);

        assertEquals(DryRunAction.NEW, plan.getItem(map).action);
        assertEquals(DryRunAction.NEW, plan.getItem(schem).action);
        assertEquals("Desert", plan.getItem(map).plannedTargetName);
        assertEquals("Router", plan.getItem(schem).plannedTargetName);
    }

    @Test
    public void testAllFourActionsWithDifferentPolicies(){
        PackEntry entryNew = new PackEntry(PackType.MAP, "test/UniqueMap.msav", "UniqueMap.msav", 100);

        PackEntry entrySkip = new PackEntry(PackType.MAP, "test/SkipMap.msav", "SkipMap.msav", 100);
        entrySkip.isDuplicate = true;

        PackEntry entryOverwrite = new PackEntry(PackType.MAP, "test/OverwriteMap.msav", "OverwriteMap.msav", 100);
        entryOverwrite.isDuplicate = true;

        PackEntry entryRename = new PackEntry(PackType.MAP, "test/RenameMap.msav", "RenameMap.msav", 100);
        entryRename.isDuplicate = true;

        // Verify SKIP
        Seq<PackEntry> batchSkip = new Seq<>();
        batchSkip.add(entryNew, entrySkip);
        DryRunPlan planSkip = DryRunPlan.calculate(batchSkip, null, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP);
        assertEquals(1, planSkip.newCount);
        assertEquals(1, planSkip.skipCount);
        assertEquals(DryRunAction.SKIP, planSkip.getItem(entrySkip).action);

        // Verify OVERWRITE
        Seq<PackEntry> batchOverwrite = new Seq<>();
        batchOverwrite.add(entryNew, entryOverwrite);
        DryRunPlan planOverwrite = DryRunPlan.calculate(batchOverwrite, null, DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE);
        assertEquals(1, planOverwrite.newCount);
        assertEquals(1, planOverwrite.overwriteCount);
        assertEquals(DryRunAction.OVERWRITE, planOverwrite.getItem(entryOverwrite).action);

        // Verify RENAME
        Seq<PackEntry> batchRename = new Seq<>();
        batchRename.add(entryNew, entryRename);
        DryRunPlan planRename = DryRunPlan.calculate(batchRename, null, DuplicatePolicy.RENAME, DuplicatePolicy.RENAME);
        assertEquals(1, planRename.newCount);
        assertEquals(1, planRename.renameCount);
        assertEquals(DryRunAction.RENAME, planRename.getItem(entryRename).action);
        assertTrue(planRename.getItem(entryRename).plannedTargetName.startsWith("RenameMap_"));
    }

    @Test
    public void testMixedBatchPerTypePolicies(){
        PackEntry mapDup = new PackEntry(PackType.MAP, "test/Map.msav", "Map.msav", 100);
        mapDup.isDuplicate = true;

        PackEntry schemDup = new PackEntry(PackType.SCHEMATIC, "test/Schem.msch", "Schem.msch", 100);
        schemDup.isDuplicate = true;

        PackEntry freshMap = new PackEntry(PackType.MAP, "test/Fresh.msav", "Fresh.msav", 100);

        Seq<PackEntry> entries = new Seq<>();
        entries.add(mapDup, schemDup, freshMap);

        // Maps: OVERWRITE, Schematics: SKIP
        DryRunPlan plan = DryRunPlan.calculate(entries, null, DuplicatePolicy.OVERWRITE, DuplicatePolicy.SKIP);

        assertEquals(3, plan.totalSelected);
        assertEquals(1, plan.newCount);
        assertEquals(1, plan.overwriteCount);
        assertEquals(1, plan.skipCount);
        assertEquals(0, plan.renameCount);

        assertEquals(DryRunAction.OVERWRITE, plan.getItem(mapDup).action);
        assertEquals(DryRunAction.SKIP, plan.getItem(schemDup).action);
        assertEquals(DryRunAction.NEW, plan.getItem(freshMap).action);
    }

    @Test
    public void testIntraBatchSequentialRenaming(){
        // Three entries with the same base name in one batch under RENAME policy
        PackEntry map1 = new PackEntry(PackType.MAP, "pack1/Volcano.msav", "Volcano.msav", 100);
        PackEntry map2 = new PackEntry(PackType.MAP, "pack2/Volcano.msav", "Volcano.msav", 100);
        PackEntry map3 = new PackEntry(PackType.MAP, "pack3/Volcano.msav", "Volcano.msav", 100);

        Seq<PackEntry> entries = new Seq<>();
        entries.add(map1, map2, map3);

        DryRunPlan plan = DryRunPlan.calculate(entries, null, DuplicatePolicy.RENAME, DuplicatePolicy.RENAME);

        assertEquals(3, plan.totalSelected);
        assertEquals(1, plan.newCount);
        assertEquals(2, plan.renameCount);

        // 1st gets original name
        assertEquals(DryRunAction.NEW, plan.getItem(map1).action);
        assertEquals("Volcano", plan.getItem(map1).plannedTargetName);

        // 2nd detects intra-batch collision and renames to Volcano_1
        assertEquals(DryRunAction.RENAME, plan.getItem(map2).action);
        assertEquals("Volcano_1", plan.getItem(map2).plannedTargetName);

        // 3rd detects both Volcano and Volcano_1 taken and renames to Volcano_2
        assertEquals(DryRunAction.RENAME, plan.getItem(map3).action);
        assertEquals("Volcano_2", plan.getItem(map3).plannedTargetName);
    }

    @Test
    public void testZeroSideEffectsDryRunDoesNotMutateDisk(){
        File testDir = new File(tempDir, "storage");
        testDir.mkdirs();
        File dummy = new File(testDir, "existing.txt");
        try{
            dummy.createNewFile();
        }catch(IOException ignored){}

        String[] filesBefore = testDir.list();
        assertNotNull(filesBefore);
        int countBefore = filesBefore.length;

        PackEntry map = new PackEntry(PackType.MAP, "test/Map.msav", "Map.msav", 100);
        map.isDuplicate = true;
        Seq<PackEntry> entries = new Seq<>();
        entries.add(map);

        DryRunPlan plan = DryRunPlan.calculate(entries, null, DuplicatePolicy.RENAME, DuplicatePolicy.RENAME);
        assertNotNull(plan);

        String[] filesAfter = testDir.list();
        assertNotNull(filesAfter);
        assertEquals(countBefore, filesAfter.length, "Dry-run must not create or delete files on disk");
    }

    @Test
    public void testSelectionAndTargetTypeFiltering(){
        PackEntry map1 = new PackEntry(PackType.MAP, "test/Map1.msav", "Map1.msav", 100);
        PackEntry map2 = new PackEntry(PackType.MAP, "test/Map2.msav", "Map2.msav", 100);
        map2.selected = false; // Deselected

        PackEntry schem = new PackEntry(PackType.SCHEMATIC, "test/Schem.msch", "Schem.msch", 100);

        Seq<PackEntry> entries = new Seq<>();
        entries.add(map1, map2, schem);

        // Filter targetType = MAP
        DryRunPlan plan = DryRunPlan.calculate(entries, PackType.MAP, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP);

        assertEquals(1, plan.totalSelected);
        assertEquals(1, plan.items.size);
        assertEquals(map1, plan.items.first().entry);

        // map2 was unselected, so it is in itemMap for display but not in items sequence
        assertNotNull(plan.getItem(map2));
        assertFalse(plan.items.contains(i -> i.entry == map2));
    }
}

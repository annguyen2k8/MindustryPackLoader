package packloader.model;

import arc.struct.Seq;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class EntryFilterTest{

    private PackEntry createEntry(PackType type, String name, boolean duplicate, boolean valid){
        PackEntry entry = new PackEntry(type, "/path/" + name, name, 1024);
        entry.isDuplicate = duplicate;
        entry.isValid = valid;
        entry.selected = false;
        return entry;
    }

    @Test
    public void testMatchingFilters(){
        PackEntry mapEntry = createEntry(PackType.MAP, "canyon_map.msav", false, true);
        PackEntry schemEntry = createEntry(PackType.SCHEMATIC, "factory.msch", false, true);

        // Target type constraint
        assertTrue(EntryFilter.matches(mapEntry, PackType.MAP, null, ""));
        assertFalse(EntryFilter.matches(mapEntry, PackType.SCHEMATIC, null, ""));

        // Filter type constraint
        assertTrue(EntryFilter.matches(schemEntry, null, PackType.SCHEMATIC, ""));
        assertFalse(EntryFilter.matches(schemEntry, null, PackType.MAP, ""));

        // Search text matching (case-insensitive substring)
        assertTrue(EntryFilter.matches(mapEntry, null, null, "canyon"));
        assertTrue(EntryFilter.matches(mapEntry, null, null, "CANYON"));
        assertFalse(EntryFilter.matches(mapEntry, null, null, "reactor"));
    }

    @Test
    public void testSelectNewOnly(){
        Seq<PackEntry> list = new Seq<>();
        PackEntry e1 = createEntry(PackType.MAP, "new_map.msav", false, true);
        PackEntry e2 = createEntry(PackType.MAP, "dup_map.msav", true, true);
        PackEntry e3 = createEntry(PackType.MAP, "invalid_map.msav", false, false);
        list.add(e1, e2, e3);

        EntryFilter.selectNewOnly(list, PackType.MAP, null, "");

        assertTrue(e1.selected, "New valid entry must be selected");
        assertFalse(e2.selected, "Duplicate entry must not be selected");
        assertFalse(e3.selected, "Invalid entry must not be selected");
    }

    @Test
    public void testSelectDuplicatesOnly(){
        Seq<PackEntry> list = new Seq<>();
        PackEntry e1 = createEntry(PackType.MAP, "new_map.msav", false, true);
        PackEntry e2 = createEntry(PackType.MAP, "dup_map.msav", true, true);
        list.add(e1, e2);

        EntryFilter.selectDuplicatesOnly(list, PackType.MAP, null, "");

        assertFalse(e1.selected, "New entry must not be selected");
        assertTrue(e2.selected, "Duplicate entry must be selected");
    }

    @Test
    public void testInvertVisibleSelected(){
        Seq<PackEntry> list = new Seq<>();
        PackEntry e1 = createEntry(PackType.MAP, "map_1.msav", false, true);
        PackEntry e2 = createEntry(PackType.MAP, "map_2.msav", false, true);
        e1.selected = true;
        e2.selected = false;
        list.add(e1, e2);

        EntryFilter.invertVisibleSelected(list, PackType.MAP, null, "");

        assertFalse(e1.selected, "e1 must be flipped to unselected");
        assertTrue(e2.selected, "e2 must be flipped to selected");
    }

    @Test
    public void testSetVisibleSelectedPreservesHiddenEntries(){
        Seq<PackEntry> list = new Seq<>();
        PackEntry match = createEntry(PackType.MAP, "visible.msav", false, true);
        PackEntry hidden = createEntry(PackType.SCHEMATIC, "hidden.msch", false, true);
        hidden.selected = true;
        list.add(match, hidden);

        // Select visible maps only
        EntryFilter.setVisibleSelected(list, PackType.MAP, null, "", true);
        assertTrue(match.selected);
        assertTrue(hidden.selected, "Hidden schematic selection state must remain unchanged");

        // Deselect visible maps only
        EntryFilter.setVisibleSelected(list, PackType.MAP, null, "", false);
        assertFalse(match.selected);
        assertTrue(hidden.selected, "Hidden schematic selection state must still remain unchanged");
    }

    @Test
    public void testSelectEligibleForQuickImportSkipPolicy(){
        Seq<PackEntry> list = new Seq<>();
        PackEntry fresh = createEntry(PackType.MAP, "fresh.msav", false, true);
        PackEntry duplicate = createEntry(PackType.MAP, "dup.msav", true, true);
        PackEntry corruptFresh = createEntry(PackType.MAP, "bad_fresh.msav", false, false);
        PackEntry corruptDup = createEntry(PackType.MAP, "bad_dup.msav", true, false);
        list.add(fresh, duplicate, corruptFresh, corruptDup);

        int eligible = EntryFilter.selectEligibleForQuickImport(list, PackType.MAP, DuplicatePolicy.SKIP);

        assertEquals(1, eligible, "Under SKIP, only valid fresh files should be eligible");
        assertTrue(fresh.selected, "Fresh valid file must be selected");
        assertFalse(duplicate.selected, "Duplicate must be excluded under SKIP");
        assertFalse(corruptFresh.selected, "Corrupt files must never be selected");
        assertFalse(corruptDup.selected, "Corrupt duplicates must never be selected");
    }

    @Test
    public void testSelectEligibleForQuickImportOverwriteAndRenamePolicies(){
        Seq<PackEntry> list = new Seq<>();
        PackEntry fresh = createEntry(PackType.SCHEMATIC, "fresh.msch", false, true);
        PackEntry duplicate = createEntry(PackType.SCHEMATIC, "dup.msch", true, true);
        PackEntry corrupt = createEntry(PackType.SCHEMATIC, "bad.msch", false, false);
        list.add(fresh, duplicate, corrupt);

        int overwriteCount = EntryFilter.selectEligibleForQuickImport(list, PackType.SCHEMATIC, DuplicatePolicy.OVERWRITE);
        assertEquals(2, overwriteCount, "OVERWRITE selects all valid files (new & duplicates)");
        assertTrue(fresh.selected);
        assertTrue(duplicate.selected);
        assertFalse(corrupt.selected);

        // Reset
        fresh.selected = false;
        duplicate.selected = false;

        int renameCount = EntryFilter.selectEligibleForQuickImport(list, PackType.SCHEMATIC, DuplicatePolicy.RENAME);
        assertEquals(2, renameCount, "RENAME selects all valid files (new & duplicates)");
        assertTrue(fresh.selected);
        assertTrue(duplicate.selected);
        assertFalse(corrupt.selected);
    }

    @Test
    public void testSelectEligibleForQuickImportTargetTypeAndEmpty(){
        Seq<PackEntry> list = new Seq<>();
        PackEntry map = createEntry(PackType.MAP, "map.msav", false, true);
        PackEntry schem = createEntry(PackType.SCHEMATIC, "schem.msch", false, true);
        list.add(map, schem);

        int mapEligible = EntryFilter.selectEligibleForQuickImport(list, PackType.MAP, DuplicatePolicy.SKIP);
        assertEquals(1, mapEligible);
        assertTrue(map.selected);
        assertFalse(schem.selected, "Schematic must not be selected when targetType is MAP");

        assertEquals(0, EntryFilter.selectEligibleForQuickImport(null, PackType.MAP, DuplicatePolicy.SKIP));
        assertEquals(0, EntryFilter.selectEligibleForQuickImport(new Seq<>(), PackType.MAP, DuplicatePolicy.SKIP));
    }

    @Test
    public void testSelectEligibleForQuickImportAllDuplicatesWithSkip(){
        Seq<PackEntry> list = new Seq<>();
        PackEntry dup1 = createEntry(PackType.MAP, "dup1.msav", true, true);
        PackEntry dup2 = createEntry(PackType.MAP, "dup2.msav", true, true);
        list.add(dup1, dup2);

        int eligible = EntryFilter.selectEligibleForQuickImport(list, PackType.MAP, DuplicatePolicy.SKIP);
        assertEquals(0, eligible, "No entries eligible when all are duplicates and policy is SKIP");
        assertFalse(dup1.selected);
        assertFalse(dup2.selected);
    }

    @Test
    public void testSelectEligibleForQuickImportPerTypePolicies(){
        Seq<PackEntry> list = new Seq<>();
        PackEntry mapFresh = createEntry(PackType.MAP, "map_fresh.msav", false, true);
        PackEntry mapDup = createEntry(PackType.MAP, "map_dup.msav", true, true);
        PackEntry schemFresh = createEntry(PackType.SCHEMATIC, "schem_fresh.msch", false, true);
        PackEntry schemDup = createEntry(PackType.SCHEMATIC, "schem_dup.msch", true, true);
        list.add(mapFresh, mapDup, schemFresh, schemDup);

        // Case 1: Map = OVERWRITE (accepts duplicate), Schematic = SKIP (rejects duplicate)
        int eligible1 = EntryFilter.selectEligibleForQuickImport(list, null, DuplicatePolicy.OVERWRITE, DuplicatePolicy.SKIP);
        assertEquals(3, eligible1);
        assertTrue(mapFresh.selected);
        assertTrue(mapDup.selected, "Map duplicate must be selected under OVERWRITE");
        assertTrue(schemFresh.selected);
        assertFalse(schemDup.selected, "Schematic duplicate must be excluded under SKIP");

        // Reset
        for(PackEntry e : list) e.selected = false;

        // Case 2: Map = SKIP (rejects duplicate), Schematic = RENAME (accepts duplicate)
        int eligible2 = EntryFilter.selectEligibleForQuickImport(list, null, DuplicatePolicy.SKIP, DuplicatePolicy.RENAME);
        assertEquals(3, eligible2);
        assertTrue(mapFresh.selected);
        assertFalse(mapDup.selected, "Map duplicate must be excluded under SKIP");
        assertTrue(schemFresh.selected);
        assertTrue(schemDup.selected, "Schematic duplicate must be selected under RENAME");
    }
}

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
}

package packloader.model;

import arc.struct.Seq;

/** Shared, allocation-free filtering and selection helpers for the entry list. */
public final class EntryFilter{
    private EntryFilter(){}

    public static boolean matches(PackEntry entry, PackType targetType, PackType filterType, String searchText){
        if(entry == null) return false;
        if(targetType != null && entry.type != targetType) return false;
        if(filterType != null && entry.type != filterType) return false;

        if(searchText != null && !searchText.isEmpty()){
            return containsIgnoreCase(entry.displayName, searchText) ||
                containsIgnoreCase(entry.sanitizedFileName, searchText);
        }
        return true;
    }

    /** Counts selected entries visible under the current filter without allocating a Seq. */
    public static int countSelected(Seq<PackEntry> entries, PackType targetType, PackType filterType, String searchText){
        if(entries == null) return 0;
        int count = 0;
        for(int i = 0; i < entries.size; i++){
            PackEntry entry = entries.get(i);
            if(entry.selected && matches(entry, targetType, filterType, searchText)) count++;
        }
        return count;
    }

    /** Applies select/deselect to visible entries only, leaving hidden entries untouched. */
    public static void setVisibleSelected(Seq<PackEntry> entries, PackType targetType, PackType filterType,
                                          String searchText, boolean selected){
        if(entries == null) return;
        for(int i = 0; i < entries.size; i++){
            PackEntry entry = entries.get(i);
            if(matches(entry, targetType, filterType, searchText)){
                entry.selected = selected;
            }
        }
    }

    /** Selects only new (non-duplicate and valid) visible entries, deselecting duplicates. */
    public static void selectNewOnly(Seq<PackEntry> entries, PackType targetType, PackType filterType,
                                     String searchText){
        if(entries == null) return;
        for(int i = 0; i < entries.size; i++){
            PackEntry entry = entries.get(i);
            if(matches(entry, targetType, filterType, searchText)){
                entry.selected = !entry.isDuplicate && entry.isValid;
            }
        }
    }

    /** Selects only duplicate visible entries, deselecting new ones. */
    public static void selectDuplicatesOnly(Seq<PackEntry> entries, PackType targetType, PackType filterType,
                                            String searchText){
        if(entries == null) return;
        for(int i = 0; i < entries.size; i++){
            PackEntry entry = entries.get(i);
            if(matches(entry, targetType, filterType, searchText)){
                entry.selected = entry.isDuplicate && entry.isValid;
            }
        }
    }

    /** Inverts the selection status of all visible valid entries. */
    public static void invertVisibleSelected(Seq<PackEntry> entries, PackType targetType, PackType filterType,
                                             String searchText){
        if(entries == null) return;
        for(int i = 0; i < entries.size; i++){
            PackEntry entry = entries.get(i);
            if(matches(entry, targetType, filterType, searchText)){
                entry.selected = !entry.selected;
            }
        }
    }

    /** Collects entries that are selected and visible. Used once when an import starts. */
    public static Seq<PackEntry> collectSelected(Seq<PackEntry> entries, PackType targetType,
                                                  PackType filterType, String searchText){
        Seq<PackEntry> result = new Seq<>();
        if(entries == null) return result;
        for(int i = 0; i < entries.size; i++){
            PackEntry entry = entries.get(i);
            if(entry.selected && matches(entry, targetType, filterType, searchText)){
                result.add(entry);
            }
        }
        return result;
    }

    private static boolean containsIgnoreCase(String value, String term){
        if(value == null || term == null || term.isEmpty() || value.length() < term.length()) return false;
        int last = value.length() - term.length();
        for(int i = 0; i <= last; i++){
            if(value.regionMatches(true, i, term, 0, term.length())) return true;
        }
        return false;
    }
}

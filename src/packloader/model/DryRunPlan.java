package packloader.model;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import packloader.core.PackImporter;
import packloader.util.PackLoaderSettings;

import java.util.HashSet;
import java.util.Set;

/**
 * Computes a non-destructive dry-run simulation of an import operation, classifying each entry
 * into NEW, SKIP, OVERWRITE, or RENAME based on existing game registries and per-type conflict policies.
 *
 * Simulates intra-batch sequential naming without mutating live game registries or disk.
 */
public class DryRunPlan{

    public static class DryRunItem{
        public final PackEntry entry;
        public final DryRunAction action;
        public final String plannedTargetName;
        public final boolean isContentDuplicate;
        public final String contentDuplicateMatch;

        public DryRunItem(PackEntry entry, DryRunAction action, String plannedTargetName){
            this(entry, action, plannedTargetName, entry != null && entry.isContentDuplicate,
                entry != null ? entry.contentDuplicateMatch : "");
        }

        public DryRunItem(PackEntry entry, DryRunAction action, String plannedTargetName,
                          boolean isContentDuplicate, String contentDuplicateMatch){
            this.entry = entry;
            this.action = action;
            this.plannedTargetName = plannedTargetName;
            this.isContentDuplicate = isContentDuplicate;
            this.contentDuplicateMatch = contentDuplicateMatch == null ? "" : contentDuplicateMatch;
        }

        public boolean isRenamed(){
            return action == DryRunAction.RENAME && plannedTargetName != null
                && !plannedTargetName.equalsIgnoreCase(entry.displayName);
        }
    }

    /** Selected items planned for import in batch order. */
    public final Seq<DryRunItem> items = new Seq<>();
    /** Map of all evaluated entries (selected and unselected) to their planned action. */
    public final ObjectMap<PackEntry, DryRunItem> itemMap = new ObjectMap<>();

    public int newCount = 0;
    public int overwriteCount = 0;
    public int renameCount = 0;
    public int skipCount = 0;
    public int contentDuplicateCount = 0;
    public int totalSelected = 0;

    public DryRunItem getItem(PackEntry entry){
        return itemMap.get(entry);
    }

    public static DryRunPlan calculate(Seq<PackEntry> entries, PackType targetType,
                                      DuplicatePolicy mapPolicy, DuplicatePolicy schemPolicy){
        return calculate(entries, targetType, mapPolicy, schemPolicy, PackLoaderSettings.isSkipIdenticalContent());
    }

    public static DryRunPlan calculate(Seq<PackEntry> entries, PackType targetType,
                                      DuplicatePolicy mapPolicy, DuplicatePolicy schemPolicy,
                                      boolean skipIdenticalContent){
        DryRunPlan plan = new DryRunPlan();
        if(entries == null || entries.isEmpty()) return plan;

        if(mapPolicy == null) mapPolicy = DuplicatePolicy.SKIP;
        if(schemPolicy == null) schemPolicy = DuplicatePolicy.SKIP;

        Set<String> takenMapNames = new HashSet<>();
        Set<String> takenMapFiles = new HashSet<>();
        Set<String> takenSchemNames = new HashSet<>();
        Set<String> takenSchemFiles = new HashSet<>();

        // First pass: evaluate selected entries that will be imported in batch order
        for(int i = 0; i < entries.size; i++){
            PackEntry entry = entries.get(i);
            if(targetType != null && entry.type != targetType) continue;
            if(!entry.selected || !entry.isValid) continue;

            DuplicatePolicy policy = entry.type == PackType.MAP ? mapPolicy : schemPolicy;
            boolean isMap = entry.type == PackType.MAP;

            Set<String> takenNames = isMap ? takenMapNames : takenSchemNames;
            Set<String> takenFiles = isMap ? takenMapFiles : takenSchemFiles;

            boolean hasConflict = entry.isDuplicate
                || PackImporter.containsIgnoreCase(takenNames, entry.displayName)
                || PackImporter.containsIgnoreCase(takenFiles, entry.sanitizedFileName);

            DryRunAction action;
            String plannedName = entry.displayName;

            if(entry.isContentDuplicate){
                plan.contentDuplicateCount++;
            }

            boolean isContentSkip = skipIdenticalContent && entry.isContentDuplicate;

            if(isContentSkip){
                action = DryRunAction.SKIP;
                plan.skipCount++;
            }else if(!hasConflict){
                action = DryRunAction.NEW;
                takenNames.add(entry.displayName);
                takenFiles.add(entry.sanitizedFileName);
                plan.newCount++;
            }else{
                if(policy == DuplicatePolicy.SKIP){
                    action = DryRunAction.SKIP;
                    plan.skipCount++;
                }else if(policy == DuplicatePolicy.OVERWRITE){
                    action = DryRunAction.OVERWRITE;
                    plan.overwriteCount++;
                }else{ // RENAME
                    action = DryRunAction.RENAME;
                    if(isMap){
                        plannedName = PackImporter.findUniqueMapName(entry.displayName, takenNames);
                    }else{
                        plannedName = PackImporter.findUniqueSchematicName(entry.displayName, takenNames);
                    }
                    takenNames.add(plannedName);
                    plan.renameCount++;
                }
            }

            DryRunItem item = new DryRunItem(entry, action, plannedName);
            plan.items.add(item);
            plan.itemMap.put(entry, item);
            plan.totalSelected++;
        }

        // Second pass: for unselected or invalid entries, provide their preview item without altering batch allocation
        for(int i = 0; i < entries.size; i++){
            PackEntry entry = entries.get(i);
            if(targetType != null && entry.type != targetType) continue;
            if(plan.itemMap.containsKey(entry)) continue;

            DuplicatePolicy policy = entry.type == PackType.MAP ? mapPolicy : schemPolicy;
            DryRunAction action;
            String plannedName = entry.displayName;

            if(!entry.isValid){
                action = DryRunAction.SKIP;
            }else if(PackLoaderSettings.isSkipIdenticalContent() && entry.isContentDuplicate){
                action = DryRunAction.SKIP;
            }else if(!entry.isDuplicate){
                action = DryRunAction.NEW;
            }else if(policy == DuplicatePolicy.SKIP){
                action = DryRunAction.SKIP;
            }else if(policy == DuplicatePolicy.OVERWRITE){
                action = DryRunAction.OVERWRITE;
            }else{
                action = DryRunAction.RENAME;
                if(entry.type == PackType.MAP){
                    plannedName = PackImporter.findUniqueMapName(entry.displayName, takenMapNames);
                }else{
                    plannedName = PackImporter.findUniqueSchematicName(entry.displayName, takenSchemNames);
                }
            }

            DryRunItem item = new DryRunItem(entry, action, plannedName);
            plan.itemMap.put(entry, item);
        }

        return plan;
    }
}

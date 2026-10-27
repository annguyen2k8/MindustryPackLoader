package packloader.core;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.io.MapIO;
import mindustry.maps.Map;
import packloader.model.DuplicatePolicy;
import packloader.model.ImportItemResult;
import packloader.model.ImportReport;
import packloader.model.PackEntry;
import packloader.model.PackType;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

import static packloader.util.Localization.getLocalizedText;

public class PackImporter{
    private final Fi mapDirectory;
    private final Fi schematicDirectory;

    public PackImporter(){
        this(Vars.customMapDirectory, Vars.schematicDirectory);
    }

    public PackImporter(Fi mapDirectory, Fi schematicDirectory){
        this.mapDirectory = mapDirectory;
        this.schematicDirectory = schematicDirectory;
    }

    /**
     * Imports all selected entries using the specified duplicate conflict policy.
     * Reports per-item results and optionally updates progress (0.0 to 1.0).
     */
    public ImportReport importEntries(Seq<PackEntry> entries, DuplicatePolicy policy, Cons<Float> progressCallback){
        ImportReport report = new ImportReport();
        long startTime = System.currentTimeMillis();

        if(entries == null || entries.isEmpty()){
            report.elapsedMillis = 0;
            return report;
        }

        Seq<PackEntry> toImport = entries.select(e -> e.selected);
        report.totalSelected = toImport.size;

        boolean anyMapsImported = false;
        boolean anySchematicsImported = false;

        for(int i = 0; i < toImport.size; i++){
            PackEntry entry = toImport.get(i);
            if(progressCallback != null){
                progressCallback.get((float)i / toImport.size);
            }

            if(!entry.isValid){
                report.addResult(ImportItemResult.failed(entry, entry.errorReason));
                continue;
            }

            // In-game native Mindustry map import (safely generates thumbnails, preview PNG & cache, no corruption)
            if(entry.type == PackType.MAP && Vars.maps != null && mapDirectory == Vars.customMapDirectory){
                Fi tempFile = Core.files.local("cache").child("packload_" + System.nanoTime() + ".msav");
                try{
                    entry.copyTo(tempFile);
                    Map meta = MapIO.createMap(tempFile, true);
                    String mapName = meta.tags.get("name", () -> PackEntry.extractBaseName(entry.sanitizedFileName));

                    Map conflict = Vars.maps.all().find(m -> m.name().equalsIgnoreCase(mapName));
                    boolean isOverwrite = false;

                    if(conflict != null){
                        if(policy == DuplicatePolicy.SKIP){
                            tempFile.delete();
                            report.addResult(ImportItemResult.skipped(entry, getLocalizedText("packloader.result.msg.skipped", mapName)));
                            continue;
                        }else if(policy == DuplicatePolicy.OVERWRITE){
                            if(conflict.custom){
                                Vars.maps.removeMap(conflict);
                                isOverwrite = true;
                            }
                        }
                    }

                    Map imported = Vars.maps.importMap(tempFile);
                    tempFile.delete();
                    anyMapsImported = true;

                    if(isOverwrite){
                        report.addResult(ImportItemResult.overwritten(entry, imported.file));
                    }else{
                        report.addResult(ImportItemResult.success(entry, imported.file));
                    }
                }catch(Throwable t){
                    if(tempFile.exists()) tempFile.delete();
                    recordFailure(report, entry, "Native Map import error", t);
                }
                continue;
            }

            // In-game native Mindustry schematic import
            if(entry.type == PackType.SCHEMATIC && Vars.schematics != null && schematicDirectory == Vars.schematicDirectory){
                Fi tempFile = Core.files.local("cache").child("packload_" + System.nanoTime() + ".msch");
                try{
                    entry.copyTo(tempFile);
                    Schematic s = Schematics.read(tempFile);
                    tempFile.delete();

                    String schemName = s.name();
                    Schematic conflict = Vars.schematics.all().find(sc -> sc.name().equalsIgnoreCase(schemName));
                    boolean isOverwrite = false;

                    if(conflict != null){
                        if(policy == DuplicatePolicy.SKIP){
                            report.addResult(ImportItemResult.skipped(entry, getLocalizedText("packloader.result.msg.skipped", schemName)));
                            continue;
                        }else if(policy == DuplicatePolicy.OVERWRITE){
                            Vars.schematics.remove(conflict);
                            isOverwrite = true;
                        }else if(policy == DuplicatePolicy.RENAME){
                            String newName = findUniqueSchematicName(schemName);
                            s.tags.put("name", newName);
                        }
                    }

                    Vars.schematics.add(s);
                    anySchematicsImported = true;

                    if(isOverwrite){
                        report.addResult(ImportItemResult.overwritten(entry, s.file));
                    }else{
                        report.addResult(ImportItemResult.success(entry, s.file));
                    }
                }catch(Throwable t){
                    if(tempFile.exists()) tempFile.delete();
                    recordFailure(report, entry, "Native Schematic import error", t);
                }
                continue;
            }

            // Fallback for custom directories or headless unit tests
            Fi targetDir = (entry.type == PackType.MAP) ? mapDirectory : schematicDirectory;
            if(targetDir == null){
                report.addResult(ImportItemResult.failed(entry, getLocalizedText("packloader.error.target-unavailable")));
                continue;
            }

            if(!targetDir.exists()){
                targetDir.mkdirs();
            }

            try{
                // Verify canonical path to prevent escaping target directory
                String targetFileName = entry.sanitizedFileName;
                Fi targetFile = targetDir.child(targetFileName);
                if(!isWithinDirectory(targetDir, targetFile)){
                    report.addResult(ImportItemResult.failed(entry, getLocalizedText("packloader.error.path-escape")));
                    continue;
                }

                boolean fileExists = targetFile.exists();
                boolean isConflict = entry.isDuplicate || fileExists;

                if(isConflict){
                    if(policy == DuplicatePolicy.SKIP){
                        report.addResult(ImportItemResult.skipped(entry, getLocalizedText("packloader.result.msg.skipped", targetFileName)));
                        continue;
                    }else if(policy == DuplicatePolicy.RENAME){
                        String baseName = PackEntry.extractBaseName(entry.sanitizedFileName);
                        targetFile = findUniqueFile(targetDir, baseName, entry.type.extension);
                    }
                    // For OVERWRITE, targetFile remains as is
                }

                // Perform safe atomic extraction
                boolean isOverwrite = fileExists && (policy == DuplicatePolicy.OVERWRITE);
                safeExtract(entry, targetFile);

                if(entry.type == PackType.MAP){
                    anyMapsImported = true;
                }else if(entry.type == PackType.SCHEMATIC){
                    anySchematicsImported = true;
                }

                if(isOverwrite){
                    report.addResult(ImportItemResult.overwritten(entry, targetFile));
                }else{
                    report.addResult(ImportItemResult.success(entry, targetFile));
                }

            }catch(Throwable t){
                recordFailure(report, entry, "Import error", t);
            }
        }

        if(progressCallback != null){
            progressCallback.get(1f);
        }

        // Refresh game UI subsystems safely (never reload/wipe previews)
        refreshGameSubsystems(anyMapsImported, anySchematicsImported);

        report.elapsedMillis = System.currentTimeMillis() - startTime;
        return report;
    }

    private void safeExtract(PackEntry entry, Fi targetFile) throws IOException{
        Fi tempFile = targetFile.parent().child(targetFile.name() + "." + System.nanoTime() + ".packload_tmp");
        try{
            entry.copyTo(tempFile);
            if(tempFile.length() == 0){
                tempFile.delete();
                throw new IOException(getLocalizedText("packloader.error.empty-file"));
            }
            if(targetFile.exists()){
                targetFile.delete();
            }
            tempFile.moveTo(targetFile);
        }catch(Throwable t){
            if(tempFile.exists()){
                try{
                    tempFile.delete();
                }catch(Throwable ignored){}
            }
            if(t instanceof IOException io) throw io;
            throw new IOException(t.getMessage(), t);
        }
    }

    public static Fi findUniqueFile(Fi dir, String baseName, String extension){
        String clean = Strings.sanitizeFilename(baseName);
        if(clean.isEmpty()) clean = "unnamed";

        Fi target = dir.child(clean + "." + extension);
        int index = 1;
        while(target.exists()){
            target = dir.child(clean + "_" + index + "." + extension);
            index++;
        }
        return target;
    }

    public static String findUniqueSchematicName(String baseName){
        int index = 1;
        String name = baseName + " " + index;
        while(isSchematicNameTaken(name)){
            index++;
            name = baseName + " " + index;
        }
        return name;
    }

    private static boolean isSchematicNameTaken(String name){
        if(Vars.schematics == null) return false;
        for(Schematic sc : Vars.schematics.all()){
            if(sc.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static boolean isWithinDirectory(Fi parent, Fi child){
        try{
            String parentPath = parent.file().getCanonicalPath();
            String childPath = child.file().getCanonicalPath();
            return childPath.startsWith(parentPath + File.separator) || childPath.equals(parentPath);
        }catch(IOException e){
            return false;
        }
    }

    /** Safely refreshes Mindustry UI without destroying map textures or existing instances. */
    public void refreshGameSubsystems(boolean reloadMaps, boolean reloadSchematics){
        Runnable refreshAction = () -> {
            try{
                if(reloadMaps && Vars.maps != null){
                    // Ensure all map previews are loaded/queued without wiping textures
                    try{
                        Vars.maps.loadPreviews();
                    }catch(Throwable ignored){}

                    if(Vars.ui != null){
                        if(Vars.ui.maps != null) invokeSetup(Vars.ui.maps);
                        if(Vars.ui.custom != null) invokeSetup(Vars.ui.custom);
                    }
                    Log.info("[PackLoader] Mindustry custom maps UI refreshed safely.");
                }
            }catch(Throwable t){
                Log.err("[PackLoader] Error refreshing Maps UI:", t);
            }

            try{
                if(reloadSchematics && Vars.schematics != null){
                    if(Vars.ui != null && Vars.ui.schematics != null){
                        invokeSetup(Vars.ui.schematics);
                    }
                    Log.info("[PackLoader] Mindustry schematics UI refreshed safely.");
                }
            }catch(Throwable t){
                Log.err("[PackLoader] Error refreshing Schematics UI:", t);
            }
        };

        if(Core.app != null){
            Core.app.post(refreshAction);
        }else{
            refreshAction.run();
        }
    }

    private void recordFailure(ImportReport report, PackEntry entry, String logPrefix, Throwable t){
        Log.err("[PackLoader] " + logPrefix + " for '@':", entry.sourcePath, t);
        String msg = (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        report.addResult(ImportItemResult.failed(entry, getLocalizedText("packloader.result.msg.failed", msg)));
    }

    private static void invokeSetup(Object dialog){
        if(dialog == null) return;
        Class<?> clazz = dialog.getClass();
        while(clazz != null && clazz != Object.class){
            try{
                java.lang.reflect.Method m = clazz.getDeclaredMethod("setup");
                m.setAccessible(true);
                m.invoke(dialog);
                return;
            }catch(NoSuchMethodException e){
                clazz = clazz.getSuperclass();
            }catch(Throwable ignored){
                return;
            }
        }
    }
}

package packloader.ui;

import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import packloader.model.PackType;

import java.util.HashSet;
import java.util.Set;

/**
 * Handles desktop file drag-and-drop events onto the game window.
 * Batches rapid multi-file drops across frames, validates extensions,
 * determines pack types, and routes valid sources into the import pipeline.
 */
public class FileDropHandler{
    public interface DropTarget{
        void scanAsync(PackType targetType, boolean append, Fi... sources);
        boolean isInReviewMode();
        PackType getTargetType();
    }

    private final DropTarget target;
    private final Seq<Fi> pendingDrops = new Seq<>();
    private final Set<String> pendingPaths = new HashSet<>();
    private boolean scheduled = false;

    public FileDropHandler(DropTarget target){
        this.target = target;
    }

    /** Returns true if the file is a directory, ZIP archive, map (.msav), or schematic (.msch). */
    public static boolean isSupported(Fi file){
        if(file == null || !file.exists()) return false;
        if(file.isDirectory()) return true;
        String ext = file.extension().toLowerCase();
        return ext.equals("zip") || ext.equals("msav") || ext.equals("msch");
    }

    /**
     * Called when a file is dropped onto the game window.
     * Batches multiple dropped files arriving within 2 frames to handle multi-file drag operations.
     */
    public synchronized void onFileDropped(Fi file){
        if(file == null || !file.exists()) return;
        if(!isSupported(file)){
            Log.info("[PackLoader] Ignored unsupported file drop: @", file.name());
            return;
        }

        String path = file.absolutePath();
        if(pendingPaths.contains(path)) return; // Deduplicate within the same drop operation

        pendingPaths.add(path);
        pendingDrops.add(file);

        if(!scheduled){
            scheduled = true;
            if(Core.app != null){
                Time.runTask(2f, this::flushPendingDrops);
            }
        }
    }

    /** Immediately flushes all pending dropped files and dispatches them. */
    public synchronized void flushPendingDrops(){
        scheduled = false;
        if(pendingDrops.isEmpty()) return;

        Seq<Fi> files = new Seq<>(pendingDrops);
        pendingDrops.clear();
        pendingPaths.clear();

        dispatchFiles(files);
    }

    /** Dispatches a list of dropped files to the import pipeline. */
    public void dispatchFiles(Seq<Fi> files){
        if(files == null || files.isEmpty() || target == null) return;

        // Determine if all dropped files belong to a specific pack type
        PackType detectedType = null;
        boolean allMaps = true;
        boolean allSchems = true;

        for(Fi f : files){
            if(f.isDirectory() || f.extEquals("zip")){
                allMaps = false;
                allSchems = false;
                break;
            }
            if(!f.extEquals("msav")) allMaps = false;
            if(!f.extEquals("msch")) allSchems = false;
        }

        if(allMaps && !allSchems) detectedType = PackType.MAP;
        else if(allSchems && !allMaps) detectedType = PackType.SCHEMATIC;

        boolean append = target.isInReviewMode();
        PackType dropTargetType = append ? target.getTargetType() : detectedType;

        Log.info("[PackLoader] Processing @ dropped source(s) (append=@, target=@)",
            files.size, append, dropTargetType);

        target.scanAsync(dropTargetType, append, files.toArray(Fi.class));
    }

    public Seq<Fi> getPendingDrops(){
        return pendingDrops;
    }
}

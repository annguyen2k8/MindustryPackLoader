package packloader.ui;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.Pixmap;
import arc.graphics.Texture;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.Threads;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.io.MapIO;
import packloader.core.PackFormat;
import packloader.model.PackEntry;
import packloader.model.PackType;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Manages async preview generation, caching, and lifecycle for Mindustry maps and schematics.
 *
 * <p>Preview generation is non-blocking and lazy: entries are only rendered when requested by the UI.
 * Map pixmaps are generated on background daemon threads, while OpenGL textures are safely created
 * and disposed on the main thread. Textures created internally are tracked separately from Mindustry's
 * game registries to prevent unintended side effects.</p>
 */
public final class PreviewManager{
    private static final ObjectMap<String, Texture> textureCache = new ObjectMap<>();
    private static final ObjectSet<Texture> locallyCreatedTextures = new ObjectSet<>();
    private static final ObjectSet<String> failedPreviews = new ObjectSet<>();
    private static final ObjectSet<String> loadingPreviews = new ObjectSet<>();

    private PreviewManager(){}

    /** Returns a unique cache key for a given entry. */
    public static String entryKey(PackEntry entry){
        if(entry == null) return "";
        if(entry.sha256 != null && !entry.sha256.isEmpty()){
            return entry.sha256;
        }
        return entry.sourcePath + "#" + entry.sanitizedFileName;
    }

    /** Returns true if a decoded texture is currently cached and valid. */
    public static boolean hasTexture(PackEntry entry){
        if(entry == null) return false;
        String key = entryKey(entry);
        Texture tex = textureCache.get(key);
        return tex != null && !tex.isDisposed();
    }

    /** Returns the cached texture if available, or null. */
    public static Texture getCachedTexture(PackEntry entry){
        if(entry == null) return null;
        String key = entryKey(entry);
        Texture tex = textureCache.get(key);
        if(tex != null && !tex.isDisposed()){
            return tex;
        }
        return null;
    }

    /** Returns true if preview generation previously failed for this entry. */
    public static boolean isFailed(PackEntry entry){
        if(entry == null) return true;
        return failedPreviews.contains(entryKey(entry));
    }

    /**
     * Loads a preview texture asynchronously and delivers it to the callback on the main thread.
     * If the texture is already cached, the callback is invoked immediately.
     */
    public static void loadPreviewAsync(PackEntry entry, Cons<Texture> callback){
        if(entry == null || !entry.isValid){
            if(callback != null) callback.get(null);
            return;
        }

        String key = entryKey(entry);
        Texture cached = textureCache.get(key);
        if(cached != null && !cached.isDisposed()){
            if(callback != null) callback.get(cached);
            return;
        }

        if(failedPreviews.contains(key)){
            if(callback != null) callback.get(null);
            return;
        }

        if(entry.type == PackType.SCHEMATIC){
            loadSchematicPreview(entry, key, callback);
        }else if(entry.type == PackType.MAP){
            loadMapPreview(entry, key, callback);
        }else{
            if(callback != null) callback.get(null);
        }
    }

    private static void loadSchematicPreview(PackEntry entry, String key, Cons<Texture> callback){
        try{
            Schematic s = entry.schematic != null ? entry.schematic : entry.getOrReadSchematic();
            if(s != null && Vars.schematics != null){
                Texture tex = Vars.schematics.getPreview(s);
                if(tex != null && !tex.isDisposed()){
                    textureCache.put(key, tex);
                    if(callback != null) callback.get(tex);
                    return;
                }
            }
        }catch(Throwable t){
            Log.debug("[PackLoader] Failed to get schematic preview for @: @", entry.sanitizedFileName, t.getMessage());
            failedPreviews.add(key);
        }
        if(callback != null) callback.get(null);
    }

    private static void loadMapPreview(PackEntry entry, String key, Cons<Texture> callback){
        if(loadingPreviews.contains(key)){
            return;
        }
        loadingPreviews.add(key);

        // Check if matching game map already has an active texture in registry
        try{
            if(Vars.maps != null){
                mindustry.maps.Map existing = Vars.maps.byName(entry.displayName);
                if(existing != null && existing.texture != null && !existing.texture.isDisposed()){
                    textureCache.put(key, existing.texture);
                    loadingPreviews.remove(key);
                    if(callback != null) callback.get(existing.texture);
                    return;
                }
            }
        }catch(Throwable ignored){}

        // Background pixmap generation
        Threads.daemon("MapPreview-" + entry.displayName, () -> {
            Fi tempFi = null;
            Pixmap generatedPixmap = null;
            try{
                tempFi = getOrExtractTempFi(entry);
                PackFormat.MapInfo info = entry.mapInfo != null ? entry.mapInfo : entry.getOrReadMapInfo();
                int w = (info != null && info.width > 0) ? info.width : 100;
                int h = (info != null && info.height > 0) ? info.height : 100;

                mindustry.maps.Map map = new mindustry.maps.Map(tempFi, w, h, new StringMap(), true);
                generatedPixmap = MapIO.generatePreview(map);
            }catch(Throwable t){
                Log.debug("[PackLoader] Failed to generate map preview for @: @", entry.sanitizedFileName, t.getMessage());
            }finally{
                if(tempFi != null && tempFi != entry.sourceFile){
                    try{
                        tempFi.delete();
                    }catch(Throwable ignored){}
                }
            }

            final Pixmap finalPixmap = generatedPixmap;
            Runnable postAction = () -> {
                loadingPreviews.remove(key);
                if(finalPixmap != null){
                    try{
                        if(Core.gl != null){
                            Texture tex = new Texture(finalPixmap);
                            textureCache.put(key, tex);
                            locallyCreatedTextures.add(tex);
                            if(callback != null) callback.get(tex);
                        }else{
                            if(callback != null) callback.get(null);
                        }
                    }catch(Throwable t){
                        Log.debug("[PackLoader] Failed to upload map texture for @: @", entry.sanitizedFileName, t.getMessage());
                        failedPreviews.add(key);
                        if(callback != null) callback.get(null);
                    }finally{
                        try{
                            finalPixmap.dispose();
                        }catch(Throwable ignored){}
                    }
                }else{
                    failedPreviews.add(key);
                    if(callback != null) callback.get(null);
                }
            };

            if(Core.app != null){
                Core.app.post(postAction);
            }else{
                postAction.run();
            }
        });
    }

    private static Fi getOrExtractTempFi(PackEntry entry) throws Exception{
        if(entry.sourceFile != null && entry.sourceFile.exists()){
            return entry.sourceFile;
        }

        Fi temp = Fi.tempFile("pl-map-" + Math.abs(entry.sanitizedFileName.hashCode()));
        temp.file().deleteOnExit();
        try(InputStream in = entry.openStream();
            OutputStream out = temp.write(false, 8192)){
            byte[] buf = new byte[8192];
            int len;
            while((len = in.read(buf)) != -1){
                out.write(buf, 0, len);
            }
        }
        return temp;
    }

    /**
     * Clears all cached preview textures and disposes GPU resources created by PreviewManager.
     * Mindustry's internal registry textures are left intact.
     */
    public static void dispose(){
        for(Texture tex : locallyCreatedTextures){
            if(tex != null && !tex.isDisposed()){
                try{
                    tex.dispose();
                }catch(Throwable ignored){}
            }
        }
        locallyCreatedTextures.clear();
        textureCache.clear();
        failedPreviews.clear();
        loadingPreviews.clear();
    }
}

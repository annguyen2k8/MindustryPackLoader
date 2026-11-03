package packloader.core;

import arc.files.Fi;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.maps.Map;
import packloader.model.PackType;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe index of existing game maps and schematics hashed by SHA-256.
 *
 * Employs metadata-based caching (size and lastModified) to eliminate redundant disk
 * reads across consecutive scans while preventing stale cache if a file is edited.
 *
 * Maps and Schematics are strictly separated to prevent false cross-type matches.
 */
public class ContentHashIndex{

    public static class FileRecord{
        public final String path;
        public final String name;
        public final PackType type;
        public final long lastModified;
        public final long size;
        public final String sha256;

        public FileRecord(String path, String name, PackType type, long lastModified, long size, String sha256){
            this.path = path;
            this.name = name;
            this.type = type;
            this.lastModified = lastModified;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private final java.util.Map<String, FileRecord> mapCache = new ConcurrentHashMap<>();
    private final java.util.Map<String, FileRecord> schemCache = new ConcurrentHashMap<>();

    private final Fi mapDirectory;
    private final Fi schematicDirectory;

    public ContentHashIndex(Fi mapDirectory, Fi schematicDirectory){
        this.mapDirectory = mapDirectory;
        this.schematicDirectory = schematicDirectory;
    }

    public void clear(){
        mapCache.clear();
        schemCache.clear();
    }

    /** Indexes directories and live game registries, refreshing modified files and pruning removed files. */
    public void refresh(){
        indexDirectory(mapDirectory, PackType.MAP);
        indexDirectory(schematicDirectory, PackType.SCHEMATIC);
        indexGameRegistries();
    }

    /** Indexes files in the specified directory for the given pack type. */
    public void indexDirectory(Fi dir, PackType type){
        if(dir == null || !dir.exists() || !dir.isDirectory()) return;

        java.util.Map<String, FileRecord> cache = (type == PackType.MAP) ? mapCache : schemCache;
        String ext = (type == PackType.MAP) ? "msav" : "msch";

        Fi[] list = dir.list();
        if(list == null) return;

        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        for(Fi file : list){
            if(file.isDirectory() || !file.extension().equalsIgnoreCase(ext)) continue;
            seenPaths.add(file.path());
            getOrComputeRecord(file, file.name(), type, cache);
        }

        // Prune cache entries belonging to this directory that no longer exist on disk
        cache.keySet().removeIf(path -> path.startsWith(dir.path()) && !seenPaths.contains(path));
    }

    private void indexGameRegistries(){
        try{
            if(Vars.maps != null && Vars.maps.all() != null){
                for(Map m : Vars.maps.all()){
                    if(m.file != null && m.file.exists()){
                        getOrComputeRecord(m.file, m.name() != null ? m.name() : m.file.name(), PackType.MAP, mapCache);
                    }
                }
            }
        }catch(Throwable ignored){}

        try{
            if(Vars.schematics != null && Vars.schematics.all() != null){
                for(Schematic s : Vars.schematics.all()){
                    if(s.file != null && s.file.exists()){
                        getOrComputeRecord(s.file, s.name() != null ? s.name() : s.file.name(), PackType.SCHEMATIC, schemCache);
                    }
                }
            }
        }catch(Throwable ignored){}
    }

    private FileRecord getOrComputeRecord(Fi file, String displayName, PackType type, java.util.Map<String, FileRecord> cache){
        if(file == null || !file.exists() || file.isDirectory()) return null;

        String key = file.path();
        long currentModified = file.lastModified();
        long currentSize = file.length();

        FileRecord existing = cache.get(key);
        if(existing != null && existing.lastModified == currentModified && existing.size == currentSize){
            return existing; // Valid cached record
        }

        // Modified or new file: compute fresh hash
        String sha256 = ContentHash.computeSha256(file);
        if(sha256.isEmpty()) return null;

        FileRecord record = new FileRecord(key, displayName, type, currentModified, currentSize, sha256);
        cache.put(key, record);
        return record;
    }

    /**
     * Looks up an existing file record with identical content hash within the same pack type.
     * Returns null if no content duplicate exists or if the type differs.
     */
    public FileRecord findMatch(Fi file, PackType type){
        if(file == null || !file.exists() || file.isDirectory()) return null;
        String hash = ContentHash.computeSha256(file);
        return findMatch(hash, type);
    }

    /**
     * Looks up an existing file record with identical content hash within the same pack type.
     * Returns null if no content duplicate exists or if the type differs.
     */
    public FileRecord findMatch(String sha256, PackType type){
        if(sha256 == null || sha256.isEmpty() || type == null) return null;

        java.util.Map<String, FileRecord> cache = (type == PackType.MAP) ? mapCache : schemCache;

        for(FileRecord record : cache.values()){
            if(record.type == type && record.sha256.equalsIgnoreCase(sha256)){
                Fi f = new Fi(record.path);
                if(f.exists()){
                    // Check if file was modified after record was cached
                    if(f.lastModified() == record.lastModified && f.length() == record.size){
                        return record;
                    }else{
                        // File changed, recompute
                        FileRecord updated = getOrComputeRecord(f, record.name, type, cache);
                        if(updated != null && updated.sha256.equalsIgnoreCase(sha256)){
                            return updated;
                        }
                    }
                }else{
                    cache.remove(record.path);
                }
            }
        }
        return null;
    }

    /** Returns all indexed records for a pack type (useful for testing & inspection). */
    public Seq<FileRecord> getRecords(PackType type){
        java.util.Map<String, FileRecord> cache = (type == PackType.MAP) ? mapCache : schemCache;
        Seq<FileRecord> result = new Seq<>();
        for(FileRecord r : cache.values()){
            result.add(r);
        }
        return result;
    }
}

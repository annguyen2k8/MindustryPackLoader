package packloader.util;

import arc.Core;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import packloader.model.PackType;

public final class RecentSources{
    public static final int MAX_RECENT = 6;
    public static final String SETTING_KEY = "packloader.recent-sources";

    public static final class SourceItem{
        public final String path;
        public final String name;
        public final PackType type;
        public final boolean isDirectory;
        public final long lastUsed;

        public SourceItem(String path, String name, PackType type, boolean isDirectory, long lastUsed){
            this.path = path;
            this.name = name;
            this.type = type;
            this.isDirectory = isDirectory;
            this.lastUsed = lastUsed;
        }
    }

    private RecentSources(){}

    public static Seq<SourceItem> load(){
        Seq<SourceItem> list = new Seq<>();
        if(Core.settings == null) return list;

        String raw = Core.settings.getString(SETTING_KEY, "[]");
        if(raw == null || raw.trim().isEmpty()) return list;

        try{
            Jval json = Jval.read(raw);
            if(json != null && json.isArray()){
                for(Jval item : json.asArray()){
                    String path = item.getString("path", "");
                    String name = item.getString("name", "");
                    if(path.isEmpty() || name.isEmpty()) continue;

                    String typeStr = item.getString("type", null);
                    PackType type = null;
                    if(typeStr != null){
                        try{
                            type = PackType.valueOf(typeStr);
                        }catch(Exception ignored){}
                    }

                    boolean isDir = item.getBool("isDir", false);
                    long lastUsed = item.getLong("lastUsed", 0L);
                    list.add(new SourceItem(path, name, type, isDir, lastUsed));
                }
            }
        }catch(Throwable t){
            Log.warn("[PackLoader] Failed to read recent sources: @", t.getMessage());
        }
        return list;
    }

    public static void save(Seq<SourceItem> list){
        if(Core.settings == null) return;
        try{
            Jval arr = Jval.newArray();
            if(list != null){
                for(SourceItem item : list){
                    Jval obj = Jval.newObject();
                    obj.put("path", item.path);
                    obj.put("name", item.name);
                    if(item.type != null) obj.put("type", item.type.name());
                    obj.put("isDir", item.isDirectory);
                    obj.put("lastUsed", item.lastUsed);
                    arr.add(obj);
                }
            }
            Core.settings.put(SETTING_KEY, arr.toString());
        }catch(Throwable t){
            Log.warn("[PackLoader] Failed to save recent sources: @", t.getMessage());
        }
    }

    public static void add(String path, String name, PackType type, boolean isDirectory){
        if(path == null || path.trim().isEmpty() || name == null || name.trim().isEmpty()) return;
        Seq<SourceItem> items = load();

        // Remove existing entry for the same path
        items.removeAll(it -> it.path.equalsIgnoreCase(path));

        // Add to front
        items.insert(0, new SourceItem(path, name, type, isDirectory, System.currentTimeMillis()));

        // Trim
        while(items.size > MAX_RECENT){
            items.pop();
        }

        save(items);
    }

    public static void remove(String path){
        if(path == null || path.trim().isEmpty()) return;
        Seq<SourceItem> items = load();
        int initial = items.size;
        items.removeAll(it -> it.path.equalsIgnoreCase(path));
        if(items.size != initial){
            save(items);
        }
    }

    public static void clear(){
        save(new Seq<>());
    }

    public static Seq<SourceItem> getForType(PackType targetType){
        Seq<SourceItem> all = load();
        if(targetType == null) return all;
        return all.select(it -> it.type == null || it.type == targetType);
    }
}

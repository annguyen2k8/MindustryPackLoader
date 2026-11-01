package packloader;

import arc.files.Fi;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Strings;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.maps.Map;
import mindustry.maps.Maps;
import packloader.core.PackFormat;

import java.io.IOException;

/**
 * Minimal game-registry doubles for native map/schematic import tests. They mirror the
 * relevant real behavior: {@code Maps.importMap} derives the destination file from the
 * staged file name, and {@code Schematics.add} writes a file before setting {@code s.file}.
 */
public final class FakeGame{
    private FakeGame(){}

    public static final class FakeMaps extends Maps{
        public final Fi directory;
        public final Seq<Map> all = new Seq<>();
        public int importCalls;
        public boolean failImport;
        public boolean failNextRemove;

        public FakeMaps(Fi directory){
            this.directory = directory;
        }

        @Override
        public Seq<Map> all(){
            return all;
        }

        @Override
        public Map importMap(Fi file) throws IOException{
            importCalls++;
            if(failImport){
                throw new IOException("simulated map write failure");
            }

            Fi destination = uniqueFile(directory, file.name(), "msav");
            file.copyTo(destination);

            PackFormat.MapInfo info = PackFormat.validateMap(destination.read());
            StringMap tags = new StringMap();
            tags.put("name", info.name);
            Map map = new Map(destination, info.width, info.height, tags, true);
            all.add(map);
            return map;
        }

        @Override
        public void removeMap(Map map){
            all.remove(map, true);
            if(failNextRemove){
                failNextRemove = false;
                return;
            }
            if(map.file != null && map.file.exists()){
                map.file.delete();
            }
        }

        @Override
        public void loadPreviews(){
            // No GL context in unit tests.
        }
    }

    public static final class FakeSchematics extends Schematics{
        public final Fi directory;
        public final Seq<Schematic> all = new Seq<>();
        /** When true, mimic Schematics.add swallowing a write failure. */
        public boolean failWrites;
        /** When set, the "write" goes outside the schematic directory. */
        public Fi outsideFile;
        /** Mirrors the loadout cache updated by Schematics.add/remove. */
        public final Seq<Schematic> loadoutCache = new Seq<>();
        public boolean failNextRemove;

        public FakeSchematics(Fi directory){
            this.directory = directory;
        }

        @Override
        public Seq<Schematic> all(){
            return all;
        }

        @Override
        public void add(Schematic schematic){
            all.add(schematic);
            loadoutCache.add(schematic);
            if(failWrites){
                return;
            }

            Fi destination = outsideFile != null ? outsideFile :
                uniqueFile(directory, Strings.sanitizeFilename(schematic.name()), "msch");
            try{
                Schematics.write(schematic, destination);
                schematic.file = destination;
            }catch(IOException e){
                throw new RuntimeException(e);
            }
        }

        @Override
        public void remove(Schematic schematic){
            all.remove(schematic, true);
            loadoutCache.remove(schematic, true);
            if(failNextRemove){
                failNextRemove = false;
                return;
            }
            if(schematic.file != null){
                if(schematic.file.exists()){
                    schematic.file.delete();
                }
                schematic.file = null;
            }
        }
    }

    static Fi uniqueFile(Fi directory, String baseName, String extension){
        String clean = Strings.sanitizeFilename(baseName == null ? "" : baseName);
        String suffix = "." + extension;
        if(clean.toLowerCase().endsWith(suffix)){
            clean = clean.substring(0, clean.length() - suffix.length());
        }
        if(clean.isEmpty()) clean = "unnamed";

        Fi target = directory.child(clean + suffix);
        int index = 1;
        while(target.exists()){
            target = directory.child(clean + "_" + index + suffix);
            index++;
        }
        return target;
    }
}

package packloader.core;

import mindustry.Vars;
import mindustry.ctype.ContentType;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.io.SaveFileReader;
import mindustry.io.SaveIO;
import mindustry.world.Block;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Structural validation and metadata helpers for Mindustry map and schematic files.
 *
 * <p>The game's own readers are deliberately lenient in a few places: {@code MapIO.createMap}
 * only reads the metadata chunk, and schematic validation used to stop after the four-byte
 * header. PackLoader uses this class to reject truncated or malformed files before they are
 * written into the game directories.</p>
 *
 * <p>Map validation parses every save region in the order used by the save version. In
 * particular, the map chunk is walked using the version-specific tile layout instead of only
 * checking its declared width and height. The entity region is also walked so a truncated
 * entity chunk cannot hide behind a valid map prefix.</p>
 */
public final class PackFormat{
    public static final long MAX_MAP_RAW_BYTES = 100L * 1024 * 1024;
    public static final long MAX_MAP_INFLATED_BYTES = 512L * 1024 * 1024;
    public static final int MAX_MAP_CHUNK_BYTES = 256 * 1024 * 1024;
    public static final int MAX_META_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CONTENT_BYTES = 64 * 1024 * 1024;
    public static final long MAX_SCHEMATIC_RAW_BYTES = 10L * 1024 * 1024;
    public static final long MAX_SCHEMATIC_INFLATED_BYTES = 64L * 1024 * 1024;
    public static final int MAX_MAP_DIMENSION = 4096;
    public static final long MAX_MAP_TILES = 4L * 1024 * 1024;
    public static final int MAX_META_TAGS = 100_000;
    public static final int MAX_CONTENT_ENTRIES = 32767;
    public static final int MAX_ENTITY_CHUNKS = 4 * 1024 * 1024;
    /** TypeIO's array cap for non-safe reads, which is what maps and schematics use. */
    public static final int MAX_TYPE_OBJECT_ARRAY = 200;
    public static final int MAX_TYPE_OBJECT_BYTE_ARRAY = 40_000;

    private PackFormat(){}

    public static final class MapInfo{
        public final String name;
        public final int width;
        public final int height;
        public final int version;
        public final boolean hasNameTag;
        public final String author;
        public final String description;
        public final Map<String, String> tags;

        public MapInfo(String name, int width, int height, int version){
            this(name, width, height, version, true, "", "", null);
        }

        public MapInfo(String name, int width, int height, int version, boolean hasNameTag){
            this(name, width, height, version, hasNameTag, "", "", null);
        }

        public MapInfo(String name, int width, int height, int version, boolean hasNameTag, String author, String description, Map<String, String> tags){
            this.name = name;
            this.width = width;
            this.height = height;
            this.version = version;
            this.hasNameTag = hasNameTag;
            this.author = author != null ? author : "";
            this.description = description != null ? description : "";
            this.tags = tags != null ? tags : java.util.Collections.emptyMap();
        }
    }

    /** Reads and validates a map, returning its metadata. The input stream must be closed by the caller. */
    public static MapInfo validateMap(InputStream raw) throws IOException{
        return validateMapInternal(raw);
    }

    /** Reads map metadata without rewriting the file. */
    public static MapInfo readMap(InputStream raw) throws IOException{
        return validateMapInternal(raw);
    }

    /**
     * Rewrites the metadata name of a map into {@code output}. The rest of the chunks are copied
     * verbatim. The input map is not validated here; call {@link #validateMap(InputStream)} first.
     */
    public static void renameMap(InputStream raw, OutputStream output, String newName) throws IOException{
        if(newName == null || newName.trim().isEmpty()){
            throw new IOException("Map name cannot be empty");
        }

        try(InputStream limited = new LimitedInputStream(raw, MAX_MAP_RAW_BYTES);
            InflaterInputStream inflated = new InflaterInputStream(limited);
            InputStream bounded = new LimitedInputStream(inflated, MAX_MAP_INFLATED_BYTES);
            DataInputStream in = new DataInputStream(bounded);
            DeflaterOutputStream deflater = new DeflaterOutputStream(output)){
            DataOutputStream out = new DataOutputStream(deflater);
            byte[] header = new byte[4];
            in.readFully(header);
            if(header[0] != 'M' || header[1] != 'S' || header[2] != 'A' || header[3] != 'V'){
                throw new IOException("Invalid map header (missing MSAV)");
            }
            int version = in.readInt();
            if(SaveIO.getSaveWriter(version) == null){
                throw new IOException("Unknown save version: " + version);
            }
            out.write(header);
            out.writeInt(version);

            int metaLength = readChunkLength(in);
            if(metaLength < 0) throw new IOException("Map is missing its metadata chunk");
            if(metaLength > MAX_META_BYTES) throw new IOException("Map metadata chunk is too large");
            LinkedHashMap<String, String> tags = parseMeta(readPayload(in, metaLength));
            tags.put("name", newName);
            if(tags.containsKey("mapname")){
                tags.put("mapname", newName);
            }
            byte[] meta = serializeMeta(tags);
            out.writeInt(meta.length);
            out.write(meta);

            while(true){
                int length = readChunkLengthOrEof(in);
                if(length == -1) break;
                out.writeInt(length);
                copyBytes(in, out, length);
            }
            out.flush();
        }
    }

    private static MapInfo validateMapInternal(InputStream raw) throws IOException{
        try(InputStream limited = new LimitedInputStream(raw, MAX_MAP_RAW_BYTES);
            TrackedInflaterInputStream inflated = new TrackedInflaterInputStream(limited);
            InputStream bounded = new LimitedInputStream(inflated, MAX_MAP_INFLATED_BYTES);
            DataInputStream in = new DataInputStream(bounded)){
            byte[] header = new byte[4];
            in.readFully(header);
            if(header[0] != 'M' || header[1] != 'S' || header[2] != 'A' || header[3] != 'V'){
                throw new IOException("Invalid map header (missing MSAV)");
            }

            int version = in.readInt();
            if(SaveIO.getSaveWriter(version) == null){
                throw new IOException("Unknown save version: " + version);
            }

            int metaLength = readChunkLength(in);
            if(metaLength > MAX_META_BYTES) throw new IOException("Map metadata chunk is too large");
            LinkedHashMap<String, String> tags = parseMeta(readPayload(in, metaLength));

            String rawName = tags.get("name");
            boolean hasNameTag = rawName != null && !rawName.trim().isEmpty();
            String name = hasNameTag ? rawName : tags.get("mapname");
            if(name == null || name.trim().isEmpty()){
                throw new IOException("Map has no name in its metadata");
            }

            String author = tags.get("author");
            String description = tags.get("description");

            int metaWidth = parseOptionalInt(tags.get("width"), "width");
            int metaHeight = parseOptionalInt(tags.get("height"), "height");
            if(metaWidth < 0 || metaHeight < 0){
                throw new IOException("Map has invalid dimensions in its metadata");
            }

            if(version >= 12){
                skipChunk(in, "patches");
            }

            int contentLength = readChunkLength(in);
            if(contentLength > MAX_CONTENT_BYTES) throw new IOException("Map content chunk is too large");
            ContentNames content;
            try(ChunkInputStream chunk = chunk(in, contentLength)){
                content = parseContentHeader(new DataInputStream(chunk));
                requireFullyConsumed(chunk, "content");
            }

            if(version == 11){
                skipChunk(in, "patches");
            }

            int mapLength = readChunkLength(in);
            int width;
            int height;
            try(ChunkInputStream chunk = chunk(in, mapLength)){
                DataInputStream mapStream = new DataInputStream(chunk);
                width = mapStream.readUnsignedShort();
                height = mapStream.readUnsignedShort();
                if(width <= 0 || height <= 0 || width > MAX_MAP_DIMENSION || height > MAX_MAP_DIMENSION ||
                   (long)width * height > MAX_MAP_TILES){
                    throw new IOException("Map has invalid dimensions: " + width + "x" + height);
                }
                if(metaWidth > 0 && metaWidth != width){
                    throw new IOException("Map metadata width does not match the map chunk");
                }
                if(metaHeight > 0 && metaHeight != height){
                    throw new IOException("Map metadata height does not match the map chunk");
                }

                parseMapTiles(mapStream, chunk, version, width, height, content);
                requireFullyConsumed(chunk, "map");
            }

            // Entities are structured and must consume their region exactly.
            int entityLength = readChunkLength(in);
            try(ChunkInputStream chunk = chunk(in, entityLength)){
                parseEntities(new DataInputStream(chunk), chunk, version);
                requireFullyConsumed(chunk, "entities");
            }

            // v7 adds the custom region, v8 adds markers. Markers and custom chunks are
            // opaque JSON/custom payloads; their declared lengths are still bounded.
            if(version == 7){
                skipChunk(in, "custom");
            }else if(version >= 8){
                skipChunk(in, "markers");
                skipChunk(in, "custom");
            }

            // The game stops reading after the custom chunk. Anything after it is not part
            // of the map format and must not be silently accepted.
            if(readChunkLengthOrEof(in) != -1){
                throw new IOException("Map contains unexpected trailing chunk data");
            }

            // Also reject raw bytes appended after the deflate stream. Without this, a
            // truncated or corrupted archive entry could carry arbitrary junk unnoticed.
            if(inflated.bufferedInputRemaining() != 0 || limited.read() != -1){
                throw new IOException("Map contains trailing compressed data");
            }

            return new MapInfo(name, width, height, version, hasNameTag, author, description, tags);
        }catch(IOException e){
            throw e;
        }catch(RuntimeException e){
            throw new IOException("Malformed map: " + safeMessage(e), e);
        }
    }

    private static ContentNames parseContentHeader(DataInputStream in) throws IOException{
        int mapped = in.readUnsignedByte();
        if(mapped > ContentType.all.length){
            throw new IOException("Invalid content type count in map");
        }

        String[][] names = new String[ContentType.all.length][];
        for(int i = 0; i < mapped; i++){
            int type = in.readUnsignedByte();
            if(type >= ContentType.all.length){
                throw new IOException("Invalid content type in map");
            }
            int total = in.readUnsignedShort();
            if(total > MAX_CONTENT_ENTRIES){
                throw new IOException("Invalid content entry count in map");
            }
            String[] values = new String[total];
            for(int j = 0; j < total; j++){
                values[j] = in.readUTF();
            }
            if(names[type] != null){
                throw new IOException("Duplicate content type in map");
            }
            names[type] = values;
        }
        return new ContentNames(names);
    }

    private static void parseMapTiles(DataInputStream in, ChunkInputStream chunk, int version,
                                      int width, int height, ContentNames content) throws IOException{
        int tiles = width * height;
        for(int i = 0; i < tiles; i++){
            in.readShort(); // floor
            in.readShort(); // overlay/ore
            int consecutives = in.readUnsignedByte();
            if(consecutives > tiles - i - 1){
                throw new IOException("Map tile run exceeds the map dimensions");
            }
            i += consecutives;
        }

        if(version <= 3){
            parseLegacyBlocks(in, chunk, tiles, content);
        }else if(version <= 9){
            parseShortChunkBlocks(in, chunk, tiles);
        }else{
            parseModernBlocks(in, chunk, tiles);
        }
    }

    private static void parseLegacyBlocks(DataInputStream in, ChunkInputStream chunk, int tiles,
                                          ContentNames content) throws IOException{
        for(int i = 0; i < tiles; i++){
            int blockId = in.readShort();
            if(content.isBuilding(blockId)){
                skipLengthPrefixedChunk(in, chunk, 2, "tile entity");
            }else{
                int consecutives = in.readUnsignedByte();
                if(consecutives > tiles - i - 1){
                    throw new IOException("Map block run exceeds the map dimensions");
                }
                i += consecutives;
            }
        }
    }

    private static void parseShortChunkBlocks(DataInputStream in, ChunkInputStream chunk, int tiles) throws IOException{
        for(int i = 0; i < tiles; i++){
            in.readShort(); // block
            int packed = in.readUnsignedByte();
            boolean hadEntity = (packed & 1) != 0;
            boolean hadDataOld = (packed & 2) != 0;
            boolean hadDataNew = (packed & 4) != 0;

            if(hadDataNew){
                in.readByte();
                in.readByte();
                in.readByte();
                in.readInt();
            }

            boolean center = true;
            if(hadEntity){
                center = in.readBoolean();
            }

            if(hadEntity){
                if(center){
                    skipLengthPrefixedChunk(in, chunk, 2, "tile entity");
                }
            }else if(hadDataOld || hadDataNew){
                if(hadDataOld){
                    in.readByte();
                }
            }else{
                int consecutives = in.readUnsignedByte();
                if(consecutives > tiles - i - 1){
                    throw new IOException("Map block run exceeds the map dimensions");
                }
                i += consecutives;
            }
        }
    }

    private static void parseModernBlocks(DataInputStream in, ChunkInputStream chunk, int tiles) throws IOException{
        for(int i = 0; i < tiles; i++){
            in.readShort(); // block
            int packed = in.readUnsignedByte();
            boolean hadEntity = (packed & 1) != 0;
            boolean hadData = (packed & 4) != 0;

            if(hadData){
                in.readByte();
                in.readByte();
                in.readByte();
                in.readInt();
            }

            boolean center = true;
            if(hadEntity){
                center = in.readBoolean();
            }

            if(hadEntity){
                if(center){
                    skipLengthPrefixedChunk(in, chunk, 4, "tile entity");
                }
            }else if(!hadData){
                int consecutives = in.readUnsignedByte();
                if(consecutives > tiles - i - 1){
                    throw new IOException("Map block run exceeds the map dimensions");
                }
                i += consecutives;
            }
        }
    }

    private static void parseEntities(DataInputStream in, ChunkInputStream chunk, int version) throws IOException{
        if(version <= 2){
            parseLegacyEntityGroups(in, chunk);
        }else if(version == 3){
            parseLegacyTeamBlocks(in, chunk);
            parseLegacyEntityGroups(in, chunk);
        }else if(version == 4){
            parseModernTeamBlocks(in, chunk);
            parseShortWorldEntities(in, chunk);
        }else if(version == 5){
            parseEntityMapping(in, chunk);
            parseModernTeamBlocks(in, chunk);
            parseShortWorldEntities(in, chunk);
        }else if(version <= 9){
            parseEntityMapping(in, chunk);
            parseModernTeamBlocks(in, chunk);
            parseShortWorldEntities(in, chunk);
        }else{
            parseEntityMapping(in, chunk);
            parseModernTeamBlocks(in, chunk);
            parseModernWorldEntities(in, chunk);
        }
    }

    private static void parseEntityMapping(DataInputStream in, ChunkInputStream chunk) throws IOException{
        int amount = in.readUnsignedShort();
        if(amount > chunk.remaining() / 3){
            throw new IOException("Entity mapping is truncated");
        }
        for(int i = 0; i < amount; i++){
            in.readShort();
            in.readUTF();
        }
    }

    private static void parseLegacyTeamBlocks(DataInputStream in, ChunkInputStream chunk) throws IOException{
        int teams = in.readInt();
        if(teams < 0 || teams > MAX_ENTITY_CHUNKS || teams > chunk.remaining() / 8){
            throw new IOException("Invalid team count in map entities");
        }
        for(int i = 0; i < teams; i++){
            in.readInt();
            int blocks = in.readInt();
            if(blocks < 0 || blocks > MAX_ENTITY_CHUNKS || blocks > chunk.remaining() / 12){
                throw new IOException("Invalid block plan count in map entities");
            }
            skipBytes(in, (long)blocks * 12);
        }
    }

    private static void parseModernTeamBlocks(DataInputStream in, ChunkInputStream chunk) throws IOException{
        int teams = in.readInt();
        if(teams < 0 || teams > MAX_ENTITY_CHUNKS || teams > chunk.remaining() / 9){
            throw new IOException("Invalid team count in map entities");
        }
        for(int i = 0; i < teams; i++){
            in.readInt();
            int blocks = in.readInt();
            // A plan is four shorts plus a TypeIO object; null is the smallest object at
            // one byte, so a dense plan list can be as small as nine bytes per entry.
            if(blocks < 0 || blocks > MAX_ENTITY_CHUNKS || blocks > chunk.remaining() / 9){
                throw new IOException("Invalid block plan count in map entities");
            }
            for(int j = 0; j < blocks; j++){
                in.readShort();
                in.readShort();
                in.readShort();
                in.readShort();
                skipTypeObject(in, chunk, 0, true);
            }
        }
    }

    private static void parseLegacyEntityGroups(DataInputStream in, ChunkInputStream chunk) throws IOException{
        int groups = in.readUnsignedByte();
        for(int i = 0; i < groups; i++){
            int amount = in.readInt();
            if(amount < 0 || amount > MAX_ENTITY_CHUNKS || amount > chunk.remaining() / 2){
                throw new IOException("Invalid entity count in map");
            }
            for(int j = 0; j < amount; j++){
                skipLengthPrefixedChunk(in, chunk, 2, "world entity");
            }
        }
    }

    private static void parseShortWorldEntities(DataInputStream in, ChunkInputStream chunk) throws IOException{
        int amount = in.readInt();
        if(amount < 0 || amount > MAX_ENTITY_CHUNKS || amount > chunk.remaining() / 2){
            throw new IOException("Invalid entity count in map");
        }
        for(int i = 0; i < amount; i++){
            skipLengthPrefixedChunk(in, chunk, 2, "world entity");
        }
    }

    private static void parseModernWorldEntities(DataInputStream in, ChunkInputStream chunk) throws IOException{
        int amount = in.readInt();
        if(amount < 0 || amount > MAX_ENTITY_CHUNKS || amount > chunk.remaining() / 4){
            throw new IOException("Invalid entity count in map");
        }
        for(int i = 0; i < amount; i++){
            skipLengthPrefixedChunk(in, chunk, 4, "world entity");
        }
    }

    /** Skips one of the {type, id, payload} chunks in the entity or tile data regions. */
    private static void skipLengthPrefixedChunk(DataInputStream in, ChunkInputStream parent,
                                                int lengthBytes, String name) throws IOException{
        int length;
        if(lengthBytes == 2){
            length = in.readUnsignedShort();
        }else{
            length = in.readInt();
        }
        if(length < 0 || length > parent.remaining()){
            throw new IOException("Invalid " + name + " length in map");
        }
        skipBytes(in, length);
    }

    /**
     * Skips a TypeIO object. This mirrors the object tags used by Mindustry's save version.
     * Unknown or impossible tags are rejected instead of guessing at the payload length.
     */
    private static void skipTypeObject(DataInputStream in, ChunkInputStream chunk, int depth,
                                       boolean allowArrays) throws IOException{
        if(depth > 16){
            throw new IOException("TypeIO object nesting is too deep");
        }
        int type = in.readUnsignedByte();
        switch(type){
            case 0, 10 -> {
                if(type == 10) in.readBoolean();
            }
            case 1, 3, 12, 17 -> skipBytes(in, 4);
            case 2, 7, 11, 19 -> skipBytes(in, 8);
            case 4 -> {
                int exists = in.readUnsignedByte();
                if(exists != 0){
                    int length = in.readUnsignedShort();
                    if(length > chunk.remaining()) throw new IOException("TypeIO string is truncated");
                    skipBytes(in, length);
                }
            }
            case 5, 9 -> skipBytes(in, 3);
            case 6 -> {
                requireArrays(allowArrays);
                skipArray(in, chunk, in.readShort(), 4, MAX_TYPE_OBJECT_ARRAY, "int sequence");
            }
            case 8 -> {
                requireArrays(allowArrays);
                skipArray(in, chunk, in.readUnsignedByte(), 4, 0xFF, "point array");
            }
            case 13, 23 -> skipBytes(in, 2);
            case 14 -> {
                requireArrays(allowArrays);
                skipArray(in, chunk, in.readInt(), 1, MAX_TYPE_OBJECT_BYTE_ARRAY, "byte array");
            }
            case 15 -> skipBytes(in, 1);
            case 16 -> {
                requireArrays(allowArrays);
                skipArray(in, chunk, in.readInt(), 1, MAX_TYPE_OBJECT_ARRAY, "boolean array");
            }
            case 18 -> {
                requireArrays(allowArrays);
                skipArray(in, chunk, in.readShort(), 8, MAX_TYPE_OBJECT_ARRAY, "vector array");
            }
            case 20 -> skipBytes(in, 1);
            case 21 -> skipArray(in, chunk, in.readShort(), 4, Short.MAX_VALUE, "integer array");
            case 22 -> {
                requireArrays(allowArrays);
                int length = in.readInt();
                if(length < 0 || length > MAX_TYPE_OBJECT_ARRAY){
                    throw new IOException("Invalid TypeIO object array length");
                }
                for(int i = 0; i < length; i++){
                    skipTypeObject(in, chunk, depth + 1, false);
                }
            }
            default -> throw new IOException("Unknown TypeIO object type: " + type);
        }
    }

    private static void requireArrays(boolean allowArrays) throws IOException{
        if(!allowArrays) throw new IOException("Nested TypeIO arrays are not allowed");
    }

    private static void skipArray(DataInputStream in, ChunkInputStream chunk, int length, int bytesPerItem,
                                  int maxLength, String name) throws IOException{
        if(length < 0 || length > maxLength){
            throw new IOException("Invalid TypeIO " + name + " length: " + length);
        }
        if((long)length * bytesPerItem > chunk.remaining()){
            throw new IOException("Invalid TypeIO " + name + " length");
        }
        skipBytes(in, (long)length * bytesPerItem);
    }

    private static LinkedHashMap<String, String> parseMeta(byte[] data) throws IOException{
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int count = in.readShort();
        if(count < 0 || count > MAX_META_TAGS){
            throw new IOException("Invalid metadata tag count in map");
        }
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for(int i = 0; i < count; i++){
            String key = in.readUTF();
            String value = in.readUTF();
            map.put(key, value);
        }
        if(in.available() != 0){
            throw new IOException("Map metadata contains trailing data");
        }
        return map;
    }

    private static byte[] serializeMeta(Map<String, String> tags) throws IOException{
        if(tags.size() > Short.MAX_VALUE){
            throw new IOException("Too many map metadata tags");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(bytes)){
            out.writeShort(tags.size());
            for(Map.Entry<String, String> entry : tags.entrySet()){
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return bytes.toByteArray();
    }

    private static int parseOptionalInt(String value, String name) throws IOException{
        if(value == null || value.trim().isEmpty()) return 0;
        try{
            return Integer.parseInt(value.trim());
        }catch(NumberFormatException e){
            throw new IOException("Map metadata " + name + " is not a number");
        }
    }

    private static void skipChunk(DataInputStream in, String name) throws IOException{
        int length = readChunkLength(in);
        skipBytes(in, length);
    }

    private static int readChunkLengthOrEof(DataInputStream in) throws IOException{
        int first = in.read();
        if(first == -1) return -1;
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if(b2 == -1 || b3 == -1 || b4 == -1){
            throw new IOException("Truncated map chunk length");
        }
        int length = (first << 24) | (b2 << 16) | (b3 << 8) | b4;
        validateChunkLength(length);
        return length;
    }

    private static int readChunkLength(DataInputStream in) throws IOException{
        int length;
        try{
            length = in.readInt();
        }catch(EOFException e){
            throw new IOException("Truncated map chunk length", e);
        }
        validateChunkLength(length);
        return length;
    }

    private static void validateChunkLength(int length) throws IOException{
        if(length < 0){
            throw new IOException("Invalid negative map chunk length");
        }
        if(length > MAX_MAP_CHUNK_BYTES){
            throw new IOException("Map chunk is too large");
        }
    }

    private static ChunkInputStream chunk(DataInputStream in, int length) throws IOException{
        if(length < 0 || length > MAX_MAP_CHUNK_BYTES){
            throw new IOException("Invalid map chunk length");
        }
        return new ChunkInputStream(in, length);
    }

    private static void requireFullyConsumed(ChunkInputStream chunk, String name) throws IOException{
        if(chunk.remaining() != 0){
            throw new IOException("Map " + name + " chunk has " + chunk.remaining() + " trailing byte(s)");
        }
    }

    private static byte[] readPayload(DataInputStream in, int length) throws IOException{
        if(length < 0 || length > MAX_MAP_CHUNK_BYTES){
            throw new IOException("Invalid map chunk length");
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    private static void skipBytes(DataInputStream in, long length) throws IOException{
        if(length < 0) throw new IOException("Invalid map chunk length");
        byte[] buffer = new byte[Math.min(8192, Math.max(1, (int)Math.min(Integer.MAX_VALUE, length)))];
        long remaining = length;
        while(remaining > 0){
            int read = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
            if(read < 0) throw new IOException("Truncated map chunk payload");
            remaining -= read;
        }
    }

    private static void copyBytes(DataInputStream in, DataOutputStream out, int length) throws IOException{
        if(length < 0) throw new IOException("Invalid map chunk length");
        byte[] buffer = new byte[Math.min(8192, Math.max(1, length))];
        int remaining = length;
        while(remaining > 0){
            int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if(read < 0) throw new IOException("Truncated map chunk payload");
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static String safeMessage(Throwable t){
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? t.getClass().getSimpleName() : message;
    }

    /**
     * Reads and fully validates a schematic. The game parser is invoked only after a bounded
     * decompression pass, so a compact deflate bomb cannot allocate an unbounded payload.
     */
    public static Schematic readSchematic(InputStream raw) throws IOException{
        byte[] bytes = readAllLimited(raw, MAX_SCHEMATIC_RAW_BYTES);
        checkSchematicStructure(bytes);
        try{
            return Schematics.read(new ByteArrayInputStream(bytes));
        }catch(IOException e){
            throw e;
        }catch(RuntimeException e){
            throw new IOException("Malformed schematic: " + safeMessage(e), e);
        }
    }

    /** Reads a schematic from a local file. */
    public static Schematic readSchematic(File file) throws IOException{
        try(InputStream in = new FileInputStream(file)){
            return readSchematic(in);
        }
    }

    /**
     * Decompresses a schematic with a hard bound and walks every field the game reads.
     * The game parser ignores trailing decompressed bytes, so a structurally invalid file
     * could otherwise be accepted as long as its prefix looked valid.
     */
    private static void checkSchematicStructure(byte[] bytes) throws IOException{
        DataInputStream header = new DataInputStream(new ByteArrayInputStream(bytes));
        byte[] magic = new byte[4];
        int read = header.read(magic);
        if(read < 4 || magic[0] != 'm' || magic[1] != 's' || magic[2] != 'c' || magic[3] != 'h'){
            throw new IOException("Not a schematic file (missing header).");
        }
        int version = header.read();
        if(version < 0 || version > 1){
            throw new IOException("Unknown schematic version: " + version);
        }

        byte[] data;
        data = inflateExact(bytes, 5, bytes.length - 5, MAX_SCHEMATIC_INFLATED_BYTES);

        ChunkInputStream chunk = new ChunkInputStream(new ByteArrayInputStream(data), data.length);
        DataInputStream in = new DataInputStream(chunk);

        int width = in.readShort();
        int height = in.readShort();
        if(width < 0 || height < 0 || width > 128 || height > 128){
            throw new IOException("Invalid schematic dimensions: " + width + "x" + height);
        }

        int tags = in.readUnsignedByte();
        for(int i = 0; i < tags; i++){
            skipUtf(in, chunk, "schematic tag name");
            skipUtf(in, chunk, "schematic tag value");
        }

        int blockCount = in.readUnsignedByte();
        for(int i = 0; i < blockCount; i++){
            skipUtf(in, chunk, "schematic block name");
        }

        int total = in.readInt();
        if(total < 0 || total > 128 * 128){
            throw new IOException("Invalid schematic tile count: " + total);
        }

        for(int i = 0; i < total; i++){
            int block = in.readByte();
            if(block < 0 || block >= blockCount){
                throw new IOException("Schematic tile references an invalid block index");
            }
            in.readInt(); // packed position
            if(version == 0){
                skipBytes(in, 4); // legacy integer config
            }else{
                skipTypeObject(in, chunk, 0, true);
            }
            in.readByte(); // rotation
        }

        if(chunk.remaining() != 0){
            throw new IOException("Schematic contains " + chunk.remaining() + " trailing byte(s)");
        }
    }

    private static void skipUtf(DataInputStream in, ChunkInputStream chunk, String name) throws IOException{
        int length = in.readUnsignedShort();
        if(length > chunk.remaining()){
            throw new IOException(name + " is truncated");
        }
        skipBytes(in, length);
    }

    /**
     * Decompresses a complete deflate stream and rejects unused compressed input. The game
     * reader stops at the end of the stream, so appended junk would otherwise be ignored.
     */
    private static byte[] inflateExact(byte[] input, int offset, int length, long limit) throws IOException{
        if(length <= 0){
            throw new IOException("Schematic is truncated: missing compressed data");
        }

        Inflater inflater = new Inflater();
        try{
            inflater.setInput(input, offset, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];

            while(!inflater.finished()){
                int read;
                try{
                    read = inflater.inflate(buffer);
                }catch(DataFormatException e){
                    throw new IOException("Malformed schematic: invalid compressed data", e);
                }

                if(read > 0){
                    out.write(buffer, 0, read);
                    if(out.size() > limit){
                        throw new IOException("Decompressed data exceeds the safe limit");
                    }
                }else if(inflater.needsDictionary()){
                    throw new IOException("Malformed schematic: missing compression dictionary");
                }else if(inflater.needsInput()){
                    throw new IOException("Schematic is truncated: incomplete compressed data");
                }else{
                    throw new IOException("Malformed schematic: compressed data made no progress");
                }
            }

            if(inflater.getRemaining() != 0){
                throw new IOException("Schematic contains trailing compressed data");
            }
            return out.toByteArray();
        }finally{
            inflater.end();
        }
    }

    private static byte[] readAllLimited(InputStream raw, long limit) throws IOException{
        try(LimitedInputStream in = new LimitedInputStream(raw, limit);
            ByteArrayOutputStream out = new ByteArrayOutputStream()){
            byte[] buffer = new byte[8192];
            int read;
            while((read = in.read(buffer)) != -1){
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static final class ContentNames{
        private final String[][] names;

        ContentNames(String[][] names){
            this.names = names;
        }

        boolean isBuilding(int blockId){
            if(blockId < 0) return false;
            int type = ContentType.block.ordinal();
            if(type < 0 || type >= names.length || names[type] == null || blockId >= names[type].length){
                return false;
            }

            String name = names[type][blockId];
            try{
                if(Vars.content == null) return false;
                String resolved = SaveFileReader.mapFallback(name);
                Block block = Vars.content.block(resolved);
                if(block == null && !name.equals(resolved)){
                    block = Vars.content.block(name);
                }
                return block != null && block.hasBuilding();
            }catch(Throwable ignored){
                return false;
            }
        }
    }

    /**
     * A bounded view over the remaining bytes of one save chunk. Closing it does not close the
     * enclosing stream, so the caller can continue with the next region.
     */
    private static final class ChunkInputStream extends FilterInputStream{
        private int remaining;

        ChunkInputStream(InputStream in, int length){
            super(in);
            this.remaining = length;
        }

        int remaining(){
            return remaining;
        }

        @Override
        public int read() throws IOException{
            if(remaining <= 0) return -1;
            int value = super.read();
            if(value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException{
            if(remaining <= 0) return -1;
            int read = super.read(b, off, Math.min(len, remaining));
            if(read > 0) remaining -= read;
            return read;
        }

        @Override
        public long skip(long n) throws IOException{
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= (int)skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException{
            return Math.min(super.available(), remaining);
        }

        @Override
        public void close(){
            // The parent stream is shared by all regions.
        }
    }

    /** Exposes compressed bytes buffered inside the inflater so trailing junk can be detected. */
    private static final class TrackedInflaterInputStream extends InflaterInputStream{
        TrackedInflaterInputStream(InputStream in){
            super(in);
        }

        int bufferedInputRemaining(){
            return inf.getRemaining();
        }
    }

    /** An input stream that fails instead of reading more than {@code limit} bytes. */
    private static final class LimitedInputStream extends FilterInputStream{
        private final long limit;
        private long count;

        LimitedInputStream(InputStream in, long limit){
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException{
            int value = super.read();
            if(value != -1){
                count++;
                if(count > limit) throw new IOException("Decompressed data exceeds the safe limit");
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException{
            int read = super.read(b, off, len);
            if(read > 0){
                count += read;
                if(count > limit) throw new IOException("Decompressed data exceeds the safe limit");
            }
            return read;
        }
    }
}

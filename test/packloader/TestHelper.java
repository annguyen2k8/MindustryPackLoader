package packloader;

import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;

import java.io.*;
import java.util.Locale;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TestHelper{

    public static void initBundle(Locale locale){
        Core.bundle = I18NBundle.createBundle(new Fi("assets/bundles/bundle"), locale);
    }

    /** Generates a minimal valid Mindustry schematic byte stream */
    public static byte[] createValidSchematicBytes(String name) throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Magic header
        baos.write(new byte[]{'m', 's', 'c', 'h'});
        baos.write((byte)1); // Version 1

        try(DataOutputStream dos = new DataOutputStream(new DeflaterOutputStream(baos))){
            dos.writeShort(2); // width
            dos.writeShort(2); // height

            // Tags: 1 tag (name)
            dos.writeByte(1);
            dos.writeUTF("name");
            dos.writeUTF(name);

            // Blocks dictionary: 0 blocks
            dos.writeByte(0);

            // Total tiles: 0
            dos.writeInt(0);
        }

        return baos.toByteArray();
    }

    /** Generates a minimal valid Mindustry schematic byte stream with no name tag. */
    public static byte[] createNamelessSchematicBytes() throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Magic header
        baos.write(new byte[]{'m', 's', 'c', 'h'});
        baos.write((byte)1); // Version 1

        try(DataOutputStream dos = new DataOutputStream(new DeflaterOutputStream(baos))){
            dos.writeShort(2); // width
            dos.writeShort(2); // height

            // No tags: the game derives the name from the file name when reading a file.
            dos.writeByte(0);

            // Blocks dictionary: 0 blocks
            dos.writeByte(0);

            // Total tiles: 0
            dos.writeInt(0);
        }

        return baos.toByteArray();
    }

    /** A valid schematic followed by bytes that the game reader would silently ignore. */
    public static byte[] createSchematicWithTrailingCompressedData(String name) throws IOException{
        return concat(createValidSchematicBytes(name), new byte[]{1, 2, 3, 4});
    }

    /** A valid map followed by bytes that the game reader would silently ignore. */
    public static byte[] createMapWithTrailingCompressedData(String name) throws IOException{
        return concat(createValidMapBytes(name), new byte[]{1, 2, 3, 4});
    }

    private static byte[] concat(byte[] first, byte[] second){
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** Generates a minimal valid Mindustry map (.msav) byte stream. */
    public static byte[] createValidMapBytes(String name) throws IOException{
        return createValidMapBytes(name, 13, 4, 4);
    }

    /**
     * Generates a structurally valid map stream using the real unnamed-chunk layout:
     * int length followed by payload. The map chunk contains real floor/block tile runs,
     * and the entity region uses the layout for the requested save version.
     */
    public static byte[] createValidMapBytes(String name, int version, int width, int height) throws IOException{
        return createMapBytes(name, version, width, height, new byte[]{0}, defaultTrailingChunks(version));
    }

    /**
     * Builds a map with an explicit content-chunk payload and explicit number of trailing
     * chunks (entities/markers/custom). Used to exercise truncation rules without having
     * to corrupt the deflate stream itself.
     */
    public static byte[] createMapBytes(String name, int version, int width, int height,
                                        byte[] contentPayload, int trailingChunks) throws IOException{
        return createMapBytes(name, version, width, height, contentPayload,
            createMapPayload(version, width, height), createEntityPayload(version), trailingChunks);
    }

    /** Builds a map with explicit map/entity region payloads for malformed-structure tests. */
    public static byte[] createMapBytes(String name, int version, int width, int height,
                                        byte[] contentPayload, byte[] mapPayload,
                                        byte[] entityPayload, int trailingChunks) throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try(DataOutputStream dos = new DataOutputStream(new DeflaterOutputStream(baos))){
            dos.write(new byte[]{'M', 'S', 'A', 'V'});
            dos.writeInt(version);

            ByteArrayOutputStream metaBaos = new ByteArrayOutputStream();
            try(DataOutputStream metaDos = new DataOutputStream(metaBaos)){
                metaDos.writeShort(4);
                metaDos.writeUTF("name");
                metaDos.writeUTF(name);
                metaDos.writeUTF("width");
                metaDos.writeUTF(Integer.toString(width));
                metaDos.writeUTF("height");
                metaDos.writeUTF(Integer.toString(height));
                metaDos.writeUTF("build");
                metaDos.writeUTF("160");
            }
            writeChunk(dos, metaBaos.toByteArray());

            if(version >= 12){
                writeChunk(dos, new byte[0]); // patches
            }

            writeChunk(dos, contentPayload);

            if(version == 11){
                writeChunk(dos, new byte[0]); // patches after content
            }

            writeChunk(dos, mapPayload);

            if(trailingChunks >= 1){
                writeChunk(dos, entityPayload);
            }

            if(trailingChunks >= 2){
                if(version >= 8){
                    writeChunk(dos, new byte[0]); // markers
                }else{
                    writeChunk(dos, createCustomPayload());
                }
            }

            if(trailingChunks >= 3){
                writeChunk(dos, createCustomPayload());
            }
            // Any additional trailing chunks are deliberately malformed; they let tests
            // verify that the validator rejects data after the custom chunk.
            for(int i = 3; i < trailingChunks; i++){
                writeChunk(dos, new byte[0]);
            }
        }
        return baos.toByteArray();
    }

    /** A structurally valid v13 map followed by an extra, unknown chunk. */
    public static byte[] createMapWithExtraTrailingChunk(String name) throws IOException{
        return createMapBytes(name, 13, 4, 4, new byte[]{0}, 4);
    }

    /** A v13 map with a dense list of build plans whose configs are all null. */
    public static byte[] createMapWithDenseTeamPlans(String name, int plans) throws IOException{
        return createMapWithModernPlan(name, null, plans);
    }

    /** A v13 map whose single build plan uses a Vec2 TypeIO config. */
    public static byte[] createMapWithVec2TeamPlan(String name) throws IOException{
        // vec2Type (19) followed by two floats.
        return createMapWithModernPlan(name, new byte[]{19, 0, 0, 0, 0, 0, 0, 0, 0}, 1);
    }

    private static byte[] createMapWithModernPlan(String name, byte[] config, int plans) throws IOException{
        ByteArrayOutputStream entityBaos = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(entityBaos)){
            out.writeShort(0); // no entity mapping
            out.writeInt(1);   // one active team
            out.writeInt(0);   // team id
            out.writeInt(plans);
            for(int i = 0; i < plans; i++){
                out.writeShort(i % 4);
                out.writeShort(i / 4);
                out.writeShort(0);
                out.writeShort(0);
                out.write(config == null ? new byte[]{0} : config);
            }
            out.writeInt(0); // no world entities
        }
        return createMapBytes(name, 13, 4, 4, new byte[]{0},
            createMapPayload(13, 4, 4), entityBaos.toByteArray(), 3);
    }

    /**
     * A map with one tile using the new data bits, one using the old data bit
     * (v4-v9 only), and one containing a length-prefixed entity chunk.
     */
    public static byte[] createMapWithTileDataAndEntities(String name, int version) throws IOException{
        ByteArrayOutputStream mapBaos = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(mapBaos)){
            out.writeShort(4);
            out.writeShort(4);

            for(int i = 0; i < 16; i++){
                out.writeShort(0);
                out.writeShort(0);
                out.writeByte(0);
            }

            if(version <= 9){
                // New data: 3 bytes + int extra data.
                out.writeShort(0);
                out.writeByte(4);
                out.writeByte(1);
                out.writeByte(2);
                out.writeByte(3);
                out.writeInt(4);

                // Old data: one byte, only present when there is no building.
                out.writeShort(0);
                out.writeByte(2);
                out.writeByte(9);

                // Entity center with one byte of opaque tile-entity payload.
                out.writeShort(0);
                out.writeByte(1);
                out.writeBoolean(true);
                out.writeShort(1);
                out.writeByte(0);

                // Consecutive run covering the remaining 12 tiles.
                out.writeShort(0);
                out.writeByte(0);
                out.writeByte(12);
            }else{
                out.writeShort(0);
                out.writeByte(4);
                out.writeByte(1);
                out.writeByte(2);
                out.writeByte(3);
                out.writeInt(4);

                out.writeShort(0);
                out.writeByte(1);
                out.writeBoolean(true);
                out.writeInt(1);
                out.writeByte(0);

                out.writeShort(0);
                out.writeByte(0);
                out.writeByte(13);
            }
        }
        return createMapBytes(name, version, 4, 4, new byte[]{0}, mapBaos.toByteArray(),
            createEntityPayload(version), defaultTrailingChunks(version));
    }

    /** A v13 map with one trailing chunk missing (markers/custom/entities). */
    public static byte[] createTruncatedMapBytes(String name) throws IOException{
        return createMapBytes(name, 13, 4, 4, new byte[]{0}, 2);
    }

    /** A v13 map whose content header contains an impossible content type. */
    public static byte[] createMalformedMapContentBytes(String name) throws IOException{
        return createMapBytes(name, 13, 4, 4, new byte[]{(byte)250}, 3);
    }

    /** A map whose declared map chunk ends immediately after the dimensions. */
    public static byte[] createMapWithTruncatedTiles(String name) throws IOException{
        ByteArrayOutputStream map = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(map)){
            out.writeShort(4);
            out.writeShort(4);
        }
        return createMapBytes(name, 13, 4, 4, new byte[]{0}, map.toByteArray(),
            createEntityPayload(13), 3);
    }

    /** A map whose entity-region prefix claims one mapping but contains no mapping data. */
    public static byte[] createMapWithTruncatedEntities(String name) throws IOException{
        byte[] entities = new byte[]{0, 1};
        return createMapBytes(name, 13, 4, 4, new byte[]{0},
            createMapPayload(13, 4, 4), entities, 3);
    }

    public static byte[] createMapPayload(int version, int width, int height) throws IOException{
        ByteArrayOutputStream mapBaos = new ByteArrayOutputStream();
        try(DataOutputStream mapDos = new DataOutputStream(mapBaos)){
            mapDos.writeShort(width);
            mapDos.writeShort(height);
            int tiles = width * height;

            // Floor runs: floor id, overlay id, then a zero consecutive count.
            for(int i = 0; i < tiles; i++){
                mapDos.writeShort(0);
                mapDos.writeShort(0);
                mapDos.writeByte(0);
            }

            // Air blocks with no entity/data, followed by a zero run count.
            for(int i = 0; i < tiles; i++){
                mapDos.writeShort(0);
                if(version >= 4){
                    mapDos.writeByte(0);
                }
                mapDos.writeByte(0);
            }
        }
        return mapBaos.toByteArray();
    }

    public static byte[] createEntityPayload(int version) throws IOException{
        ByteArrayOutputStream entityBaos = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(entityBaos)){
            if(version <= 2){
                out.writeByte(0); // legacy entity groups
            }else if(version == 3){
                out.writeInt(0); // team blocks
                out.writeByte(0); // legacy entity groups
            }else if(version == 4){
                out.writeInt(0); // team blocks
                out.writeInt(0); // legacy world entities
            }else{
                out.writeShort(0); // entity mapping
                out.writeInt(0); // team blocks
                out.writeInt(0); // world entities
            }
        }
        return entityBaos.toByteArray();
    }

    private static byte[] createCustomPayload() throws IOException{
        ByteArrayOutputStream custom = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(custom)){
            out.writeInt(0);
        }
        return custom.toByteArray();
    }

    /** A schematic with a valid header but a truncated deflate payload. */
    public static byte[] createTruncatedSchematicBytes(){
        return new byte[]{'m', 's', 'c', 'h', 1, 0x78};
    }

    /** A schematic whose decompressed stream contains data after the final tile. */
    public static byte[] createSchematicWithTrailingData(String name) throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(new byte[]{'m', 's', 'c', 'h'});
        baos.write((byte)1);

        try(DataOutputStream dos = new DataOutputStream(new DeflaterOutputStream(baos))){
            dos.writeShort(2);
            dos.writeShort(2);
            dos.writeByte(1);
            dos.writeUTF("name");
            dos.writeUTF(name);
            dos.writeByte(0);
            dos.writeInt(0);
            dos.writeByte(0x42); // ignored by the game reader
        }

        return baos.toByteArray();
    }

    /** A schematic with one tile that references a block not present in the dictionary. */
    public static byte[] createSchematicWithInvalidBlockIndex(String name) throws IOException{
        return createSchematicWithTile(name, 0, 0, new byte[]{0});
    }

    /** A schematic with one tile using the supplied raw TypeIO config bytes. */
    public static byte[] createSchematicWithConfig(String name, byte[] config) throws IOException{
        return createSchematicWithTile(name, 1, 0, config);
    }

    /**
     * Builds a schematic with one tile, a block dictionary of {@code dictionarySize} entries,
     * and the supplied raw TypeIO config bytes.
     */
    public static byte[] createSchematicWithTile(String name, int dictionarySize, int blockIndex,
                                                 byte[] config) throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(new byte[]{'m', 's', 'c', 'h'});
        baos.write((byte)1);

        try(DataOutputStream dos = new DataOutputStream(new DeflaterOutputStream(baos))){
            dos.writeShort(2);
            dos.writeShort(2);
            dos.writeByte(1);
            dos.writeUTF("name");
            dos.writeUTF(name);
            dos.writeByte(dictionarySize);
            for(int i = 0; i < dictionarySize; i++){
                dos.writeUTF("block" + i);
            }
            dos.writeInt(1);  // one tile
            dos.writeByte(blockIndex);
            dos.writeInt(0);  // position
            dos.write(config);
            dos.writeByte(0); // rotation
        }

        return baos.toByteArray();
    }

    private static int defaultTrailingChunks(int version){
        if(version >= 8) return 3; // entities + markers + custom
        if(version == 7) return 2; // entities + custom
        return 1; // legacy: entities only
    }

    private static void writeChunk(DataOutputStream out, byte[] payload) throws IOException{
        out.writeInt(payload.length);
        out.write(payload);
    }

    /** Creates a test ZIP archive with custom entries */
    public static void createTestZip(File zipFile, ZipEntryBuilder... entries) throws IOException{
        if(zipFile.getParentFile() != null && !zipFile.getParentFile().exists()){
            zipFile.getParentFile().mkdirs();
        }
        try(ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))){
            for(ZipEntryBuilder entry : entries){
                ZipEntry ze = new ZipEntry(entry.name);
                zos.putNextEntry(ze);
                zos.write(entry.data);
                zos.closeEntry();
            }
        }
    }

    public static class ZipEntryBuilder{
        public final String name;
        public final byte[] data;

        public ZipEntryBuilder(String name, byte[] data){
            this.name = name;
            this.data = data;
        }
    }
}

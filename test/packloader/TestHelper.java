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

    /** Generates a minimal valid Mindustry map (.msav) byte stream */
    public static byte[] createValidMapBytes(String name) throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try(DataOutputStream dos = new DataOutputStream(new DeflaterOutputStream(baos))){
            // Header
            dos.write(new byte[]{'M', 'S', 'A', 'V'});
            dos.writeInt(13); // Save version 13

            // Minimal region data for meta
            dos.writeUTF("meta");
            ByteArrayOutputStream metaBaos = new ByteArrayOutputStream();
            try(DataOutputStream metaDos = new DataOutputStream(metaBaos)){
                // StringMap: 1 entry ("name" -> name)
                metaDos.writeInt(1);
                metaDos.writeUTF("name");
                metaDos.writeUTF(name);
            }
            byte[] metaBytes = metaBaos.toByteArray();
            dos.writeInt(metaBytes.length);
            dos.write(metaBytes);

            // End of regions indicator
            dos.writeUTF("last");
            dos.writeInt(0);
        }
        return baos.toByteArray();
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

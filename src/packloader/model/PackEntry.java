package packloader.model;

import arc.files.Fi;
import arc.util.Strings;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static packloader.util.Localization.getLocalizedText;

public class PackEntry{
    public static final long MAX_MAP_BYTES = 100 * 1024 * 1024L;       // 100MB max per map
    public static final long MAX_SCHEMATIC_BYTES = 10 * 1024 * 1024L;  // 10MB max per schematic

    public final PackType type;
    public final String sourcePath;
    public final String rawFileName;
    public final String sanitizedFileName;
    public String displayName;
    public long size;

    public boolean isDuplicate;
    public String duplicateReason = "";
    public boolean selected = true;
    public boolean isValid = true;
    public String errorReason = "";

    // Source references
    public Fi sourceFile;
    public Fi zipFile;
    public String zipEntryName;
    public byte[] cachedData;

    public PackEntry(PackType type, String sourcePath, String rawFileName, long size){
        this.type = type;
        this.sourcePath = sourcePath;
        this.rawFileName = rawFileName;
        this.sanitizedFileName = sanitizeName(rawFileName);
        this.displayName = extractBaseName(this.sanitizedFileName);
        this.size = size;
    }

    public static String sanitizeName(String filename){
        if(filename == null) return "unnamed";
        String clean = filename.trim();

        // Android SAF virtual URI prefix cleanup (e.g. "primary:Download/map.msav")
        int colon = clean.lastIndexOf(':');
        if(colon != -1 && colon < clean.length() - 1){
            clean = clean.substring(colon + 1);
        }
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if(slash != -1 && slash < clean.length() - 1){
            clean = clean.substring(slash + 1);
        }

        clean = Strings.sanitizeFilename(clean);
        if(clean.isEmpty()) return "unnamed";
        return clean;
    }

    public static String extractBaseName(String filename){
        int dot = filename.lastIndexOf('.');
        if(dot > 0){
            return filename.substring(0, dot);
        }
        return filename;
    }

    public long getMaxAllowedBytes(){
        return (type == PackType.MAP) ? MAX_MAP_BYTES : MAX_SCHEMATIC_BYTES;
    }

    /** Copies the content of this entry directly to the destination Fi safely with decompression limit. */
    public void copyTo(Fi dest) throws IOException{
        if(dest == null) throw new IllegalArgumentException("Destination file cannot be null");

        if(dest.parent() != null && !dest.parent().exists()){
            dest.parent().mkdirs();
        }

        long maxBytes = getMaxAllowedBytes();

        if(cachedData != null){
            if(cachedData.length > maxBytes){
                throw new IOException(getLocalizedText("packloader.error.zipbomb", maxBytes / (1024 * 1024)));
            }
            dest.writeBytes(cachedData);
            return;
        }

        try(InputStream in = openStream();
            OutputStream out = dest.write(false, 8192)){
            copyStreamWithLimit(in, out, maxBytes);
        }
    }

    private void copyStreamWithLimit(InputStream in, OutputStream out, long maxBytes) throws IOException{
        byte[] buffer = new byte[8192];
        long totalBytes = 0;
        int len;
        while((len = in.read(buffer)) > 0){
            totalBytes += len;
            if(totalBytes > maxBytes){
                throw new IOException(getLocalizedText("packloader.error.zipbomb", maxBytes / (1024 * 1024)));
            }
            out.write(buffer, 0, len);
        }
    }

    /** Opens an input stream for reading the entry. Caller is responsible for closing. */
    public InputStream openStream() throws IOException{
        if(cachedData != null){
            return new ByteArrayInputStream(cachedData);
        }
        if(sourceFile != null){
            return sourceFile.read(8192);
        }
        if(zipFile != null && zipEntryName != null){
            ZipFile zf = new ZipFile(zipFile.file());
            ZipEntry entry = zf.getEntry(zipEntryName);
            if(entry == null){
                zf.close();
                throw new FileNotFoundException(getLocalizedText("packloader.error.notfound", zipEntryName));
            }
            InputStream in = zf.getInputStream(entry);
            return new FilterInputStream(in){
                @Override
                public void close() throws IOException{
                    try{
                        super.close();
                    }finally{
                        zf.close();
                    }
                }
            };
        }
        throw new IOException(getLocalizedText("packloader.error.notfound", sourcePath));
    }
}

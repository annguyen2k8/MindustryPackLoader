package packloader.core;

import arc.files.Fi;
import packloader.model.PackEntry;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * High-performance, streaming cryptographic hash computation (SHA-256)
 * for Mindustry map (.msav) and schematic (.msch) entries and files.
 *
 * Enforces resource bounds to prevent decompression bomb exploits and does
 * not load entire large files into monolithic heap arrays.
 */
public final class ContentHash{
    private static final int BUFFER_SIZE = 8192;

    private ContentHash(){}

    /** Computes SHA-256 of the given file on disk, returning empty string if inaccessible. */
    public static String computeSha256(Fi file){
        if(file == null || !file.exists() || file.isDirectory()) return "";
        try(InputStream in = file.read()){
            return computeSha256(in, Long.MAX_VALUE);
        }catch(Throwable t){
            return "";
        }
    }

    /** Computes SHA-256 of the content represented by the PackEntry without mutating disk. */
    public static String computeSha256(PackEntry entry){
        if(entry == null || !entry.isValid || entry.size <= 0) return "";
        if(entry.cachedData != null){
            return computeSha256(entry.cachedData);
        }
        try(InputStream in = entry.openStream()){
            return computeSha256(in, entry.getMaxAllowedBytes());
        }catch(Throwable t){
            return "";
        }
    }

    /** Computes SHA-256 from an in-memory byte array. */
    public static String computeSha256(byte[] data){
        if(data == null || data.length == 0) return "";
        try{
            MessageDigest digest = createDigest();
            byte[] hash = digest.digest(data);
            return toHex(hash);
        }catch(Throwable t){
            return "";
        }
    }

    /** Computes SHA-256 streaming up to maxBytes to avoid unbounded decompression. */
    public static String computeSha256(InputStream in, long maxBytes){
        if(in == null) return "";
        try{
            MessageDigest digest = createDigest();
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalRead = 0;
            int n;
            while((n = in.read(buffer)) != -1){
                totalRead += n;
                if(totalRead > maxBytes){
                    return ""; // Limit exceeded, unsafe
                }
                digest.update(buffer, 0, n);
            }
            if(totalRead == 0) return "";
            byte[] hash = digest.digest();
            return toHex(hash);
        }catch(Throwable t){
            return "";
        }
    }

    private static MessageDigest createDigest(){
        try{
            return MessageDigest.getInstance("SHA-256");
        }catch(NoSuchAlgorithmException e){
            throw new RuntimeException("SHA-256 algorithm not available in runtime", e);
        }
    }

    private static String toHex(byte[] bytes){
        if(bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for(byte b : bytes){
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

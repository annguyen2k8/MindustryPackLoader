package packloader.model;

import arc.Core;

public enum PackType{
    MAP("msav"),
    SCHEMATIC("msch");

    public final String extension;

    PackType(String extension){
        this.extension = extension;
    }

    public String localizedName(){
        return Core.bundle != null ? Core.bundle.get(this == MAP ? "packloader.type.map" : "packloader.type.schematic") : name();
    }

    public static PackType fromFileName(String filename){
        if(filename == null) return null;
        String lower = filename.trim().toLowerCase();
        if(lower.endsWith(".msav")) return MAP;
        if(lower.endsWith(".msch")) return SCHEMATIC;
        return null;
    }

    public static boolean isSupported(String filename){
        return fromFileName(filename) != null;
    }
}

package packloader.util;

import arc.Core;
import packloader.model.DuplicatePolicy;
import packloader.model.PackType;

public final class PackLoaderSettings{
    public static final String KEY_QUICK_IMPORT = "packloader.quick-import";
    public static final String KEY_DUPLICATE_POLICY = "packloader.duplicate-policy";
    public static final String KEY_DUPLICATE_POLICY_MAP = "packloader.duplicate-policy.map";
    public static final String KEY_DUPLICATE_POLICY_SCHEM = "packloader.duplicate-policy.schematic";

    private PackLoaderSettings(){}

    public static boolean isQuickImport(){
        return Core.settings != null && Core.settings.getBool(KEY_QUICK_IMPORT, false);
    }

    public static void setQuickImport(boolean quick){
        if(Core.settings != null){
            Core.settings.put(KEY_QUICK_IMPORT, quick);
        }
    }

    public static String getDuplicatePolicyKey(PackType type){
        if(type == PackType.MAP) return KEY_DUPLICATE_POLICY_MAP;
        if(type == PackType.SCHEMATIC) return KEY_DUPLICATE_POLICY_SCHEM;
        return KEY_DUPLICATE_POLICY;
    }

    public static DuplicatePolicy getDuplicatePolicy(){
        return getDuplicatePolicy(null);
    }

    public static DuplicatePolicy getDuplicatePolicy(PackType type){
        if(Core.settings == null) return DuplicatePolicy.SKIP;
        if(type != null){
            String key = getDuplicatePolicyKey(type);
            if(Core.settings.has(key)){
                String val = Core.settings.getString(key, DuplicatePolicy.SKIP.name());
                try{
                    return DuplicatePolicy.valueOf(val);
                }catch(Throwable ignored){}
            }
        }
        // Fallback to legacy global policy setting if set, else default to SKIP
        String legacy = Core.settings.getString(KEY_DUPLICATE_POLICY, DuplicatePolicy.SKIP.name());
        try{
            return DuplicatePolicy.valueOf(legacy);
        }catch(Throwable t){
            return DuplicatePolicy.SKIP;
        }
    }

    public static void setDuplicatePolicy(DuplicatePolicy policy){
        setDuplicatePolicy(null, policy);
    }

    public static void setDuplicatePolicy(PackType type, DuplicatePolicy policy){
        if(Core.settings == null || policy == null) return;
        if(type != null){
            Core.settings.put(getDuplicatePolicyKey(type), policy.name());
        }else{
            Core.settings.put(KEY_DUPLICATE_POLICY_MAP, policy.name());
            Core.settings.put(KEY_DUPLICATE_POLICY_SCHEM, policy.name());
            Core.settings.put(KEY_DUPLICATE_POLICY, policy.name());
        }
    }
}

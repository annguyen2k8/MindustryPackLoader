package packloader.util;

import arc.Core;
import packloader.model.DuplicatePolicy;

public final class PackLoaderSettings{
    public static final String KEY_QUICK_IMPORT = "packloader.quick-import";
    public static final String KEY_DUPLICATE_POLICY = "packloader.duplicate-policy";

    private PackLoaderSettings(){}

    public static boolean isQuickImport(){
        return Core.settings != null && Core.settings.getBool(KEY_QUICK_IMPORT, false);
    }

    public static void setQuickImport(boolean quick){
        if(Core.settings != null){
            Core.settings.put(KEY_QUICK_IMPORT, quick);
        }
    }

    public static DuplicatePolicy getDuplicatePolicy(){
        if(Core.settings == null) return DuplicatePolicy.SKIP;
        String val = Core.settings.getString(KEY_DUPLICATE_POLICY, DuplicatePolicy.SKIP.name());
        try{
            return DuplicatePolicy.valueOf(val);
        }catch(Throwable t){
            return DuplicatePolicy.SKIP;
        }
    }

    public static void setDuplicatePolicy(DuplicatePolicy policy){
        if(Core.settings != null && policy != null){
            Core.settings.put(KEY_DUPLICATE_POLICY, policy.name());
        }
    }
}

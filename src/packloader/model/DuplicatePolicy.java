package packloader.model;

import arc.Core;

public enum DuplicatePolicy{
    SKIP("packloader.conflict.skip", "packloader.conflict.skip.desc"),
    OVERWRITE("packloader.conflict.overwrite", "packloader.conflict.overwrite.desc"),
    RENAME("packloader.conflict.rename", "packloader.conflict.rename.desc");

    public final String titleKey;
    public final String descKey;

    DuplicatePolicy(String titleKey, String descKey){
        this.titleKey = titleKey;
        this.descKey = descKey;
    }

    public String title(){
        return Core.bundle != null ? Core.bundle.get(titleKey) : name();
    }

    public String description(){
        return Core.bundle != null ? Core.bundle.get(descKey) : "";
    }
}

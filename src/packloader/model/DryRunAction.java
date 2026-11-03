package packloader.model;

import arc.graphics.Color;
import mindustry.graphics.Pal;

import static packloader.util.Localization.getLocalizedText;

public enum DryRunAction{
    NEW("packloader.dryrun.action.new", Pal.heal),
    SKIP("packloader.dryrun.action.skip", Pal.lightishGray),
    OVERWRITE("packloader.dryrun.action.overwrite", Pal.accent),
    RENAME("packloader.dryrun.action.rename", Color.gold);

    public final String labelKey;
    public final Color color;

    DryRunAction(String labelKey, Color color){
        this.labelKey = labelKey;
        this.color = color;
    }

    public String localized(){
        return getLocalizedText(labelKey);
    }
}

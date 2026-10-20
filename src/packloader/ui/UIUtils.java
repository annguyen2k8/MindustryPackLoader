package packloader.ui;

import arc.scene.ui.TextButton;

/** UI helper methods for consistent Mindustry styling. */
public final class UIUtils{
    private UIUtils(){}

    /** Adds consistent spacing between icon and label in a TextButton. */
    public static <T extends TextButton> T pad(T btn){
        if(btn != null){
            if(btn.getCells().size > 0){
                btn.getCells().get(0).padRight(8f);
            }
            if(btn.getLabelCell() != null){
                btn.getLabelCell().padLeft(8f);
            }
        }
        return btn;
    }
}

package packloader.ui;

import arc.scene.ui.Button;
import arc.scene.ui.TextButton;
import mindustry.ui.Styles;

/** UI helper methods for consistent Mindustry styling. */
public final class UIUtils{
    private UIUtils(){}

    /** Standard card style with hover/pressed/disabled feedback. */
    public static Button.ButtonStyle cardStyle(){
        return new Button.ButtonStyle(Styles.defaultb);
    }

    /** Pane-like row style that still exposes hover/pressed feedback. */
    public static Button.ButtonStyle rowStyle(){
        return new Button.ButtonStyle(Styles.grayt);
    }

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

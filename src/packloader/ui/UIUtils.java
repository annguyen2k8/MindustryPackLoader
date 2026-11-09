package packloader.ui;

import arc.scene.style.Drawable;
import arc.scene.ui.Button;
import arc.scene.ui.CheckBox;
import arc.scene.ui.TextButton;
import arc.util.Scaling;
import mindustry.ui.Styles;

/** UI helper methods for consistent Mindustry styling. */
public final class UIUtils{
    private UIUtils(){}

    /** Native Mindustry button card style with bevel border and hover/pressed feedback. */
    public static Button.ButtonStyle cardStyle(){
        return Styles.defaultb != null ? new Button.ButtonStyle(Styles.defaultb) : new Button.ButtonStyle();
    }

    /** Borderless flat row style with hover/pressed feedback. */
    public static Button.ButtonStyle rowStyle(){
        Button.ButtonStyle style = new Button.ButtonStyle();
        style.up = Styles.black3 != null ? Styles.black3 : Styles.none;
        style.over = Styles.flatOver != null ? Styles.flatOver : Styles.none;
        style.down = Styles.flatDown != null ? Styles.flatDown : Styles.none;
        return style;
    }

    /** Native Mindustry transparent button style with flat hover/down feedback (for Clear buttons, header actions, etc.). */
    public static Button.ButtonStyle clearButtonStyle(){
        return Styles.flatt != null ? new Button.ButtonStyle(Styles.flatt) : new Button.ButtonStyle();
    }

    /** Returns a subtle flat borderless dark panel background (black with 50% opacity). */
    public static Drawable flatPane(){
        return Styles.black5 != null ? Styles.black5 : Styles.none;
    }

    /** Returns a slightly darker subtle flat borderless panel background (black with 60% opacity). */
    public static Drawable flatPaneDark(){
        return Styles.black6 != null ? Styles.black6 : Styles.none;
    }

    /** Adds clean spacing between icon and label in a TextButton without excessive padding. */
    public static <T extends TextButton> T pad(T btn){
        if(btn != null && btn.getCells().size > 1){
            btn.getCells().get(0).padRight(6f);
        }
        return btn;
    }

    /**
     * Configures a standalone CheckBox (with empty label text) to maintain a strict 1:1 square
     * aspect ratio matching native Mindustry widgets, eliminating distortion caused by internal label padding.
     */
    public static CheckBox squareCheck(CheckBox box, float size){
        if(box != null){
            arc.scene.ui.layout.Cell<?> cell = box.getCell(box.getImage());
            if(cell != null){
                cell.size(size);
            }else if(box.getImageCell() != null){
                box.getImageCell().size(size);
            }
            if(box.getLabelCell() != null){
                box.getLabelCell().padLeft(0f);
            }
            if(box.getImage() != null){
                box.getImage().setScaling(Scaling.fit);
            }
        }
        return box;
    }
}

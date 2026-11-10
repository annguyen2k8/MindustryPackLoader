package packloader.ui;

import arc.Core;
import arc.scene.style.Drawable;
import arc.scene.ui.Button;
import arc.scene.ui.CheckBox;
import arc.scene.ui.TextButton;
import arc.util.Scaling;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.ui.Styles;

/** UI helper methods for consistent Mindustry styling. */
public final class UIUtils{
    private UIUtils(){}

    /** Returns current virtual scene width in UI units (or fallback to graphics). */
    public static float screenWidth(){
        return Core.scene != null ? Core.scene.getWidth() : (Core.graphics != null ? Core.graphics.getWidth() : 800f);
    }

    /** Returns current virtual scene height in UI units (or fallback to graphics). */
    public static float screenHeight(){
        return Core.scene != null ? Core.scene.getHeight() : (Core.graphics != null ? Core.graphics.getHeight() : 600f);
    }

    /** Returns whether the device viewport is in portrait orientation. */
    public static boolean isPortrait(){
        return Core.graphics != null && Core.graphics.isPortrait();
    }

    /** Returns whether UI should adapt to compact screens (mobile, portrait, or narrow viewports). */
    public static boolean isCompact(){
        return Vars.mobile || isPortrait() || screenWidth() < 640f;
    }

    /** Native Mindustry standard dialog button height (64f). */
    public static float buttonHeight(){
        return 64f;
    }

    /** Compact dialog button height for multi-row mobile layouts (56f). */
    public static float compactButtonHeight(){
        return 56f;
    }

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

    /** Returns an opaque black background to prevent under-dialog element bleed/ghosting. */
    public static Drawable solidBackground(){
        if(Styles.black != null) return Styles.black;
        if(Styles.none != null) return Styles.none;
        return new arc.scene.style.BaseDrawable();
    }

    /** Formats elapsed time concisely (e.g., "850 ms" or "14.3s"). */
    public static String formatDuration(long millis){
        if(millis < 1000){
            return millis + " ms";
        }
        return Strings.fixed(millis / 1000f, 1) + "s";
    }

    /** Adds clean spacing between icon and label in a TextButton without excessive padding. */
    public static <T extends TextButton> T pad(T btn){
        if(btn != null && btn.getCells().size > 1){
            btn.getCells().get(0).padRight(6f);
        }
        return btn;
    }

    /**
     * Styles and constrains a TextButton so its label never clips or spills outside the button boundaries.
     * Enforces clean icon padding, single-line text layout, and graceful ellipsis truncation if bounds are tight.
     */
    public static <T extends TextButton> T fitButton(T btn){
        if(btn != null){
            pad(btn);
            if(btn.getLabel() != null){
                btn.getLabel().setWrap(false);
                btn.getLabel().setEllipsis(true);
            }
            if(btn.getLabelCell() != null){
                btn.getLabelCell().minWidth(0f);
            }
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

package packloader;

import arc.scene.ui.CheckBox;
import arc.scene.ui.CheckBox.CheckBoxStyle;
import arc.util.Scaling;
import org.junit.jupiter.api.Test;
import packloader.ui.PackLoaderDialog;
import packloader.ui.UIUtils;

import static org.junit.jupiter.api.Assertions.*;

public class UIUtilsTest{

    @Test
    void testPadNullSafe(){
        assertNull(UIUtils.pad(null));
    }

    @Test
    void testSquareCheckNullSafe(){
        assertNull(UIUtils.squareCheck(null, 24f));
    }

    @Test
    void testSquareCheckEnforcesSquareDimensionsAndFitScaling() throws Exception{
        arc.Application prevApp = arc.Core.app;
        arc.graphics.GL20 prevGl = arc.Core.gl;
        arc.graphics.GL20 prevGl20 = arc.Core.gl20;
        arc.Graphics prevGraphics = arc.Core.graphics;
        arc.scene.Scene prevScene = arc.Core.scene;
        try{
            arc.Core.app = new arc.mock.MockApplication();
            arc.Core.gl = arc.Core.gl20 = new arc.mock.MockGL20();
            arc.Core.graphics = new arc.mock.MockGraphics();
            arc.Core.scene = new arc.scene.Scene();

            arc.graphics.g2d.Font dummyFont = new arc.graphics.g2d.Font(new arc.graphics.g2d.Font.FontData(), new arc.graphics.g2d.TextureRegion(), false);
            CheckBoxStyle style = new CheckBoxStyle();
            style.font = dummyFont;

            arc.Core.scene.addStyle(arc.scene.ui.Button.ButtonStyle.class, new arc.scene.ui.Button.ButtonStyle());
            arc.Core.scene.addStyle(CheckBoxStyle.class, style);

            CheckBox box = new CheckBox("", style);

            // Check internal fields via reflection to verify initial unconfigured Arc state
            java.lang.reflect.Field padLeftField = arc.scene.ui.layout.Cell.class.getDeclaredField("padLeft");
            padLeftField.setAccessible(true);
            java.lang.reflect.Field scalingField = arc.scene.ui.Image.class.getDeclaredField("scaling");
            scalingField.setAccessible(true);

            assertEquals(4f, (float)padLeftField.get(box.getLabelCell()), 0.001f);
            assertEquals(Scaling.stretch, scalingField.get(box.getImage()));

            // After squareCheck, dimensions become strictly square, label padding is removed, and Scaling.fit is set
            UIUtils.squareCheck(box, 26f);

            assertEquals(0f, (float)padLeftField.get(box.getLabelCell()), 0.001f);
            assertEquals(Scaling.fit, scalingField.get(box.getImage()));
            assertEquals(26f, box.getPrefWidth(), 0.001f);
            assertEquals(26f, box.getPrefHeight(), 0.001f);
        }finally{
            arc.Core.app = prevApp;
            arc.Core.gl = prevGl;
            arc.Core.gl20 = prevGl20;
            arc.Core.graphics = prevGraphics;
            arc.Core.scene = prevScene;
        }
    }

    @Test
    void testTruncateFileName(){
        assertNull(PackLoaderDialog.truncateFileName(null, 20));
        assertEquals("short.msch", PackLoaderDialog.truncateFileName("short.msch", 20));

        // Long file name with extension
        String longName = "super_mega_ultra_complex_heavy_defense_v2_final.msch";
        String truncated = PackLoaderDialog.truncateFileName(longName, 25);
        assertTrue(truncated.length() <= 25);
        assertTrue(truncated.endsWith(".msch"));
        assertTrue(truncated.contains("..."));

        // Long file name without extension
        String longNoExt = "a_very_long_file_name_without_extension_here";
        String truncatedNoExt = PackLoaderDialog.truncateFileName(longNoExt, 20);
        assertTrue(truncatedNoExt.length() <= 20);
        assertTrue(truncatedNoExt.endsWith("..."));
    }

    @Test
    void testFormatDuration(){
        assertEquals("0 ms", UIUtils.formatDuration(0L));
        assertEquals("450 ms", UIUtils.formatDuration(450L));
        assertEquals("999 ms", UIUtils.formatDuration(999L));
        assertEquals("1.0s", UIUtils.formatDuration(1000L));
        assertEquals("14.3s", UIUtils.formatDuration(14332L));
        assertEquals("60.0s", UIUtils.formatDuration(60000L));
    }

    @Test
    void testSolidBackgroundNotNull(){
        assertNotNull(UIUtils.solidBackground());
    }

    @Test
    void testShowNoticeDialogBuildsValidDialog(){
        arc.Application prevApp = arc.Core.app;
        arc.graphics.GL20 prevGl = arc.Core.gl;
        arc.graphics.GL20 prevGl20 = arc.Core.gl20;
        arc.Graphics prevGraphics = arc.Core.graphics;
        arc.scene.Scene prevScene = arc.Core.scene;
        try{
            arc.Core.app = new arc.mock.MockApplication();
            arc.Core.gl = arc.Core.gl20 = new arc.mock.MockGL20();
            arc.Core.graphics = new arc.mock.MockGraphics();
            arc.Core.scene = new arc.scene.Scene();

            arc.graphics.g2d.Font dummyFont = new arc.graphics.g2d.Font(new arc.graphics.g2d.Font.FontData(), new arc.graphics.g2d.TextureRegion(), false);
            arc.scene.ui.Dialog.DialogStyle dialogStyle = new arc.scene.ui.Dialog.DialogStyle();
            dialogStyle.titleFont = dummyFont;
            arc.Core.scene.addStyle(arc.scene.ui.Dialog.DialogStyle.class, dialogStyle);

            arc.scene.ui.TextButton.TextButtonStyle textButtonStyle = new arc.scene.ui.TextButton.TextButtonStyle();
            textButtonStyle.font = dummyFont;
            arc.Core.scene.addStyle(arc.scene.ui.TextButton.TextButtonStyle.class, textButtonStyle);
            arc.Core.scene.addStyle(arc.scene.ui.Button.ButtonStyle.class, new arc.scene.ui.Button.ButtonStyle());
            arc.Core.scene.addStyle(arc.scene.ui.Label.LabelStyle.class, new arc.scene.ui.Label.LabelStyle(dummyFont, arc.graphics.Color.white));

            boolean[] confirmed = new boolean[1];
            mindustry.ui.dialogs.BaseDialog d = UIUtils.showNoticeDialog(
                "Test Notice",
                "Test SubHeader",
                "Test Message Body",
                "Confirm",
                null,
                () -> confirmed[0] = true,
                "Cancel",
                null,
                null
            );

            assertNotNull(d);
            assertNotNull(d.cont);
            assertEquals(2, d.buttons.getChildren().size);
        }finally{
            arc.Core.app = prevApp;
            arc.Core.gl = prevGl;
            arc.Core.gl20 = prevGl20;
            arc.Core.graphics = prevGraphics;
            arc.Core.scene = prevScene;
        }
    }
}

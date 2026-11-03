package packloader.ui;

import arc.Core;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.TextureRegion;
import arc.mock.MockApplication;
import arc.mock.MockGL20;
import arc.mock.MockGraphics;
import arc.mock.MockSettings;
import arc.scene.Element;
import arc.scene.Scene;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.CheckBox;
import arc.scene.ui.CheckBox.CheckBoxStyle;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.util.PackLoaderSettings;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CheckboxRowInteractionTest{
    private arc.Application prevApp;
    private arc.graphics.GL20 prevGl;
    private arc.graphics.GL20 prevGl20;
    private arc.Graphics prevGraphics;
    private arc.scene.Scene prevScene;
    private arc.Settings prevSettings;
    private CheckBoxStyle style;

    @BeforeEach
    public void setup(){
        prevApp = Core.app;
        prevGl = Core.gl;
        prevGl20 = Core.gl20;
        prevGraphics = Core.graphics;
        prevScene = Core.scene;
        prevSettings = Core.settings;

        Core.app = new MockApplication();
        Core.gl = Core.gl20 = new MockGL20();
        Core.graphics = new MockGraphics();
        Core.scene = new Scene();
        Core.settings = new MockSettings();

        Font dummyFont = new Font(new Font.FontData(), new TextureRegion(), false);
        style = new CheckBoxStyle();
        style.font = dummyFont;

        Core.scene.addStyle(Button.ButtonStyle.class, new Button.ButtonStyle());
        Core.scene.addStyle(CheckBoxStyle.class, style);
        Core.scene.addStyle(Label.LabelStyle.class, new Label.LabelStyle(dummyFont, arc.graphics.Color.white));
    }

    @AfterEach
    public void teardown(){
        Core.app = prevApp;
        Core.gl = prevGl;
        Core.gl20 = prevGl20;
        Core.graphics = prevGraphics;
        Core.scene = prevScene;
        Core.settings = prevSettings;
    }

    @Test
    public void testNativeHoverAndDiscreteClickHandlingForQuickImport(){
        Table row = new Table();
        row.setSize(300f, 40f);

        CheckBox quickBox = new CheckBox("", style);
        quickBox.setSize(24f, 24f);
        quickBox.setChecked(false);
        UIUtils.squareCheck(quickBox, 24f);

        AtomicInteger settingSaves = new AtomicInteger(0);
        quickBox.changed(() -> {
            PackLoaderSettings.setQuickImport(quickBox.isChecked());
            settingSaves.incrementAndGet();
        });

        row.add(quickBox).size(24f).padRight(10f).center();

        Table textTable = new Table();
        textTable.touchable = Touchable.enabled;
        Label label = new Label("Quick Import");
        label.touchable = Touchable.disabled;
        textTable.add(label);

        textTable.clicked(() -> {
            quickBox.toggle();
            PackLoaderSettings.setQuickImport(quickBox.isChecked());
            settingSaves.incrementAndGet();
        });

        row.add(textTable).growX().left().center();
        row.layout();
        Core.scene.add(row);

        // 1. Hit test on checkbox region (x=10, y=10) returns quickBox or its child Image,
        // allowing Arc's native isOver() and hover drawables (Tex.checkOver) to work directly.
        Element hitBox = row.hit(10f, 10f, true);
        assertNotNull(hitBox);
        assertTrue(hitBox == quickBox || hitBox.isDescendantOf(quickBox), "Hit on checkbox must resolve to quickBox or its child Image for native hover");

        // 2. Hit test on text region inside textTable's Y bounds (x=100, y=textTable.y + textTable.height/2)
        Element hitText = row.hit(100f, textTable.y + textTable.getHeight() / 2f, true);
        assertNotNull(hitText);
        assertTrue(hitText == textTable || hitText.isDescendantOf(textTable), "Hit on label text must resolve to textTable");
        assertTrue(hitText == textTable || hitText.isDescendantOf(textTable), "Hit on label text must resolve to textTable");

        // 3. Direct click on CheckBox toggles state once
        assertFalse(quickBox.isChecked());
        quickBox.fireClick();
        assertTrue(quickBox.isChecked(), "Clicking checkbox directly toggles to true");
        assertTrue(PackLoaderSettings.isQuickImport());
        assertEquals(1, settingSaves.get());

        // 4. Click on label text toggles state once
        textTable.fireClick();
        assertFalse(quickBox.isChecked(), "Clicking label text toggles to false");
        assertFalse(PackLoaderSettings.isQuickImport());
        assertEquals(2, settingSaves.get());

        // 5. Subsequent clicks toggle cleanly without double-firing
        quickBox.fireClick();
        assertTrue(quickBox.isChecked());
        assertEquals(3, settingSaves.get());

        textTable.fireClick();
        assertFalse(quickBox.isChecked());
        assertEquals(4, settingSaves.get());
    }

    @Test
    public void testNativeHoverAndDiscreteClickHandlingForSkipIdentical(){
        Table skipRow = new Table();
        skipRow.setSize(260f, 30f);

        CheckBox skipBox = new CheckBox("", style);
        skipBox.setSize(24f, 24f);
        skipBox.setChecked(PackLoaderSettings.isSkipIdenticalContent());
        UIUtils.squareCheck(skipBox, 24f);

        AtomicInteger settingSaves = new AtomicInteger(0);
        skipBox.changed(() -> {
            PackLoaderSettings.setSkipIdenticalContent(skipBox.isChecked());
            settingSaves.incrementAndGet();
        });

        skipRow.add(skipBox).size(24f).padRight(10f).center();

        Table skipTextTable = new Table();
        skipTextTable.touchable = Touchable.enabled;
        skipTextTable.add(new Label("Skip identical content"));
        skipTextTable.clicked(() -> {
            skipBox.toggle();
            PackLoaderSettings.setSkipIdenticalContent(skipBox.isChecked());
            settingSaves.incrementAndGet();
        });

        skipRow.add(skipTextTable).growX().left().center();
        skipRow.layout();
        Core.scene.add(skipRow);

        Element hitBox = skipRow.hit(10f, 10f, true);
        assertNotNull(hitBox);
        assertTrue(hitBox == skipBox || hitBox.isDescendantOf(skipBox), "Hit on checkbox returns skipBox or its child Image for native hover");

        Element hitText = skipRow.hit(100f, skipTextTable.y + skipTextTable.getHeight() / 2f, true);
        assertNotNull(hitText);
        assertTrue(hitText == skipTextTable || hitText.isDescendantOf(skipTextTable), "Hit on text returns skipTextTable");

        boolean initial = PackLoaderSettings.isSkipIdenticalContent();

        // Click on skipBox
        skipBox.fireClick();
        assertEquals(!initial, skipBox.isChecked());
        assertEquals(!initial, PackLoaderSettings.isSkipIdenticalContent());
        assertEquals(1, settingSaves.get());

        // Click on label
        skipTextTable.fireClick();
        assertEquals(initial, skipBox.isChecked());
        assertEquals(initial, PackLoaderSettings.isSkipIdenticalContent());
        assertEquals(2, settingSaves.get());
    }

    @Test
    public void testBothRowsStrictlyAlignedOnXAxis(){
        Table container = new Table();
        container.setSize(300f, 100f);

        Table row1 = new Table();
        CheckBox box1 = new CheckBox("", style);
        UIUtils.squareCheck(box1, 24f);
        row1.add(box1).size(24f).padRight(10f);
        Table textTable1 = new Table();
        textTable1.add(new Label("Quick Import"));
        row1.add(textTable1).growX();

        Table row2 = new Table();
        CheckBox box2 = new CheckBox("", style);
        UIUtils.squareCheck(box2, 24f);
        row2.add(box2).size(24f).padRight(10f);
        Table textTable2 = new Table();
        textTable2.add(new Label("Skip identical content"));
        row2.add(textTable2).growX();

        container.add(row1).growX().row();
        container.add(row2).growX().padTop(6f).row();

        container.layout();

        // Both checkboxes start at identical X position (x = 0)
        assertEquals(box1.x, box2.x, 0.001f);
        // Both text tables start at identical X position (x = 24 + 10 = 34)
        assertEquals(textTable1.x, textTable2.x, 0.001f);
    }
}

package packloader.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.TextureRegion;
import arc.mock.MockApplication;
import arc.mock.MockGL20;
import arc.mock.MockGraphics;
import arc.mock.MockSettings;
import arc.scene.Element;
import arc.scene.Scene;
import arc.scene.event.ClickListener;
import arc.scene.event.InputEvent;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.CheckBox;
import arc.scene.ui.CheckBox.CheckBoxStyle;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import mindustry.graphics.Pal;
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
    public void testBugDemonstrationDoubleToggleWhenBoxTouchableEnabled(){
        Table row = new Table();
        row.setSize(300f, 40f);
        row.touchable = Touchable.enabled;

        CheckBox box = new CheckBox("", style);
        box.setSize(24f, 24f);
        box.setChecked(false);
        assertTrue(box.touchable == Touchable.enabled);

        AtomicInteger boxToggles = new AtomicInteger(0);
        box.changed(boxToggles::incrementAndGet);

        AtomicInteger rowClicks = new AtomicInteger(0);
        row.clicked(() -> {
            rowClicks.incrementAndGet();
            box.setChecked(!box.isChecked());
        });

        row.add(box).size(24f);
        Core.scene.add(row);

        Element hitTarget = row.hit(10f, 10f, true);
        assertEquals(box, hitTarget, "When box.touchable is enabled, hit directly returns the CheckBox");
    }

    @Test
    public void testFixedRowHandlingDirectBoxClickAndLabelClickToggleCleanly(){
        Table row = new Table();
        row.setSize(300f, 40f);
        row.touchable = Touchable.enabled;

        CheckBox box = new CheckBox("", style);
        box.setSize(24f, 24f);
        box.setChecked(false);
        box.touchable = Touchable.disabled;
        UIUtils.squareCheck(box, 24f);

        row.add(box).size(24f).padRight(10f);

        Table textTable = new Table();
        textTable.touchable = Touchable.disabled;
        Label label = new Label("Quick Import");
        label.touchable = Touchable.disabled;
        textTable.add(label);
        row.add(textTable).growX();

        AtomicInteger toggleCount = new AtomicInteger(0);
        row.clicked(() -> {
            box.setChecked(!box.isChecked());
            PackLoaderSettings.setQuickImport(box.isChecked());
            toggleCount.incrementAndGet();
        });

        row.layout();
        Core.scene.add(row);

        Element hitBox = row.hit(10f, 10f, true);
        assertEquals(row, hitBox, "Hit on checkbox area must resolve to row");

        Element hitText = row.hit(100f, 10f, true);
        assertEquals(row, hitText, "Hit on text area must resolve to row");

        assertFalse(box.isChecked());
        assertFalse(PackLoaderSettings.isQuickImport());

        row.fireClick();
        assertTrue(box.isChecked(), "First click must toggle box to true");
        assertTrue(PackLoaderSettings.isQuickImport(), "Setting must be persisted as true");
        assertEquals(1, toggleCount.get());

        row.fireClick();
        assertFalse(box.isChecked(), "Second click must toggle box to false");
        assertFalse(PackLoaderSettings.isQuickImport(), "Setting must be persisted as false");
        assertEquals(2, toggleCount.get());

        row.fireClick();
        assertTrue(box.isChecked(), "Third click must toggle box back to true");
        assertTrue(PackLoaderSettings.isQuickImport(), "Setting must be persisted as true");
        assertEquals(3, toggleCount.get());
    }

    @Test
    public void testSkipIdenticalContentRowHitboxAndToggle(){
        Table skipRow = new Table();
        skipRow.setSize(260f, 30f);
        skipRow.touchable = Touchable.enabled;

        CheckBox skipBox = new CheckBox("", style);
        skipBox.setSize(24f, 24f);
        skipBox.setChecked(PackLoaderSettings.isSkipIdenticalContent());
        skipBox.touchable = Touchable.disabled;
        UIUtils.squareCheck(skipBox, 24f);

        skipRow.add(skipBox).size(24f).padRight(10f);

        Table skipTextTable = new Table();
        skipTextTable.touchable = Touchable.disabled;
        skipTextTable.add(new Label("Skip identical content"));
        skipRow.add(skipTextTable).growX();

        skipRow.clicked(() -> {
            skipBox.setChecked(!skipBox.isChecked());
            PackLoaderSettings.setSkipIdenticalContent(skipBox.isChecked());
        });

        skipRow.layout();
        Core.scene.add(skipRow);

        assertEquals(skipRow, skipRow.hit(10f, 10f, true), "Hit on skipBox area must resolve to skipRow");
        assertEquals(skipRow, skipRow.hit(100f, 10f, true), "Hit on skipText area must resolve to skipRow");

        boolean initial = PackLoaderSettings.isSkipIdenticalContent();
        skipRow.fireClick();
        assertEquals(!initial, skipBox.isChecked());
        assertEquals(!initial, PackLoaderSettings.isSkipIdenticalContent());

        skipRow.fireClick();
        assertEquals(initial, skipBox.isChecked());
        assertEquals(initial, PackLoaderSettings.isSkipIdenticalContent());
    }

    @Test
    public void testBothRowsStrictlyAlignedOnXAxis(){
        Table container = new Table();
        container.setSize(300f, 100f);

        Table row1 = new Table();
        CheckBox box1 = new CheckBox("", style);
        box1.touchable = Touchable.disabled;
        UIUtils.squareCheck(box1, 24f);
        row1.add(box1).size(24f).padRight(10f);
        Table textTable1 = new Table();
        textTable1.add(new Label("Quick Import"));
        row1.add(textTable1).growX();

        Table row2 = new Table();
        CheckBox box2 = new CheckBox("", style);
        box2.touchable = Touchable.disabled;
        UIUtils.squareCheck(box2, 24f);
        row2.add(box2).size(24f).padRight(10f);
        Table textTable2 = new Table();
        textTable2.add(new Label("Skip identical content"));
        row2.add(textTable2).growX();

        container.add(row1).growX().row();
        container.add(row2).growX().padTop(6f).row();

        container.layout();

        // Both checkboxes must have identical X positions (x = 0)
        assertEquals(box1.x, box2.x, 0.001f, "Both checkboxes must start at identical X position");
        // Both text tables must have identical X positions (x = 24 + 10 = 34)
        assertEquals(textTable1.x, textTable2.x, 0.001f, "Both label tables must start at identical X position");
    }

    @Test
    public void testHoverAndPressedColorFeedbackDoesNotShiftLayout(){
        Table row = new Table();
        row.setSize(300f, 40f);

        CheckBox box = new CheckBox("", style);
        box.touchable = Touchable.disabled;
        box.color.set(Color.white);
        UIUtils.squareCheck(box, 24f);
        row.add(box).size(24f).padRight(10f);

        Label label = new Label("Quick Import");
        label.color.set(Color.white);
        row.add(label).growX();

        row.layout();

        float initialBoxX = box.x;
        float initialBoxY = box.y;
        float initialBoxW = box.getWidth();
        float initialBoxH = box.getHeight();

        float initialLabelX = label.x;
        float initialLabelY = label.y;
        float initialLabelW = label.getWidth();
        float initialLabelH = label.getHeight();

        // Simulate hover update
        box.color.set(Pal.accent);
        label.color.set(Pal.accent);

        // Layout / dimensions must NOT change when color changes
        assertEquals(initialBoxX, box.x, 0.001f);
        assertEquals(initialBoxY, box.y, 0.001f);
        assertEquals(initialBoxW, box.getWidth(), 0.001f);
        assertEquals(initialBoxH, box.getHeight(), 0.001f);

        assertEquals(initialLabelX, label.x, 0.001f);
        assertEquals(initialLabelY, label.y, 0.001f);
        assertEquals(initialLabelW, label.getWidth(), 0.001f);
        assertEquals(initialLabelH, label.getHeight(), 0.001f);

        // Simulate pressed update
        box.color.set(Pal.accent).mul(0.85f);
        label.color.set(Pal.accent).mul(0.85f);

        assertEquals(initialBoxX, box.x, 0.001f);
        assertEquals(initialLabelX, label.x, 0.001f);
    }
}

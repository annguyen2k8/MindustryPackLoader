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
    public void testBugDemonstrationDoubleToggleWhenBoxTouchableEnabled(){
        // When CheckBox touchable is enabled inside a clickable row:
        // clicking the box triggers both the CheckBox's internal click listener and the row's click listener.
        Table row = new Table();
        row.setSize(300f, 40f);
        row.touchable = Touchable.enabled;

        CheckBox box = new CheckBox("", style);
        box.setSize(26f, 26f);
        box.setChecked(false);
        // Default: box.touchable is Touchable.enabled
        assertTrue(box.touchable == Touchable.enabled);

        AtomicInteger boxToggles = new AtomicInteger(0);
        box.changed(boxToggles::incrementAndGet);

        AtomicInteger rowClicks = new AtomicInteger(0);
        row.clicked(() -> {
            rowClicks.incrementAndGet();
            box.setChecked(!box.isChecked());
        });

        row.add(box).size(26f);
        Core.scene.add(row);

        // Hit test on the checkbox region returns the checkbox itself
        Element hitTarget = row.hit(10f, 10f, true);
        assertEquals(box, hitTarget, "When box.touchable is enabled, hit directly returns the CheckBox");
    }

    @Test
    public void testFixedRowHandlingDirectBoxClickAndLabelClickToggleCleanly(){
        // Recreate the fixed layout pattern:
        // box.touchable = Touchable.disabled
        // textTable.touchable = Touchable.disabled
        // row.touchable = Touchable.enabled with single click listener
        Table row = new Table();
        row.setSize(300f, 40f);
        row.touchable = Touchable.enabled;

        CheckBox box = new CheckBox("", style);
        box.setSize(26f, 26f);
        box.setChecked(false);
        box.touchable = Touchable.disabled; // Fix applied
        UIUtils.squareCheck(box, 26f);

        row.add(box).size(26f).padRight(10f);

        Table textTable = new Table();
        textTable.touchable = Touchable.disabled; // Fix applied
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

        // 1. Hit test directly on the CheckBox area (x=10, y=10)
        // Because box is Touchable.disabled, hit falls through directly to the parent row
        Element hitBox = row.hit(10f, 10f, true);
        assertEquals(row, hitBox, "Hit on checkbox area must resolve to row");

        // 2. Hit test on the label/text area (x=100, y=10)
        // Because textTable is Touchable.disabled, hit falls through directly to row
        Element hitText = row.hit(100f, 10f, true);
        assertEquals(row, hitText, "Hit on text area must resolve to row");

        // 3. Simulate click via row's click listeners (toggle 1: false -> true)
        assertFalse(box.isChecked());
        assertFalse(PackLoaderSettings.isQuickImport());

        row.fireClick();
        assertTrue(box.isChecked(), "First click must toggle box to true");
        assertTrue(PackLoaderSettings.isQuickImport(), "Setting must be persisted as true");
        assertEquals(1, toggleCount.get());

        // 4. Simulate click again (toggle 2: true -> false)
        row.fireClick();
        assertFalse(box.isChecked(), "Second click must toggle box to false");
        assertFalse(PackLoaderSettings.isQuickImport(), "Setting must be persisted as false");
        assertEquals(2, toggleCount.get());

        // 5. Simulate click again (toggle 3: false -> true)
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
        skipBox.setSize(22f, 22f);
        skipBox.setChecked(PackLoaderSettings.isSkipIdenticalContent());
        skipBox.touchable = Touchable.disabled;
        UIUtils.squareCheck(skipBox, 22f);

        skipRow.add(skipBox).size(22f).padRight(8f);

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

        // Hit tests
        assertEquals(skipRow, skipRow.hit(10f, 10f, true), "Hit on skipBox area must resolve to skipRow");
        assertEquals(skipRow, skipRow.hit(100f, 10f, true), "Hit on skipText area must resolve to skipRow");

        // Toggle verification
        boolean initial = PackLoaderSettings.isSkipIdenticalContent();
        skipRow.fireClick();
        assertEquals(!initial, skipBox.isChecked());
        assertEquals(!initial, PackLoaderSettings.isSkipIdenticalContent());

        skipRow.fireClick();
        assertEquals(initial, skipBox.isChecked());
        assertEquals(initial, PackLoaderSettings.isSkipIdenticalContent());
    }
}

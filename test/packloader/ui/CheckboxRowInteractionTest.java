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
import arc.scene.ui.Dialog;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;
import packloader.model.DuplicatePolicy;
import packloader.model.PackType;
import packloader.util.PackLoaderSettings;

import java.util.Locale;
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
        TextButton.TextButtonStyle textButtonStyle = new TextButton.TextButtonStyle();
        textButtonStyle.font = dummyFont;
        Core.scene.addStyle(TextButton.TextButtonStyle.class, textButtonStyle);
        Core.scene.addStyle(ImageButton.ImageButtonStyle.class, new ImageButton.ImageButtonStyle());
        Core.scene.addStyle(CheckBoxStyle.class, style);
        Core.scene.addStyle(Label.LabelStyle.class, new Label.LabelStyle(dummyFont, arc.graphics.Color.white));

        Dialog.DialogStyle dialogStyle = new Dialog.DialogStyle();
        dialogStyle.titleFont = dummyFont;
        Core.scene.addStyle(Dialog.DialogStyle.class, dialogStyle);

        TestHelper.initBundle(Locale.ENGLISH);

        if(mindustry.ui.Styles.defaultLabel == null){
            mindustry.ui.Styles.defaultLabel = new Label.LabelStyle(dummyFont, arc.graphics.Color.white);
        }
        if(mindustry.ui.Styles.flatTogglet == null){
            mindustry.ui.Styles.flatTogglet = new TextButton.TextButtonStyle();
            mindustry.ui.Styles.flatTogglet.font = dummyFont;
        }
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
        Table container = new Table();
        container.setSize(300f, 60f);

        CheckBox quickBox = new CheckBox("", style);
        quickBox.setSize(300f, 40f);
        quickBox.setChecked(false);
        quickBox.clearChildren();
        quickBox.left().defaults().left();
        quickBox.margin(0f);

        quickBox.add(quickBox.getImage()).size(24f).padRight(10f).center();
        UIUtils.squareCheck(quickBox, 24f);

        AtomicInteger settingSaves = new AtomicInteger(0);
        quickBox.changed(() -> {
            PackLoaderSettings.setQuickImport(quickBox.isChecked());
            settingSaves.incrementAndGet();
        });

        Table textTable = new Table();
        textTable.left().defaults().left().growX();
        textTable.touchable = Touchable.disabled;
        Label label = new Label("Quick Import");
        label.touchable = Touchable.disabled;
        textTable.add(label);

        quickBox.add(textTable).growX().left().center();
        container.add(quickBox).growX().row();
        container.layout();
        Core.scene.add(container);

        // 1. Hit test on checkbox circle (x=10, y=center) resolves to quickBox or its child Image
        float testY = quickBox.y + quickBox.getHeight() / 2f;
        Element hitBox = container.hit(quickBox.x + 10f, testY, true);
        assertNotNull(hitBox);
        assertTrue(hitBox == quickBox || hitBox.isDescendantOf(quickBox), "Hit on checkbox circle must resolve to quickBox or its child Image");

        // 2. Hit test on label text (x=100, y=center) also resolves to quickBox (since textTable is touchable disabled)
        Element hitText = container.hit(quickBox.x + 100f, testY, true);
        assertNotNull(hitText);
        assertEquals(quickBox, hitText, "Hit on label text must resolve directly to composite quickBox");

        // 3. Unified native Mindustry hover state across both circle and label
        // Hover on circle:
        quickBox.getClickListener().enter(new arc.scene.event.InputEvent(), 10f, 10f, -1, null);
        assertTrue(quickBox.isOver(), "Hover on circle must activate quickBox.isOver()");
        quickBox.getClickListener().exit(new arc.scene.event.InputEvent(), 10f, 10f, -1, null);
        assertFalse(quickBox.isOver(), "Exit must clear quickBox.isOver()");

        // Hover on label text:
        quickBox.getClickListener().enter(new arc.scene.event.InputEvent(), 100f, 10f, -1, null);
        assertTrue(quickBox.isOver(), "Hover on label text must activate quickBox.isOver() on the unified CheckBox");
        quickBox.getClickListener().exit(new arc.scene.event.InputEvent(), 100f, 10f, -1, null);
        assertFalse(quickBox.isOver(), "Exit must clear quickBox.isOver()");

        // 4. Clicking anywhere (circle or label) toggles state exactly once
        assertFalse(quickBox.isChecked());
        quickBox.fireClick();
        assertTrue(quickBox.isChecked(), "Clicking checkbox directly toggles to true");
        assertTrue(PackLoaderSettings.isQuickImport());
        assertEquals(1, settingSaves.get());

        quickBox.fireClick();
        assertFalse(quickBox.isChecked(), "Clicking label text area toggles to false");
        assertFalse(PackLoaderSettings.isQuickImport());
        assertEquals(2, settingSaves.get());

        // 5. Verify single ClickListener ownership and no redundant listeners on textTable
        assertNotNull(quickBox.getClickListener());
        assertEquals(0, textTable.getListeners().size, "No redundant ClickListener on textTable");
    }

    @Test
    public void testNativeHoverAndDiscreteClickHandlingForSkipIdentical(){
        Table container = new Table();
        container.setSize(260f, 40f);

        CheckBox skipBox = new CheckBox("", style);
        skipBox.setSize(260f, 26f);
        skipBox.setChecked(PackLoaderSettings.isSkipIdenticalContent());
        skipBox.clearChildren();
        skipBox.left().defaults().left();
        skipBox.margin(0f);

        skipBox.add(skipBox.getImage()).size(24f).padRight(10f).center();
        UIUtils.squareCheck(skipBox, 24f);

        AtomicInteger settingSaves = new AtomicInteger(0);
        skipBox.changed(() -> {
            PackLoaderSettings.setSkipIdenticalContent(skipBox.isChecked());
            settingSaves.incrementAndGet();
        });

        Table skipTextTable = new Table();
        skipTextTable.left().defaults().left().growX();
        skipTextTable.touchable = Touchable.disabled;
        Label skipLabel = new Label("Skip identical content");
        skipLabel.touchable = Touchable.disabled;
        skipTextTable.add(skipLabel);

        skipBox.add(skipTextTable).growX().left().center();
        container.add(skipBox).growX().row();
        container.layout();
        Core.scene.add(container);

        float testY = skipBox.y + skipBox.getHeight() / 2f;
        Element hitBox = container.hit(skipBox.x + 10f, testY, true);
        assertNotNull(hitBox);
        assertTrue(hitBox == skipBox || hitBox.isDescendantOf(skipBox), "Hit on checkbox returns skipBox or its child Image");

        Element hitText = container.hit(skipBox.x + 100f, testY, true);
        assertNotNull(hitText);
        assertEquals(skipBox, hitText, "Hit on text returns skipBox");

        // Hover test on circle and text
        skipBox.getClickListener().enter(new arc.scene.event.InputEvent(), 10f, 10f, -1, null);
        assertTrue(skipBox.isOver(), "Hover on circle activates skipBox.isOver()");
        skipBox.getClickListener().exit(new arc.scene.event.InputEvent(), 10f, 10f, -1, null);
        assertFalse(skipBox.isOver());

        skipBox.getClickListener().enter(new arc.scene.event.InputEvent(), 100f, 10f, -1, null);
        assertTrue(skipBox.isOver(), "Hover on text activates skipBox.isOver()");
        skipBox.getClickListener().exit(new arc.scene.event.InputEvent(), 100f, 10f, -1, null);
        assertFalse(skipBox.isOver());

        boolean initial = PackLoaderSettings.isSkipIdenticalContent();

        // Click toggles once
        skipBox.fireClick();
        assertEquals(!initial, skipBox.isChecked());
        assertEquals(!initial, PackLoaderSettings.isSkipIdenticalContent());
        assertEquals(1, settingSaves.get());

        // Subsequent click toggles once
        skipBox.fireClick();
        assertEquals(initial, skipBox.isChecked());
        assertEquals(initial, PackLoaderSettings.isSkipIdenticalContent());
        assertEquals(2, settingSaves.get());

        assertNotNull(skipBox.getClickListener());
        assertEquals(0, skipTextTable.getListeners().size, "No redundant ClickListener on textTable");
    }

    @Test
    public void testBothRowsStrictlyAlignedOnXAxis(){
        Table container = new Table();
        container.setSize(300f, 100f);

        CheckBox box1 = new CheckBox("", style);
        box1.clearChildren();
        box1.left().defaults().left();
        box1.margin(0f);
        box1.add(box1.getImage()).size(24f).padRight(10f).center();
        UIUtils.squareCheck(box1, 24f);
        Table textTable1 = new Table();
        textTable1.add(new Label("Quick Import"));
        box1.add(textTable1).growX();

        CheckBox box2 = new CheckBox("", style);
        box2.clearChildren();
        box2.left().defaults().left();
        box2.margin(0f);
        box2.add(box2.getImage()).size(24f).padRight(10f).center();
        UIUtils.squareCheck(box2, 24f);
        Table textTable2 = new Table();
        textTable2.add(new Label("Skip identical content"));
        box2.add(textTable2).growX();

        container.add(box1).growX().row();
        container.add(box2).growX().padTop(6f).row();

        container.layout();

        // Both checkboxes start at identical X position (x = 0)
        assertEquals(box1.x, box2.x, 0.001f);
        // Both checkbox circles start at identical X position (x = 0)
        assertEquals(box1.getImage().x, box2.getImage().x, 0.001f);
        // Both text tables start at identical X position (x = 24 + 10 = 34)
        assertEquals(textTable1.x, textTable2.x, 0.001f);
        assertEquals(34f, textTable1.x, 0.001f);
    }

    private Table buildQuickImportConfigCard(PackType type, float rowWidth){
        Table card = new Table();
        card.left().defaults().left().growX();

        CheckBox quickBox = new CheckBox("", style);
        quickBox.setChecked(PackLoaderSettings.isQuickImport());
        quickBox.clearChildren();
        quickBox.left().defaults().left();
        quickBox.margin(0f);
        quickBox.add(quickBox.getImage()).size(24f).padRight(10f).center();
        UIUtils.squareCheck(quickBox, 24f);

        Table textTable = new Table();
        textTable.touchable = Touchable.disabled;
        textTable.add(new Label("Quick Import"));
        quickBox.add(textTable).growX().left().center();
        card.add(quickBox).growX().row();

        Table conflictContainer = new Table();
        conflictContainer.left().defaults().left();

        Runnable updateConflictVisibility = () -> {
            conflictContainer.clearChildren();
            if(quickBox.isChecked() && type != null){
                conflictContainer.margin(2f, 34f, 4f, 0f);
                for(DuplicatePolicy p : DuplicatePolicy.values()){
                    Button b = new Button();
                    b.add(new Label(p.name()));
                    b.clicked(() -> PackLoaderSettings.setDuplicatePolicy(type, p));
                    conflictContainer.add(b).size(60f, 30f);
                }
            }else{
                conflictContainer.margin(0f);
            }
            conflictContainer.invalidateHierarchy();
        };

        quickBox.changed(() -> {
            PackLoaderSettings.setQuickImport(quickBox.isChecked());
            updateConflictVisibility.run();
        });

        updateConflictVisibility.run();
        card.add(conflictContainer).growX().row();

        CheckBox skipBox = new CheckBox("", style);
        skipBox.setChecked(PackLoaderSettings.isSkipIdenticalContent());
        skipBox.changed(() -> PackLoaderSettings.setSkipIdenticalContent(skipBox.isChecked()));
        skipBox.clearChildren();
        skipBox.left().defaults().left();
        skipBox.margin(0f);
        skipBox.add(skipBox.getImage()).size(24f).padRight(10f).center();
        UIUtils.squareCheck(skipBox, 24f);

        Table skipTextTable = new Table();
        skipTextTable.touchable = Touchable.disabled;
        skipTextTable.add(new Label("Skip identical content"));
        skipBox.add(skipTextTable).growX().left().center();
        card.add(skipBox).growX().padTop(6f).row();

        return card;
    }

    @Test
    public void testDynamicConflictPolicyContainerVisibilityWhenQuickImportToggled(){
        // 1. Initial state: Quick Import is OFF by default
        PackLoaderSettings.setQuickImport(false);
        Table card = buildQuickImportConfigCard(PackType.SCHEMATIC, 360f);
        assertNotNull(card);

        // Find quickBox and conflictContainer
        CheckBox quickBox = (CheckBox)card.getChildren().get(0);
        Table conflictContainer = (Table)card.getChildren().get(1);

        // When Quick Import is OFF: conflict container must have 0 children (hidden) and 0 margin
        assertFalse(quickBox.isChecked());
        assertEquals(0, conflictContainer.getChildren().size, "Conflict container must be empty when Quick Import is OFF");
        assertEquals(0f, conflictContainer.getMarginLeft(), 0.001f);

        // 2. Toggle Quick Import ON: conflict container must reactively populate
        quickBox.fireClick();
        assertTrue(quickBox.isChecked());
        assertTrue(PackLoaderSettings.isQuickImport());
        assertEquals(DuplicatePolicy.values().length, conflictContainer.getChildren().size, "Conflict container must be populated when Quick Import is ON");
        assertEquals(34f, conflictContainer.getMarginLeft(), 0.001f, "Conflict container must be indented by 34f");

        // 3. Toggle Quick Import OFF: conflict container must reactively clear
        quickBox.fireClick();
        assertFalse(quickBox.isChecked());
        assertFalse(PackLoaderSettings.isQuickImport());
        assertEquals(0, conflictContainer.getChildren().size, "Conflict container must be cleared when Quick Import is OFF");
        assertEquals(0f, conflictContainer.getMarginLeft(), 0.001f);
    }

    @Test
    public void testConflictPolicyPreservedAcrossToggleAndSettings(){
        // Set policy to OVERWRITE
        PackLoaderSettings.setDuplicatePolicy(PackType.SCHEMATIC, DuplicatePolicy.OVERWRITE);
        assertEquals(DuplicatePolicy.OVERWRITE, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC));

        PackLoaderSettings.setQuickImport(true);
        Table card = buildQuickImportConfigCard(PackType.SCHEMATIC, 360f);

        CheckBox quickBox = (CheckBox)card.getChildren().get(0);
        Table conflictContainer = (Table)card.getChildren().get(1);

        assertTrue(quickBox.isChecked());
        assertEquals(DuplicatePolicy.values().length, conflictContainer.getChildren().size);

        // Turn Quick Import OFF -> hidden
        quickBox.fireClick();
        assertFalse(quickBox.isChecked());
        assertEquals(0, conflictContainer.getChildren().size);

        // Verify policy is NOT lost or reset to default (SKIP)
        assertEquals(DuplicatePolicy.OVERWRITE, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC), "Policy must be preserved in settings when hidden");

        // Turn Quick Import back ON -> repopulated
        quickBox.fireClick();
        assertTrue(quickBox.isChecked());
        assertEquals(DuplicatePolicy.values().length, conflictContainer.getChildren().size);
        assertEquals(DuplicatePolicy.OVERWRITE, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC), "Policy must still be OVERWRITE after re-showing");
    }

    @Test
    public void testPolicyConsistencyBetweenConfigurationAndReviewScreen(){
        PackLoaderSettings.setDuplicatePolicy(PackType.MAP, DuplicatePolicy.RENAME);
        PackLoaderSettings.setDuplicatePolicy(PackType.SCHEMATIC, DuplicatePolicy.OVERWRITE);

        assertEquals(DuplicatePolicy.RENAME, PackLoaderSettings.getDuplicatePolicy(PackType.MAP));
        assertEquals(DuplicatePolicy.OVERWRITE, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC));

        // When changing policy, it persists to settings
        PackLoaderSettings.setDuplicatePolicy(PackType.MAP, DuplicatePolicy.SKIP);
        assertEquals(DuplicatePolicy.SKIP, PackLoaderSettings.getDuplicatePolicy(PackType.MAP));

        PackLoaderSettings.setDuplicatePolicy(PackType.SCHEMATIC, DuplicatePolicy.RENAME);
        assertEquals(DuplicatePolicy.RENAME, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC));
    }
}

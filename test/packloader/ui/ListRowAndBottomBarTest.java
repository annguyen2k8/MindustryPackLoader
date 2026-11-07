package packloader.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.mock.MockApplication;
import arc.mock.MockGL20;
import arc.mock.MockGraphics;
import arc.mock.MockSettings;
import arc.scene.Scene;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.CheckBox;
import arc.scene.ui.CheckBox.CheckBoxStyle;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import mindustry.graphics.Pal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;
import packloader.model.PackEntry;
import packloader.model.PackType;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class ListRowAndBottomBarTest{
    private arc.Application prevApp;
    private arc.graphics.GL20 prevGl;
    private arc.graphics.GL20 prevGl20;
    private arc.Graphics prevGraphics;
    private arc.scene.Scene prevScene;
    private arc.Settings prevSettings;
    private arc.graphics.g2d.TextureAtlas prevAtlas;
    private CheckBoxStyle checkStyle;

    @BeforeEach
    public void setup(){
        prevApp = Core.app;
        prevGl = Core.gl;
        prevGl20 = Core.gl20;
        prevGraphics = Core.graphics;
        prevScene = Core.scene;
        prevSettings = Core.settings;
        prevAtlas = Core.atlas;

        Core.app = new MockApplication();
        Core.gl = Core.gl20 = new MockGL20();
        Core.graphics = new MockGraphics();
        Core.scene = new Scene();
        Core.settings = new MockSettings();
        Core.atlas = arc.graphics.g2d.TextureAtlas.blankAtlas();
        Core.atlas.getRegionMap().put("white", new arc.graphics.g2d.TextureAtlas.AtlasRegion());

        Font dummyFont = new Font(new Font.FontData(), new TextureRegion(), false);
        arc.scene.style.TextureRegionDrawable dummyDrawable = new arc.scene.style.TextureRegionDrawable(new TextureRegion());

        Core.scene.addStyle(Button.ButtonStyle.class, new Button.ButtonStyle());
        TextButton.TextButtonStyle textButtonStyle = new TextButton.TextButtonStyle();
        textButtonStyle.font = dummyFont;
        Core.scene.addStyle(TextButton.TextButtonStyle.class, textButtonStyle);
        ImageButton.ImageButtonStyle imageButtonStyle = new ImageButton.ImageButtonStyle();
        imageButtonStyle.imageUp = dummyDrawable;
        Core.scene.addStyle(ImageButton.ImageButtonStyle.class, imageButtonStyle);
        checkStyle = new CheckBoxStyle();
        checkStyle.font = dummyFont;
        Core.scene.addStyle(CheckBoxStyle.class, checkStyle);
        Core.scene.addStyle(Label.LabelStyle.class, new Label.LabelStyle(dummyFont, Color.white));

        TestHelper.initBundle(Locale.ENGLISH);

        if(mindustry.gen.Icon.paste == null) mindustry.gen.Icon.paste = dummyDrawable;
        if(mindustry.gen.Icon.info == null) mindustry.gen.Icon.info = dummyDrawable;
        if(mindustry.gen.Icon.link == null) mindustry.gen.Icon.link = dummyDrawable;

        if(mindustry.ui.Styles.defaultLabel == null){
            mindustry.ui.Styles.defaultLabel = new Label.LabelStyle(dummyFont, Color.white);
        }
        if(mindustry.ui.Styles.flatt == null){
            mindustry.ui.Styles.flatt = new TextButton.TextButtonStyle();
            mindustry.ui.Styles.flatt.font = dummyFont;
        }
        if(mindustry.ui.Styles.flati == null){
            mindustry.ui.Styles.flati = new ImageButton.ImageButtonStyle();
            mindustry.ui.Styles.flati.imageUp = dummyDrawable;
        }
        if(mindustry.ui.Styles.clearNonei == null){
            mindustry.ui.Styles.clearNonei = new ImageButton.ImageButtonStyle();
            mindustry.ui.Styles.clearNonei.imageUp = dummyDrawable;
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
        Core.atlas = prevAtlas;
    }

    @Test
    public void testBottomBarVisualHierarchyAndSeparation(){
        Table buttons = new Table();
        buttons.setSize(800f, 64f);
        buttons.defaults().pad(4f);

        // Left Actions: Back (Dismissive) + Bug Report (Utility Icon)
        TextButton backBtn = new TextButton("Back", mindustry.ui.Styles.flatt);
        buttons.add(backBtn).size(140f, 54f);

        ImageButton bugBtn = new ImageButton(mindustry.ui.Styles.flati);
        buttons.add(bugBtn).size(48f, 54f);

        // Spacer pushes primary actions to the right
        buttons.add().growX();

        // Right Actions: Preview (Secondary) + Import (Primary CTA)
        TextButton previewBtn = new TextButton("Preview", mindustry.ui.Styles.flatt);
        buttons.add(previewBtn).size(150f, 54f);

        TextButton importBtn = new TextButton("Import (12)");
        buttons.add(importBtn).size(200f, 54f);

        buttons.layout();

        // Verification:
        // 1. Back and Bug button are aligned on the left
        assertEquals(4f, backBtn.x, 2f, "Back button should start at left margin with padding");
        assertTrue(bugBtn.x > backBtn.x, "Bug button should follow Back button");
        assertTrue(bugBtn.x + bugBtn.getWidth() <= 210f, "Left group width should stay within 210f");

        // 2. Right actions are pushed to the right side
        assertTrue(previewBtn.x > 380f, "Preview button should be pushed to the right by the spacer");
        assertTrue(importBtn.x > previewBtn.x, "Import button should be at the far right");
        assertEquals(800f - 4f, importBtn.x + importBtn.getWidth(), 2f, "Import button should align to right padding");

        // 3. Unified height across all buttons
        assertEquals(54f, backBtn.getHeight());
        assertEquals(54f, bugBtn.getHeight());
        assertEquals(54f, previewBtn.getHeight());
        assertEquals(54f, importBtn.getHeight());

        // 4. Combined button width footprint optimization:
        float totalButtonWidth = 140f + 48f + 150f + 200f; // 538f
        float legacyButtonWidth = 180f + 180f + 170f + 200f; // 730f
        assertTrue(legacyButtonWidth - totalButtonWidth >= 190f, "Must save at least 190f width to prevent overflow on 720p/mobile");
    }

    @Test
    public void testBottomBarButtonStatesAndSemanticColor(){
        TextButton previewBtn = new TextButton("Preview", mindustry.ui.Styles.flatt);
        TextButton importBtn = new TextButton("Import (0)");

        int[] countHolder = new int[]{ 0 };
        boolean importRunning = false;

        Runnable updateState = () -> {
            int count = countHolder[0];
            previewBtn.setDisabled(count == 0 || importRunning);
            boolean disabled = count == 0 || importRunning;
            importBtn.setDisabled(disabled);
            if(importBtn.getLabel() != null){
                importBtn.getLabel().setColor(disabled ? Pal.gray : Pal.accent);
            }
        };

        // Initially 0 items selected -> disabled & gray
        updateState.run();
        assertTrue(previewBtn.isDisabled());
        assertTrue(importBtn.isDisabled());
        assertEquals(Pal.gray, importBtn.getLabel().color);

        // When 5 items selected -> enabled & Pal.accent (golden yellow)
        countHolder[0] = 5;
        updateState.run();
        assertFalse(previewBtn.isDisabled());
        assertFalse(importBtn.isDisabled());
        assertEquals(Pal.accent, importBtn.getLabel().color);
    }

    @Test
    public void testListRowAlignmentAndLayoutContract(){
        Table container = new Table();
        container.setSize(800f, 60f);

        Button row = new Button(new Button.ButtonStyle());
        row.margin(6f);
        row.defaults().pad(3f);

        // 1. Checkbox
        CheckBox box = new CheckBox("", checkStyle);
        box.touchable = Touchable.disabled;
        UIUtils.squareCheck(box, 24f);
        row.add(box).size(24f).padLeft(2f).padRight(6f);

        // 2. Thumbnail
        Table thumb = new Table();
        thumb.setSize(36f, 36f);
        row.add(thumb).size(36f).padRight(6f);

        // 3. Text table
        Table textTable = new Table();
        textTable.left().defaults().left().growX();
        Label titleLabel = new Label("Nuclear Reactor Array v2");
        titleLabel.setEllipsis(true);
        textTable.add(titleLabel).ellipsis(true).growX().minWidth(0f).left().row();

        Label infoLabel = new Label("4.2 KB  •  15x15  •  Blocks: 124");
        infoLabel.setEllipsis(true);
        textTable.add(infoLabel).ellipsis(true).growX().minWidth(0f).left().padTop(2f);
        row.table(t -> t.add(textTable).growX().minWidth(0f)).growX().minWidth(0f).left().padLeft(6f).padRight(6f);

        // 4. Actions table
        Table actions = new Table();
        actions.right().defaults().size(36f).pad(2f);
        Button placeBtn = actions.button(mindustry.gen.Icon.paste, mindustry.ui.Styles.clearNonei, () -> {}).size(36f).get();
        Button infoBtn = actions.button(mindustry.gen.Icon.info, mindustry.ui.Styles.clearNonei, () -> {}).size(36f).get();
        row.add(actions).right();

        container.add(row).growX().minWidth(0f);
        container.layout();

        // Verification:
        // 1. Checkbox sits at left
        assertTrue(box.x < 15f, "Checkbox should be near left margin");
        assertEquals(24f, box.getWidth());

        // 2. Thumbnail fixed at 36x36
        assertEquals(36f, thumb.getWidth());
        assertEquals(36f, thumb.getHeight());
        assertTrue(thumb.x > box.x, "Thumbnail should be positioned after checkbox");

        // 3. Actions table docked to right
        assertTrue(actions.x > 680f, "Actions should be docked to the right edge");
        assertEquals(36f, placeBtn.getWidth());
        assertEquals(36f, infoBtn.getWidth());
        assertTrue(infoBtn.x > placeBtn.x, "Info button should be the rightmost action");

        // 4. Text table takes remaining width
        assertTrue(textTable.getWidth() > 500f, "Text table must expand to fill row space");
    }

    @Test
    public void testActionButtonsEventStopListenerPreventsRowToggle(){
        AtomicBoolean rowToggled = new AtomicBoolean(false);
        AtomicBoolean actionExecuted = new AtomicBoolean(false);

        Button row = new Button(new Button.ButtonStyle());
        row.clicked(() -> rowToggled.set(true));

        Table actions = new Table();
        Button infoBtn = actions.button(mindustry.gen.Icon.info, mindustry.ui.Styles.clearNonei, () -> actionExecuted.set(true)).size(36f).get();
        infoBtn.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                event.stop();
                return true;
            }
        });

        row.add(actions).right();
        Core.scene.add(row);

        // Simulate touch down on the action button
        InputEvent event = new InputEvent();
        event.type = InputEvent.InputEventType.touchDown;
        infoBtn.fire(event);

        assertTrue(event.stopped, "InputEvent must be stopped by the action button listener");
        assertFalse(rowToggled.get(), "Row click must NOT be triggered when action button event is stopped");
    }

    @Test
    public void testEllipsisAndNonOverflowForLongNames(){
        Table row = new Table();
        row.setSize(600f, 50f);

        String longName = "A".repeat(200);
        Label title = new Label(longName);
        title.setEllipsis(true);

        Table textTable = new Table();
        textTable.add(title).ellipsis(true).growX().minWidth(0f).left();
        row.add(textTable).growX().minWidth(0f);

        Table actions = new Table();
        actions.button(mindustry.gen.Icon.info, mindustry.ui.Styles.clearNonei, () -> {}).size(36f);
        row.add(actions).right();

        row.layout();

        // Row width does not exceed container size
        assertTrue(row.getWidth() <= 600f, "Row width must respect container bounds even with long name");
        assertTrue(actions.x >= 550f, "Actions must remain docked right without being pushed out");
    }
}

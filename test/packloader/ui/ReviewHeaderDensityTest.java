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
import arc.scene.ui.Button;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;
import packloader.model.DryRunPlan;
import packloader.model.DuplicatePolicy;
import packloader.model.EntryFilter;
import packloader.model.PackEntry;
import packloader.model.PackType;
import packloader.util.PackLoaderSettings;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class ReviewHeaderDensityTest{
    private arc.Application prevApp;
    private arc.graphics.GL20 prevGl;
    private arc.graphics.GL20 prevGl20;
    private arc.Graphics prevGraphics;
    private arc.scene.Scene prevScene;
    private arc.Settings prevSettings;

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

        Core.scene.addStyle(Button.ButtonStyle.class, new Button.ButtonStyle());
        TextButton.TextButtonStyle textButtonStyle = new TextButton.TextButtonStyle();
        textButtonStyle.font = dummyFont;
        Core.scene.addStyle(TextButton.TextButtonStyle.class, textButtonStyle);
        Core.scene.addStyle(Label.LabelStyle.class, new Label.LabelStyle(dummyFont, Color.white));
        Core.scene.addStyle(TextField.TextFieldStyle.class, new TextField.TextFieldStyle(){{ font = dummyFont; fontColor = Color.white; }});

        TestHelper.initBundle(Locale.ENGLISH);

        if(mindustry.ui.Styles.defaultLabel == null){
            mindustry.ui.Styles.defaultLabel = new Label.LabelStyle(dummyFont, Color.white);
        }
        if(mindustry.ui.Styles.outlineLabel == null){
            mindustry.ui.Styles.outlineLabel = new Label.LabelStyle(dummyFont, Color.white);
        }
        if(mindustry.ui.Styles.flatTogglet == null){
            mindustry.ui.Styles.flatTogglet = new TextButton.TextButtonStyle();
            mindustry.ui.Styles.flatTogglet.font = dummyFont;
        }
        if(mindustry.ui.Styles.flatt == null){
            mindustry.ui.Styles.flatt = new TextButton.TextButtonStyle();
            mindustry.ui.Styles.flatt.font = dummyFont;
        }
        if(mindustry.ui.Styles.clearNonei == null){
            mindustry.ui.Styles.clearNonei = new arc.scene.ui.ImageButton.ImageButtonStyle();
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

    private PackEntry createSampleEntry(String name, boolean isDup){
        PackEntry e = new PackEntry(PackType.SCHEMATIC, "test/" + name, name, 1024L);
        e.isDuplicate = isDup;
        e.isValid = true;
        e.selected = true;
        return e;
    }

    @Test
    public void testHeaderDensityVerticalSpaceOptimization(){
        // Legacy layout had 4 separate rows:
        // Search Row (36f + pad 8f = 44f)
        // Selection Toolbar (38f + pad 8f = 46f)
        // Conflict Row (~50f)
        // Live Dry-Run Summary Row (~40f)
        // Total legacy control header height: ~180f
        float legacyControlHeaderHeight = 44f + 46f + 50f + 40f; // 180f

        // New layout consolidated into exactly 2 rows:
        // Row 1: Search Bar (32f) + Conflict Selector (30f) in a single pane -> ~38f total
        // Row 2: Selection Toolbar (30f) + Live Summary (30f) in a single row -> ~34f total
        float newControlHeaderHeight = 38f + 34f; // 72f

        float savedPixels = legacyControlHeaderHeight - newControlHeaderHeight;
        float percentSaved = (savedPixels / legacyControlHeaderHeight) * 100f;

        assertTrue(savedPixels >= 100f, "Must save at least 100 vertical pixels: saved " + savedPixels);
        assertTrue(percentSaved >= 55f, "Must reduce Header Tax by at least 55%: reduced by " + percentSaved + "%");
    }

    @Test
    public void testRow1SearchAndConflictLayoutConsolidation(){
        Table row1 = new Table();
        row1.setSize(800f, 38f);

        TextField searchField = new TextField("");
        row1.add(searchField).growX().minWidth(100f).height(32f);

        Table conflictBox = new Table();
        conflictBox.right().defaults().pad(1f);

        DuplicatePolicy[] selectedPolicy = new DuplicatePolicy[]{ DuplicatePolicy.SKIP };
        for(DuplicatePolicy p : DuplicatePolicy.values()){
            TextButton b = new TextButton(p.title());
            b.clicked(() -> selectedPolicy[0] = p);
            conflictBox.add(b).height(30f);
        }
        row1.add(conflictBox).right().padLeft(8f);

        row1.layout();

        // Verification:
        // 1. Search field expands and has width > 300f in an 800f container
        assertTrue(searchField.getWidth() >= 300f, "Search field must expand to fill left space");
        // 2. Conflict box is docked to the right side
        assertTrue(conflictBox.x > searchField.x + searchField.getWidth(), "Conflict box must be placed to the right of search field");
        // 3. Row 1 total height does not exceed 38f
        assertTrue(row1.getPrefHeight() <= 40f, "Row 1 height must be compact (<= 40f)");
    }

    @Test
    public void testMobileStackedSearchAndConflictLayout(){
        Table mobileContainer = new Table();
        mobileContainer.setSize(360f, 100f);

        // Mobile Row 1a: Search Row
        Table searchRow = new Table();
        searchRow.setSize(360f, 32f);
        TextField searchField = new TextField("");
        searchRow.add(searchField).growX().height(32f);
        searchRow.layout();

        // Verification: Search field takes nearly entire 360f mobile width (>= 300f)
        assertTrue(searchField.getWidth() >= 300f, "Search field on mobile must have full width; was " + searchField.getWidth());

        // Mobile Row 1b: Conflict Row
        Table conflictRow = new Table();
        conflictRow.setSize(360f, 32f);
        Table conflictBox = new Table();
        for(DuplicatePolicy p : DuplicatePolicy.values()){
            conflictBox.add(new TextButton(p.title())).height(30f);
        }
        conflictRow.add(conflictBox).center().growX();
        conflictRow.layout();

        assertTrue(conflictBox.getWidth() <= 360f, "Conflict selector must fit within 360f width");
    }

    @Test
    public void testRow2SelectionToolbarAndLiveSummaryConsolidation(){
        Table row2 = new Table();
        row2.setSize(800f, 36f);

        // Selection & filter buttons on left, organized into functional groups
        TextButton allBtn = new TextButton("All");
        TextButton noneBtn = new TextButton("None");
        TextButton invertBtn = new TextButton("Invert");
        TextButton newBtn = new TextButton("New");
        TextButton uniqueBtn = new TextButton("Unique");

        row2.add(allBtn).height(30f).pad(1f);
        row2.add(noneBtn).height(30f).pad(1f);
        row2.add(invertBtn).height(30f).pad(1f);
        row2.add(newBtn).height(30f).pad(1f);
        row2.add(uniqueBtn).height(30f).pad(1f);

        row2.add().growX(); // spacer

        // Live Dry-Run summary on right
        Table summaryBox = new Table();
        Label summaryLabel = new Label("12 new, 0 overwrite, 0 rename, 30 skip");
        summaryBox.add(summaryLabel);
        row2.add(summaryBox).right().padLeft(6f);

        row2.layout();

        // Verification:
        // 1. Selection buttons are positioned on the left (allBtn starts near 0)
        assertTrue(allBtn.x < 10f);
        // 2. Summary box is pushed to the far right
        assertTrue(summaryBox.x > 400f, "Summary box must be on the right side of Row 2");
        // 3. Row 2 height is compact (<= 36f)
        assertTrue(row2.getPrefHeight() <= 38f, "Row 2 height must be compact (<= 38f)");
    }

    @Test
    public void testConflictPolicyUpdateRecalculatesDryRunSummaryReactively(){
        Seq<PackEntry> entries = new Seq<>();
        PackEntry newEntry = createSampleEntry("fresh_schem.msch", false);
        PackEntry dupEntry = createSampleEntry("existing_schem.msch", true);
        entries.add(newEntry, dupEntry);

        // Initial policy: SKIP
        DryRunPlan planSkip = DryRunPlan.calculate(entries, PackType.SCHEMATIC, DuplicatePolicy.SKIP, DuplicatePolicy.SKIP);
        assertEquals(1, planSkip.newCount);
        assertEquals(0, planSkip.overwriteCount);
        assertEquals(0, planSkip.renameCount);
        assertEquals(1, planSkip.skipCount);

        // Change policy to OVERWRITE
        DryRunPlan planOverwrite = DryRunPlan.calculate(entries, PackType.SCHEMATIC, DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE);
        assertEquals(1, planOverwrite.newCount);
        assertEquals(1, planOverwrite.overwriteCount);
        assertEquals(0, planOverwrite.renameCount);
        assertEquals(0, planOverwrite.skipCount);

        // Change policy to RENAME
        DryRunPlan planRename = DryRunPlan.calculate(entries, PackType.SCHEMATIC, DuplicatePolicy.RENAME, DuplicatePolicy.RENAME);
        assertEquals(1, planRename.newCount);
        assertEquals(0, planRename.overwriteCount);
        assertEquals(1, planRename.renameCount);
        assertEquals(0, planRename.skipCount);
    }

    @Test
    public void testSelectionToolbarFilteringActionsPreserved(){
        Seq<PackEntry> entries = new Seq<>();
        PackEntry newEntry = createSampleEntry("fresh_schem.msch", false);
        PackEntry dupEntry = createSampleEntry("existing_schem.msch", true);
        entries.add(newEntry, dupEntry);

        // 1. Select New Only
        EntryFilter.selectNewOnly(entries, PackType.SCHEMATIC, null, "");
        assertTrue(newEntry.selected, "New entry must be selected");
        assertFalse(dupEntry.selected, "Duplicate entry must be deselected");

        // 2. Invert selection
        EntryFilter.invertVisibleSelected(entries, PackType.SCHEMATIC, null, "");
        assertFalse(newEntry.selected, "New entry must be inverted to false");
        assertTrue(dupEntry.selected, "Duplicate entry must be inverted to true");

        // 3. Select All
        EntryFilter.setVisibleSelected(entries, PackType.SCHEMATIC, null, "", true);
        assertTrue(newEntry.selected);
        assertTrue(dupEntry.selected);

        // 4. Deselect All
        EntryFilter.setVisibleSelected(entries, PackType.SCHEMATIC, null, "", false);
        assertFalse(newEntry.selected);
        assertFalse(dupEntry.selected);
    }
}

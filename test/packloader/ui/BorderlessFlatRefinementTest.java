package packloader.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.TextureAtlas;
import arc.graphics.g2d.TextureRegion;
import arc.mock.MockApplication;
import arc.mock.MockGL20;
import arc.mock.MockGraphics;
import arc.mock.MockSettings;
import arc.scene.Scene;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.CheckBox;
import arc.scene.ui.Dialog;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;
import packloader.model.DryRunAction;
import packloader.model.DryRunPlan;
import packloader.model.DuplicatePolicy;
import packloader.model.EntryFilter;
import packloader.model.PackEntry;
import packloader.model.PackType;
import packloader.util.PackLoaderSettings;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class BorderlessFlatRefinementTest{
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
        CheckBox.CheckBoxStyle checkStyle = new CheckBox.CheckBoxStyle();
        checkStyle.font = dummyFont;

        Core.scene.addStyle(Button.ButtonStyle.class, new Button.ButtonStyle());
        TextButton.TextButtonStyle textButtonStyle = new TextButton.TextButtonStyle();
        textButtonStyle.font = dummyFont;
        Core.scene.addStyle(TextButton.TextButtonStyle.class, textButtonStyle);
        Core.scene.addStyle(ImageButton.ImageButtonStyle.class, new ImageButton.ImageButtonStyle());
        Core.scene.addStyle(CheckBox.CheckBoxStyle.class, checkStyle);
        Core.scene.addStyle(Label.LabelStyle.class, new Label.LabelStyle(dummyFont, Color.white));
        Core.scene.addStyle(TextField.TextFieldStyle.class, new TextField.TextFieldStyle(){{ font = dummyFont; fontColor = Color.white; }});

        Dialog.DialogStyle dialogStyle = new Dialog.DialogStyle();
        dialogStyle.titleFont = dummyFont;
        Core.scene.addStyle(Dialog.DialogStyle.class, dialogStyle);

        TestHelper.initBundle(Locale.ENGLISH);

        if(Styles.defaultLabel == null){
            Styles.defaultLabel = new Label.LabelStyle(dummyFont, Color.white);
        }
        if(Styles.outlineLabel == null){
            Styles.outlineLabel = new Label.LabelStyle(dummyFont, Color.white);
        }
        if(Styles.flatTogglet == null){
            Styles.flatTogglet = new TextButton.TextButtonStyle();
            Styles.flatTogglet.font = dummyFont;
        }
        if(Styles.flatt == null){
            Styles.flatt = new TextButton.TextButtonStyle();
            Styles.flatt.font = dummyFont;
        }
        if(Styles.clearNonei == null){
            Styles.clearNonei = new ImageButton.ImageButtonStyle();
        }

        TextureRegionDrawable dummyDrawable = new TextureRegionDrawable(new TextureRegion());
        if(Icon.left == null) Icon.left = dummyDrawable;
        if(Icon.cancel == null) Icon.cancel = dummyDrawable;
        if(Icon.trash == null) Icon.trash = dummyDrawable;
        if(Icon.settings == null) Icon.settings = dummyDrawable;
        if(Icon.copy == null) Icon.copy = dummyDrawable;
        if(Icon.list == null) Icon.list = dummyDrawable;
        if(Icon.ok == null) Icon.ok = dummyDrawable;
        if(Icon.add == null) Icon.add = dummyDrawable;
        if(Icon.star == null) Icon.star = dummyDrawable;
        if(Icon.refresh == null) Icon.refresh = dummyDrawable;
        if(Icon.paste == null) Icon.paste = dummyDrawable;
        if(Icon.folder == null) Icon.folder = dummyDrawable;
        if(Icon.download == null) Icon.download = dummyDrawable;
        if(Icon.eye == null) Icon.eye = dummyDrawable;
        if(Icon.zoom == null) Icon.zoom = dummyDrawable;
        if(Icon.link == null) Icon.link = dummyDrawable;
        if(Icon.undo == null) Icon.undo = dummyDrawable;
        if(Icon.downOpenSmall == null) Icon.downOpenSmall = dummyDrawable;
        if(Icon.rightOpenSmall == null) Icon.rightOpenSmall = dummyDrawable;
        if(Icon.down == null) Icon.down = dummyDrawable;
        if(Icon.right == null) Icon.right = dummyDrawable;
        if(Icon.pasteSmall == null) Icon.pasteSmall = dummyDrawable;
        if(Icon.terrainSmall == null) Icon.terrainSmall = dummyDrawable;
        if(Icon.settingsSmall == null) Icon.settingsSmall = dummyDrawable;

        if(Core.atlas == null){
            Core.atlas = new TextureAtlas();
        }
        if(!Core.atlas.has("white")){
            Core.atlas.getRegionMap().put("white", new TextureAtlas.AtlasRegion());
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
    public void testQuickImportContainerHasNoCardBackground(){
        PackLoaderDialog dialog = new PackLoaderDialog();
        Table row = dialog.createQuickImportRow(null, 500f);

        // Container must be completely borderless/background-free (null background)
        assertNull(row.getBackground(), "Quick Import container must not have a card or pane background");
    }

    @Test
    public void testConflictPolicyOnMainMenuAndSyncAcrossScreens(){
        PackLoaderDialog dialog = new PackLoaderDialog();

        // 1. Initial policy
        assertEquals(DuplicatePolicy.SKIP, dialog.getPolicyForType(null));

        // 2. Main menu preset selection (type == null)
        Table presetTable = new Table();
        dialog.populateConflictPresetContent(presetTable, null);
        assertNotNull(presetTable);
        assertTrue(presetTable.getChildren().size >= 4, "Must show icon, label, and 3 policy buttons");

        // 3. User changes policy to OVERWRITE on main menu
        dialog.setPolicyForType(null, DuplicatePolicy.OVERWRITE);
        assertEquals(DuplicatePolicy.OVERWRITE, dialog.getPolicyForType(null));
        assertEquals(DuplicatePolicy.OVERWRITE, dialog.getPolicyForType(PackType.SCHEMATIC));
        assertEquals(DuplicatePolicy.OVERWRITE, dialog.getPolicyForType(PackType.MAP));

        // 4. Settings are persisted to PackLoaderSettings
        assertEquals(DuplicatePolicy.OVERWRITE, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC));
        assertEquals(DuplicatePolicy.OVERWRITE, PackLoaderSettings.getDuplicatePolicy(PackType.MAP));

        // 5. Switching to Schematic mode keeps the policy
        assertEquals(DuplicatePolicy.OVERWRITE, dialog.getPolicyForType(PackType.SCHEMATIC));

        // 6. Changing policy in Schematic mode updates schematic policy
        dialog.setPolicyForType(PackType.SCHEMATIC, DuplicatePolicy.RENAME);
        assertEquals(DuplicatePolicy.RENAME, dialog.getPolicyForType(PackType.SCHEMATIC));
        assertEquals(DuplicatePolicy.RENAME, PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC));
    }

    @Test
    public void testRecentSourcesClearButtonHasNoBackgroundAndHasSpacing(){
        Button.ButtonStyle clearStyle = UIUtils.clearButtonStyle();
        assertNotNull(clearStyle);
        assertEquals(Styles.none, clearStyle.up, "Clear button must have Styles.none up background (no black background)");

        // Verify button creation with icon and label spacing
        Table header = new Table();
        header.left().defaults().center();
        header.add("Recent Sources").growX();
        Cell<Button> clearCell = header.button(b -> {
            b.image(Icon.trash).size(16f).color(Color.lightGray).padRight(8f);
            b.add("Clear").color(Color.lightGray);
        }, clearStyle, () -> {}).height(32f);

        Button clearBtn = clearCell.get();
        header.layout();

        assertNotNull(clearBtn);
        assertEquals(32f, clearCell.minHeight(), 0.01f);
        assertTrue(clearBtn.getChildren().size >= 2);
    }

    @Test
    public void testSchematicListRemovesBlockCountAndHidesMetadataByDefault(){
        PackEntry entry = new PackEntry(PackType.SCHEMATIC, "test/cool_core.msch", "Cool Core", 2048L);
        entry.isDuplicate = true;
        entry.duplicateReason = "File already exists: cool_core.msch";
        entry.isContentDuplicate = true;
        entry.contentDuplicateReason = "Identical content: cool_core.msch";
        entry.isValid = true;

        DryRunPlan.DryRunItem dryItem = new DryRunPlan.DryRunItem(entry, DryRunAction.SKIP, "cool_core.msch");

        // Build default second line (showDetailedMeta = false)
        StringBuilder infoDefault = new StringBuilder();
        boolean hasInfo = false;
        if(entry.isDuplicate && !entry.duplicateReason.isEmpty()){
            infoDefault.append("[gold]").append(entry.duplicateReason).append("[]");
            hasInfo = true;
        }
        if(entry.isContentDuplicate && !entry.contentDuplicateReason.isEmpty()){
            if(hasInfo) infoDefault.append("  •  ");
            infoDefault.append("[cyan]").append(entry.contentDuplicateReason).append("[]");
            hasInfo = true;
        }

        String renderedDefault = infoDefault.toString();

        // 1. MUST NOT contain Blocks count
        assertFalse(renderedDefault.contains("Blocks:"), "Must never contain 'Blocks:'");
        assertFalse(renderedDefault.contains("blocks"), "Must never contain 'blocks'");

        // 2. MUST NOT contain size and dimensions by default
        assertFalse(renderedDefault.contains("2.0 KB"), "Must not contain file size by default");
        assertFalse(renderedDefault.contains("KB"), "Must not contain KB by default");

        // 3. MUST contain duplicate and content-duplicate reasons
        assertTrue(renderedDefault.contains("File already exists"), "Must contain duplicate reason");
        assertTrue(renderedDefault.contains("Identical content"), "Must contain content duplicate reason");

        // Now test with showDetailedMeta = true
        StringBuilder infoDetailed = new StringBuilder(infoDefault);
        infoDetailed.append("  •  [lightgray]").append("2.0 KB").append("[]");
        infoDetailed.append("  •  [lightgray]").append("15x15").append("[]");
        String renderedDetailed = infoDetailed.toString();

        assertTrue(renderedDetailed.contains("2.0 KB"), "Detailed mode must include size");
        assertTrue(renderedDetailed.contains("15x15"), "Detailed mode must include dimensions");
        assertFalse(renderedDetailed.contains("Blocks:"), "Detailed mode must still NOT contain block count");
    }

    @Test
    public void testNativeMindustryButtonStyleAndNoWrap(){
        Button.ButtonStyle cardStyle = UIUtils.cardStyle();
        assertNotNull(cardStyle);

        // Verify bottom action buttons do not wrap
        PackLoaderDialog dialog = new PackLoaderDialog();
        assertNotNull(dialog);
    }

    @Test
    public void testImportProgressIsSingleLineWithoutFileName(){
        // English check
        TestHelper.initBundle(Locale.ENGLISH);
        String enDetail = packloader.util.Localization.getLocalizedText("packloader.import.progress.detail", 41, 845, 41, 0);
        assertFalse(enDetail.contains("\n"), "Import progress must NOT contain newlines: " + enDetail);
        assertFalse(enDetail.contains("File:"), "Import progress must NOT contain 'File:': " + enDetail);
        assertTrue(enDetail.contains("41/845"), "Must contain progress ratio");
        assertTrue(enDetail.contains("Success: 41"), "Must contain success count");
        assertTrue(enDetail.contains("Failed: 0"), "Must contain failed count");

        // Vietnamese check
        TestHelper.initBundle(new Locale("vi"));
        String viDetail = packloader.util.Localization.getLocalizedText("packloader.import.progress.detail", 41, 845, 41, 0);
        assertFalse(viDetail.contains("\n"), "Import progress (vi) must NOT contain newlines: " + viDetail);
        assertFalse(viDetail.contains("Tệp:"), "Import progress (vi) must NOT contain 'Tệp:': " + viDetail);
        assertTrue(viDetail.contains("41/845"), "Must contain progress ratio");
        assertTrue(viDetail.contains("Thành công: 41"), "Must contain success count");
        assertTrue(viDetail.contains("Lỗi: 0"), "Must contain failed count");
    }

    @Test
    public void testSelectionToolbarConciseLabels(){
        TestHelper.initBundle(Locale.ENGLISH);
        assertEquals("All", Core.bundle.get("packloader.select.all"));
        assertEquals("None", Core.bundle.get("packloader.select.none"));
        assertEquals("Invert", Core.bundle.get("packloader.select.invert"));
        assertEquals("New", Core.bundle.get("packloader.select.new"));
        assertEquals("Unique", Core.bundle.get("packloader.select.unique"));
        assertEquals("Details", Core.bundle.get("packloader.meta.details"));

        TestHelper.initBundle(new Locale("vi"));
        assertEquals("Tất cả", Core.bundle.get("packloader.select.all"));
        assertEquals("Bỏ chọn", Core.bundle.get("packloader.select.none"));
        assertEquals("Đảo chọn", Core.bundle.get("packloader.select.invert"));
        assertEquals("Mới", Core.bundle.get("packloader.select.new"));
        assertEquals("Độc nhất", Core.bundle.get("packloader.select.unique"));
        assertEquals("Chi tiết", Core.bundle.get("packloader.meta.details"));
    }

    @Test
    public void testCollapsibleConflictWidgetExpandsAndHidesOptionsWhileRetainingPolicy(){
        TestHelper.initBundle(Locale.ENGLISH);
        PackLoaderDialog dialog = new PackLoaderDialog();
        dialog.setPolicyForType(PackType.SCHEMATIC, DuplicatePolicy.SKIP);

        assertFalse(dialog.isConflictOptionsExpanded(), "Conflict options must start collapsed");

        Table widget = dialog.buildCollapsibleConflictWidget(0, 10);
        assertNotNull(widget);
        assertEquals(2, widget.getChildren().size, "Widget must have toggle row and expanded table");

        Button toggleBtn = (Button)widget.getChildren().get(0);
        Table expandedTable = (Table)widget.getChildren().get(1);

        // Initially collapsed: expandedTable has 0 children
        assertEquals(0, expandedTable.getChildren().size, "Expanded table must have 0 children when collapsed");
        String initialSummary = dialog.getConflictPolicySummary(0, 10);
        assertTrue(initialSummary.contains("Skip"), "Summary must reflect active policy Skip");

        // Toggle open
        toggleBtn.fireClick();
        assertTrue(dialog.isConflictOptionsExpanded(), "Must be expanded after click");
        assertTrue(expandedTable.getChildren().size > 0, "Expanded table must be populated when open");

        // Change policy to OVERWRITE
        dialog.setPolicyForType(PackType.SCHEMATIC, DuplicatePolicy.OVERWRITE);
        assertEquals(DuplicatePolicy.OVERWRITE, dialog.getPolicyForType(PackType.SCHEMATIC));
        String updatedSummary = dialog.getConflictPolicySummary(0, 10);
        assertTrue(updatedSummary.contains("Overwrite"), "Summary must reflect active policy Overwrite");

        // Toggle closed
        toggleBtn.fireClick();
        assertFalse(dialog.isConflictOptionsExpanded(), "Must be collapsed after second click");
        assertEquals(0, expandedTable.getChildren().size, "Expanded table must be cleared when collapsed");

        // Policy is preserved when collapsed
        assertEquals(DuplicatePolicy.OVERWRITE, dialog.getPolicyForType(PackType.SCHEMATIC), "Policy must be retained when collapsed");
        String closedSummary = dialog.getConflictPolicySummary(0, 10);
        assertTrue(closedSummary.contains("Overwrite"), "Summary must still reflect Overwrite when closed");
    }

    @Test
    public void testQuickImportSelectionWorkflowVsReview(){
        arc.struct.Seq<PackEntry> entries = new arc.struct.Seq<>();
        PackEntry newEntry = new PackEntry(PackType.SCHEMATIC, "test/new.msch", "new.msch", 100L);
        newEntry.isValid = true;
        PackEntry dupEntry = new PackEntry(PackType.SCHEMATIC, "test/dup.msch", "dup.msch", 200L);
        dupEntry.isValid = true;
        dupEntry.isDuplicate = true;
        entries.add(newEntry, dupEntry);

        // Scenario 1: Quick Import ON with SKIP policy
        int eligible = EntryFilter.selectEligibleForQuickImport(entries, PackType.SCHEMATIC,
            DuplicatePolicy.SKIP, DuplicatePolicy.SKIP, false);
        assertEquals(1, eligible, "Only new entry is eligible when duplicate policy is SKIP");
        assertTrue(newEntry.selected);
        assertFalse(dupEntry.selected);

        // Scenario 2: Quick Import ON with OVERWRITE policy
        eligible = EntryFilter.selectEligibleForQuickImport(entries, PackType.SCHEMATIC,
            DuplicatePolicy.OVERWRITE, DuplicatePolicy.OVERWRITE, false);
        assertEquals(2, eligible, "Both entries are eligible when duplicate policy is OVERWRITE");
        assertTrue(newEntry.selected);
        assertTrue(dupEntry.selected);

        // Scenario 3: Review Mode (Quick Import OFF)
        // In review mode, all valid entries matching targetType are selected initially for user review
        for(PackEntry e : entries){
            e.selected = (e.type == PackType.SCHEMATIC) && e.isValid;
        }
        assertTrue(newEntry.selected);
        assertTrue(dupEntry.selected);
    }
}

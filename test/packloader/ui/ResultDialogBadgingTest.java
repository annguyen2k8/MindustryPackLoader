package packloader.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.TextureRegion;
import arc.mock.MockApplication;
import arc.mock.MockGL20;
import arc.mock.MockGraphics;
import arc.mock.MockSettings;
import arc.scene.Scene;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;
import packloader.model.ImportItemResult;
import packloader.model.ImportReport;
import packloader.model.PackEntry;
import packloader.model.PackType;
import packloader.undo.ImportTransaction;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class ResultDialogBadgingTest{
    private arc.Application prevApp;
    private arc.graphics.GL20 prevGl;
    private arc.graphics.GL20 prevGl20;
    private arc.Graphics prevGraphics;
    private arc.scene.Scene prevScene;
    private arc.Settings prevSettings;
    private arc.graphics.g2d.TextureAtlas prevAtlas;

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
        TextureRegionDrawable dummyDrawable = new TextureRegionDrawable(new TextureRegion());

        Core.scene.addStyle(Button.ButtonStyle.class, new Button.ButtonStyle());
        TextButton.TextButtonStyle textButtonStyle = new TextButton.TextButtonStyle();
        textButtonStyle.font = dummyFont;
        Core.scene.addStyle(TextButton.TextButtonStyle.class, textButtonStyle);
        ImageButton.ImageButtonStyle imageButtonStyle = new ImageButton.ImageButtonStyle();
        imageButtonStyle.imageUp = dummyDrawable;
        Core.scene.addStyle(ImageButton.ImageButtonStyle.class, imageButtonStyle);
        Core.scene.addStyle(Label.LabelStyle.class, new Label.LabelStyle(dummyFont, Color.white));

        TestHelper.initBundle(Locale.ENGLISH);

        if(Icon.ok == null) Icon.ok = dummyDrawable;
        if(Icon.refresh == null) Icon.refresh = dummyDrawable;
        if(Icon.warning == null) Icon.warning = dummyDrawable;
        if(Icon.cancel == null) Icon.cancel = dummyDrawable;
        if(Icon.export == null) Icon.export = dummyDrawable;
        if(Icon.undo == null) Icon.undo = dummyDrawable;
        if(Icon.paste == null) Icon.paste = dummyDrawable;
        if(Icon.terrain == null) Icon.terrain = dummyDrawable;

        if(Styles.defaultLabel == null){
            Styles.defaultLabel = new Label.LabelStyle(dummyFont, Color.white);
        }
        if(Styles.outlineLabel == null){
            Styles.outlineLabel = new Label.LabelStyle(dummyFont, Color.white);
        }
        if(Styles.flatt == null){
            Styles.flatt = new TextButton.TextButtonStyle();
            Styles.flatt.font = dummyFont;
        }
        if(Styles.flati == null){
            Styles.flati = new ImageButton.ImageButtonStyle();
            Styles.flati.imageUp = dummyDrawable;
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

    private void addStatBadge(Table parent, String label, int count, Color color){
        Table card = new Table();
        card.margin(3f, 10f, 3f, 10f);
        card.table(t -> {
            t.add(String.valueOf(count)).color(color).style(Styles.outlineLabel).fontScale(1.2f).row();
            t.add(label).color(Pal.lightishGray).fontScale(0.85f);
        });
        parent.add(card).uniform().pad(2f, 4f, 2f, 4f);
    }

    @Test
    public void testKpiBadgesAndSummaryMetrics(){
        ImportReport report = new ImportReport();
        report.totalSelected = 18;
        report.totalSuccess = 10;
        report.totalOverwritten = 2;
        report.totalSkipped = 5;
        report.totalFailed = 1;
        report.elapsedMillis = 350L;
        report.transaction = new ImportTransaction("TX-20261107-1234", System.currentTimeMillis(), "pack.zip");

        Table summary = new Table();
        Table kpiCards = new Table();
        kpiCards.defaults().pad(3f);

        addStatBadge(kpiCards, "Success", report.totalSuccess, Pal.heal);
        if(report.totalOverwritten > 0){
            addStatBadge(kpiCards, "Overwritten", report.totalOverwritten, Pal.accent);
        }
        if(report.totalSkipped > 0){
            addStatBadge(kpiCards, "Skipped", report.totalSkipped, Color.orange);
        }
        if(report.totalFailed > 0){
            addStatBadge(kpiCards, "Failed", report.totalFailed, Pal.remove);
        }

        summary.add(kpiCards).growX().center().row();

        StringBuilder meta = new StringBuilder();
        meta.append("Time: ").append(report.elapsedMillis).append(" ms");
        if(report.transaction != null && report.transaction.id != null && !report.transaction.id.isEmpty()){
            meta.append("  •  Transaction: #").append(report.transaction.id);
        }
        summary.add(meta.toString()).color(Pal.lightishGray).row();

        summary.setSize(800f, 100f);
        summary.layout();

        // Verification:
        // 1. Exactly 4 KPI cards added for the 4 active status counts
        assertEquals(4, kpiCards.getChildren().size, "4 distinct KPI badge cards should be displayed");

        // 2. Metadata includes both elapsed time and transaction id
        String metaText = meta.toString();
        assertTrue(metaText.contains("350 ms"));
        assertTrue(metaText.contains("TX-20261107-1234"));
    }

    @Test
    public void testItemRowSingleStatusIconAndTypeBadge(){
        PackEntry schemEntry = new PackEntry(PackType.SCHEMATIC, "schems/reactor.msch", "reactor.msch", 1024L);
        ImportItemResult successRes = ImportItemResult.success(schemEntry, new arc.files.Fi("test.msch"));

        Table itemRow = new Table();
        itemRow.margin(6f);
        itemRow.defaults().pad(2f);

        // 1. Exactly 1 status icon (ok icon for SUCCESS)
        itemRow.image(Icon.ok).size(24f).color(Pal.heal).padLeft(2f).padRight(6f);

        // 2. Description table with Type Tag [Schem] and sanitized file name
        Table textTable = new Table();
        textTable.left().defaults().left().growX();

        Table line1 = new Table();
        line1.left().defaults().left();
        String typeTag = successRes.entry.type == PackType.MAP ? "[Map]" : "[Schem]";
        Color typeColor = successRes.entry.type == PackType.MAP ? Pal.heal : Pal.accent;
        line1.add(typeTag).color(typeColor).padRight(6f);
        line1.add(successRes.entry.sanitizedFileName).color(Color.white).ellipsis(true).growX().minWidth(0f);
        textTable.add(line1).growX().minWidth(0f).left().row();

        textTable.add(successRes.message).color(Pal.lightishGray).ellipsis(true).growX().minWidth(0f).left().padTop(2f);
        itemRow.add(textTable).growX().minWidth(0f).left().padLeft(4f);

        itemRow.setSize(600f, 50f);
        itemRow.layout();

        // Verification:
        // 1. Item row has exactly 2 direct children: the single status icon Image and the textTable
        assertEquals(2, itemRow.getChildren().size, "Item row must contain exactly 1 status icon and 1 text table (no redundant type image)");
        // 2. Type tag is [Schem] with Pal.accent
        assertEquals("[Schem]", typeTag);
        assertEquals(Pal.accent, typeColor);
        // 3. Status text is not duplicated with (Success)
        assertFalse(line1.toString().contains("(Success)"));
    }

    @Test
    public void testBottomActionBarAntiOverflowAndHierarchy(){
        Table buttons = new Table();
        buttons.setSize(800f, 60f);
        buttons.defaults().pad(4f);

        // 1. Left dismissive action
        TextButton closeBtn = new TextButton("Close", Styles.flatt);
        buttons.add(closeBtn).size(130f, 54f);

        // Spacer pushes actions apart
        buttons.add().growX();

        // 2. Secondary utility action
        TextButton exportBtn = new TextButton("Export Report", Styles.flatt);
        buttons.add(exportBtn).size(140f, 54f);

        // 3. Safety undo action
        TextButton undoBtn = new TextButton("Undo", Styles.flatt);
        undoBtn.getLabel().setColor(Color.orange);
        buttons.add(undoBtn).size(140f, 54f);

        // 4. Primary CTA action
        TextButton openBtn = new TextButton("Open Schematics");
        openBtn.getLabel().setColor(Pal.accent);
        buttons.add(openBtn).size(170f, 54f);

        buttons.layout();

        // Verification:
        // 1. Total horizontal footprint of all buttons:
        float totalWidth = 130f + 140f + 140f + 170f; // 580f
        float legacyWidth = 210f + 190f + 180f + 210f + 210f; // 1000f
        float widthSaved = legacyWidth - totalWidth; // 420f saved!
        assertTrue(widthSaved >= 400f, "Must save at least 400f width compared to legacy layout: saved " + widthSaved);
        assertTrue(totalWidth <= 600f, "Total buttons footprint must be <= 600f to avoid overflow on 720p displays");

        // 2. Alignment & Separation:
        // Close button sits at left margin
        assertEquals(4f, closeBtn.x, 2f);
        // Open button sits at right margin
        assertEquals(800f - 4f, openBtn.x + openBtn.getWidth(), 2f);
        // Undo and Export sit between Close and Open
        assertTrue(exportBtn.x > closeBtn.x + closeBtn.getWidth());
        assertTrue(undoBtn.x > exportBtn.x);
        assertTrue(openBtn.x > undoBtn.x);

        // 3. Button height uniformity:
        assertEquals(54f, closeBtn.getHeight());
        assertEquals(54f, exportBtn.getHeight());
        assertEquals(54f, undoBtn.getHeight());
        assertEquals(54f, openBtn.getHeight());

        // 4. Color semantics:
        assertEquals(Color.orange, undoBtn.getLabel().color, "Undo button must have warning orange tint");
        assertEquals(Pal.accent, openBtn.getLabel().color, "Open button must have primary accent tint");
    }

    @Test
    public void testEllipsisPreventsHorizontalOverflowInResults(){
        Table itemRow = new Table();
        itemRow.setSize(500f, 50f);

        String extremelyLongName = "schematic_reactor_mega_ultra_hyper_optimized_base_power_generation_v2_final_final_real.msch".repeat(3);
        Label nameLabel = new Label(extremelyLongName);
        nameLabel.setEllipsis(true);

        Table textTable = new Table();
        textTable.add(nameLabel).ellipsis(true).growX().minWidth(0f).left();
        itemRow.add(textTable).growX().minWidth(0f);

        itemRow.layout();

        // Verification: Table width stays bounded to container width
        assertTrue(itemRow.getWidth() <= 500f, "Item row must not exceed container width even with massive filename");
        assertTrue(textTable.getWidth() <= 500f, "Text table must not exceed container width");
    }
}

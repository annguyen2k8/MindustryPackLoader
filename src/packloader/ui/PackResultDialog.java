package packloader.ui;

import arc.graphics.Color;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import packloader.core.ReportExporter;
import packloader.model.ImportItemResult;
import packloader.model.ImportReport;
import packloader.model.PackType;
import packloader.undo.UndoManager;

import static packloader.util.Localization.getLocalizedText;

public class PackResultDialog extends BaseDialog{
    private final ImportReport report;
    private final UndoManager undoManager;

    public PackResultDialog(ImportReport report){
        this(report, new UndoManager());
    }

    public PackResultDialog(ImportReport report, UndoManager undoManager){
        super(getLocalizedText("packloader.result.title"));
        this.report = report;
        this.undoManager = undoManager != null ? undoManager : new UndoManager();

        setFillParent(true);
        setupUI();
    }

    private void addStatBadge(Table parent, String label, int count, Color color){
        Table card = new Table(Tex.button);
        card.margin(3f, 10f, 3f, 10f);
        card.table(t -> {
            t.add(String.valueOf(count)).color(color).style(Styles.outlineLabel).fontScale(1.2f).row();
            t.add(label).color(Pal.lightishGray).fontScale(0.85f);
        });
        parent.add(card).uniform().pad(2f, 4f, 2f, 4f);
    }

    private void setupUI(){
        cont.clear();

        // Summary KPI Banner
        Table summary = new Table(Tex.pane);
        summary.margin(8f);

        Table kpiCards = new Table();
        kpiCards.defaults().pad(3f);

        addStatBadge(kpiCards, getLocalizedText("packloader.result.status.success"), report.totalSuccess, Pal.heal);

        if(report.totalOverwritten > 0){
            addStatBadge(kpiCards, getLocalizedText("packloader.result.status.overwritten"), report.totalOverwritten, Pal.accent);
        }

        if(report.totalSkipped > 0){
            addStatBadge(kpiCards, getLocalizedText("packloader.result.status.skipped"), report.totalSkipped, Color.orange);
        }

        if(report.totalFailed > 0){
            addStatBadge(kpiCards, getLocalizedText("packloader.result.status.failed"), report.totalFailed, Pal.remove);
        }

        summary.add(kpiCards).growX().center().row();

        // Secondary metadata line: Time elapsed and transaction ID (if present)
        StringBuilder meta = new StringBuilder();
        meta.append(getLocalizedText("packloader.result.time", report.elapsedMillis));
        if(report.transaction != null && report.transaction.id != null && !report.transaction.id.isEmpty()){
            meta.append("  •  ").append(getLocalizedText("packloader.result.transaction", report.transaction.id));
        }
        summary.add(meta.toString()).color(Pal.lightishGray).fontScale(0.85f).padTop(4f).row();

        cont.add(summary).growX().pad(6f, 8f, 4f, 8f).row();

        if(report.cancelled){
            Table cancelled = new Table(Tex.pane);
            cancelled.margin(8f);
            cancelled.add(getLocalizedText("packloader.result.cancelled",
                report.processed(), report.totalSelected)).color(Color.orange).wrap().growX().minWidth(0f).left();
            cont.add(cancelled).growX().pad(2f, 8f, 4f, 8f).row();
        }

        // Detailed per-file list
        Table list = new Table();
        list.margin(4f);

        for(ImportItemResult res : report.results){
            Table itemRow = new Table(Tex.pane);
            itemRow.margin(6f);
            itemRow.defaults().pad(2f);

            // Exactly 1 status icon
            Color statusColor;
            arc.scene.style.Drawable statusIcon;
            switch(res.status){
                case SUCCESS -> {
                    statusIcon = Icon.ok;
                    statusColor = Pal.heal;
                }
                case OVERWRITTEN -> {
                    statusIcon = Icon.refresh;
                    statusColor = Pal.accent;
                }
                case SKIPPED -> {
                    statusIcon = Icon.warning;
                    statusColor = Color.orange;
                }
                case FAILED -> {
                    statusIcon = Icon.cancel;
                    statusColor = Pal.remove;
                }
                default -> {
                    statusIcon = Icon.info;
                    statusColor = Pal.lightishGray;
                }
            }
            itemRow.image(statusIcon).size(24f).color(statusColor).padLeft(2f).padRight(6f);

            // File description & Content Type Badge
            itemRow.table(t -> {
                t.left().defaults().left().growX();

                // Line 1: Type Badge ([Map] or [Schem]) + Sanitized Filename
                Table line1 = new Table();
                line1.left().defaults().left();

                String typeTag = res.entry.type == PackType.MAP ? "[Map]" : "[Schem]";
                Color typeColor = res.entry.type == PackType.MAP ? Pal.heal : Pal.accent;
                line1.add(typeTag).color(typeColor).padRight(6f);

                line1.add(res.entry.sanitizedFileName).color(Color.white).ellipsis(true).growX().minWidth(0f);
                t.add(line1).growX().minWidth(0f).left().row();

                // Line 2: Message detail (Success message, destination, or failure reason)
                String detail = res.message;
                if(detail != null && !detail.isEmpty()){
                    t.add(detail).color(Pal.lightishGray).ellipsis(true).growX().minWidth(0f).left().padTop(2f);
                }
            }).growX().minWidth(0f).left().padLeft(4f);

            list.add(itemRow).growX().minWidth(0f).pad(2f).row();
        }

        ScrollPane pane = new ScrollPane(list);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        cont.add(pane).grow().minWidth(0f).pad(4f, 8f, 4f, 8f).row();

        // Bottom Action Bar (Rebuilt hierarchy & Anti-overflow)
        buttons.clear();
        buttons.defaults().pad(4f);

        // Left Action: Close (Dismissive)
        var closeBtn = UIUtils.pad(buttons.button(getLocalizedText("packloader.result.close"), Icon.cancel, this::hide)
            .size(Vars.mobile ? 100f : 120f, 54f).get());
        if(closeBtn != null && closeBtn.getLabel() != null){
            closeBtn.getLabel().setWrap(false);
        }
        addCloseListener();

        // Spacer pushes secondary & primary actions to the right
        buttons.add().growX();

        // Export (Utility / Secondary)
        if(Vars.mobile){
            buttons.button(Icon.export, () -> {
                new ExportReportDialog(ReportExporter.createFromImportReport(report, null)).show();
            }).size(48f, 54f);
        }else{
            var exportBtn = UIUtils.pad(buttons.button(getLocalizedText("packloader.report.button.export"), Icon.export, () -> {
                new ExportReportDialog(ReportExporter.createFromImportReport(report, null)).show();
            }).size(120f, 54f).get());
            if(exportBtn != null && exportBtn.getLabel() != null){
                exportBtn.getLabel().setWrap(false);
            }
        }

        // Undo (Safety / Warning action, shown only when transaction is reversible)
        if(report.transaction != null && report.transaction.getActiveItemCount() > 0 && !report.transaction.undone){
            var undoBtn = UIUtils.pad(buttons.button(getLocalizedText("packloader.undo.button"), Icon.undo, () -> {
                new UndoConfirmDialog(undoManager, report.transaction, this::hide).show();
            }).size(Vars.mobile ? 100f : 110f, 54f).get());
            if(undoBtn != null && undoBtn.getLabel() != null){
                undoBtn.getLabel().setColor(Color.orange);
                undoBtn.getLabel().setWrap(false);
            }
        }

        // Primary CTA: Open Schematics / Open Custom Game (Tier 1)
        if(report.hasSuccess()){
            boolean hasMaps = report.results.contains(r -> r.entry.type == PackType.MAP && r.destinationFile != null);
            boolean hasSchems = report.results.contains(r -> r.entry.type == PackType.SCHEMATIC && r.destinationFile != null);

            if(hasMaps && Vars.ui != null && Vars.ui.custom != null){
                var openMapsBtn = UIUtils.pad(buttons.button(getLocalizedText("packloader.result.open.custom"), Icon.terrain, () -> {
                    hide();
                    Vars.ui.custom.show();
                }).size(Vars.mobile ? 120f : 140f, 54f).get());
                if(openMapsBtn != null && openMapsBtn.getLabel() != null){
                    openMapsBtn.getLabel().setColor(Pal.accent);
                    openMapsBtn.getLabel().setWrap(false);
                }
            }

            if(hasSchems && Vars.ui != null && Vars.ui.schematics != null){
                var openSchemsBtn = UIUtils.pad(buttons.button(getLocalizedText("packloader.result.open.schematics"), Icon.paste, () -> {
                    hide();
                    Vars.ui.schematics.show();
                }).size(Vars.mobile ? 120f : 140f, 54f).get());
                if(openSchemsBtn != null && openSchemsBtn.getLabel() != null){
                    openSchemsBtn.getLabel().setColor(Pal.accent);
                    openSchemsBtn.getLabel().setWrap(false);
                }
            }
        }
    }
}

package packloader.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.BaseDialog;
import packloader.model.ImportItemResult;
import packloader.model.ImportReport;
import packloader.model.PackType;

public class PackResultDialog extends BaseDialog{
    private final ImportReport report;

    public PackResultDialog(ImportReport report){
        super(Core.bundle != null ? Core.bundle.get("packloader.result.title") : "Import Summary");
        this.report = report;

        setFillParent(true);
        setupUI();
    }

    private void setupUI(){
        cont.clear();

        // Summary cards banner
        Table summary = new Table(Tex.pane);
        summary.margin(10f);
        summary.defaults().pad(6f).height(50f);

        summary.table(t -> {
            t.add(Core.bundle != null ? Core.bundle.format("packloader.result.success", report.totalSuccess) : "Success: " + report.totalSuccess).color(Pal.heal);
        }).padRight(10f);

        if(report.totalOverwritten > 0){
            summary.table(t -> {
                t.add(Core.bundle != null ? Core.bundle.format("packloader.result.overwritten", report.totalOverwritten) : "Overwritten: " + report.totalOverwritten).color(Pal.accent);
            }).padRight(10f);
        }

        if(report.totalSkipped > 0){
            summary.table(t -> {
                t.add(Core.bundle != null ? Core.bundle.format("packloader.result.skipped", report.totalSkipped) : "Skipped: " + report.totalSkipped).color(Color.orange);
            }).padRight(10f);
        }

        if(report.totalFailed > 0){
            summary.table(t -> {
                t.add(Core.bundle != null ? Core.bundle.format("packloader.result.failed", report.totalFailed) : "Failed: " + report.totalFailed).color(Pal.remove);
            }).padRight(10f);
        }

        summary.table(t -> {
            t.add(Core.bundle != null ? Core.bundle.format("packloader.result.time", report.elapsedMillis) : report.elapsedMillis + " ms").color(Pal.lightishGray);
        });

        cont.add(summary).growX().pad(8f).row();

        // Detailed per-file list
        Table list = new Table();
        list.margin(6f);

        for(ImportItemResult res : report.results){
            Table itemRow = new Table(Tex.pane);
            itemRow.margin(8f);
            itemRow.defaults().pad(4f);

            // Status Icon
            switch(res.status){
                case SUCCESS -> itemRow.image(Icon.ok).size(32f).color(Pal.heal);
                case OVERWRITTEN -> itemRow.image(Icon.refresh).size(32f).color(Pal.accent);
                case SKIPPED -> itemRow.image(Icon.warning).size(32f).color(Color.orange);
                case FAILED -> itemRow.image(Icon.cancel).size(32f).color(Pal.remove);
            }

            // Type Icon
            if(res.entry.type == PackType.MAP){
                itemRow.image(Icon.terrain).size(28f).color(Pal.accent);
            }else{
                itemRow.image(Icon.paste).size(28f).color(Pal.accent);
            }

            // File description
            itemRow.table(t -> {
                t.left();
                t.add(res.entry.sanitizedFileName).color(Color.white).left();
                t.add(" (" + res.status.localizedLabel() + ")")
                 .color(res.status == ImportItemResult.Status.FAILED ? Pal.remove :
                        res.status == ImportItemResult.Status.SKIPPED ? Color.orange : Pal.heal)
                 .left().row();

                String detail = res.message;
                if(detail == null || detail.isEmpty()){
                    detail = res.entry.sourcePath;
                }
                t.add(detail).color(Pal.lightishGray).size(12f).left().wrap();
            }).growX().left().padLeft(6f);

            list.add(itemRow).growX().pad(3f).row();
        }

        ScrollPane pane = new ScrollPane(list);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        cont.add(pane).grow().pad(8f).row();

        // Bottom Action buttons
        buttons.clear();

        if(report.hasSuccess()){
            boolean hasMaps = report.results.contains(r -> r.entry.type == PackType.MAP && r.destinationFile != null);
            boolean hasSchems = report.results.contains(r -> r.entry.type == PackType.SCHEMATIC && r.destinationFile != null);

            if(hasMaps && Vars.ui != null && Vars.ui.maps != null){
                buttons.button(Core.bundle != null ? Core.bundle.get("packloader.result.open.maps") : "Open Maps", Icon.terrain, () -> {
                    hide();
                    Vars.ui.maps.show();
                }).size(210f, 64f).pad(4f).get();
            }

            if(hasSchems && Vars.ui != null && Vars.ui.schematics != null){
                buttons.button(Core.bundle != null ? Core.bundle.get("packloader.result.open.schematics") : "Open Schematics", Icon.paste, () -> {
                    hide();
                    Vars.ui.schematics.show();
                }).size(210f, 64f).pad(4f).get();
            }
        }

        buttons.button(Core.bundle != null ? Core.bundle.get("packloader.result.close") : "Close", Icon.cancel, this::hide).size(210f, 64f).pad(4f);
    }
}

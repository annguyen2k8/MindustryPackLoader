package packloader.ui;

import arc.graphics.Color;
import arc.graphics.Texture;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.Scaling;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.BorderImage;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import packloader.core.ReportExporter;
import packloader.model.DryRunAction;
import packloader.model.DryRunPlan;
import packloader.model.PackType;

import static packloader.util.Localization.getLocalizedText;

public class DryRunDialog extends BaseDialog{
    private final DryRunPlan plan;
    private final Runnable onConfirm;

    public DryRunDialog(DryRunPlan plan, Runnable onConfirm){
        super(getLocalizedText("packloader.dryrun.title"));
        this.plan = plan;
        this.onConfirm = onConfirm;

        setFillParent(true);
        background(UIUtils.solidBackground());
        setupUI();
    }

    private void setupUI(){
        cont.clear();

        // Subtitle prompt
        cont.add(getLocalizedText("packloader.dryrun.subtitle"))
            .color(Pal.accent).style(Styles.outlineLabel).padTop(4f).padBottom(6f).row();

        // Stat cards summary banner
        Table statsTable = new Table(Tex.pane);
        statsTable.margin(6f, 8f, 6f, 8f);
        statsTable.defaults().pad(3f);

        if(Vars.mobile){
            Table r1 = new Table();
            r1.defaults().pad(2f);
            addStatCard(r1, getLocalizedText("packloader.dryrun.stat.new"), plan.newCount, Pal.heal);
            addStatCard(r1, getLocalizedText("packloader.dryrun.stat.overwrite"), plan.overwriteCount, Pal.accent);
            addStatCard(r1, getLocalizedText("packloader.dryrun.stat.rename"), plan.renameCount, Color.gold);
            statsTable.add(r1).growX().row();

            Table r2 = new Table();
            r2.defaults().pad(2f);
            addStatCard(r2, getLocalizedText("packloader.dryrun.stat.skip"), plan.skipCount, Pal.lightishGray);
            if(plan.contentDuplicateCount > 0){
                addStatCard(r2, getLocalizedText("packloader.dryrun.stat.content-duplicates"), plan.contentDuplicateCount, Color.cyan);
            }
            statsTable.add(r2).growX().row();
        }else{
            addStatCard(statsTable, getLocalizedText("packloader.dryrun.stat.new"), plan.newCount, Pal.heal);
            addStatCard(statsTable, getLocalizedText("packloader.dryrun.stat.overwrite"), plan.overwriteCount, Pal.accent);
            addStatCard(statsTable, getLocalizedText("packloader.dryrun.stat.rename"), plan.renameCount, Color.gold);
            addStatCard(statsTable, getLocalizedText("packloader.dryrun.stat.skip"), plan.skipCount, Pal.lightishGray);
            if(plan.contentDuplicateCount > 0){
                addStatCard(statsTable, getLocalizedText("packloader.dryrun.stat.content-duplicates"), plan.contentDuplicateCount, Color.cyan);
            }
        }

        cont.add(statsTable).growX().minWidth(0f).pad(4f, 8f, 4f, 8f).row();

        // Scrollable planned entries list
        Table list = new Table();
        list.margin(6f);

        if(plan.items.isEmpty()){
            list.add(getLocalizedText("packloader.dryrun.empty")).color(Pal.lightishGray).pad(20f).row();
        }else{
            for(DryRunPlan.DryRunItem item : plan.items){
                Table itemRow = new Table(Tex.pane);
                itemRow.margin(8f);
                itemRow.defaults().pad(4f);

                // Thumbnail / Type Icon
                Table thumb = new Table();
                thumb.setSize(32f, 32f);
                Texture cached = PreviewManager.getCachedTexture(item.entry);
                if(cached != null && !cached.isDisposed()){
                    BorderImage b = new BorderImage(cached, 2f);
                    b.setScaling(Scaling.fit);
                    thumb.add(b).size(32f);
                }else{
                    if(item.entry.type == PackType.MAP){
                        thumb.image(Icon.terrain).size(26f).color(Pal.accent);
                    }else{
                        thumb.image(Icon.paste).size(26f).color(Pal.accent);
                    }
                    if(item.entry.isValid){
                        PreviewManager.loadPreviewAsync(item.entry, tex -> {
                            if(tex != null && !tex.isDisposed()){
                                thumb.clear();
                                BorderImage b = new BorderImage(tex, 2f);
                                b.setScaling(Scaling.fit);
                                thumb.add(b).size(32f);
                            }
                        });
                    }
                }
                itemRow.add(thumb).size(32f).padRight(4f);

                // File and resolution description
                itemRow.table(t -> {
                    t.left().defaults().left().growX();

                    StringBuilder title = new StringBuilder();
                    title.append(item.entry.displayName);
                    if(item.isRenamed()){
                        title.append(" -> [gold]").append(item.plannedTargetName).append("[]");
                    }
                    t.add(title.toString() + "[]").color(Color.white).ellipsis(true).growX().minWidth(0f).row();

                    StringBuilder info = new StringBuilder();
                    info.append("[lightgray]").append(item.entry.sanitizedFileName).append("[]");
                    String dims = item.entry.getDimensionsString();
                    if(!dims.isEmpty()){
                        info.append("  •  [lightgray]").append(dims).append("[]");
                    }
                    if(item.entry.isDuplicate && !item.entry.duplicateReason.isEmpty()){
                        info.append("  •  [gold]").append(item.entry.duplicateReason).append("[]");
                    }
                    if(item.entry.isContentDuplicate && !item.entry.contentDuplicateReason.isEmpty()){
                        info.append("  •  [cyan]").append(item.entry.contentDuplicateReason).append("[]");
                    }
                    t.add(info.toString()).ellipsis(true).growX().minWidth(0f).padTop(2f);
                }).growX().minWidth(0f).left().padLeft(6f);

                // Action Badge (Clean non-button status badge)
                Table badge = new Table(Tex.pane);
                badge.margin(3f, 8f, 3f, 8f);
                badge.add(item.action.localized()).color(item.action.color).style(Styles.outlineLabel);
                itemRow.add(badge).padLeft(8f);

                // Info action button
                if(item.entry.isValid){
                    itemRow.button(Icon.info, Styles.clearNonei, () -> {
                        if(item.entry.type == PackType.MAP){
                            new MapInfoDialog().show(item.entry);
                        }else{
                            Schematic s = item.entry.schematic != null ? item.entry.schematic : item.entry.getOrReadSchematic();
                            if(s != null && Vars.ui != null && Vars.ui.schematics != null){
                                Vars.ui.schematics.showInfo(s);
                            }
                        }
                    }).size(32f).padLeft(4f);
                }

                list.add(itemRow).growX().pad(3f).row();
            }
        }

        ScrollPane pane = new ScrollPane(list);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        cont.add(pane).grow().pad(4f, 8f, 4f, 8f).row();

        // Disclaimer notice at bottom
        cont.add(getLocalizedText("packloader.dryrun.disclaimer"))
            .color(Pal.lightishGray).style(Styles.outlineLabel).fontScale(0.85f).pad(6f).wrap().growX().minWidth(0f).row();

        // Bottom Action buttons
        buttons.clear();
        buttons.defaults().pad(4f);
        var backBtn = UIUtils.pad(buttons.button(getLocalizedText("packloader.action.back"), Icon.left, this::hide)
            .size(Vars.mobile ? 100f : 130f, 54f).get());
        if(backBtn != null && backBtn.getLabel() != null){
            backBtn.getLabel().setWrap(false);
        }

        if(Vars.mobile){
            buttons.button(Icon.export, () -> {
                new ExportReportDialog(ReportExporter.createFromDryRunPlan(plan, null)).show();
            }).size(48f, 54f);
        }else{
            var exportBtn = UIUtils.pad(buttons.button(getLocalizedText("packloader.report.button.plan"), Icon.export, () -> {
                new ExportReportDialog(ReportExporter.createFromDryRunPlan(plan, null)).show();
            }).size(130f, 54f).get());
            if(exportBtn != null && exportBtn.getLabel() != null){
                exportBtn.getLabel().setWrap(false);
            }
        }

        var confirmBtn = UIUtils.pad(buttons.button(
            getLocalizedText("packloader.dryrun.button.confirm", plan.totalSelected),
            Icon.download, () -> {
                hide();
                if(onConfirm != null) onConfirm.run();
            }
        ).size(Vars.mobile ? 140f : 180f, 54f).get());
        if(confirmBtn != null){
            confirmBtn.setDisabled(plan.totalSelected == 0);
            if(confirmBtn.getLabel() != null){
                confirmBtn.getLabel().setWrap(false);
            }
        }
    }

    private void addStatCard(Table parent, String label, int count, Color color){
        Table card = new Table(Tex.button);
        card.margin(4f, 10f, 4f, 10f);
        card.table(t -> {
            t.add(String.valueOf(count)).color(color).style(Styles.outlineLabel).fontScale(1.2f).row();
            t.add(label).color(Pal.lightishGray).fontScale(0.85f);
        });
        parent.add(card).growX().uniform().pad(2f);
    }
}

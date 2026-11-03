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
        setupUI();
    }

    private void setupUI(){
        cont.clear();

        // Subtitle prompt
        cont.add(getLocalizedText("packloader.dryrun.subtitle"))
            .color(Pal.accent).style(Styles.outlineLabel).padTop(4f).padBottom(6f).row();

        // Stat cards summary banner
        Table statsTable = new Table(Tex.pane);
        statsTable.margin(8f);
        statsTable.defaults().pad(4f);

        addStatCard(statsTable, getLocalizedText("packloader.dryrun.stat.new"), plan.newCount, Pal.heal);
        addStatCard(statsTable, getLocalizedText("packloader.dryrun.stat.overwrite"), plan.overwriteCount, Pal.accent);
        addStatCard(statsTable, getLocalizedText("packloader.dryrun.stat.rename"), plan.renameCount, Color.gold);
        addStatCard(statsTable, getLocalizedText("packloader.dryrun.stat.skip"), plan.skipCount, Pal.lightishGray);
        if(plan.contentDuplicateCount > 0){
            addStatCard(statsTable, getLocalizedText("packloader.dryrun.stat.content-duplicates"), plan.contentDuplicateCount, Color.cyan);
        }

        cont.add(statsTable).growX().pad(4f, 8f, 4f, 8f).row();

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

                // Type Icon
                if(item.entry.type == PackType.MAP){
                    itemRow.image(Icon.terrain).size(28f).color(Pal.accent);
                }else{
                    itemRow.image(Icon.paste).size(28f).color(Pal.accent);
                }

                // File and resolution description
                itemRow.table(t -> {
                    t.left().defaults().left().growX();

                    StringBuilder title = new StringBuilder();
                    title.append(item.entry.displayName);
                    if(item.isRenamed()){
                        title.append(" -> [gold]").append(item.plannedTargetName).append("[]");
                    }
                    t.add(title.toString()).color(Color.white).ellipsis(true).growX().minWidth(0f).row();

                    StringBuilder info = new StringBuilder();
                    info.append("[lightgray]").append(item.entry.sanitizedFileName).append("[]");
                    if(item.entry.isDuplicate && !item.entry.duplicateReason.isEmpty()){
                        info.append("  •  [gold]").append(item.entry.duplicateReason).append("[]");
                    }
                    if(item.entry.isContentDuplicate && !item.entry.contentDuplicateReason.isEmpty()){
                        info.append("  •  [cyan]").append(item.entry.contentDuplicateReason).append("[]");
                    }
                    t.add(info.toString()).ellipsis(true).growX().minWidth(0f).padTop(2f);
                }).growX().minWidth(0f).left().padLeft(6f);

                // Action Badge
                Table badge = new Table(Tex.button);
                badge.margin(4f, 10f, 4f, 10f);
                badge.add(item.action.localized()).color(item.action.color).style(Styles.outlineLabel);
                itemRow.add(badge).padLeft(8f);

                list.add(itemRow).growX().pad(3f).row();
            }
        }

        ScrollPane pane = new ScrollPane(list);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        cont.add(pane).grow().pad(4f, 8f, 4f, 8f).row();

        // Disclaimer notice at bottom
        cont.add(getLocalizedText("packloader.dryrun.disclaimer"))
            .color(Pal.lightishGray).style(Styles.outlineLabel).fontScale(0.85f).pad(6f).wrap().growX().row();

        // Bottom Action buttons
        buttons.clear();
        UIUtils.pad(buttons.button(getLocalizedText("packloader.action.back"), Icon.left, this::hide)
            .size(Vars.mobile ? 140f : 180f, 60f).get());

        var confirmBtn = UIUtils.pad(buttons.button(
            getLocalizedText("packloader.dryrun.button.confirm", plan.totalSelected),
            Icon.download, () -> {
                hide();
                if(onConfirm != null) onConfirm.run();
            }
        ).size(Vars.mobile ? 180f : 240f, 60f).get());
        if(confirmBtn != null){
            confirmBtn.setDisabled(plan.totalSelected == 0);
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

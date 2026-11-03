package packloader.ui;

import arc.graphics.Color;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import packloader.undo.UndoManager;

import static packloader.util.Localization.getLocalizedText;

public class UndoResultDialog extends BaseDialog{
    private final UndoManager.UndoResult result;
    private final Runnable onClosed;

    public UndoResultDialog(UndoManager.UndoResult result, Runnable onClosed){
        super(getLocalizedText("packloader.undo.result.title"));
        this.result = result;
        this.onClosed = onClosed;

        setFillParent(false);
        setupUI();
    }

    private void setupUI(){
        cont.clear();
        cont.margin(16f);
        cont.defaults().pad(6f);

        // Header icon
        cont.image(result.success ? Icon.ok : Icon.warning)
            .size(48f).color(result.success ? Pal.heal : Color.orange).padBottom(8f).row();

        // Summary text
        cont.add(getLocalizedText("packloader.undo.result.summary", result.deletedCount, result.restoredCount))
            .style(Styles.defaultLabel).color(Color.white).padBottom(4f).row();

        if(result.conflictCount > 0){
            cont.add(getLocalizedText("packloader.undo.result.conflicts", result.conflictCount))
                .style(Styles.defaultLabel).color(Color.orange).padBottom(8f).row();
        }

        // Stat cards banner
        Table statsTable = new Table(Tex.pane);
        statsTable.margin(8f);
        statsTable.defaults().pad(4f);

        addStatCard(statsTable, getLocalizedText("packloader.undo.stat.delete"), result.deletedCount, Pal.remove);
        addStatCard(statsTable, getLocalizedText("packloader.undo.stat.restore"), result.restoredCount, Pal.heal);
        if(result.conflictCount > 0){
            addStatCard(statsTable, getLocalizedText("packloader.undo.stat.conflict"), result.conflictCount, Color.orange);
        }

        cont.add(statsTable).growX().pad(6f).row();

        // Close button
        buttons.clear();
        UIUtils.pad(buttons.button(getLocalizedText("packloader.welcome.close"), Icon.ok, () -> {
            hide();
            if(onClosed != null) onClosed.run();
        }).size(Vars.mobile ? 160f : 200f, 54f).get());
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

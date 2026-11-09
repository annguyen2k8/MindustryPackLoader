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
import packloader.model.PackType;
import packloader.undo.ImportTransaction;
import packloader.undo.UndoManager;
import packloader.undo.UndoVerification;

import static packloader.util.Localization.getLocalizedText;

public class UndoConfirmDialog extends BaseDialog{
    private final UndoManager undoManager;
    private final ImportTransaction transaction;
    private final Runnable onCompleted;

    public UndoConfirmDialog(UndoManager undoManager, ImportTransaction transaction, Runnable onCompleted){
        super(getLocalizedText("packloader.undo.title"));
        this.undoManager = undoManager;
        this.transaction = transaction;
        this.onCompleted = onCompleted;

        setFillParent(true);
        setupUI();
    }

    private void setupUI(){
        cont.clear();

        UndoVerification ver = undoManager.verifyUndo(transaction);

        // Subtitle prompt
        cont.add(getLocalizedText("packloader.undo.subtitle"))
            .color(Pal.accent).style(Styles.outlineLabel).padTop(4f).padBottom(4f).row();

        // Source Name
        cont.add(getLocalizedText("packloader.undo.source", transaction.sourceName))
            .color(Pal.lightishGray).padBottom(6f).row();

        // Stat cards summary banner
        Table statsTable = new Table(Tex.pane);
        statsTable.margin(8f);
        statsTable.defaults().pad(4f);

        addStatCard(statsTable, getLocalizedText("packloader.undo.stat.delete"), ver.canDeleteCount, Pal.remove);
        addStatCard(statsTable, getLocalizedText("packloader.undo.stat.restore"), ver.canRestoreCount, Pal.heal);
        addStatCard(statsTable, getLocalizedText("packloader.undo.stat.conflict"), ver.conflictCount, ver.conflictCount > 0 ? Color.orange : Pal.lightishGray);

        cont.add(statsTable).growX().pad(4f, 8f, 4f, 8f).row();

        // Warning banner if conflicts detected
        if(ver.hasConflicts()){
            Table warnTable = new Table(Tex.pane);
            warnTable.margin(6f);
            warnTable.image(Icon.warning).size(24f).color(Color.orange).padRight(6f);
            warnTable.add(getLocalizedText("packloader.undo.conflict.warning"))
                .color(Color.orange).wrap().growX().minWidth(0f).left();
            cont.add(warnTable).growX().pad(2f, 8f, 4f, 8f).row();
        }

        // Scrollable entries list
        Table list = new Table();
        list.margin(6f);

        for(UndoVerification.VerifiedEntry ve : ver.verifiedEntries){
            Table itemRow = new Table(Tex.pane);
            itemRow.margin(8f);
            itemRow.defaults().pad(4f);

            // Type Icon
            if(ve.entry.type == PackType.MAP){
                itemRow.image(Icon.terrain).size(28f).color(Pal.accent);
            }else{
                itemRow.image(Icon.paste).size(28f).color(Pal.accent);
            }

            // Names & Details
            itemRow.table(t -> {
                t.left().defaults().left().growX();

                t.add(ve.entry.originalDisplayName.isEmpty() ? ve.entry.originalFileName : ve.entry.originalDisplayName)
                    .color(Color.white).ellipsis(true).growX().minWidth(0f).row();

                StringBuilder info = new StringBuilder();
                info.append("[lightgray]").append(ve.entry.originalFileName).append("[]");
                if(!ve.detail.isEmpty()){
                    info.append("  •  [stat]").append(ve.detail).append("[]");
                }
                t.add(info.toString()).ellipsis(true).growX().minWidth(0f).padTop(2f);
            }).growX().minWidth(0f).left().padLeft(6f);

            // Action Status Badge
            Table badge = new Table(Tex.button);
            badge.margin(4f, 10f, 4f, 10f);

            Color badgeColor;
            String badgeText;
            switch(ve.status){
                case CAN_DELETE -> {
                    badgeColor = Pal.remove;
                    badgeText = getLocalizedText("packloader.undo.status.delete");
                }
                case CAN_RESTORE -> {
                    badgeColor = Pal.heal;
                    badgeText = getLocalizedText("packloader.undo.status.restore");
                }
                case CONFLICT_MODIFIED -> {
                    badgeColor = Color.orange;
                    badgeText = getLocalizedText("packloader.undo.status.modified");
                }
                case CONFLICT_BACKUP_MISSING -> {
                    badgeColor = Pal.remove;
                    badgeText = getLocalizedText("packloader.undo.status.missing-backup");
                }
                case CONFLICT_BACKUP_CORRUPTED -> {
                    badgeColor = Pal.remove;
                    badgeText = getLocalizedText("packloader.undo.status.corrupted");
                }
                case ALREADY_REMOVED -> {
                    badgeColor = Pal.lightishGray;
                    badgeText = getLocalizedText("packloader.undo.status.removed");
                }
                default -> {
                    badgeColor = Pal.lightishGray;
                    badgeText = getLocalizedText("packloader.conflict.skip");
                }
            }

            badge.add(badgeText).color(badgeColor).style(Styles.outlineLabel);
            itemRow.add(badge).padLeft(8f);

            list.add(itemRow).growX().pad(3f).row();
        }

        ScrollPane pane = new ScrollPane(list);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        cont.add(pane).grow().pad(4f, 8f, 4f, 8f).row();

        // Bottom Action buttons
        buttons.clear();
        var backBtn = UIUtils.pad(buttons.button(getLocalizedText("packloader.action.back"), Icon.left, this::hide)
            .size(Vars.mobile ? 140f : 180f, 60f).get());
        if(backBtn != null && backBtn.getLabel() != null){
            backBtn.getLabel().setWrap(false);
        }

        var confirmBtn = UIUtils.pad(buttons.button(
            getLocalizedText("packloader.undo.confirm"),
            Icon.undo,
            () -> {
                hide();
                executeUndo();
            }
        ).size(Vars.mobile ? 180f : 240f, 60f).get());
        if(confirmBtn != null){
            confirmBtn.setDisabled(!ver.canUndoAny());
            if(confirmBtn.getLabel() != null){
                confirmBtn.getLabel().setWrap(false);
            }
        }
    }

    private void executeUndo(){
        UndoManager.UndoResult result = undoManager.executeUndo(transaction, null);
        new UndoResultDialog(result, onCompleted).show();
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

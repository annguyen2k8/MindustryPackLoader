package packloader.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.Scaling;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.type.ItemSeq;
import mindustry.type.ItemStack;
import mindustry.ui.BorderImage;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import packloader.core.PackFormat;
import packloader.core.PackScanner;
import packloader.model.PackEntry;

import static packloader.util.Localization.getLocalizedText;

/**
 * Native Mindustry dialog displaying full details, block count, resource costs,
 * power metrics, enlarged preview, and in-game direct placement for a schematic.
 */
public class SchematicInfoDialog extends BaseDialog{
    private final Runnable onPlacedCallback;

    public SchematicInfoDialog(){
        this(null);
    }

    public SchematicInfoDialog(Runnable onPlacedCallback){
        super(getLocalizedText("packloader.preview.schematic.info"));
        this.onPlacedCallback = onPlacedCallback;
        addCloseButton();
    }

    public void show(PackEntry entry){
        cont.clear();
        buttons.clearChildren();
        boolean isCompact = UIUtils.isCompact();
        float btnW = isCompact ? Math.min(210f, (UIUtils.screenWidth() - 36f) / 2f) : 210f;
        float btnH = UIUtils.buttonHeight();

        var backBtn = UIUtils.fitButton(buttons.button("@back", Icon.left, this::hide).size(btnW, btnH).pad(4f).get());
        addCloseListener();

        if(entry == null){
            super.show();
            return;
        }

        Schematic s = entry.schematic != null ? entry.schematic : entry.getOrReadSchematic();
        if(s == null){
            if(Vars.ui != null){
                Vars.ui.showErrorMessage(getLocalizedText("packloader.error.invalid-schematic"));
            }
            return;
        }

        title.setText(s.name() != null && !s.name().isEmpty() ? s.name() : entry.displayName);

        Table main = new Table();
        main.margin(10f);
        main.defaults().pad(6f);

        // Preview thumbnail image
        Table previewHolder = new Table();
        float previewSize = Math.min(260f, UIUtils.screenWidth() * 0.7f);
        previewHolder.setSize(previewSize, previewSize);

        Texture tex = PreviewManager.getCachedTexture(entry);
        if(tex != null && !tex.isDisposed()){
            BorderImage img = new BorderImage(tex, 4f);
            img.setScaling(Scaling.fit);
            img.borderColor = Pal.accent;
            previewHolder.add(img).size(previewSize - 10f);
        }else{
            previewHolder.image(Icon.paste).size(64f).color(Pal.accent);
            if(entry.isValid){
                PreviewManager.loadPreviewAsync(entry, loaded -> {
                    if(loaded != null && !loaded.isDisposed() && isShown()){
                        previewHolder.clear();
                        BorderImage img = new BorderImage(loaded, 4f);
                        img.setScaling(Scaling.fit);
                        img.borderColor = Pal.accent;
                        previewHolder.add(img).size(previewSize - 10f);
                    }
                });
            }
        }
        main.add(previewHolder).size(previewSize).center().row();

        // Metadata details card
        Table details = new Table(Styles.black6);
        details.margin(12f);
        details.defaults().left().pad(3f);

        float maxW = Math.min(UIUtils.screenWidth() * 0.85f, 440f);

        // Dimensions: W x H
        if(s.width > 0 && s.height > 0){
            details.add(getLocalizedText("packloader.preview.dimensions", s.width, s.height))
                .color(Color.white).style(Styles.defaultLabel).row();
        }

        // Block count
        if(s.tiles != null){
            details.add(getLocalizedText("packloader.preview.blocks", s.tiles.size))
                .color(Color.white).style(Styles.defaultLabel).row();
        }

        // File size
        details.add(getLocalizedText("packloader.preview.size", PackScanner.formatSize(entry.size)))
            .color(Pal.lightishGray).style(Styles.defaultLabel).row();

        // Power balance
        try{
            float prod = s.powerProduction();
            float cons = s.powerConsumption();
            if(prod > 0.01f || cons > 0.01f){
                if(prod > 0.01f){
                    details.add(getLocalizedText("packloader.preview.power.prod", Math.round(prod * 60)))
                        .color(Pal.heal).style(Styles.defaultLabel).row();
                }
                if(cons > 0.01f){
                    details.add(getLocalizedText("packloader.preview.power.cons", Math.round(cons * 60)))
                        .color(Pal.remove).style(Styles.defaultLabel).row();
                }
            }
        }catch(Throwable ignored){}

        // Requirements list
        try{
            ItemSeq reqs = s.requirements();
            if(reqs != null && reqs.total > 0){
                details.add(getLocalizedText("packloader.preview.requirements"))
                    .color(Pal.accent).style(Styles.defaultLabel).padTop(6f).row();

                Table reqTable = new Table();
                reqTable.defaults().left().pad(2f);
                int col = 0;
                for(ItemStack stack : reqs){
                    if(stack == null || stack.item == null || stack.amount <= 0) continue;
                    Table itemCell = new Table();
                    if(stack.item.uiIcon != null){
                        itemCell.image(stack.item.uiIcon).size(20f).padRight(4f);
                    }
                    itemCell.add(String.valueOf(stack.amount)).color(Color.lightGray).style(Styles.defaultLabel);
                    reqTable.add(itemCell).pad(4f);
                    col++;
                    if(col % 4 == 0) reqTable.row();
                }
                details.add(reqTable).growX().row();
            }
        }catch(Throwable ignored){}

        // Description
        String desc = s.description();
        if(desc != null && !desc.trim().isEmpty()){
            details.add(getLocalizedText("packloader.preview.description"))
                .color(Pal.accent).style(Styles.defaultLabel).padTop(6f).row();
            details.add(desc.trim())
                .color(Color.lightGray).style(Styles.defaultLabel).width(maxW).wrap().row();
        }

        main.add(details).growX().width(maxW).row();

        ScrollPane pane = new ScrollPane(main);
        cont.add(pane).grow().row();

        // Direct Placement button in game
        if(Vars.state != null && Vars.state.isGame()){
            final Schematic targetSchematic = s;
            var placeBtn = UIUtils.fitButton(buttons.button(getLocalizedText("packloader.action.place"), Icon.paste, () -> {
                boolean placed = SchematicPlacementHelper.placeSchematic(entry, () -> {
                    hide();
                    if(onPlacedCallback != null) onPlacedCallback.run();
                });
            }).size(btnW, btnH).pad(4f).get());
        }

        super.show();
    }
}

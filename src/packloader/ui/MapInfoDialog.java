package packloader.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.Scaling;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.BorderImage;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import packloader.core.PackFormat;
import packloader.core.PackScanner;
import packloader.model.PackEntry;

import static packloader.util.Localization.getLocalizedText;

/**
 * Native Mindustry dialog displaying full details, metadata, and enlarged preview for a map.
 */
public class MapInfoDialog extends BaseDialog{
    public MapInfoDialog(){
        super(getLocalizedText("packloader.preview.map.info"));
        addCloseButton();
    }

    public void show(PackEntry entry){
        cont.clear();
        if(entry == null){
            super.show();
            return;
        }

        title.setText(entry.displayName);
        PackFormat.MapInfo info = entry.mapInfo != null ? entry.mapInfo : entry.getOrReadMapInfo();

        Table main = new Table();
        main.margin(10f);
        main.defaults().pad(6f);

        // Preview thumbnail image
        Table previewHolder = new Table();
        float previewSize = Math.min(260f, Core.graphics != null ? Core.graphics.getWidth() * 0.7f : 260f);
        previewHolder.setSize(previewSize, previewSize);

        Texture tex = PreviewManager.getCachedTexture(entry);
        if(tex != null && !tex.isDisposed()){
            BorderImage img = new BorderImage(tex, 4f);
            img.setScaling(Scaling.fit);
            img.borderColor = Pal.accent;
            previewHolder.add(img).size(previewSize - 10f);
        }else{
            previewHolder.image(Icon.terrain).size(64f).color(Pal.accent);
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

        // Metadata details
        Table details = new Table(Styles.black6);
        details.margin(12f);
        details.defaults().left().pad(3f);

        float maxW = Math.min(Core.graphics != null ? Core.graphics.getWidth() * 0.85f : 440f, 440f);

        if(info != null && !info.author.isEmpty()){
            details.add(getLocalizedText("packloader.preview.author", info.author))
                .color(Pal.accent).style(Styles.defaultLabel).width(maxW).wrap().row();
        }

        if(info != null && info.width > 0 && info.height > 0){
            details.add(getLocalizedText("packloader.preview.dimensions", info.width, info.height))
                .color(Color.white).style(Styles.defaultLabel).row();
        }

        details.add(getLocalizedText("packloader.preview.size", PackScanner.formatSize(entry.size)))
            .color(Pal.lightishGray).style(Styles.defaultLabel).row();

        if(info != null && !info.description.isEmpty()){
            details.add(getLocalizedText("packloader.preview.description"))
                .color(Pal.accent).style(Styles.defaultLabel).padTop(6f).row();
            details.add(info.description)
                .color(Color.lightGray).style(Styles.defaultLabel).width(maxW).wrap().row();
        }

        main.add(details).growX().width(maxW).row();

        ScrollPane pane = new ScrollPane(main);
        cont.add(pane).grow().row();

        super.show();
    }
}

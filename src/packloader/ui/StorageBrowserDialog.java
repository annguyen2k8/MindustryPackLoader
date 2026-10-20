package packloader.ui;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import packloader.model.PackType;

public class StorageBrowserDialog extends BaseDialog{
    private final PackType targetType;
    private Fi currentDirectory;
    private Fi selectedDirectory;
    private Fi prevDirectory;
    private ScrollPane pane;
    private final Cons<Fi> onFileSelected;
    private final Cons<Fi> onFolderSelected;
    private final Table listTable = new Table();
    private arc.scene.ui.TextButton selectFolderButton;

    public StorageBrowserDialog(Cons<Fi> onFileSelected, Cons<Fi> onFolderSelected){
        this(null, onFileSelected, onFolderSelected);
    }

    public StorageBrowserDialog(PackType targetType, Cons<Fi> onFileSelected, Cons<Fi> onFolderSelected){
        super(targetType == PackType.MAP ?
            (Core.bundle != null ? Core.bundle.get("packloader.browser.title.maps") : "Scan Maps in Folder") :
            targetType == PackType.SCHEMATIC ?
            (Core.bundle != null ? Core.bundle.get("packloader.browser.title.schematics") : "Scan Schematics in Folder") :
            (Core.bundle != null ? Core.bundle.get("packloader.browser.title") : "Storage Browser"));
        this.targetType = targetType;
        this.onFileSelected = onFileSelected;
        this.onFolderSelected = onFolderSelected;

        // Start at external storage or data directory
        currentDirectory = getInitialDirectory();

        setupUI();
        shown(() -> {
            selectedDirectory = null;
            rebuild();
        });
    }

    private Fi getInitialDirectory(){
        try{
            String ext = Core.files.getExternalStoragePath();
            if(ext != null && !ext.isEmpty()){
                Fi extFi = Core.files.absolute(ext);
                Fi download = extFi.child("Download");
                if(download.exists() && download.isDirectory()) return download;
                if(extFi.exists() && extFi.isDirectory()) return extFi;
            }
        }catch(Throwable ignored){}

        Fi sdDownload = Core.files.absolute("/sdcard/Download");
        if(sdDownload.exists() && sdDownload.isDirectory()) return sdDownload;

        return Core.files.absolute(System.getProperty("user.home", "/"));
    }

    private void setupUI(){
        setFillParent(true);
        addCloseButton();

        selectFolderButton = UIUtils.pad(buttons.button(Core.bundle != null ? Core.bundle.get("packloader.browser.select.folder") : "Select", Icon.ok, () -> {
            Fi chosen = selectedDirectory != null ? selectedDirectory : currentDirectory;
            hide();
            if(onFolderSelected != null && chosen != null){
                onFolderSelected.get(chosen);
            }
        }).get());
    }

    public void rebuild(){
        float prevScroll = (pane != null && currentDirectory != null && currentDirectory.equals(prevDirectory)) ? pane.getScrollY() : 0f;
        prevDirectory = currentDirectory;

        if(selectFolderButton != null){
            Fi chosen = selectedDirectory != null ? selectedDirectory : currentDirectory;
            String chosenName = (chosen != null) ? chosen.name() : "";
            if(chosenName.isEmpty() && chosen != null) chosenName = chosen.path();
            selectFolderButton.setText(Core.bundle != null ?
                Core.bundle.format("packloader.browser.select.target", chosenName) :
                ("Select: " + chosenName));
            if(selectFolderButton.getLabel() != null){
                selectFolderButton.getLabel().setEllipsis(true);
            }
            if(selectFolderButton.getLabelCell() != null){
                selectFolderButton.getLabelCell().minWidth(0f);
            }
        }

        cont.clear();
        cont.top();

        // Header: Path and navigation buttons
        Table navTable = new Table(Tex.pane);
        navTable.margin(8f);

        // Quick path shortcuts with proper padding and icon alignment
        Table shortcuts = new Table();
        shortcuts.left();
        shortcuts.defaults().height(36f).pad(2f).padRight(8f);

        shortcuts.button(b -> {
            b.margin(4f, 10f, 4f, 10f);
            b.image(Icon.download).size(18f).color(Pal.accent).padRight(10f);
            b.add("Download").color(Color.white);
        }, Styles.flatt, () -> {
            try{
                String ext = Core.files.getExternalStoragePath();
                Fi dl = Core.files.absolute(ext).child("Download");
                if(dl.exists()){
                    currentDirectory = dl;
                    selectedDirectory = null;
                    rebuild();
                    return;
                }
            }catch(Throwable ignored){}
            Fi dl = Core.files.absolute("/sdcard/Download");
            if(dl.exists()){
                currentDirectory = dl;
                selectedDirectory = null;
                rebuild();
            }
        });

        shortcuts.button(b -> {
            b.margin(4f, 10f, 4f, 10f);
            b.image(Icon.folder).size(18f).color(Pal.accent).padRight(10f);
            b.add("SDCard").color(Color.white);
        }, Styles.flatt, () -> {
            try{
                String extPath = Core.files.getExternalStoragePath();
                if(extPath != null && Core.files.absolute(extPath).exists()){
                    currentDirectory = Core.files.absolute(extPath);
                    selectedDirectory = null;
                    rebuild();
                    return;
                }
            }catch(Throwable ignored){}
            Fi sd = Core.files.absolute("/sdcard");
            if(sd.exists()){
                currentDirectory = sd;
                selectedDirectory = null;
                rebuild();
            }
        });

        ScrollPane shortcutScroller = new ScrollPane(shortcuts);
        shortcutScroller.setScrollingDisabled(false, true);
        shortcutScroller.setOverscroll(false, false);
        shortcutScroller.setFadeScrollBars(false);
        navTable.add(shortcutScroller).growX().height(40f).left().row();

        // Current Directory row with Up button
        Table curRow = new Table();
        curRow.defaults().pad(2f);

        curRow.button(Icon.left, Styles.flati, () -> {
            if(currentDirectory != null && currentDirectory.parent() != null && currentDirectory.parent().exists()){
                currentDirectory = currentDirectory.parent();
                selectedDirectory = null;
                rebuild();
            }
        }).size(40f, 40f).disabled(b -> currentDirectory == null || currentDirectory.parent() == null || currentDirectory.equals(currentDirectory.parent()));

        String displayPath = currentDirectory == null ? (Core.bundle != null ? Core.bundle.get("packloader.browser.unknown") : "Unknown") : currentDirectory.path();
        curRow.add(displayPath).color(Pal.accent).ellipsis(true).growX().minWidth(0f).left().padLeft(6f);

        curRow.button(Icon.refresh, Styles.flati, () -> {
            selectedDirectory = null;
            rebuild();
        }).size(40f, 40f);

        curRow.clicked(() -> {
            if(selectedDirectory != null){
                selectedDirectory = null;
                rebuild();
            }
        });

        navTable.add(curRow).growX().minWidth(0f).height(44f).padTop(4f);
        cont.add(navTable).growX().minWidth(0f).pad(8f).padBottom(4f).row();

        // File and Folder listing
        listTable.clear();
        listTable.margin(6f);

        if(currentDirectory == null || !currentDirectory.exists() || !currentDirectory.isDirectory()){
            listTable.add(Core.bundle != null ? Core.bundle.get("packloader.browser.unreadable") : "Cannot read folder").color(Pal.remove).pad(20f);
        }else{
            Fi[] list = null;
            boolean permissionDenied = false;
            try{
                list = currentDirectory.list();
            }catch(Throwable t){
                permissionDenied = true;
                Log.err("[PackLoader] Cannot list directory: @", currentDirectory.path(), t);
            }

            if(permissionDenied || list == null){
                boolean isRestricted = packloader.core.PackScanner.isScopedStorageRestricted(currentDirectory);
                listTable.add(Core.bundle != null ?
                    (isRestricted ? Core.bundle.get("packloader.browser.scoped-denied") : Core.bundle.get("packloader.browser.denied")) :
                    (isRestricted ? "Folder access restricted by Android Scoped Storage.\nUse [Import .zip] to load all files without restriction." : "Access denied"))
                    .color(Pal.remove).wrap().growX().pad(16f).center().row();

                if(isRestricted){
                    UIUtils.pad(listTable.button(
                        Core.bundle != null ? Core.bundle.get("packloader.action.import-zip", "Import .zip") : "Import .zip",
                        Icon.download, () -> {
                            hide();
                            if(packloader.PackLoaderMod.dialog != null){
                                packloader.PackLoaderMod.dialog.pickZip(targetType);
                            }
                        }).size(210f, 64f).pad(6f).get());
                    listTable.row();
                }
            }else{
                Seq<Fi> dirs = new Seq<>();
                Seq<Fi> files = new Seq<>();

                for(Fi f : list){
                    try{
                        if(f.name().startsWith(".")) continue;
                        if(f.isDirectory()){
                            dirs.add(f);
                        }else{
                            boolean isZip = packloader.core.PackScanner.isZipFi(f);
                            PackType type = PackType.fromFileName(f.name());
                            if(type == null && !isZip){
                                type = packloader.core.PackScanner.detectPackType(f);
                            }

                            if(isZip){
                                files.add(f);
                            }else if(type != null){
                                if(targetType == null || type == targetType){
                                    files.add(f);
                                }
                            }
                        }
                    }catch(Throwable ignored){}
                }

                dirs.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
                files.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));

                // Parent directory entry (..) with folder icon
                if(currentDirectory != null && currentDirectory.parent() != null && currentDirectory.parent().exists() && !currentDirectory.equals(currentDirectory.parent())){
                    Table parentRow = new Table(Tex.pane);
                    parentRow.margin(6f);
                    parentRow.defaults().pad(3f);

                    parentRow.image(Icon.folder).size(30f).color(Pal.accent).padRight(6f);
                    parentRow.add("..").growX().left().color(Color.white).padLeft(6f);

                    parentRow.clicked(() -> {
                        currentDirectory = currentDirectory.parent();
                        selectedDirectory = null;
                        rebuild();
                    });

                    listTable.add(parentRow).growX().height(52f).pad(2f).row();
                }

                if(dirs.isEmpty() && files.isEmpty()){
                    boolean isRestricted = packloader.core.PackScanner.isScopedStorageRestricted(currentDirectory);
                    if(isRestricted){
                        listTable.add(Core.bundle != null ?
                            Core.bundle.get("packloader.browser.scoped-hint") :
                            "Android Scoped Storage may hide .msch/.msav files here.\nUse [Import .zip] to load all files without restriction.")
                            .color(Pal.accent).wrap().growX().pad(16f).center().row();

                        UIUtils.pad(listTable.button(
                            Core.bundle != null ? Core.bundle.get("packloader.action.import-zip", "Import .zip") : "Import .zip",
                            Icon.download, () -> {
                                hide();
                                if(packloader.PackLoaderMod.dialog != null){
                                    packloader.PackLoaderMod.dialog.pickZip(targetType);
                                }
                            }).size(210f, 64f).pad(6f).get());
                        listTable.row();
                    }else{
                        listTable.add(Core.bundle != null ? Core.bundle.get("packloader.browser.empty") : "No compatible files found").color(Pal.lightishGray).pad(20f).row();
                    }
                }

                // Directories first (clean row without arrow button)
                for(Fi d : dirs){
                    boolean isSelected = d.equals(selectedDirectory);
                    Table row = new Table(Tex.pane){
                        @Override
                        public void draw(){
                            super.draw();
                            if(isSelected){
                                Lines.stroke(Scl.scl(2f), Pal.accent);
                                Draw.alpha(parentAlpha);
                                Lines.rect(x, y, width, height);
                                Draw.reset();
                            }
                        }
                    };
                    row.margin(6f);
                    row.defaults().pad(3f);

                    row.image(Icon.folder).size(30f).color(Pal.accent).padRight(6f);
                    row.add(d.name()).growX().minWidth(0f).ellipsis(true).left().color(isSelected ? Pal.accent : Color.white).padLeft(6f);

                    row.clicked(() -> {
                        if(d.equals(selectedDirectory)){
                            currentDirectory = d;
                            selectedDirectory = null;
                            rebuild();
                        }else{
                            selectedDirectory = d;
                            rebuild();
                        }
                    });

                    listTable.add(row).growX().minWidth(0f).minHeight(52f).pad(2f).row();
                }

                // Compatible files
                for(Fi f : files){
                    Table row = new Table(Tex.pane);
                    row.margin(6f);
                    row.defaults().pad(3f);

                    String name = f.name();
                    PackType type = PackType.fromFileName(name);
                    boolean isZip = name.toLowerCase().endsWith(".zip");

                    if(isZip){
                        row.image(Icon.fileArchive).size(30f).color(Pal.accent);
                    }else if(type == PackType.MAP){
                        row.image(Icon.terrain).size(30f).color(Pal.accent);
                    }else{
                        row.image(Icon.paste).size(30f).color(Pal.accent);
                    }

                    row.table(t -> {
                        t.left().defaults().left().growX();
                        t.add(name).color(Color.white).ellipsis(true).growX().minWidth(0f).left().row();
                        String typeDesc = isZip ? (Core.bundle != null ? Core.bundle.get("packloader.type.zip") : "ZIP Archive") : (type != null ? type.localizedName() : "");
                        t.add(packloader.core.PackScanner.formatSize(f.length()) + " (" + typeDesc + ")")
                         .color(Pal.lightishGray).ellipsis(true).growX().minWidth(0f).left();
                    }).growX().minWidth(0f).left().padLeft(8f);

                    row.button(Core.bundle != null ? Core.bundle.get("packloader.browser.select.file") : "Select", Styles.flatt, () -> {
                        hide();
                        if(onFileSelected != null){
                            onFileSelected.get(f);
                        }
                    }).size(80f, 38f).right().padRight(4f);

                    row.clicked(() -> {
                        hide();
                        if(onFileSelected != null){
                            onFileSelected.get(f);
                        }
                    });

                    listTable.add(row).growX().minWidth(0f).minHeight(54f).pad(2f).row();
                }
            }
        }

        pane = new ScrollPane(listTable);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        if(prevScroll > 0){
            pane.setScrollYForce(prevScroll);
            Core.app.post(() -> {
                if(pane != null) pane.setScrollYForce(prevScroll);
            });
        }
        cont.add(pane).grow().minWidth(0f).pad(8f).row();
    }
}

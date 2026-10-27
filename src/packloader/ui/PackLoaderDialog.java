package packloader.ui;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.ui.CheckBox;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.Threads;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.FileChooser;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import packloader.core.PackImporter;
import packloader.core.PackScanner;
import packloader.model.DuplicatePolicy;
import packloader.model.ImportReport;
import packloader.model.PackEntry;
import packloader.model.PackType;

import static packloader.util.Localization.getLocalizedText;

public class PackLoaderDialog extends BaseDialog{
    private final PackScanner scanner = new PackScanner();
    private final PackImporter importer = new PackImporter();

    private final Seq<PackEntry> entries = new Seq<>();
    private String sourceName = "";
    private DuplicatePolicy duplicatePolicy = DuplicatePolicy.RENAME;
    private PackType targetType = null;

    private enum FilterMode{ ALL, MAPS, SCHEMATICS }
    private FilterMode filterMode = FilterMode.ALL;
    private String searchText = "";

    public PackLoaderDialog(){
        super(getLocalizedText("packloader.dialog.title"));

        shouldPause = true;
        setFillParent(true);
        addCloseButton();

        shown(this::rebuild);
        onResize(this::rebuild);
    }

    @Override
    public arc.scene.ui.Dialog show(){
        if(entries.isEmpty() && sourceName.isEmpty()){
            this.targetType = null;
        }
        return super.show();
    }

    public void showMenu(){
        this.targetType = null;
        this.entries.clear();
        this.sourceName = "";
        super.show();
    }

    public void showForType(PackType targetType){
        this.targetType = targetType;
        this.entries.clear();
        this.sourceName = "";
        super.show();
    }

    public void rebuild(){
        cont.clear();

        // Contextual title: in source selection and review mode, respect targetType
        if(title != null){
            if(targetType == PackType.MAP){
                title.setText(getLocalizedText("packloader.dialog.title.maps"));
            }else if(targetType == PackType.SCHEMATIC){
                title.setText(getLocalizedText("packloader.dialog.title.schematics"));
            }else{
                title.setText(getLocalizedText("packloader.dialog.title"));
            }
        }

        if(entries.isEmpty() && sourceName.isEmpty()){
            if(targetType == null){
                buildModeSelection();
            }else{
                buildSourceSelection();
            }
        }else{
            buildEntriesView();
        }
    }

    /** View 0: Initial Mode Selection (Choose Load Schematics vs Load Maps) */
    private void buildModeSelection(){
        Table root = new Table();
        float maxW = Math.min(Core.graphics.getWidth() * 0.92f, 480f);
        root.defaults().pad(6f).width(maxW);

        // Prompt subtitle
        root.add(getLocalizedText("packloader.mode.prompt"))
            .color(Pal.lightishGray).style(Styles.defaultLabel).padTop(4f).padBottom(10f).row();

        // Choice 1: Schematics (.msch)
        root.add(createSourceOptionButton(
            Icon.paste,
            getLocalizedText("packloader.mode.schem.title"),
            getLocalizedText("packloader.mode.schem.desc"),
            () -> {
                targetType = PackType.SCHEMATIC;
                rebuild();
            },
            maxW
        )).minHeight(76f).pad(4f).row();

        // Choice 2: Maps (.msav)
        root.add(createSourceOptionButton(
            Icon.terrain,
            getLocalizedText("packloader.mode.map.title"),
            getLocalizedText("packloader.mode.map.desc"),
            () -> {
                targetType = PackType.MAP;
                rebuild();
            },
            maxW
        )).minHeight(76f).pad(4f).row();

        ScrollPane pane = new ScrollPane(root);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        cont.add(pane).grow().pad(4f).row();

        buttons.clear();
        addCloseButton();
    }

    /** View 1: Source Selection (When a specific pack type is active) */
    private void buildSourceSelection(){
        Table root = new Table();
        float maxW = Math.min(Core.graphics.getWidth() * 0.92f, 480f);
        root.defaults().pad(4f).width(maxW);

        // Schematics Section
        if(targetType == PackType.SCHEMATIC){
            root.add(createSourceOptionButton(
                Icon.folder,
                getLocalizedText("packloader.source.scan-folder.schem"),
                getLocalizedText("packloader.source.scan-folder.schem.desc"),
                () -> openFolderPicker(PackType.SCHEMATIC),
                maxW
            )).minHeight(68f).pad(4f).padTop(10f).padBottom(6f).row();

            root.add(createSourceOptionButton(
                Icon.download,
                getLocalizedText("packloader.source.zip.schem"),
                getLocalizedText("packloader.source.zip.schem.desc"),
                () -> pickZip(PackType.SCHEMATIC),
                maxW
            )).minHeight(68f).pad(4f).row();
        }

        // Maps Section
        if(targetType == PackType.MAP){
            root.add(createSourceOptionButton(
                Icon.folder,
                getLocalizedText("packloader.source.scan-folder.map"),
                getLocalizedText("packloader.source.scan-folder.map.desc"),
                () -> openFolderPicker(PackType.MAP),
                maxW
            )).minHeight(68f).pad(4f).padTop(10f).padBottom(6f).row();

            root.add(createSourceOptionButton(
                Icon.download,
                getLocalizedText("packloader.source.zip.map"),
                getLocalizedText("packloader.source.zip.map.desc"),
                () -> pickZip(PackType.MAP),
                maxW
            )).minHeight(68f).pad(4f).row();
        }

        // Option: Manual Path Input (Desktop power users)
        if(!Vars.mobile){
            root.add(createSourceOptionButton(
                Icon.paste,
                getLocalizedText("packloader.source.path.title"),
                getLocalizedText("packloader.source.path.desc"),
                this::openPathInputDialog,
                maxW
            )).minHeight(68f).pad(3f).padTop(8f).row();
        }

        ScrollPane pane = new ScrollPane(root);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        cont.add(pane).grow().pad(4f).row();

        buttons.clear();
        UIUtils.pad(buttons.button(getLocalizedText("packloader.action.back"), Icon.left, () -> {
            targetType = null;
            rebuild();
        }).size(210f, 64f).get());
    }

    private Table createSourceOptionButton(Drawable icon, String title, String desc, Runnable clicked, float buttonWidth){
        Table btn = new Table(Tex.button);
        btn.clicked(clicked);
        btn.margin(8f);

        // Icon column
        btn.image(icon).size(40f).color(Pal.accent).left().padRight(12f);

        // Text column: explicit width and wrap() ensure subtitle and title never overflow right
        float textWidth = Math.max(160f, buttonWidth - 92f);
        btn.table(t -> {
            t.left().defaults().left().growX();
            t.add(title).style(Styles.defaultLabel).color(Color.white).width(textWidth).wrap().padBottom(4f).row();
            t.add(desc).style(Styles.defaultLabel).color(Pal.lightishGray).width(textWidth).wrap();
        }).growX().left();

        return btn;
    }

    /** View 2: File Review and Bulk Import Controls */
    private void buildEntriesView(){
        // Top Header: Source Info & Change Source
        Table header = new Table(Tex.pane);
        header.margin(8f);
        header.defaults().pad(4f);

        int mapsCount = entries.count(e -> e.type == PackType.MAP);
        int schemsCount = entries.count(e -> e.type == PackType.SCHEMATIC);

        header.table(t -> {
            t.left();
            t.add(getLocalizedText("packloader.source.info", sourceName))
             .color(Pal.accent).ellipsis(true).growX().minWidth(0f).left().row();
            String summary;
            if(targetType == PackType.MAP){
                summary = getLocalizedText("packloader.source.summary.single.map", mapsCount);
            }else if(targetType == PackType.SCHEMATIC){
                summary = getLocalizedText("packloader.source.summary.single.schem", schemsCount);
            }else{
                summary = getLocalizedText("packloader.source.summary", mapsCount, schemsCount, entries.size);
            }
            t.add(summary).color(Pal.lightishGray).ellipsis(true).growX().minWidth(0f).left();
        }).growX().left().padLeft(6f).minWidth(0f);

        var changeBtn = UIUtils.pad(header.button(
            getLocalizedText("packloader.source.change"),
            Icon.refresh, Styles.flatt, () -> {
                entries.clear();
                sourceName = "";
                targetType = null;
                rebuild();
            }).height(40f).padRight(4f).get());
        if(changeBtn != null){
            changeBtn.margin(4f, 10f, 4f, 10f);
        }

        cont.add(header).growX().pad(6f).row();

        // Toolbar: Filter, Selection, Conflict Resolution Mode
        Table toolbar = new Table();
        toolbar.defaults().pad(3f);

        // Filter buttons (only shown in general multi-type mode)
        if(targetType == null){
            toolbar.button(getLocalizedText("packloader.filter.all", entries.size), Styles.flatTogglet, () -> {
                filterMode = FilterMode.ALL;
                rebuildEntriesList();
            }).checked(b -> filterMode == FilterMode.ALL).height(38f);

            toolbar.button(getLocalizedText("packloader.filter.maps", mapsCount), Styles.flatTogglet, () -> {
                filterMode = FilterMode.MAPS;
                rebuildEntriesList();
            }).checked(b -> filterMode == FilterMode.MAPS).height(38f);

            toolbar.button(getLocalizedText("packloader.filter.schematics", schemsCount), Styles.flatTogglet, () -> {
                filterMode = FilterMode.SCHEMATICS;
                rebuildEntriesList();
            }).checked(b -> filterMode == FilterMode.SCHEMATICS).height(38f);
        }else{
            String title = (targetType == PackType.MAP ? getLocalizedText("packloader.dialog.title.maps") : getLocalizedText("packloader.dialog.title.schematics")) + " (" + entries.size + ")";
            toolbar.add(title).color(Pal.accent).style(Styles.outlineLabel).left().padRight(8f);
        }

        toolbar.add().growX(); // spacer pushes mass selection buttons to right

        // Mass selection buttons with proper spacing between icon and text
        var selectAllBtn = UIUtils.pad(toolbar.button(
            getLocalizedText("packloader.select.all"),
            Icon.ok, Styles.flatt, () -> {
                for(PackEntry e : entries){
                    if(filterMatches(e)) e.selected = true;
                }
                rebuildEntriesList();
            }).height(38f).pad(2f).get());
        if(selectAllBtn != null){
            selectAllBtn.margin(4f, 10f, 4f, 10f);
        }

        var deselectBtn = UIUtils.pad(toolbar.button(
            getLocalizedText("packloader.select.none"),
            Icon.cancel, Styles.flatt, () -> {
                for(PackEntry e : entries){
                    if(filterMatches(e)) e.selected = false;
                }
                rebuildEntriesList();
            }).height(38f).pad(2f).get());
        if(deselectBtn != null){
            deselectBtn.margin(4f, 10f, 4f, 10f);
        }

        cont.add(toolbar).growX().pad(4f).row();

        // Conflict Resolution Policy Row
        Table conflictRow = new Table(Tex.pane);
        conflictRow.margin(6f);
        conflictRow.defaults().pad(3f);

        conflictRow.add(getLocalizedText("packloader.conflict.label")).color(Pal.accent).padRight(8f);

        for(DuplicatePolicy p : DuplicatePolicy.values()){
            conflictRow.button(b -> {
                b.margin(4f);
                b.add(p.title()).padLeft(4f).padRight(4f);
            }, Styles.flatTogglet, () -> {
                duplicatePolicy = p;
                rebuildEntriesList();
            }).checked(btn -> duplicatePolicy == p).height(34f).pad(2f);
        }

        cont.add(conflictRow).growX().pad(4f).row();

        // Scrollable Entries List Container
        cont.table(this::buildListTable).grow().minWidth(0f).pad(4f).name("listContainer").row();

        // Bottom Action Bar
        buttons.clear();
        addCloseButton();

        int selectedCount = entries.count(e -> e.selected);
        String initialImportText = getLocalizedText("packloader.import.action", selectedCount);
        var importBtn = UIUtils.pad(buttons.button(initialImportText, Icon.download, this::executeImport).size(210f, 64f).get());
        importBtn.update(() -> {
            int count = entries.count(e -> e.selected);
            String text = getLocalizedText("packloader.import.action", count);
            importBtn.setText(text);
            importBtn.setDisabled(count == 0);
        });
    }

    private Table listTableRef;

    private void buildListTable(Table container){
        listTableRef = new Table();
        listTableRef.margin(6f);
        populateListTable(listTableRef);

        ScrollPane pane = new ScrollPane(listTableRef);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        container.add(pane).grow().minWidth(0f);
    }

    private void rebuildEntriesList(){
        if(listTableRef != null){
            listTableRef.clear();
            populateListTable(listTableRef);
        }
    }

    private void populateListTable(Table table){
        Seq<PackEntry> filtered = entries.select(this::filterMatches);

        if(filtered.isEmpty()){
            table.add(getLocalizedText("packloader.filter.none")).color(Pal.lightishGray).pad(20f).row();
            return;
        }

        for(PackEntry entry : filtered){
            Table row = new Table(Tex.pane);
            row.margin(6f);
            row.defaults().pad(3f);

            // Large touch-friendly CheckBox (touch disabled so row click handles toggle cleanly)
            CheckBox box = new CheckBox("");
            box.setChecked(entry.selected);
            box.touchable = arc.scene.event.Touchable.disabled;
            row.add(box).size(36f).padRight(4f);

            // Clicking whole row toggles selection
            row.clicked(() -> {
                entry.selected = !entry.selected;
                box.setChecked(entry.selected);
            });

            // Type Icon
            if(entry.type == PackType.MAP){
                row.image(Icon.terrain).size(28f).color(Pal.accent);
            }else{
                row.image(Icon.paste).size(28f).color(Pal.accent);
            }

            // Title & Subtitle Info
            row.table(t -> {
                t.left().defaults().left().growX();

                StringBuilder titleBuilder = new StringBuilder();
                titleBuilder.append(entry.displayName);

                String nameNoExt = entry.sanitizedFileName.contains(".") ?
                    entry.sanitizedFileName.substring(0, entry.sanitizedFileName.lastIndexOf('.')) : entry.sanitizedFileName;

                if(entry.isDuplicate){
                    titleBuilder.append("  [gold]").append(getLocalizedText("packloader.badge.duplicate"));
                }
                if(!entry.isValid){
                    titleBuilder.append("  [scarlet]").append(getLocalizedText("packloader.badge.invalid"));
                }

                t.add(titleBuilder.toString()).color(Color.white).ellipsis(true).growX().minWidth(0f).left().row();

                // Clean second line: File size & optional details (no confusing repeating path)
                StringBuilder infoBuilder = new StringBuilder();
                infoBuilder.append(PackScanner.formatSize(entry.size));

                if(!entry.displayName.equalsIgnoreCase(nameNoExt) && !entry.displayName.equalsIgnoreCase(entry.sanitizedFileName)){
                    infoBuilder.append("  •  [lightgray]").append(entry.sanitizedFileName);
                }

                if(entry.isDuplicate && !entry.duplicateReason.isEmpty()){
                    infoBuilder.append("  •  [gold]").append(entry.duplicateReason);
                }
                if(!entry.isValid && !entry.errorReason.isEmpty()){
                    infoBuilder.append("  •  [scarlet]").append(entry.errorReason);
                }

                t.add(infoBuilder.toString()).color(entry.isDuplicate ? Color.orange : entry.isValid ? Pal.lightishGray : Pal.remove)
                 .ellipsis(true).growX().minWidth(0f).left().padTop(2f);
            }).growX().minWidth(0f).left().padLeft(8f);

            table.add(row).growX().minWidth(0f).pad(2f).row();
        }
    }

    private boolean filterMatches(PackEntry e){
        if(targetType != null && e.type != targetType) return false;
        if(filterMode == FilterMode.MAPS && e.type != PackType.MAP) return false;
        if(filterMode == FilterMode.SCHEMATICS && e.type != PackType.SCHEMATIC) return false;
        if(!searchText.isEmpty()){
            String term = searchText.toLowerCase();
            return e.displayName.toLowerCase().contains(term) || e.sanitizedFileName.toLowerCase().contains(term);
        }
        return true;
    }

    /** Triggers the import operation with progress on the main thread */
    private void executeImport(){
        Seq<PackEntry> toImport = entries.select(e -> e.selected);
        if(toImport.isEmpty()) return;

        if(Vars.ui != null && Vars.ui.loadfrag != null){
            Vars.ui.loadfrag.show(getLocalizedText("packloader.import.progress", toImport.size));
            Vars.ui.loadfrag.setProgress(0f);
        }

        arc.util.Time.runTask(2f, () -> {
            try{
                ImportReport report = importer.importEntries(entries, duplicatePolicy, progress -> {
                    if(Vars.ui != null && Vars.ui.loadfrag != null){
                        Vars.ui.loadfrag.setProgress(progress);
                    }
                });

                if(Vars.ui != null && Vars.ui.loadfrag != null){
                    Vars.ui.loadfrag.hide();
                }
                hide();
                entries.clear();
                sourceName = "";
                targetType = null;
                new PackResultDialog(report).show();
            }catch(Throwable t){
                Log.err("[PackLoader] Import error:", t);
                if(Vars.ui != null && Vars.ui.loadfrag != null){
                    Vars.ui.loadfrag.hide();
                }
                if(Vars.ui != null){
                    Vars.ui.showException(getLocalizedText("packloader.import.error", t.getMessage()), t);
                }
            }
        });
    }

    /**
     * Launches native system file picker for multiple files (SAF multi-select).
     */
    public void pickMultipleFiles(PackType targetType){
        this.targetType = targetType;
        try{
            String ext = (targetType == PackType.MAP) ? "msav" : "msch";
            String titleKey = (targetType == PackType.MAP) ? "packloader.source.batch.map" : "packloader.source.batch.schem";
            FileChooser.open(ext)
                .title(getLocalizedText(titleKey))
                .submitMulti(files -> {
                    if(files != null && files.length > 0){
                        scanAsync(targetType, files);
                    }
                });
        }catch(Throwable t){
            Log.warn("[PackLoader] System Multi-FileChooser failed, falling back to folder picker: @", t.getMessage());
            openFolderPicker(targetType);
        }
    }

    /**
     * Directly launches native file picker for .zip archives.
     * Respects targetType (MAP, SCHEMATIC, or null).
     */
    public void pickZip(PackType targetType){
        this.targetType = targetType;
        try{
            String titleKey = targetType == PackType.MAP ? "packloader.action.import-map-zip" :
                             targetType == PackType.SCHEMATIC ? "packloader.action.import-schem-zip" :
                             "packloader.source.saf.title";
            FileChooser.open("zip")
                .title(getLocalizedText(titleKey))
                .submit(file -> {
                    if(file != null){
                        scanAsync(targetType, file);
                    }
                });
        }catch(Throwable t){
            Log.warn("[PackLoader] System FileChooser failed, falling back to folder picker: @", t.getMessage());
            openFolderPicker(targetType);
        }
    }

    public void pickZipAndOpen(){
        pickZip(targetType);
    }

    /** Opens in-game Touch-Friendly Storage Browser configured for folder or file selection */
    public void openFolderPicker(PackType targetType){
        this.targetType = targetType;
        new StorageBrowserDialog(
            targetType,
            file -> scanAsync(targetType, file),
            dir -> scanAsync(targetType, dir)
        ).show();
    }

    /** Prompts user to input manual filesystem path */
    private void openPathInputDialog(){
        BaseDialog pathDialog = new BaseDialog(getLocalizedText("packloader.source.path.title"));
        pathDialog.addCloseButton();

        TextField field = new TextField("/sdcard/Download");
        field.setSize(340f, 50f);

        pathDialog.cont.add(getLocalizedText("packloader.source.path.prompt")).pad(10f).row();
        pathDialog.cont.add(field).width(360f).height(50f).pad(10f).row();

        pathDialog.buttons.button(getLocalizedText("packloader.source.path.scan"), Icon.ok, () -> {
            String path = field.getText().trim();
            if(!path.isEmpty()){
                Fi file = Core.files.absolute(path);
                if(file.exists()){
                    pathDialog.hide();
                    scanAsync(targetType, file);
                }else{
                    Vars.ui.showErrorMessage(getLocalizedText("packloader.source.path.notfound", path));
                }
            }
        }).size(130f, 50f);

        pathDialog.show();
    }

    /** Runs scanner asynchronously to prevent UI freeze on large archives or deep folders */
    public void scanAsync(PackType targetType, Fi... sources){
        if(sources == null || sources.length == 0) return;

        this.targetType = targetType;
        String name = sources.length == 1 ? sources[0].name() : (sources.length + " files");
        if(Vars.ui != null && Vars.ui.loadfrag != null){
            Vars.ui.loadfrag.show(getLocalizedText("packloader.import.scanning", name));
        }

        Threads.daemon("PackLoader-Scan", () -> {
            try{
                Seq<PackEntry> scanned = new Seq<>();
                for(Fi src : sources){
                    if(src.isDirectory()){
                        scanned.addAll(scanner.scanDirectory(src, targetType));
                    }else if(PackScanner.isZipFi(src)){
                        scanned.addAll(scanner.scanZip(src, targetType));
                    }else{
                        scanned.addAll(scanner.scanFiles(targetType, src));
                    }
                }

                Core.app.post(() -> {
                    if(Vars.ui != null && Vars.ui.loadfrag != null){
                        Vars.ui.loadfrag.hide();
                    }

                    if(scanned.isEmpty()){
                        if(Vars.ui != null){
                            boolean isRestricted = false;
                            for(Fi src : sources){
                                if(src.isDirectory() && PackScanner.isScopedStorageRestricted(src)){
                                    isRestricted = true;
                                    break;
                                }
                            }

                            if(isRestricted){
                                showScopedStorageDialog(targetType);
                            }else{
                                String emptyMsg;
                                if(targetType == PackType.MAP){
                                    emptyMsg = getLocalizedText("packloader.source.empty.maps");
                                }else if(targetType == PackType.SCHEMATIC){
                                    emptyMsg = getLocalizedText("packloader.source.empty.schematics");
                                }else{
                                    emptyMsg = getLocalizedText("packloader.source.empty");
                                }
                                Vars.ui.showInfo(emptyMsg);
                            }
                        }
                    }else{
                        entries.clear();
                        entries.addAll(scanned);
                        sourceName = name;
                        rebuild();
                        if(!isShown()) super.show();
                    }
                });
            }catch(Throwable t){
                Log.err("[PackLoader] Scan error:", t);
                Core.app.post(() -> {
                    if(Vars.ui != null && Vars.ui.loadfrag != null){
                        Vars.ui.loadfrag.hide();
                    }
                    if(Vars.ui != null){
                        boolean isPermission = (t instanceof java.io.IOException) && (
                            t.getMessage() != null && (t.getMessage().contains("permission-denied") || t.getMessage().contains("Permission denied"))
                        );
                        boolean isRestrictedDir = false;
                        for(Fi src : sources){
                            if(src.isDirectory() && PackScanner.isScopedStorageRestricted(src)){
                                isRestrictedDir = true;
                                break;
                            }
                        }
                        if((isPermission || isRestrictedDir) && Vars.mobile){
                            showScopedStorageDialog(targetType);
                        }else{
                            String errorMsg;
                            if(t instanceof java.util.zip.ZipException){
                                errorMsg = getLocalizedText("packloader.error.zip-corrupt", t.getMessage());
                            }else{
                                errorMsg = getLocalizedText("packloader.import.error", t.getMessage());
                            }
                            Vars.ui.showErrorMessage(errorMsg);
                        }
                    }
                });
            }
        });
    }

    public void showScopedStorageDialog(PackType targetType){
        BaseDialog d = new BaseDialog(getLocalizedText("packloader.scoped.title"));
        d.setFillParent(false);
        d.cont.margin(16f);

        Table t = d.cont;
        t.image(Icon.info).size(48f).color(Pal.accent).padBottom(10f).row();

        String headerText = getLocalizedText("packloader.scoped.header");
        t.add(headerText).color(Pal.accent).fontScale(1.1f).padBottom(8f).row();

        String msgText = getLocalizedText("packloader.scoped.message");

        float maxW = Math.min(Core.graphics.getWidth() * 0.88f, 480f);
        t.add(msgText).width(maxW).wrap().color(Color.lightGray).padBottom(16f).row();

        d.buttons.defaults().size(210f, 64f).pad(4f);

        String zipText = targetType == PackType.MAP ? getLocalizedText("packloader.action.import-map-zip") :
            targetType == PackType.SCHEMATIC ? getLocalizedText("packloader.action.import-schem-zip") :
            getLocalizedText("packloader.action.import-zip");

        UIUtils.pad(d.buttons.button(zipText, Icon.download, () -> {
            d.hide();
            pickZip(targetType);
        }).get());

        UIUtils.pad(d.buttons.button(getLocalizedText("packloader.action.close"), Icon.cancel, d::hide).get());

        d.show();
    }

    public void scanAsync(Fi... sources){
        scanAsync(targetType, sources);
    }
}

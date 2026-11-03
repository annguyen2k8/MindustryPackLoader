package packloader.ui;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.Button;
import arc.scene.ui.CheckBox;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Threads;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.FileChooser;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import packloader.core.PackFormat;
import packloader.core.PackImporter;
import packloader.core.PackImporter.ImportProgress;
import packloader.core.PackScanner;
import packloader.model.DuplicatePolicy;
import packloader.model.EntryFilter;
import packloader.model.ImportReport;
import packloader.model.PackEntry;
import packloader.model.PackType;
import packloader.util.MainThread;
import packloader.util.PackLoaderSettings;
import packloader.util.RecentSources;
import packloader.util.TaskGeneration;

import java.util.concurrent.atomic.AtomicBoolean;

import static packloader.util.Localization.getLocalizedText;

public class PackLoaderDialog extends BaseDialog{
    public static final String ISSUES_URL = "https://github.com/annguyen2k8/MindustryPackLoader/issues";

    private final PackScanner scanner = new PackScanner();
    private final PackImporter importer = new PackImporter();

    private final Seq<PackEntry> entries = new Seq<>();
    private final Seq<PackEntry> deferredCleanup = new Seq<>();
    private final Seq<Fi> queuedSources = new Seq<>();
    private String sourceName = "";
    private DuplicatePolicy duplicatePolicy = PackLoaderSettings.getDuplicatePolicy();
    private PackType targetType = null;

    private enum FilterMode{ ALL, MAPS, SCHEMATICS }
    private FilterMode filterMode = FilterMode.ALL;
    private String searchText = "";

    private final TaskGeneration scanGeneration = new TaskGeneration();
    private final TaskGeneration importGeneration = new TaskGeneration();
    private final AtomicBoolean importCancelled = new AtomicBoolean(false);
    private boolean importRunning = false;
    private long lastScanProgressPost = 0L;
    private long lastImportProgressPost = 0L;
    private float lastImportProgress = -1f;

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
        arc.scene.ui.Dialog d = super.show();
        checkFirstRunFeedback();
        return d;
    }

    private void checkFirstRunFeedback(){
        if(!Core.settings.getBool("packloader.first-run.shown", false)){
            Core.settings.put("packloader.first-run.shown", true);
            Core.settings.manualSave();

            BaseDialog welcomeDialog = new BaseDialog(getLocalizedText("packloader.welcome.title"));
            welcomeDialog.cont.margin(16f);
            welcomeDialog.cont.defaults().pad(6f);

            welcomeDialog.cont.image(Icon.info).size(48f).color(Pal.accent).padBottom(10f).row();

            float maxW = Math.min(Core.graphics.getWidth() * 0.9f, 480f);
            welcomeDialog.cont.add(getLocalizedText("packloader.welcome.message"))
                .style(Styles.defaultLabel)
                .color(Color.white)
                .width(maxW)
                .wrap()
                .padBottom(8f)
                .row();

            welcomeDialog.cont.add(getLocalizedText("packloader.welcome.hint"))
                .style(Styles.defaultLabel)
                .color(Pal.lightishGray)
                .width(maxW)
                .wrap()
                .padBottom(12f)
                .row();

            welcomeDialog.buttons.defaults().size(180f, 54f).pad(4f);

            UIUtils.pad(welcomeDialog.buttons.button(getLocalizedText("packloader.welcome.issues"), Icon.link, () -> {
                Core.app.openURI(ISSUES_URL);
            }).get());

            UIUtils.pad(welcomeDialog.buttons.button(getLocalizedText("packloader.welcome.close"), Icon.ok, welcomeDialog::hide).get());

            welcomeDialog.show();
        }
    }

    public void showMenu(){
        this.targetType = null;
        clearEntries();
        this.sourceName = "";
        resetFilters();
        super.show();
    }

    public void showForType(PackType targetType){
        this.targetType = targetType;
        clearEntries();
        this.sourceName = "";
        resetFilters();
        super.show();
    }

    @Override
    public void hide(){
        // Invalidate any in-flight work and release materialized SAF archives.
        scanGeneration.cancel();
        importGeneration.cancel();
        clearEntries();
        runDeferredCleanup();
        if(Vars.ui != null && Vars.ui.loadfrag != null){
            Vars.ui.loadfrag.hide();
        }
        super.hide();
    }

    private void clearEntries(){
        queuedSources.clear();
        if(entries.isEmpty()) return;
        if(importRunning){
            // The import worker may still be reading a materialized SAF ZIP. Move ownership
            // to a deferred list and release it only after the worker has stopped.
            deferredCleanup.addAll(entries);
            entries.clear();
            return;
        }
        scanner.cleanupTemporaryZips(entries);
        entries.clear();
    }

    private void runDeferredCleanup(){
        if(importRunning || deferredCleanup.isEmpty()) return;
        scanner.cleanupTemporaryZips(deferredCleanup);
        deferredCleanup.clear();
    }

    private void resetFilters(){
        filterMode = FilterMode.ALL;
        searchText = "";
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
        float maxW = Math.min(Core.graphics.getWidth() * 0.92f, Vars.mobile ? 480f : 520f);
        root.defaults().pad(4f).width(maxW);

        // Prompt subtitle
        root.add(getLocalizedText("packloader.mode.prompt"))
            .color(Pal.accent).style(Styles.outlineLabel).padTop(6f).padBottom(10f).row();

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
        )).minHeight(72f).pad(4f).row();

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
        )).minHeight(72f).pad(4f).row();

        // Quick Import Option Toggle (no border, aligned with options above)
        root.add(createQuickImportRow(maxW)).pad(4f, 6f, 4f, 6f).padTop(10f).padBottom(8f).row();

        // Recent Sources
        Seq<RecentSources.SourceItem> recents = RecentSources.load();
        if(!recents.isEmpty()){
            Table recentsHeader = new Table();
            recentsHeader.add(getLocalizedText("packloader.recent.title")).color(Pal.accent).style(Styles.outlineLabel).left().growX();
            recentsHeader.button(getLocalizedText("packloader.recent.clear"), Icon.trash, Styles.flatt, () -> {
                RecentSources.clear();
                rebuild();
            }).height(32f).padRight(2f);
            root.add(recentsHeader).pad(4f).padTop(12f).padBottom(4f).row();

            for(RecentSources.SourceItem item : recents){
                root.add(createRecentSourceCard(item, null)).minHeight(56f).pad(4f).row();
            }
        }

        ScrollPane pane = new ScrollPane(root);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        cont.add(pane).grow().pad(4f).row();

        buttons.clear();
        addCloseButton();
        if(Vars.mobile){
            buttons.button(Icon.link, Styles.flati, () -> Core.app.openURI(ISSUES_URL)).size(64f, 64f);
        }else{
            UIUtils.pad(buttons.button(getLocalizedText("packloader.bug.report"), Icon.link, () -> {
                Core.app.openURI(ISSUES_URL);
            }).size(210f, 64f).get());
        }
    }

    /** View 1: Source Selection (When a specific pack type is active) */
    private void buildSourceSelection(){
        Table root = new Table();
        float maxW = Math.min(Core.graphics.getWidth() * 0.92f, Vars.mobile ? 480f : 520f);
        root.defaults().pad(4f).width(maxW);

        // Schematics Section
        if(targetType == PackType.SCHEMATIC){
            root.add(createSourceOptionButton(
                Icon.folder,
                getLocalizedText("packloader.source.scan-folder.schem"),
                getLocalizedText("packloader.source.scan-folder.schem.desc"),
                () -> openFolderPicker(PackType.SCHEMATIC),
                maxW
            )).minHeight(72f).pad(4f).padTop(6f).row();

            root.add(createSourceOptionButton(
                Icon.download,
                getLocalizedText("packloader.source.zip.schem"),
                getLocalizedText("packloader.source.zip.schem.desc"),
                () -> pickZip(PackType.SCHEMATIC),
                maxW
            )).minHeight(72f).pad(4f).row();
        }

        // Maps Section
        if(targetType == PackType.MAP){
            root.add(createSourceOptionButton(
                Icon.folder,
                getLocalizedText("packloader.source.scan-folder.map"),
                getLocalizedText("packloader.source.scan-folder.map.desc"),
                () -> openFolderPicker(PackType.MAP),
                maxW
            )).minHeight(72f).pad(4f).padTop(6f).row();

            root.add(createSourceOptionButton(
                Icon.download,
                getLocalizedText("packloader.source.zip.map"),
                getLocalizedText("packloader.source.zip.map.desc"),
                () -> pickZip(PackType.MAP),
                maxW
            )).minHeight(72f).pad(4f).row();
        }

        // Option: Manual Path Input (Desktop power users)
        if(!Vars.mobile){
            root.add(createSourceOptionButton(
                Icon.paste,
                getLocalizedText("packloader.source.path.title"),
                getLocalizedText("packloader.source.path.desc"),
                this::openPathInputDialog,
                maxW
            )).minHeight(72f).pad(4f).row();
        }

        // Quick Import Option Toggle (no border, aligned with options above)
        root.add(createQuickImportRow(maxW)).pad(4f, 6f, 4f, 6f).padTop(10f).padBottom(8f).row();

        // Recent Sources
        Seq<RecentSources.SourceItem> recents = RecentSources.getForType(targetType);
        if(!recents.isEmpty()){
            Table recentsHeader = new Table();
            recentsHeader.add(getLocalizedText("packloader.recent.title")).color(Pal.accent).style(Styles.outlineLabel).left().growX();
            recentsHeader.button(getLocalizedText("packloader.recent.clear"), Icon.trash, Styles.flatt, () -> {
                RecentSources.clear();
                rebuild();
            }).height(32f).padRight(2f);
            root.add(recentsHeader).pad(4f).padTop(12f).padBottom(4f).row();

            for(RecentSources.SourceItem item : recents){
                root.add(createRecentSourceCard(item, targetType)).minHeight(56f).pad(4f).row();
            }
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
        UIUtils.pad(buttons.button(getLocalizedText("packloader.bug.report"), Icon.link, () -> {
            Core.app.openURI(ISSUES_URL);
        }).size(210f, 64f).get());
    }

    private Button createRecentSourceCard(RecentSources.SourceItem item, PackType activeType){
        Button card = new Button(UIUtils.cardStyle());
        card.margin(6f);
        card.clicked(() -> {
            Fi file = Core.files.absolute(item.path);
            if(!file.exists()){
                RecentSources.remove(item.path, item.type);
                if(Vars.ui != null){
                    Vars.ui.showErrorMessage(getLocalizedText("packloader.source.path.notfound", item.path));
                }
                rebuild();
                return;
            }
            scanAsync(activeType != null ? activeType : item.type, false, file);
        });

        PackType displayType = item.type != null ? item.type : activeType;
        boolean isMap = displayType == PackType.MAP;
        Color typeColor = isMap ? Pal.heal : Pal.accent;

        // Column 1: Source format icon (Folder vs Archive)
        card.image(item.isDirectory ? Icon.folder : Icon.download).size(28f).color(typeColor).pad(4f);

        // Column 2: Name and path info block
        card.table(t -> {
            t.left().defaults().left().growX();
            t.add(item.name).style(Styles.defaultLabel).color(Color.white).ellipsis(true).growX().minWidth(0f).row();
            t.add(item.path).style(Styles.defaultLabel).color(Pal.lightishGray).ellipsis(true).growX().minWidth(0f);
        }).growX().minWidth(0f).left().padLeft(6f);

        // Column 3: Type badge (Map vs Schematic)
        if(displayType != null){
            Drawable typeIcon = isMap ?
                (Icon.terrainSmall != null ? Icon.terrainSmall : Icon.terrain) :
                (Icon.pasteSmall != null ? Icon.pasteSmall : Icon.paste);

            card.table(badge -> {
                badge.right().defaults().right();
                badge.image(typeIcon).size(16f).color(typeColor).padRight(4f);
                badge.add(displayType.localizedName()).style(Styles.outlineLabel).color(typeColor);
            }).padLeft(4f).padRight(6f);
        }

        // Column 4: Remove button
        Button removeBtn = card.button(Icon.cancel, Styles.clearNonei, () -> {
            RecentSources.remove(item.path, item.type);
            rebuild();
        }).size(32f).pad(2f).get();
        removeBtn.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                event.stop();
                return true;
            }
        });

        return card;
    }

    private Table createSourceOptionButton(Drawable icon, String title, String desc, Runnable clicked, float buttonWidth){
        // A Button (rather than a plain Table) provides hover/pressed/disabled feedback
        // from the standard Mindustry button style on both Desktop and Android.
        Button btn = new Button(UIUtils.cardStyle());
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

    private Table createQuickImportRow(float rowWidth){
        Table row = new Table();
        row.left();

        CheckBox quickBox = new CheckBox("");
        quickBox.setChecked(PackLoaderSettings.isQuickImport());
        quickBox.changed(() -> PackLoaderSettings.setQuickImport(quickBox.isChecked()));
        UIUtils.squareCheck(quickBox, 26f);

        row.add(quickBox).size(26f).padRight(10f).center();

        float textWidth = Math.max(160f, rowWidth - 46f);
        Table textTable = new Table();
        textTable.left().defaults().left().growX();
        textTable.add(getLocalizedText("packloader.quick-import"))
            .style(Styles.defaultLabel)
            .color(Color.white)
            .padBottom(2f)
            .row();
        textTable.add(getLocalizedText("packloader.quick-import.desc"))
            .style(Styles.defaultLabel)
            .color(Pal.lightishGray)
            .width(textWidth)
            .wrap();

        // Tapping anywhere on the Quick Import row toggles the checkbox
        row.touchable = Touchable.enabled;
        row.clicked(() -> {
            quickBox.setChecked(!quickBox.isChecked());
            PackLoaderSettings.setQuickImport(quickBox.isChecked());
        });

        row.add(textTable).growX().left().center();

        return row;
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

        var addBtn = UIUtils.pad(header.button(
            getLocalizedText("packloader.source.add"),
            Icon.add, Styles.flatt, this::openAddSourceMenu
        ).height(40f).padRight(4f).get());
        if(addBtn != null){
            addBtn.margin(4f, 10f, 4f, 10f);
        }

        var changeBtn = UIUtils.pad(header.button(
            getLocalizedText("packloader.source.change"),
            Icon.refresh, Styles.flatt, () -> {
                clearEntries();
                sourceName = "";
                targetType = null;
                resetFilters();
                rebuild();
            }).height(40f).padRight(4f).get());
        if(changeBtn != null){
            changeBtn.margin(4f, 10f, 4f, 10f);
        }

        cont.add(header).growX().pad(6f).row();

        // Search bar row
        Table searchRow = new Table(Tex.pane);
        searchRow.margin(4f);
        searchRow.image(Icon.zoom).size(22f).color(Pal.accent).padLeft(6f).padRight(6f);
        TextField searchField = new TextField(searchText);
        searchField.setMessageText(getLocalizedText("packloader.search.placeholder"));
        searchField.changed(() -> {
            searchText = searchField.getText();
            rebuildEntriesList();
        });
        searchRow.add(searchField).growX().height(36f);
        if(!searchText.isEmpty()){
            searchRow.button(Icon.cancel, Styles.clearNonei, () -> {
                searchText = "";
                searchField.setText("");
                rebuildEntriesList();
            }).size(32f).padRight(4f);
        }
        cont.add(searchRow).growX().pad(4f).row();

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
        }

        toolbar.add().growX(); // spacer pushes mass selection buttons to right

        // Smart selection buttons
        var selectAllBtn = UIUtils.pad(toolbar.button(
            getLocalizedText("packloader.select.all"),
            Icon.ok, Styles.flatt, () -> {
                EntryFilter.setVisibleSelected(entries, targetType, filterType(), searchText, true);
                rebuildEntriesList();
            }).height(38f).pad(2f).get());
        if(selectAllBtn != null){
            selectAllBtn.margin(4f, 8f, 4f, 8f);
        }

        var selectNewBtn = UIUtils.pad(toolbar.button(
            getLocalizedText("packloader.select.new"),
            Icon.add, Styles.flatt, () -> {
                EntryFilter.selectNewOnly(entries, targetType, filterType(), searchText);
                rebuildEntriesList();
            }).height(38f).pad(2f).get());
        if(selectNewBtn != null){
            selectNewBtn.margin(4f, 8f, 4f, 8f);
        }

        var invertBtn = UIUtils.pad(toolbar.button(
            getLocalizedText("packloader.select.invert"),
            Icon.refresh, Styles.flatt, () -> {
                EntryFilter.invertVisibleSelected(entries, targetType, filterType(), searchText);
                rebuildEntriesList();
            }).height(38f).pad(2f).get());
        if(invertBtn != null){
            invertBtn.margin(4f, 8f, 4f, 8f);
        }

        var deselectBtn = UIUtils.pad(toolbar.button(
            getLocalizedText("packloader.select.none"),
            Icon.cancel, Styles.flatt, () -> {
                EntryFilter.setVisibleSelected(entries, targetType, filterType(), searchText, false);
                rebuildEntriesList();
            }).height(38f).pad(2f).get());
        if(deselectBtn != null){
            deselectBtn.margin(4f, 8f, 4f, 8f);
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
                PackLoaderSettings.setDuplicatePolicy(p);
                rebuildEntriesList();
            }).checked(btn -> duplicatePolicy == p).height(34f).pad(2f);
        }

        cont.add(conflictRow).growX().pad(4f).row();

        // Scrollable Entries List Container
        cont.table(this::buildListTable).grow().minWidth(0f).pad(4f).name("listContainer").row();

        // Bottom Action Bar
        buttons.clear();
        addCloseButton();
        if(Vars.mobile){
            buttons.button(Icon.link, Styles.flati, () -> Core.app.openURI(ISSUES_URL)).size(64f, 64f);
        }else{
            UIUtils.pad(buttons.button(getLocalizedText("packloader.bug.report"), Icon.link, () -> {
                Core.app.openURI(ISSUES_URL);
            }).size(210f, 64f).get());
        }

        int selectedCount = visibleSelectedCount();
        String initialImportText = getLocalizedText("packloader.import.action", selectedCount);
        var importBtn = UIUtils.pad(buttons.button(initialImportText, Icon.download, this::executeImport).size(210f, 64f).get());
        importBtn.update(() -> {
            int count = visibleSelectedCount();
            String text = getLocalizedText("packloader.import.action", count);
            importBtn.setText(text);
            importBtn.setDisabled(count == 0 || importRunning);
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

    private Seq<PackEntry> visibleEntries(){
        return entries.select(this::filterMatches);
    }

    private int visibleSelectedCount(){
        // Called from a per-frame update callback; do not allocate a filtered Seq here.
        return EntryFilter.countSelected(entries, targetType, filterType(), searchText);
    }

    private void populateListTable(Table table){
        Seq<PackEntry> filtered = visibleEntries();

        if(filtered.isEmpty()){
            table.add(getLocalizedText("packloader.filter.none")).color(Pal.lightishGray).pad(20f).row();
            return;
        }

        for(PackEntry entry : filtered){
            // Button rows get hover/pressed feedback while keeping the pane-like look.
            Button row = new Button(UIUtils.rowStyle());
            row.margin(6f);
            row.defaults().pad(3f);

            // Touch-friendly CheckBox (touch disabled so row click handles toggle cleanly)
            CheckBox box = new CheckBox("");
            box.setChecked(entry.selected);
            box.touchable = Touchable.disabled;
            UIUtils.squareCheck(box, 26f);
            row.add(box).size(26f).padRight(6f);

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
                    titleBuilder.append("  [gold]").append(getLocalizedText("packloader.badge.duplicate")).append("[]");
                }
                if(!entry.isValid){
                    titleBuilder.append("  [scarlet]").append(getLocalizedText("packloader.badge.invalid")).append("[]");
                }

                t.add(titleBuilder.toString()).color(Color.white).ellipsis(true).growX().minWidth(0f).left().row();

                // Clean second line: File size & optional details (no confusing repeating path)
                StringBuilder infoBuilder = new StringBuilder();
                infoBuilder.append(PackScanner.formatSize(entry.size));

                if(!entry.displayName.equalsIgnoreCase(nameNoExt) && !entry.displayName.equalsIgnoreCase(entry.sanitizedFileName)){
                    infoBuilder.append("  •  [lightgray]").append(entry.sanitizedFileName).append("[]");
                }

                if(entry.isDuplicate && !entry.duplicateReason.isEmpty()){
                    infoBuilder.append("  •  [gold]").append(entry.duplicateReason).append("[]");
                }
                if(!entry.isValid && !entry.errorReason.isEmpty()){
                    infoBuilder.append("  •  [scarlet]").append(entry.errorReason).append("[]");
                }

                t.add(infoBuilder.toString()).color(entry.isDuplicate ? Color.orange : entry.isValid ? Pal.lightishGray : Pal.remove)
                 .ellipsis(true).growX().minWidth(0f).left().padTop(2f);
            }).growX().minWidth(0f).left().padLeft(8f);

            // Schematic actions: Info & In-World Placement
            if(entry.type == PackType.SCHEMATIC && entry.isValid){
                Button infoBtn = row.button(Icon.info, Styles.clearNonei, () -> {
                    showSchematicInfo(entry);
                }).size(36f).padLeft(2f).padRight(2f).get();
                infoBtn.addListener(new InputListener(){
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                        event.stop();
                        return true;
                    }
                });

                if(Vars.state != null && Vars.state.isGame()){
                    Button placeBtn = row.button(Icon.play, Styles.clearNonei, () -> {
                        placeSchematic(entry);
                    }).size(36f).padLeft(2f).padRight(2f).get();
                    placeBtn.addListener(new InputListener(){
                        @Override
                        public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                            event.stop();
                            return true;
                        }
                    });
                }
            }

            table.add(row).growX().minWidth(0f).pad(2f).row();
        }
    }

    private PackType filterType(){
        return switch(filterMode){
            case MAPS -> PackType.MAP;
            case SCHEMATICS -> PackType.SCHEMATIC;
            default -> null;
        };
    }

    private boolean filterMatches(PackEntry e){
        return EntryFilter.matches(e, targetType, filterType(), searchText);
    }

    /**
     * Starts an import on a daemon worker, importing only the selected entries that are
     * currently visible under the active filter. File parsing/staging stays on the worker;
     * the few game-registry operations are marshalled to the main thread by PackImporter.
     */
    private void executeImport(){
        if(importRunning) return;

        Seq<PackEntry> toImport = EntryFilter.collectSelected(entries, targetType, filterType(), searchText);
        if(toImport.isEmpty()) return;

        importRunning = true;
        importCancelled.set(false);
        lastImportProgressPost = 0L;
        lastImportProgress = -1f;
        int token = importGeneration.begin();
        // Snapshot the policy so changing the UI mid-import cannot affect later entries.
        DuplicatePolicy policy = duplicatePolicy;

        if(Vars.ui != null && Vars.ui.loadfrag != null){
            Vars.ui.loadfrag.show(getLocalizedText("packloader.import.progress.start", 0, toImport.size));
            Vars.ui.loadfrag.showProgressBar();
            Vars.ui.loadfrag.setProgress(0f);
            Vars.ui.loadfrag.setButton(() -> {
                if(importCancelled.compareAndSet(false, true) && Vars.ui != null && Vars.ui.loadfrag != null){
                    Vars.ui.loadfrag.setText(getLocalizedText("packloader.import.cancelling"));
                }
            });
        }

        PackImporter.ImportListener listener = new PackImporter.ImportListener(){
            @Override
            public void onProgress(ImportProgress progress){
                if(!importGeneration.isCurrent(token)) return;
                if(MainThread.isMainThread()){
                    updateImportProgress(progress);
                }else{
                    MainThread.post(() -> {
                        if(importGeneration.isCurrent(token)) updateImportProgress(progress);
                    });
                }
            }

            @Override
            public boolean isCancelled(){
                return importCancelled.get() || !importGeneration.isCurrent(token);
            }
        };

        Threads.daemon("PackLoader-Import", () -> {
            ImportReport report;
            try{
                report = importer.importEntries(toImport, policy, listener);
            }catch(Throwable t){
                Log.err("[PackLoader] Import error:", t);
                MainThread.post(() -> {
                    // Completion must always release deferred SAF archives, even when the
                    // dialog was closed and this generation is no longer current.
                    boolean current = importGeneration.isCurrent(token);
                    importRunning = false;
                    if(current){
                        if(Vars.ui != null && Vars.ui.loadfrag != null){
                            Vars.ui.loadfrag.hide();
                        }
                        if(Vars.ui != null){
                            Vars.ui.showException(getLocalizedText("packloader.import.error", safeMessage(t)), t);
                        }
                    }
                    runDeferredCleanup();
                });
                return;
            }

            MainThread.post(() -> {
                boolean current = importGeneration.isCurrent(token);
                importRunning = false;
                if(current){
                    if(Vars.ui != null && Vars.ui.loadfrag != null){
                        Vars.ui.loadfrag.hide();
                    }
                    sourceName = "";
                    targetType = null;
                    resetFilters();
                    // hide() also releases materialized SAF ZIPs once importing has finished.
                    hide();
                    new PackResultDialog(report).show();
                }
                runDeferredCleanup();
            });
        });
    }

    private void updateImportProgress(ImportProgress progress){
        if(Vars.ui == null || Vars.ui.loadfrag == null) return;

        boolean finished = progress.total <= 0 || progress.processed >= progress.total;
        long now = System.currentTimeMillis();
        if(!finished && now - lastImportProgressPost < 80L) return;
        lastImportProgressPost = now;

        String current = progress.currentFile == null || progress.currentFile.isEmpty() ?
            getLocalizedText("packloader.import.finishing") :
            truncateFileName(progress.currentFile, 36);
        Vars.ui.loadfrag.setText(getLocalizedText("packloader.import.progress.detail",
            current, progress.processed, progress.total, progress.success, progress.failed));

        float value = progress.total <= 0 ? 0f :
            Math.min(1f, progress.processed / (float)progress.total);
        if(value != lastImportProgress){
            lastImportProgress = value;
            Vars.ui.loadfrag.setProgress(value);
        }
    }

    public void showSchematicInfo(PackEntry entry){
        if(entry == null) return;
        Schematic s = entry.schematic;
        if(s == null){
            try(java.io.InputStream in = entry.openStream()){
                s = PackFormat.readSchematic(in);
                entry.schematic = s;
            }catch(Throwable t){
                Log.err("[PackLoader] Failed to read schematic for info", t);
                if(Vars.ui != null){
                    Vars.ui.showErrorMessage(getLocalizedText("packloader.error.invalid-schematic"));
                }
                return;
            }
        }
        if(s != null && Vars.ui != null && Vars.ui.schematics != null){
            Vars.ui.schematics.showInfo(s);
        }
    }

    public void placeSchematic(PackEntry entry){
        if(entry == null) return;
        Schematic s = entry.schematic;
        if(s == null){
            try(java.io.InputStream in = entry.openStream()){
                s = PackFormat.readSchematic(in);
                entry.schematic = s;
            }catch(Throwable t){
                Log.err("[PackLoader] Failed to read schematic for placement", t);
                if(Vars.ui != null){
                    Vars.ui.showErrorMessage(getLocalizedText("packloader.error.invalid-schematic"));
                }
                return;
            }
        }
        if(s != null && Vars.control != null && Vars.control.input != null){
            Vars.control.input.useSchematic(s);
            hide();
            if(Vars.ui != null){
                Vars.ui.showInfoToast(getLocalizedText("packloader.schematic.placed", s.name()), 2.5f);
            }
        }
    }

    public static String truncateFileName(String name, int maxLength){
        if(name == null || name.length() <= maxLength) return name;
        int extIdx = name.lastIndexOf('.');
        if(extIdx > 0 && name.length() - extIdx <= 8){
            String ext = name.substring(extIdx);
            int keepBase = maxLength - ext.length() - 3;
            if(keepBase > 3){
                return name.substring(0, keepBase) + "..." + ext;
            }
        }
        return name.substring(0, maxLength - 3) + "...";
    }

    private static String safeMessage(Throwable t){
        return t.getMessage() == null || t.getMessage().trim().isEmpty() ?
            t.getClass().getSimpleName() : t.getMessage();
    }

    private static String joinWarnings(Seq<String> warnings){
        StringBuilder result = new StringBuilder();
        for(String warning : warnings){
            if(result.length() > 0) result.append('\n');
            result.append("• ").append(warning);
        }
        return result.toString();
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
        pickZip(targetType, false);
    }

    public void pickZip(PackType targetType, boolean append){
        this.targetType = targetType;
        try{
            String titleKey = targetType == PackType.MAP ? "packloader.action.import-map-zip" :
                             targetType == PackType.SCHEMATIC ? "packloader.action.import-schem-zip" :
                             "packloader.source.saf.title";
            FileChooser.open("zip")
                .title(getLocalizedText(titleKey))
                .submit(file -> {
                    if(file != null){
                        scanAsync(targetType, append, file);
                    }
                });
        }catch(Throwable t){
            Log.warn("[PackLoader] System FileChooser failed, falling back to folder picker: @", t.getMessage());
            openFolderPicker(targetType, append);
        }
    }

    public void pickZipAndOpen(){
        pickZip(targetType);
    }

    /** Opens in-game Touch-Friendly Storage Browser configured for folder or file selection */
    public void openFolderPicker(PackType targetType){
        openFolderPicker(targetType, false);
    }

    public void openFolderPicker(PackType targetType, boolean append){
        this.targetType = targetType;
        new StorageBrowserDialog(
            targetType,
            file -> scanAsync(targetType, append, file),
            dir -> scanAsync(targetType, append, dir)
        ).show();
    }

    /** Prompts user to input manual filesystem path */
    private void openPathInputDialog(){
        openPathInputDialog(false);
    }

    private void openPathInputDialog(boolean append){
        BaseDialog pathDialog = new BaseDialog(getLocalizedText("packloader.source.path.title"));
        pathDialog.addCloseButton();

        // Prioritize recent sources or leave empty with placeholder guidance
        String defaultPath = "";
        Seq<RecentSources.SourceItem> recents = RecentSources.getForType(targetType);
        if(!recents.isEmpty()){
            for(RecentSources.SourceItem item : recents){
                if(Core.files.absolute(item.path).exists()){
                    defaultPath = item.path;
                    break;
                }
            }
        }

        TextField field = new TextField(defaultPath);
        field.setMessageText(getLocalizedText("packloader.source.path.placeholder"));
        field.setSize(340f, 50f);

        pathDialog.cont.add(getLocalizedText("packloader.source.path.prompt")).pad(10f).row();
        pathDialog.cont.add(field).width(360f).height(50f).pad(10f).row();

        pathDialog.buttons.button(getLocalizedText("packloader.source.path.scan"), Icon.ok, () -> {
            String path = field.getText().trim();
            if(path.isEmpty()){
                if(Vars.ui != null){
                    Vars.ui.showErrorMessage(getLocalizedText("packloader.source.path.empty"));
                }
                return;
            }

            String[] parts = path.split("[,;\\r?\\n]+");
            Seq<Fi> sources = new Seq<>();
            for(String p : parts){
                String trimmed = p.trim();
                if(trimmed.isEmpty()) continue;
                Fi file = Core.files.absolute(trimmed);
                if(!file.exists()){
                    if(Vars.ui != null){
                        Vars.ui.showErrorMessage(getLocalizedText("packloader.source.path.notfound", trimmed));
                    }
                }else if(file.isDirectory() && PackScanner.isScopedStorageRestricted(file)){
                    showScopedStorageDialog(targetType);
                }else{
                    sources.add(file);
                }
            }
            if(!sources.isEmpty()){
                pathDialog.hide();
                scanAsync(targetType, append, sources.toArray(Fi.class));
            }
        }).size(130f, 50f);

        pathDialog.show();
    }

    private void openAddSourceMenu(){
        showAddSourceDialog(targetType);
    }

    private void showAddSourceDialog(PackType type){
        BaseDialog d = new BaseDialog(getLocalizedText("packloader.source.add"));
        d.addCloseButton();
        d.cont.defaults().size(300f, 64f).pad(6f);

        String zipText = type == PackType.MAP ? getLocalizedText("packloader.source.zip.map") :
                         type == PackType.SCHEMATIC ? getLocalizedText("packloader.source.zip.schem") :
                         getLocalizedText("packloader.source.saf.title");
        String folderText = type == PackType.MAP ? getLocalizedText("packloader.source.scan-folder.map") :
                            type == PackType.SCHEMATIC ? getLocalizedText("packloader.source.scan-folder.schem") :
                            getLocalizedText("packloader.action.scan-folder");

        d.cont.button(zipText, Icon.download, () -> {
            d.hide();
            pickZip(type, true);
        }).row();

        d.cont.button(folderText, Icon.folder, () -> {
            d.hide();
            openFolderPicker(type, true);
        }).row();

        if(!Vars.mobile){
            d.cont.button(getLocalizedText("packloader.source.path.title"), Icon.paste, () -> {
                d.hide();
                openPathInputDialog(true);
            }).row();
        }

        d.show();
    }

    /** Runs scanner asynchronously to prevent UI freeze on large archives or deep folders. */
    public void scanAsync(PackType targetType, Fi... sources){
        scanAsync(targetType, false, sources);
    }

    public void scanAsync(PackType targetType, boolean append, Fi... sources){
        if(sources == null || sources.length == 0) return;

        this.targetType = targetType;
        String name = sources.length == 1 ? sources[0].name() :
            getLocalizedText("packloader.source.multiple", sources.length);
        int token = scanGeneration.begin();
        lastScanProgressPost = 0L;
        Seq<String> scanWarnings = new Seq<>();
        final int[] completedEntries = {0};

        if(Vars.ui != null && Vars.ui.loadfrag != null){
            Vars.ui.loadfrag.show(getLocalizedText("packloader.scan.starting", name));
            Vars.ui.loadfrag.showProgressBar();
            Vars.ui.loadfrag.setProgress(0f);
            Vars.ui.loadfrag.setButton(() -> {
                scanGeneration.cancel();
                if(Vars.ui != null && Vars.ui.loadfrag != null){
                    Vars.ui.loadfrag.hide();
                }
            });
        }

        PackScanner.ScanListener listener = new PackScanner.ScanListener(){
            @Override
            public void onProgress(String currentFile, int discovered, int total){
                if(!scanGeneration.isCurrent(token)) return;

                int found = completedEntries[0] + discovered;
                boolean totalKnown = sources.length == 1 && total > 0;
                boolean completed = totalKnown && discovered >= total;
                long now = System.currentTimeMillis();
                if(!completed && now - lastScanProgressPost < 80L) return;
                lastScanProgressPost = now;

                MainThread.post(() -> {
                    if(!scanGeneration.isCurrent(token) || Vars.ui == null || Vars.ui.loadfrag == null) return;
                    String fileName = currentFile == null ? "" : truncateFileName(currentFile, 36);
                    if(totalKnown){
                        Vars.ui.loadfrag.setText(getLocalizedText("packloader.scan.progress.total",
                            Math.min(found, total), total, fileName));
                        Vars.ui.loadfrag.setProgress(Math.min(1f, found / (float)total));
                    }else{
                        Vars.ui.loadfrag.setText(getLocalizedText("packloader.scan.progress", found, fileName));
                    }
                });
            }

            @Override
            public void onWarning(String message){
                synchronized(scanWarnings){
                    scanWarnings.add(message);
                }
            }

            @Override
            public boolean isCancelled(){
                return !scanGeneration.isCurrent(token);
            }
        };

        Threads.daemon("PackLoader-Scan", () -> {
            Seq<PackEntry> scanned = new Seq<>();
            try{
                for(Fi src : sources){
                    if(!scanGeneration.isCurrent(token)){
                        scanner.cleanupTemporaryZips(scanned);
                        return;
                    }

                    if(src.isDirectory()){
                        scanned.addAll(scanner.scanDirectory(src, targetType, listener));
                    }else if(PackScanner.isZipFi(src)){
                        scanned.addAll(scanner.scanZip(src, targetType, listener));
                    }else{
                        scanned.addAll(scanner.scanFiles(targetType, listener, src));
                    }
                    completedEntries[0] = scanned.size;
                }

                if(!scanGeneration.isCurrent(token)){
                    scanner.cleanupTemporaryZips(scanned);
                    return;
                }

                MainThread.post(() -> {
                    if(!scanGeneration.isCurrent(token)){
                        scanner.cleanupTemporaryZips(scanned);
                        return;
                    }

                    if(Vars.ui != null && Vars.ui.loadfrag != null){
                        Vars.ui.loadfrag.hide();
                    }

                    Seq<String> warningSnapshot;
                    synchronized(scanWarnings){
                        warningSnapshot = new Seq<>(scanWarnings);
                    }
                    String warningText = warningSnapshot.isEmpty() ? "" :
                        getLocalizedText("packloader.scan.warnings", joinWarnings(warningSnapshot));

                    if(scanned.isEmpty()){
                        if(!append && Vars.ui != null){
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
                                Vars.ui.showInfo(warningText.isEmpty() ? emptyMsg : warningText + "\n\n" + emptyMsg);
                            }
                        }
                    }else{
                        for(Fi src : sources){
                            RecentSources.add(src.path(), src.name(), targetType, src.isDirectory());
                        }

                        if(!append){
                            clearEntries();
                            queuedSources.clear();
                            queuedSources.addAll(sources);
                            entries.addAll(scanned);
                            sourceName = name;
                        }else{
                            queuedSources.addAll(sources);
                            entries.addAll(scanned);
                            sourceName = getLocalizedText("packloader.source.queued", queuedSources.size);
                        }

                        // Duplicate badges read live game registries, so refresh them on the main thread.
                        scanner.checkGameDuplicates(entries);
                        resetFilters();

                        duplicatePolicy = PackLoaderSettings.getDuplicatePolicy();
                        if(!append && PackLoaderSettings.isQuickImport()){
                            int eligible = EntryFilter.selectEligibleForQuickImport(entries, targetType, duplicatePolicy);
                            if(eligible > 0){
                                executeImport();
                                return;
                            }
                        }

                        rebuild();
                        if(!isShown()) super.show();

                        if(!warningText.isEmpty() && Vars.ui != null){
                            Vars.ui.showInfo(warningText);
                        }
                    }
                });
            }catch(PackScanner.ScanCancelledException e){
                scanner.cleanupTemporaryZips(scanned);
                Log.info("[PackLoader] Scan cancelled.");
            }catch(Throwable t){
                scanner.cleanupTemporaryZips(scanned);
                Log.err("[PackLoader] Scan error:", t);
                MainThread.post(() -> {
                    if(!scanGeneration.isCurrent(token)) return;
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
                                errorMsg = getLocalizedText("packloader.error.zip-corrupt", safeMessage(t));
                            }else{
                                errorMsg = getLocalizedText("packloader.import.error", safeMessage(t));
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

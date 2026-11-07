package packloader.ui;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.Button;
import arc.scene.ui.CheckBox;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Scaling;
import arc.util.Threads;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.BorderImage;
import mindustry.ui.FileChooser;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import packloader.core.PackFormat;
import packloader.core.PackImporter;
import packloader.core.PackImporter.ImportProgress;
import packloader.core.PackScanner;
import packloader.model.DryRunAction;
import packloader.model.DryRunPlan;
import packloader.model.DuplicatePolicy;
import packloader.model.EntryFilter;
import packloader.model.ImportReport;
import packloader.model.PackEntry;
import packloader.model.PackType;
import packloader.undo.ImportTransaction;
import packloader.undo.UndoManager;
import packloader.util.MainThread;
import packloader.util.PackLoaderSettings;
import packloader.util.RecentSources;
import packloader.util.TaskGeneration;

import java.util.concurrent.atomic.AtomicBoolean;

import static packloader.util.Localization.getLocalizedText;

public class PackLoaderDialog extends BaseDialog implements FileDropHandler.DropTarget{
    public static final String ISSUES_URL = "https://github.com/annguyen2k8/MindustryPackLoader/issues";

    private final PackScanner scanner = new PackScanner();
    private final PackImporter importer = new PackImporter();

    private final Seq<PackEntry> entries = new Seq<>();
    private final Seq<PackEntry> deferredCleanup = new Seq<>();
    private final Seq<Fi> queuedSources = new Seq<>();
    private String sourceName = "";
    private DuplicatePolicy mapDuplicatePolicy = PackLoaderSettings.getDuplicatePolicy(PackType.MAP);
    private DuplicatePolicy schemDuplicatePolicy = PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC);
    private PackType targetType = null;

    private enum FilterMode{ ALL, MAPS, SCHEMATICS }
    private FilterMode filterMode = FilterMode.ALL;
    private String searchText = "";
    private Label previewSummaryLabel;
    private boolean showDetailedMeta = false;

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
        hidden(PreviewManager::dispose);
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

    public boolean isInReviewMode(){
        return isShown() && !entries.isEmpty();
    }

    public PackType getTargetType(){
        return targetType;
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
            recentsHeader.left().defaults().center();
            recentsHeader.add(getLocalizedText("packloader.recent.title")).color(Pal.accent).style(Styles.outlineLabel).left().growX();
            Button clearBtn = recentsHeader.button(b -> {
                b.image(Icon.trash).size(16f).color(Pal.lightishGray).padRight(8f);
                b.add(getLocalizedText("packloader.recent.clear")).color(Pal.lightishGray).style(Styles.outlineLabel);
            }, UIUtils.clearButtonStyle(), () -> {
                RecentSources.clear();
                rebuild();
            }).height(32f).padRight(2f).get();
            clearBtn.margin(4f, 8f, 4f, 8f);
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
        buttons.defaults().pad(4f);

        UIUtils.pad(buttons.button(getLocalizedText("packloader.action.close"), Icon.cancel, Styles.flatt, this::hide)
            .size(Vars.mobile ? 110f : 140f, 54f).get());
        addCloseListener();

        buttons.add().growX();

        ImportTransaction latestTx = importer.undoManager.getLatestTransaction();
        if(latestTx != null && !latestTx.undone && latestTx.getActiveItemCount() > 0){
            var undoBtn = UIUtils.pad(buttons.button(getLocalizedText("packloader.undo.button.last"), Icon.undo, Styles.flatt, () -> {
                new UndoConfirmDialog(importer.undoManager, latestTx, this::rebuild).show();
            }).size(Vars.mobile ? 120f : 150f, 54f).get());
            if(undoBtn != null && undoBtn.getLabel() != null){
                undoBtn.getLabel().setColor(Color.orange);
            }
        }

        if(Vars.mobile){
            buttons.button(Icon.link, Styles.flati, () -> Core.app.openURI(ISSUES_URL)).size(48f, 54f);
        }else{
            UIUtils.pad(buttons.button(getLocalizedText("packloader.bug.report"), Icon.link, Styles.flatt, () -> {
                Core.app.openURI(ISSUES_URL);
            }).size(150f, 54f).get());
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

        // Import Configuration Card (Quick Import, dynamic conflict preset, skip identical content)
        root.add(createQuickImportRow(targetType, maxW)).pad(4f, 6f, 4f, 6f).padTop(10f).padBottom(8f).row();

        // Recent Sources
        Seq<RecentSources.SourceItem> recents = RecentSources.getForType(targetType);
        if(!recents.isEmpty()){
            Table recentsHeader = new Table();
            recentsHeader.left().defaults().center();
            recentsHeader.add(getLocalizedText("packloader.recent.title")).color(Pal.accent).style(Styles.outlineLabel).left().growX();
            Button clearBtn = recentsHeader.button(b -> {
                b.image(Icon.trash).size(16f).color(Pal.lightishGray).padRight(8f);
                b.add(getLocalizedText("packloader.recent.clear")).color(Pal.lightishGray).style(Styles.outlineLabel);
            }, UIUtils.clearButtonStyle(), () -> {
                RecentSources.clear();
                rebuild();
            }).height(32f).padRight(2f).get();
            clearBtn.margin(4f, 8f, 4f, 8f);
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
        buttons.defaults().pad(4f);
        UIUtils.pad(buttons.button(getLocalizedText("packloader.action.back"), Icon.left, Styles.flatt, () -> {
            targetType = null;
            rebuild();
        }).size(Vars.mobile ? 120f : 140f, 54f).get());
        addCloseListener();

        buttons.add().growX();

        if(Vars.mobile){
            buttons.button(Icon.link, Styles.flati, () -> Core.app.openURI(ISSUES_URL)).size(48f, 54f);
        }else{
            UIUtils.pad(buttons.button(getLocalizedText("packloader.bug.report"), Icon.link, Styles.flatt, () -> {
                Core.app.openURI(ISSUES_URL);
            }).size(150f, 54f).get());
        }
    }

    private Button createRecentSourceCard(RecentSources.SourceItem item, PackType activeType){
        Button card = new Button(UIUtils.cardStyle());
        card.margin(8f, 12f, 8f, 12f);
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
                badge.right();
                badge.image(typeIcon).size(14f).scaling(Scaling.fit).color(typeColor).padRight(6f);
                badge.add(displayType.localizedName()).style(Styles.outlineLabel).color(typeColor);
            }).padLeft(6f).padRight(6f);
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
        Button btn = new Button(UIUtils.cardStyle());
        btn.clicked(clicked);
        btn.margin(10f, 14f, 10f, 14f);

        // Icon column
        btn.image(icon).size(36f).color(Pal.accent).left().padRight(14f);

        // Text column: explicit width and wrap() ensure subtitle and title never overflow right
        float textWidth = Math.max(160f, buttonWidth - 92f);
        btn.table(t -> {
            t.left().defaults().left().growX();
            t.add(title).style(Styles.defaultLabel).color(Color.white).width(textWidth).wrap().padBottom(3f).row();
            t.add(desc).style(Styles.defaultLabel).color(Pal.lightishGray).width(textWidth).wrap();
        }).growX().left();

        return btn;
    }

    Table createQuickImportRow(float rowWidth){
        return createQuickImportRow(targetType, rowWidth);
    }

    Table createQuickImportRow(PackType type, float rowWidth){
        Table container = new Table();
        container.left().defaults().left().growX();
        container.margin(6f, 0f, 6f, 0f);

        CheckBox quickBox = new CheckBox("");
        quickBox.setChecked(PackLoaderSettings.isQuickImport());
        quickBox.clearChildren();
        quickBox.left().defaults().left();
        quickBox.margin(0f);

        quickBox.add(quickBox.getImage()).size(24f).padRight(10f).center();
        UIUtils.squareCheck(quickBox, 24f);

        float textWidth = Math.max(160f, rowWidth - 64f);
        Table textTable = new Table();
        textTable.left().defaults().left().growX();
        textTable.touchable = Touchable.disabled;
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

        quickBox.add(textTable).growX().left().center();
        container.add(quickBox).growX().row();

        // Conflict Policy Preset Container (dynamically shown ONLY when Quick Import is ON)
        Table conflictContainer = new Table();
        conflictContainer.left().defaults().left();

        Runnable updateConflictVisibility = () -> {
            conflictContainer.clearChildren();
            if(quickBox.isChecked()){
                conflictContainer.margin(2f, 34f, 4f, 0f);
                populateConflictPresetContent(conflictContainer, type);
            }else{
                conflictContainer.margin(0f);
            }
            conflictContainer.invalidateHierarchy();
        };

        quickBox.changed(() -> {
            PackLoaderSettings.setQuickImport(quickBox.isChecked());
            updateConflictVisibility.run();
        });

        updateConflictVisibility.run();

        container.add(conflictContainer).growX().row();

        // Sub-option: Skip identical content
        CheckBox skipBox = new CheckBox("");
        skipBox.setChecked(PackLoaderSettings.isSkipIdenticalContent());
        skipBox.changed(() -> PackLoaderSettings.setSkipIdenticalContent(skipBox.isChecked()));
        skipBox.clearChildren();
        skipBox.left().defaults().left();
        skipBox.margin(0f);

        skipBox.add(skipBox.getImage()).size(24f).padRight(10f).center();
        UIUtils.squareCheck(skipBox, 24f);

        Table skipTextTable = new Table();
        skipTextTable.left().defaults().left().growX();
        skipTextTable.touchable = Touchable.disabled;
        skipTextTable.add(getLocalizedText("packloader.quick-import.skip-identical"))
            .style(Styles.defaultLabel)
            .color(Color.lightGray);

        skipBox.add(skipTextTable).growX().left().center();

        container.add(skipBox).growX().padTop(6f).row();

        return container;
    }

    void populateConflictPresetContent(Table target, PackType type){
        boolean isMap = type == PackType.MAP;
        boolean isSchem = type == PackType.SCHEMATIC;
        Color typeColor = isMap ? Pal.heal : isSchem ? Pal.accent : Pal.accent;
        Drawable typeIcon = isMap ?
            (Icon.terrainSmall != null ? Icon.terrainSmall : Icon.terrain) :
            isSchem ? (Icon.pasteSmall != null ? Icon.pasteSmall : Icon.paste) :
            (Icon.settingsSmall != null ? Icon.settingsSmall : Icon.settings);

        if(typeIcon != null){
            target.image(typeIcon).size(16f).color(typeColor).padRight(6f);
        }
        target.add(getLocalizedText("packloader.conflict.label")).color(Pal.accent).padRight(8f);

        for(DuplicatePolicy p : DuplicatePolicy.values()){
            target.button(b -> {
                b.margin(4f);
                b.add(p.title()).padLeft(4f).padRight(4f);
            }, Styles.flatTogglet, () -> {
                setPolicyForType(type, p);
            }).checked(btn -> getPolicyForType(type) == p).height(32f).pad(2f);
        }
    }

    Table createConflictPolicyPresetRow(PackType type, float rowWidth){
        Table row = new Table();
        row.defaults().pad(2f);
        populateConflictPresetContent(row, type);
        return row;
    }

    public DuplicatePolicy getPolicyForType(PackType type){
        if(type == PackType.MAP) return mapDuplicatePolicy;
        if(type == PackType.SCHEMATIC) return schemDuplicatePolicy;
        return mapDuplicatePolicy != null ? mapDuplicatePolicy : PackLoaderSettings.getDuplicatePolicy();
    }

    public void setPolicyForType(PackType type, DuplicatePolicy policy){
        if(type == PackType.MAP){
            mapDuplicatePolicy = policy;
            PackLoaderSettings.setDuplicatePolicy(PackType.MAP, policy);
        }else if(type == PackType.SCHEMATIC){
            schemDuplicatePolicy = policy;
            PackLoaderSettings.setDuplicatePolicy(PackType.SCHEMATIC, policy);
        }else{
            mapDuplicatePolicy = policy;
            schemDuplicatePolicy = policy;
            PackLoaderSettings.setDuplicatePolicy(null, policy);
        }
    }

    private void buildConflictRowSection(Table targetTable, PackType type, boolean mixedMode){
        Table section = new Table();
        section.right().defaults().pad(1f);

        boolean isMap = type == PackType.MAP;
        Color typeColor = isMap ? Pal.heal : Pal.accent;
        Drawable typeIcon = isMap ?
            (Icon.terrainSmall != null ? Icon.terrainSmall : Icon.terrain) :
            (Icon.pasteSmall != null ? Icon.pasteSmall : Icon.paste);

        section.image(typeIcon).size(16f).color(typeColor).padRight(4f);
        String labelKey = mixedMode ?
            (isMap ? "packloader.conflict.label.map" : "packloader.conflict.label.schem") :
            "packloader.conflict.label";
        section.add(getLocalizedText(labelKey)).color(mixedMode ? typeColor : Pal.accent).padRight(6f);

        for(DuplicatePolicy p : DuplicatePolicy.values()){
            section.button(b -> {
                b.margin(2f, 6f, 2f, 6f);
                b.add(p.title()).padLeft(2f).padRight(2f);
            }, Styles.flatTogglet, () -> {
                setPolicyForType(type, p);
                rebuildEntriesList();
            }).checked(btn -> getPolicyForType(type) == p).height(30f).pad(1f);
        }

        targetTable.add(section).right().row();
    }

    /** View 2: File Review and Bulk Import Controls */
    private void buildEntriesView(){
        // Top Header: Source Info & Change Source
        Table header = new Table(UIUtils.flatPane());
        header.margin(6f, 10f, 6f, 10f);
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

        // Row 1: Search Bar (Left) + Conflict Policy Selector (Right) in a unified flat container
        Table row1 = new Table(UIUtils.flatPane());
        row1.margin(4f, 8f, 4f, 8f);

        // Search Bar (Left)
        row1.image(Icon.zoom).size(20f).color(Pal.accent).padLeft(6f).padRight(6f);
        TextField searchField = new TextField(searchText);
        searchField.setMessageText(getLocalizedText("packloader.search.placeholder"));
        searchField.changed(() -> {
            searchText = searchField.getText();
            rebuildEntriesList();
        });
        row1.add(searchField).growX().minWidth(100f).height(32f);
        if(!searchText.isEmpty()){
            row1.button(Icon.cancel, Styles.clearNonei, () -> {
                searchText = "";
                searchField.setText("");
                rebuildEntriesList();
            }).size(30f).padRight(4f);
        }

        // Conflict Resolution Policy (Right)
        Table conflictBox = new Table();
        conflictBox.right().defaults().pad(1f);

        if(targetType == PackType.MAP || (targetType == null && mapsCount > 0 && schemsCount == 0)){
            buildConflictRowSection(conflictBox, PackType.MAP, false);
        }else if(targetType == PackType.SCHEMATIC || (targetType == null && schemsCount > 0 && mapsCount == 0)){
            buildConflictRowSection(conflictBox, PackType.SCHEMATIC, false);
        }else{
            buildConflictRowSection(conflictBox, PackType.MAP, true);
            buildConflictRowSection(conflictBox, PackType.SCHEMATIC, true);
        }

        row1.add(conflictBox).right().padLeft(8f).padRight(4f);
        cont.add(row1).growX().pad(2f, 4f, 2f, 4f).row();

        // Row 2: Selection & Filter Toolbar (Left) + Live Dry-Run Preview Summary (Right)
        Table row2 = new Table();
        row2.margin(2f);
        row2.defaults().pad(2f);

        // Filter buttons (only shown in general multi-type mode)
        if(targetType == null){
            row2.button(getLocalizedText("packloader.filter.all", entries.size), Styles.flatTogglet, () -> {
                filterMode = FilterMode.ALL;
                rebuildEntriesList();
            }).checked(b -> filterMode == FilterMode.ALL).height(30f).padRight(2f);

            row2.button(getLocalizedText("packloader.filter.maps", mapsCount), Styles.flatTogglet, () -> {
                filterMode = FilterMode.MAPS;
                rebuildEntriesList();
            }).checked(b -> filterMode == FilterMode.MAPS).height(30f).padRight(2f);

            row2.button(getLocalizedText("packloader.filter.schematics", schemsCount), Styles.flatTogglet, () -> {
                filterMode = FilterMode.SCHEMATICS;
                rebuildEntriesList();
            }).checked(b -> filterMode == FilterMode.SCHEMATICS).height(30f).padRight(2f);
        }

        // Smart selection buttons (compact height 30f)
        var selectAllBtn = UIUtils.pad(row2.button(
            getLocalizedText("packloader.select.all"),
            Icon.ok, Styles.flatt, () -> {
                EntryFilter.setVisibleSelected(entries, targetType, filterType(), searchText, true);
                rebuildEntriesList();
            }).height(30f).pad(1f).get());
        if(selectAllBtn != null){
            selectAllBtn.margin(3f, 6f, 3f, 6f);
        }

        var selectNewBtn = UIUtils.pad(row2.button(
            getLocalizedText("packloader.select.new"),
            Icon.add, Styles.flatt, () -> {
                EntryFilter.selectNewOnly(entries, targetType, filterType(), searchText);
                rebuildEntriesList();
            }).height(30f).pad(1f).get());
        if(selectNewBtn != null){
            selectNewBtn.margin(3f, 6f, 3f, 6f);
        }

        var selectUniqueBtn = UIUtils.pad(row2.button(
            getLocalizedText("packloader.select.unique"),
            Icon.star, Styles.flatt, () -> {
                EntryFilter.selectUniqueContentOnly(entries, targetType, filterType(), searchText);
                rebuildEntriesList();
            }).height(30f).pad(1f).get());
        if(selectUniqueBtn != null){
            selectUniqueBtn.margin(3f, 6f, 3f, 6f);
        }

        var invertBtn = UIUtils.pad(row2.button(
            getLocalizedText("packloader.select.invert"),
            Icon.refresh, Styles.flatt, () -> {
                EntryFilter.invertVisibleSelected(entries, targetType, filterType(), searchText);
                rebuildEntriesList();
            }).height(30f).pad(1f).get());
        if(invertBtn != null){
            invertBtn.margin(3f, 6f, 3f, 6f);
        }

        var deselectBtn = UIUtils.pad(row2.button(
            getLocalizedText("packloader.select.none"),
            Icon.cancel, Styles.flatt, () -> {
                EntryFilter.setVisibleSelected(entries, targetType, filterType(), searchText, false);
                rebuildEntriesList();
            }).height(30f).pad(1f).get());
        if(deselectBtn != null){
            deselectBtn.margin(3f, 6f, 3f, 6f);
        }

        var metaToggleBtn = UIUtils.pad(row2.button(
            getLocalizedText("packloader.meta.details"),
            Icon.list, Styles.flatTogglet, () -> {
                showDetailedMeta = !showDetailedMeta;
                rebuildEntriesList();
            }).checked(b -> showDetailedMeta).height(30f).pad(1f).get());
        if(metaToggleBtn != null){
            metaToggleBtn.margin(3f, 6f, 3f, 6f);
        }

        row2.add().growX(); // Spacer pushes Live Dry-Run summary to the right

        // Live Dry-Run Preview Summary (Right)
        Table summaryBox = new Table(UIUtils.flatPane());
        summaryBox.margin(3f, 8f, 3f, 8f);
        summaryBox.image(Icon.eye).size(16f).color(Pal.accent).padRight(4f);
        DryRunPlan initialPlan = calculateDryRunPlan();
        previewSummaryLabel = summaryBox.add(formatDryRunSummary(initialPlan))
            .color(Pal.lightishGray).style(Styles.outlineLabel).left().get();

        row2.add(summaryBox).right().padLeft(6f);
        cont.add(row2).growX().pad(2f, 4f, 4f, 4f).row();

        // Scrollable Entries List Container
        cont.table(this::buildListTable).grow().minWidth(0f).pad(4f).name("listContainer").row();

        // Bottom Action Bar
        buttons.clear();
        buttons.defaults().pad(4f);

        // Left Actions: Back (Dismissive) + Bug Report (Utility Icon)
        UIUtils.pad(buttons.button(getLocalizedText("packloader.action.back"), Icon.left, Styles.flatt, () -> {
            clearEntries();
            sourceName = "";
            targetType = null;
            resetFilters();
            rebuild();
        }).size(Vars.mobile ? 120f : 140f, 54f).get());
        addCloseListener();

        buttons.button(Icon.link, Styles.flati, () -> {
            Core.app.openURI(ISSUES_URL);
        }).size(48f, 54f);

        // Spacer pushes primary actions to the right
        buttons.add().growX();

        // Right Actions: Preview (Secondary) + Import (Primary CTA)
        var previewBtn = UIUtils.pad(buttons.button(
            getLocalizedText("packloader.dryrun.button.preview"),
            Icon.eye,
            Styles.flatt,
            this::showDryRunDialog
        ).size(Vars.mobile ? 130f : 150f, 54f).get());
        if(previewBtn != null){
            previewBtn.update(() -> {
                previewBtn.setDisabled(visibleSelectedCount() == 0 || importRunning);
            });
        }

        int selectedCount = visibleSelectedCount();
        String initialImportText = getLocalizedText("packloader.import.action", selectedCount);
        var importBtn = UIUtils.pad(buttons.button(initialImportText, Icon.download, this::executeImport).size(Vars.mobile ? 160f : 200f, 54f).get());
        importBtn.update(() -> {
            int count = visibleSelectedCount();
            String text = getLocalizedText("packloader.import.action", count);
            importBtn.setText(text);
            boolean disabled = count == 0 || importRunning;
            importBtn.setDisabled(disabled);
            if(importBtn.getLabel() != null){
                importBtn.getLabel().setColor(disabled ? Pal.gray : Pal.accent);
            }
        });
    }

    private void showDryRunDialog(){
        DryRunPlan plan = calculateDryRunPlan();
        new DryRunDialog(plan, this::executeImport).show();
    }

    private void updatePreviewSummary(){
        if(previewSummaryLabel != null){
            DryRunPlan plan = calculateDryRunPlan();
            previewSummaryLabel.setText(formatDryRunSummary(plan));
        }
    }

    private String formatDryRunSummary(DryRunPlan plan){
        return getLocalizedText("packloader.dryrun.summary",
            "[heal]" + plan.newCount + "[]",
            "[accent]" + plan.overwriteCount + "[]",
            "[gold]" + plan.renameCount + "[]",
            "[lightgray]" + plan.skipCount + "[]"
        );
    }

    public DryRunPlan calculateDryRunPlan(){
        return DryRunPlan.calculate(entries, targetType, getPolicyForType(PackType.MAP), getPolicyForType(PackType.SCHEMATIC));
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
        updatePreviewSummary();
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

        DryRunPlan plan = calculateDryRunPlan();

        for(PackEntry entry : filtered){
            DryRunPlan.DryRunItem dryItem = plan.getItem(entry);

            // Flat button rows get hover/pressed feedback with subtle dark background.
            Button row = new Button(UIUtils.rowStyle());
            row.margin(6f, 8f, 6f, 8f);
            row.defaults().pad(3f);

            // Touch-friendly CheckBox (touch disabled so row click handles toggle cleanly)
            CheckBox box = new CheckBox("");
            box.setChecked(entry.selected);
            box.touchable = Touchable.disabled;
            UIUtils.squareCheck(box, 24f);
            row.add(box).size(24f).padLeft(2f).padRight(6f);

            // Clicking whole row toggles selection
            row.clicked(() -> {
                entry.selected = !entry.selected;
                box.setChecked(entry.selected);
                updatePreviewSummary();
            });

            // Thumbnail / Type Icon (fixed 36x36f)
            Table thumb = new Table();
            thumb.setSize(36f, 36f);
            Texture cached = PreviewManager.getCachedTexture(entry);
            if(cached != null && !cached.isDisposed()){
                BorderImage b = new BorderImage(cached, 2f);
                b.setScaling(Scaling.fit);
                thumb.add(b).size(36f);
            }else{
                if(entry.type == PackType.MAP){
                    thumb.image(Icon.terrain).size(26f).color(Pal.accent);
                }else{
                    thumb.image(Icon.paste).size(26f).color(Pal.accent);
                }
                if(entry.isValid){
                    PreviewManager.loadPreviewAsync(entry, tex -> {
                        if(tex != null && !tex.isDisposed()){
                            thumb.clear();
                            BorderImage b = new BorderImage(tex, 2f);
                            b.setScaling(Scaling.fit);
                            thumb.add(b).size(36f);
                        }
                    });
                }
            }
            row.add(thumb).size(36f).padRight(6f);

            // Title & Subtitle Info
            row.table(t -> {
                t.left().defaults().left().growX();

                StringBuilder titleBuilder = new StringBuilder();
                titleBuilder.append(entry.displayName);

                String nameNoExt = entry.sanitizedFileName.contains(".") ?
                    entry.sanitizedFileName.substring(0, entry.sanitizedFileName.lastIndexOf('.')) : entry.sanitizedFileName;

                if(entry.isDuplicate){
                    titleBuilder.append("  [gold]").append(getLocalizedText("packloader.badge.duplicate")).append("[]");
                    if(dryItem != null){
                        String actionColor = dryItem.action == DryRunAction.NEW ? "heal" :
                                             dryItem.action == DryRunAction.OVERWRITE ? "accent" :
                                             dryItem.action == DryRunAction.RENAME ? "gold" : "lightgray";
                        titleBuilder.append(" -> [").append(actionColor).append("]").append(dryItem.action.localized()).append("[]");
                    }
                }
                if(entry.isContentDuplicate){
                    titleBuilder.append("  [cyan]").append(getLocalizedText("packloader.badge.content-duplicate")).append("[]");
                }
                if(!entry.isValid){
                    titleBuilder.append("  [scarlet]").append(getLocalizedText("packloader.badge.invalid")).append("[]");
                }

                t.add(titleBuilder.toString()).color(Color.white).ellipsis(true).growX().minWidth(0f).left().row();

                // Clean second line: status reasons, author, and optional detailed metadata
                StringBuilder infoBuilder = new StringBuilder();
                boolean hasInfo = false;

                if(dryItem != null && dryItem.isRenamed()){
                    infoBuilder.append("[gold]").append(getLocalizedText("packloader.dryrun.target.renamed", dryItem.plannedTargetName)).append("[]");
                    hasInfo = true;
                }else if(entry.isDuplicate && !entry.duplicateReason.isEmpty()){
                    infoBuilder.append("[gold]").append(entry.duplicateReason).append("[]");
                    hasInfo = true;
                }

                if(entry.isContentDuplicate && !entry.contentDuplicateReason.isEmpty()){
                    if(hasInfo) infoBuilder.append("  •  ");
                    infoBuilder.append("[cyan]").append(entry.contentDuplicateReason).append("[]");
                    hasInfo = true;
                }

                if(!entry.isValid && !entry.errorReason.isEmpty()){
                    if(hasInfo) infoBuilder.append("  •  ");
                    infoBuilder.append("[scarlet]").append(entry.errorReason).append("[]");
                    hasInfo = true;
                }

                if(entry.type == PackType.MAP && entry.mapInfo != null && !entry.mapInfo.author.isEmpty()){
                    if(hasInfo) infoBuilder.append("  •  ");
                    infoBuilder.append("[lightgray]").append(entry.mapInfo.author).append("[]");
                    hasInfo = true;
                }

                if(!entry.displayName.equalsIgnoreCase(nameNoExt) && !entry.displayName.equalsIgnoreCase(entry.sanitizedFileName)){
                    if(hasInfo) infoBuilder.append("  •  ");
                    infoBuilder.append("[lightgray]").append(entry.sanitizedFileName).append("[]");
                    hasInfo = true;
                }

                if(showDetailedMeta){
                    if(hasInfo) infoBuilder.append("  •  ");
                    infoBuilder.append("[lightgray]").append(PackScanner.formatSize(entry.size)).append("[]");
                    String dims = entry.getDimensionsString();
                    if(!dims.isEmpty()){
                        infoBuilder.append("  •  [lightgray]").append(dims).append("[]");
                    }
                    hasInfo = true;
                }

                if(!hasInfo){
                    infoBuilder.append("[heal]").append(getLocalizedText("packloader.status.ready")).append("[]");
                }

                t.add(infoBuilder.toString()).color(Color.white)
                 .ellipsis(true).growX().minWidth(0f).left().padTop(2f);
            }).growX().minWidth(0f).left().padLeft(6f).padRight(6f);

            // Actions: Info (for both Maps & Schematics) & In-World Placement (for Schematics, docked right)
            Table actions = new Table();
            actions.right().defaults().size(36f).pad(2f);

            if(entry.isValid){
                if(entry.type == PackType.SCHEMATIC && Vars.state != null && Vars.state.isGame()){
                    Button placeBtn = actions.button(Icon.paste, Styles.clearNonei, () -> {
                        placeSchematic(entry);
                    }).size(36f).get();
                    placeBtn.addListener(new InputListener(){
                        @Override
                        public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                            event.stop();
                            return true;
                        }
                    });
                }

                Button infoBtn = actions.button(Icon.info, Styles.clearNonei, () -> {
                    if(entry.type == PackType.MAP){
                        showMapInfo(entry);
                    }else{
                        showSchematicInfo(entry);
                    }
                }).size(36f).get();
                infoBtn.addListener(new InputListener(){
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                        event.stop();
                        return true;
                    }
                });
            }

            row.add(actions).right();

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
        // Snapshot the policies so changing the UI mid-import cannot affect later entries.
        DuplicatePolicy mapPolicy = mapDuplicatePolicy;
        DuplicatePolicy schemPolicy = schemDuplicatePolicy;

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

        String finalSourceName = (sourceName != null && !sourceName.isEmpty()) ? sourceName : "Batch Import";

        Threads.daemon("PackLoader-Import", () -> {
            ImportReport report;
            try{
                report = importer.importEntries(toImport, mapPolicy, schemPolicy, finalSourceName, listener);
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
                    new PackResultDialog(report, importer.undoManager).show();
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

    private MapInfoDialog mapInfoDialog;
    private SchematicInfoDialog schematicInfoDialog;

    public void showMapInfo(PackEntry entry){
        if(entry == null) return;
        if(mapInfoDialog == null){
            mapInfoDialog = new MapInfoDialog();
        }
        mapInfoDialog.show(entry);
    }

    public void showSchematicInfo(PackEntry entry){
        if(entry == null) return;
        if(schematicInfoDialog == null){
            schematicInfoDialog = new SchematicInfoDialog(this::hide);
        }
        schematicInfoDialog.show(entry);
    }

    public void placeSchematic(PackEntry entry){
        SchematicPlacementHelper.placeSchematic(entry, this::hide);
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
                        scanner.checkContentDuplicates(entries);
                        resetFilters();

                        mapDuplicatePolicy = PackLoaderSettings.getDuplicatePolicy(PackType.MAP);
                        schemDuplicatePolicy = PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC);
                        if(!append && PackLoaderSettings.isQuickImport()){
                            int eligible = EntryFilter.selectEligibleForQuickImport(entries, targetType,
                                mapDuplicatePolicy, schemDuplicatePolicy);
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

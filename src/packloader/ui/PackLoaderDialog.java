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
import mindustry.ui.FileChooser;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import packloader.core.PackImporter;
import packloader.core.PackImporter.ImportProgress;
import packloader.core.PackScanner;
import packloader.model.DuplicatePolicy;
import packloader.model.ImportReport;
import packloader.model.PackEntry;
import packloader.model.PackType;
import packloader.undo.ImportTransaction;
import packloader.util.MainThread;
import packloader.util.PackLoaderSettings;
import packloader.util.RecentSources;
import packloader.util.StorageSources;
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
        background(UIUtils.solidBackground());
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

            float maxW = Math.min(UIUtils.screenWidth() * 0.9f, 480f);
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

            welcomeDialog.buttons.defaults().size(180f, UIUtils.buttonHeight()).pad(4f);

            UIUtils.fitButton(welcomeDialog.buttons.button(getLocalizedText("packloader.welcome.close"), Icon.ok, welcomeDialog::hide).get());

            UIUtils.fitButton(welcomeDialog.buttons.button(getLocalizedText("packloader.welcome.issues"), Icon.link, () -> {
                Core.app.openURI(ISSUES_URL);
            }).get());

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
        return false;
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
    }

    public void rebuild(){
        cont.clear();

        // Contextual title: in source selection mode, respect targetType
        if(title != null){
            if(targetType == PackType.MAP){
                title.setText(getLocalizedText("packloader.dialog.title.maps"));
            }else if(targetType == PackType.SCHEMATIC){
                title.setText(getLocalizedText("packloader.dialog.title.schematics"));
            }else{
                title.setText(getLocalizedText("packloader.dialog.title"));
            }
        }

        if(targetType == null){
            buildModeSelection();
        }else{
            buildSourceSelection();
        }
    }

    /** View 0: Initial Mode Selection (Choose Load Schematics vs Load Maps) */
    private void buildModeSelection(){
        cont.top();
        Table root = new Table();
        root.top();
        float maxW = Math.min(UIUtils.screenWidth() * 0.92f, Vars.mobile ? 480f : 520f);
        root.defaults().pad(Vars.mobile ? 5f : 4f).width(maxW);

        // Prompt subtitle
        root.add(getLocalizedText("packloader.mode.prompt"))
            .color(Pal.accent).style(Styles.outlineLabel).padTop(Vars.mobile ? 12f : 6f).padBottom(10f).row();

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
        cont.add().growY().minHeight(0f).row();
        cont.add(pane).growX().maxHeight(UIUtils.screenHeight() * 0.72f).pad(4f).row();
        cont.add().growY().minHeight(0f).row();

        buttons.clear();
        buttons.defaults().pad(4f);

        boolean isCompact = UIUtils.isCompact();
        float btnH = UIUtils.buttonHeight();

        ImportTransaction latestTx = importer.undoManager.getLatestTransaction();
        boolean hasUndo = latestTx != null && !latestTx.undone && latestTx.getActiveItemCount() > 0;

        if(isCompact){
            var closeBtn = UIUtils.fitButton(buttons.button(getLocalizedText("packloader.action.close"), Icon.cancel, this::hide)
                .size(140f, btnH).get());
            addCloseListener();

            buttons.add().growX();

            var bugBtn = UIUtils.fitButton(buttons.button(getLocalizedText("packloader.bug.report"), Icon.link, () -> {
                Core.app.openURI(ISSUES_URL);
            }).size(140f, btnH).get());

            if(hasUndo){
                buttons.row();
                var undoBtn = UIUtils.fitButton(buttons.button(getLocalizedText("packloader.undo.button.last"), Icon.undo, () -> {
                    new UndoConfirmDialog(importer.undoManager, latestTx, this::rebuild).show();
                }).size(288f, btnH).colspan(3).get());
                if(undoBtn != null && undoBtn.getLabel() != null){
                    undoBtn.getLabel().setColor(Color.orange);
                }
            }
        }else{
            var closeBtn = UIUtils.fitButton(buttons.button(getLocalizedText("packloader.action.close"), Icon.cancel, this::hide)
                .size(180f, btnH).get());
            addCloseListener();

            var bugBtn = UIUtils.fitButton(buttons.button(getLocalizedText("packloader.bug.report"), Icon.link, () -> {
                Core.app.openURI(ISSUES_URL);
            }).size(180f, btnH).get());

            buttons.add().growX();

            if(hasUndo){
                var undoBtn = UIUtils.fitButton(buttons.button(getLocalizedText("packloader.undo.button.last"), Icon.undo, () -> {
                    new UndoConfirmDialog(importer.undoManager, latestTx, this::rebuild).show();
                }).size(180f, btnH).get());
                if(undoBtn != null && undoBtn.getLabel() != null){
                    undoBtn.getLabel().setColor(Color.orange);
                }
            }
        }
    }

    /** View 1: Source Selection (When a specific pack type is active) */
    private void buildSourceSelection(){
        cont.top();
        Table root = new Table();
        root.top();
        float maxW = Math.min(UIUtils.screenWidth() * 0.92f, Vars.mobile ? 480f : 520f);
        root.defaults().pad(Vars.mobile ? 5f : 4f).width(maxW);

        // Schematics Section
        if(targetType == PackType.SCHEMATIC){
            root.add(createSourceOptionButton(
                Icon.folder,
                getLocalizedText("packloader.source.scan-folder.schem"),
                getLocalizedText("packloader.source.scan-folder.schem.desc"),
                () -> openFolderPicker(PackType.SCHEMATIC),
                maxW
            )).minHeight(72f).pad(4f).padTop(Vars.mobile ? 12f : 6f).row();

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
        cont.add().growY().minHeight(0f).row();
        cont.add(pane).growX().maxHeight(UIUtils.screenHeight() * 0.72f).pad(4f).row();
        cont.add().growY().minHeight(0f).row();

        buttons.clear();
        buttons.defaults().pad(4f);
        boolean isCompact = UIUtils.isCompact();
        float btnH = UIUtils.buttonHeight();

        if(isCompact){
            var backBtn = UIUtils.fitButton(buttons.button(getLocalizedText("packloader.action.back"), Icon.left, () -> {
                targetType = null;
                rebuild();
            }).size(140f, btnH).get());
            addCloseListener();

            buttons.add().growX();

            var bugBtn = UIUtils.fitButton(buttons.button(getLocalizedText("packloader.bug.report"), Icon.link, () -> {
                Core.app.openURI(ISSUES_URL);
            }).size(140f, btnH).get());
        }else{
            var backBtn = UIUtils.fitButton(buttons.button(getLocalizedText("packloader.action.back"), Icon.left, () -> {
                targetType = null;
                rebuild();
            }).size(180f, btnH).get());
            addCloseListener();

            var bugBtn = UIUtils.fitButton(buttons.button(getLocalizedText("packloader.bug.report"), Icon.link, () -> {
                Core.app.openURI(ISSUES_URL);
            }).size(180f, btnH).get());

            buttons.add().growX();
        }
    }

    private Button createRecentSourceCard(RecentSources.SourceItem item, PackType activeType){
        Button card = new Button(UIUtils.cardStyle());
        card.margin(8f, 12f, 8f, 12f);
        card.clicked(() -> {
            Fi file = packloader.util.StorageSources.resolveFi(item.path);
            if(file == null || !packloader.util.StorageSources.canReadDirectly(file)){
                boolean isRestricted = Vars.mobile && (
                    packloader.util.StorageSources.isVirtualSafPath(item.path) ||
                    PackScanner.isScopedStorageRestricted(file != null ? file : new Fi(item.path))
                );
                if(isRestricted){
                    UIUtils.showNoticeDialog(
                        getLocalizedText("packloader.recent.saf-reselect.title"),
                        getLocalizedText("packloader.scoped.header"),
                        getLocalizedText("packloader.recent.saf-reselect.prompt", item.name),
                        getLocalizedText("packloader.action.reselect"),
                        Icon.refresh,
                        () -> {
                            PackType target = activeType != null ? activeType : item.type;
                            if(item.isDirectory){
                                openFolderPicker(target);
                            }else{
                                pickZip(target);
                            }
                        },
                        getLocalizedText("packloader.action.cancel"),
                        Icon.cancel,
                        null
                    );
                    return;
                }

                RecentSources.remove(item.path, item.type);
                if(Vars.ui != null){
                    String missingName = (item.name != null && !item.name.isEmpty()) ? item.name : item.path;
                    Vars.ui.showErrorMessage(getLocalizedText("packloader.source.path.notfound", missingName));
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

        // Text column: wrap enables natural multi-line layout on narrow screens without truncating text
        btn.table(t -> {
            t.left().defaults().left().growX();
            t.add(title).style(Styles.defaultLabel).color(Color.white).ellipsis(true).growX().minWidth(0f).padBottom(3f).row();
            Label descLabel = new Label(desc, Styles.defaultLabel);
            descLabel.setColor(Pal.lightishGray);
            descLabel.setWrap(true);
            descLabel.setEllipsis(false);
            t.add(descLabel).growX().minWidth(0f);
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

        // Conflict Policy Preset Container (always visible)
        Table conflictContainer = new Table();
        conflictContainer.left().defaults().left();
        conflictContainer.margin(2f, 0f, 4f, 0f);
        populateConflictPresetContent(conflictContainer, type);
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

    /**
     * Starts an import on a daemon worker, importing all selected entries.
     * File parsing/staging stays on the worker; the few game-registry operations
     * are marshalled to the main thread by PackImporter.
     */
    private void executeImport(){
        if(importRunning) return;

        Seq<PackEntry> toImport = entries.select(e -> e.selected);
        if(toImport.isEmpty()){
            if(Vars.ui != null){
                String emptyMsg = targetType == PackType.MAP ? getLocalizedText("packloader.source.empty.maps") :
                    targetType == PackType.SCHEMATIC ? getLocalizedText("packloader.source.empty.schematics") :
                    getLocalizedText("packloader.source.empty");
                Vars.ui.showInfo(emptyMsg);
            }
            return;
        }

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
                    clearEntries();
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

        Vars.ui.loadfrag.setText(getLocalizedText("packloader.import.progress.detail",
            progress.processed, progress.total, progress.success, progress.failed));

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
                Fi resolved = StorageSources.resolveFi(item.path);
                if(resolved != null && resolved.exists()){
                    defaultPath = resolved.path();
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
                Fi file = StorageSources.resolveFi(trimmed);
                if(file == null || !file.exists()){
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

        Table card = new Table(Tex.pane);
        card.margin(12f);
        card.defaults().size(320f, 56f).pad(4f);

        String zipText = type == PackType.MAP ? getLocalizedText("packloader.source.zip.map") :
                         type == PackType.SCHEMATIC ? getLocalizedText("packloader.source.zip.schem") :
                         getLocalizedText("packloader.source.saf.title");
        String folderText = type == PackType.MAP ? getLocalizedText("packloader.source.scan-folder.map") :
                            type == PackType.SCHEMATIC ? getLocalizedText("packloader.source.scan-folder.schem") :
                            getLocalizedText("packloader.action.scan-folder");

        card.button(b -> {
            b.left().margin(8f, 16f, 8f, 16f);
            b.image(Icon.download).size(26f).color(Pal.accent).padRight(12f);
            b.add(zipText).style(Styles.defaultLabel).color(Color.white).growX().left();
        }, UIUtils.cardStyle(), () -> {
            d.hide();
            pickZip(type, true);
        }).growX().row();

        card.button(b -> {
            b.left().margin(8f, 16f, 8f, 16f);
            b.image(Icon.folder).size(26f).color(Pal.accent).padRight(12f);
            b.add(folderText).style(Styles.defaultLabel).color(Color.white).growX().left();
        }, UIUtils.cardStyle(), () -> {
            d.hide();
            openFolderPicker(type, true);
        }).growX().row();

        if(!Vars.mobile){
            card.button(b -> {
                b.left().margin(8f, 16f, 8f, 16f);
                b.image(Icon.paste).size(26f).color(Pal.accent).padRight(12f);
                b.add(getLocalizedText("packloader.source.path.title")).style(Styles.defaultLabel).color(Color.white).growX().left();
            }, UIUtils.cardStyle(), () -> {
                d.hide();
                openPathInputDialog(true);
            }).growX().row();
        }

        d.cont.add(card).pad(10f);

        d.buttons.defaults().pad(4f);
        var closeBtn = UIUtils.pad(d.buttons.button(getLocalizedText("packloader.action.close"), Icon.cancel, d::hide)
            .size(Vars.mobile ? 120f : 160f, 54f).get());
        if(closeBtn != null && closeBtn.getLabel() != null){
            closeBtn.getLabel().setWrap(false);
        }
        d.addCloseListener();

        d.show();
    }

    /** Runs scanner asynchronously to prevent UI freeze on large archives or deep folders. */
    public void scanAsync(PackType targetType, Fi... sources){
        scanAsync(targetType, false, sources);
    }

    public void scanAsync(PackType targetType, boolean append, Fi... rawSources){
        if(rawSources == null || rawSources.length == 0) return;

        // Automatically resolve Android SAF virtual Fi to physical files ONLY if directly readable via POSIX
        final Fi[] sources = new Fi[rawSources.length];
        for(int i = 0; i < rawSources.length; i++){
            Fi resolved = packloader.util.StorageSources.resolveFi(rawSources[i]);
            sources[i] = (resolved != null && packloader.util.StorageSources.canReadDirectly(resolved)) ? resolved : rawSources[i];
        }

        this.targetType = targetType;
        String name = sources.length == 1 ?
            packloader.util.StorageSources.sanitizeSourceName(sources[0].name(), sources[0].path()) :
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
                    if(totalKnown){
                        Vars.ui.loadfrag.setText(getLocalizedText("packloader.scan.progress.total",
                            Math.min(found, total), total));
                        Vars.ui.loadfrag.setProgress(Math.min(1f, found / (float)total));
                    }else{
                        Vars.ui.loadfrag.setText(getLocalizedText("packloader.scan.progress", found));
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

                        mapDuplicatePolicy = PackLoaderSettings.getDuplicatePolicy(PackType.MAP);
                        schemDuplicatePolicy = PackLoaderSettings.getDuplicatePolicy(PackType.SCHEMATIC);

                        for(PackEntry entry : entries){
                            entry.selected = (targetType == null || entry.type == targetType) && entry.isValid;
                        }

                        if(!warningText.isEmpty()){
                            Log.warn("[PackLoader] Scan warnings: @", warningText);
                        }

                        executeImport();
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
        String title = getLocalizedText("packloader.scoped.title");
        String header = getLocalizedText("packloader.scoped.header");
        String msgText = getLocalizedText("packloader.scoped.message");

        String zipText = targetType == PackType.MAP ? getLocalizedText("packloader.action.import-map-zip") :
            targetType == PackType.SCHEMATIC ? getLocalizedText("packloader.action.import-schem-zip") :
            getLocalizedText("packloader.action.import-zip");

        UIUtils.showNoticeDialog(
            title,
            header,
            msgText,
            zipText,
            Icon.download,
            () -> pickZip(targetType),
            getLocalizedText("packloader.action.close"),
            Icon.cancel,
            null
        );
    }

    public void scanAsync(Fi... sources){
        scanAsync(targetType, sources);
    }
}

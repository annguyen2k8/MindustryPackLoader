package packloader;

import arc.Core;
import arc.Events;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.Icon;
import mindustry.mod.Mod;
import packloader.model.PackType;
import packloader.ui.PackLoaderDialog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PackLoaderMod extends Mod{
    public static PackLoaderDialog dialog;

    public PackLoaderMod(){
        Log.info("[PackLoader] Mindustry PackLoader mod constructed.");

        if(Vars.clientLoaded){
            initUI();
        }else{
            Events.on(ClientLoadEvent.class, e -> initUI());
        }
    }

    private void initUI(){
        try{
            if(dialog == null){
                dialog = new PackLoaderDialog();
            }

            String menuButtonText = Core.bundle != null ? Core.bundle.get("packloader.button.menu", "Pack Loader") : "Pack Loader";
            String importMapText = Core.bundle != null ? Core.bundle.get("packloader.action.import-map-zip", "Import .zip") : "Import .zip";
            String importSchemText = Core.bundle != null ? Core.bundle.get("packloader.action.import-schem-zip", "Import .zip") : "Import .zip";
            String scanFolderText = Core.bundle != null ? Core.bundle.get("packloader.action.scan-folder", "Scan Folder") : "Scan Folder";

            // 1. Add dedicated entry button on Main Menu (Opens 2-choice selection: Load Schematics vs Load Maps)
            if(Vars.ui != null && Vars.ui.menufrag != null){
                boolean alreadyAdded = false;
                try{
                    Field field = Vars.ui.menufrag.getClass().getDeclaredField("customButtons");
                    field.setAccessible(true);
                    arc.struct.Seq<?> custom = (arc.struct.Seq<?>)field.get(Vars.ui.menufrag);
                    if(custom != null){
                        for(Object o : custom){
                            if(o instanceof mindustry.ui.fragments.MenuFragment.MenuButton){
                                var mb = (mindustry.ui.fragments.MenuFragment.MenuButton)o;
                                if(menuButtonText.equals(mb.text)){
                                    alreadyAdded = true;
                                    break;
                                }
                            }
                        }
                    }
                }catch(Throwable ignored){}

                if(!alreadyAdded){
                    Vars.ui.menufrag.addButton(menuButtonText, Icon.download, () -> {
                        dialog.showMenu();
                    });
                }

                // Immediately rebuild menu so button shows on screen without needing to exit & re-enter
                rebuildMenu();
                Core.app.post(this::rebuildMenu);
                Time.runTask(2f, this::rebuildMenu);
            }

            // 2. Add two separate visible actions to Schematics Dialog (.msch only)
            Runnable addSchemButtons = () -> {
                if(Vars.ui != null && Vars.ui.schematics != null && Vars.ui.schematics.buttons != null){
                    addPackLoaderButtons(Vars.ui.schematics.buttons,
                        "packloader-schem-zip", importSchemText, Icon.download, () -> dialog.pickZip(PackType.SCHEMATIC),
                        "packloader-schem-folder", scanFolderText, Icon.folder, () -> dialog.openFolderPicker(PackType.SCHEMATIC));
                }
            };
            if(Vars.ui != null && Vars.ui.schematics != null){
                Vars.ui.schematics.shown(addSchemButtons);
                Vars.ui.schematics.update(() -> {
                    if(Vars.ui.schematics.isShown() && Vars.ui.schematics.buttons != null && Vars.ui.schematics.buttons.find("packloader-schem-zip") == null){
                        addSchemButtons.run();
                    }
                });
                addSchemButtons.run();
            }

            // 3. Add two separate visible actions to Custom Game Dialog (.msav only)
            Runnable addCustomGameButtons = () -> {
                if(Vars.ui != null && Vars.ui.custom != null && Vars.ui.custom.buttons != null){
                    addPackLoaderButtons(Vars.ui.custom.buttons,
                        "packloader-map-zip", importMapText, Icon.download, () -> dialog.pickZip(PackType.MAP),
                        "packloader-map-folder", scanFolderText, Icon.folder, () -> dialog.openFolderPicker(PackType.MAP));
                }
            };
            if(Vars.ui != null && Vars.ui.custom != null){
                Vars.ui.custom.shown(addCustomGameButtons);
                Vars.ui.custom.update(() -> {
                    if(Vars.ui.custom.isShown() && Vars.ui.custom.buttons != null && Vars.ui.custom.buttons.find("packloader-map-zip") == null){
                        addCustomGameButtons.run();
                    }
                });
                addCustomGameButtons.run();
            }

            // 4. Add two separate visible actions to Editor Maps Dialog (.msav only)
            Runnable addMapsButtons = () -> {
                if(Vars.ui != null && Vars.ui.maps != null && Vars.ui.maps.buttons != null){
                    addPackLoaderButtons(Vars.ui.maps.buttons,
                        "packloader-map-zip", importMapText, Icon.download, () -> dialog.pickZip(PackType.MAP),
                        "packloader-map-folder", scanFolderText, Icon.folder, () -> dialog.openFolderPicker(PackType.MAP));
                }
            };
            if(Vars.ui != null && Vars.ui.maps != null){
                Vars.ui.maps.shown(addMapsButtons);
                Vars.ui.maps.update(() -> {
                    if(Vars.ui.maps.isShown() && Vars.ui.maps.buttons != null && Vars.ui.maps.buttons.find("packloader-map-zip") == null){
                        addMapsButtons.run();
                    }
                });
                addMapsButtons.run();
            }

            // Ensure buttons are restored when PackLoaderDialog closes
            dialog.hidden(() -> {
                if(Vars.ui != null){
                    if(Vars.ui.schematics != null && Vars.ui.schematics.isShown()) addSchemButtons.run();
                    if(Vars.ui.custom != null && Vars.ui.custom.isShown()) addCustomGameButtons.run();
                    if(Vars.ui.maps != null && Vars.ui.maps.isShown()) addMapsButtons.run();
                }
            });

            Log.info("[PackLoader] PackLoader UI buttons registered successfully.");
        }catch(Throwable t){
            Log.err("[PackLoader] Failed to initialize UI integration:", t);
        }
    }

    private void rebuildMenu(){
        if(Vars.ui == null || Vars.ui.menufrag == null) return;
        try{
            Method m = Vars.ui.menufrag.getClass().getDeclaredMethod(Vars.mobile ? "buildMobile" : "buildDesktop");
            m.setAccessible(true);
            m.invoke(Vars.ui.menufrag);
        }catch(Throwable t){
            Events.fire(new mindustry.game.EventType.ResizeEvent());
        }
    }

    @Override
    public void loadContent(){
        Log.info("[PackLoader] PackLoader content ready.");
    }

    private void addPackLoaderButtons(Table table, String zipName, String zipText, Drawable zipIcon, Runnable zipAction,
                                     String folderName, String folderText, Drawable folderIcon, Runnable folderAction){
        if(table == null || table.find(zipName) != null) return;

        table.row();
        table.button(zipText, zipIcon, zipAction).size(210f, 64f).name(zipName);
        table.button(folderText, folderIcon, folderAction).size(210f, 64f).name(folderName);
    }
}

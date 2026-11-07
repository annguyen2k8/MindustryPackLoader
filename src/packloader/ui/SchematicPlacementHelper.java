package packloader.ui;

import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Schematic;
import packloader.core.PackFormat;
import packloader.model.PackEntry;

import static packloader.util.Localization.getLocalizedText;

/**
 * Coordinates direct in-game schematic placement, pre-placement validation,
 * native tool invocation, and user feedback toasts.
 */
public final class SchematicPlacementHelper{

    private SchematicPlacementHelper(){}

    /**
     * Attempts to place a schematic directly into the game world using Mindustry's native placement system.
     * Validates game conditions (game state, player alive, builder unit, server rules) before activating.
     *
     * @param entry the PackEntry containing or referencing the schematic
     * @param onPlaced optional callback executed when placement is successfully activated (e.g. to close dialogs)
     * @return true if placement mode was successfully activated, false otherwise
     */
    public static boolean placeSchematic(PackEntry entry, Runnable onPlaced){
        if(entry == null) return false;

        Schematic s = entry.schematic != null ? entry.schematic : entry.getOrReadSchematic();
        if(s == null){
            Log.err("[PackLoader] Failed to read schematic for placement: @", entry.sanitizedFileName);
            if(Vars.ui != null){
                Vars.ui.showErrorMessage(getLocalizedText("packloader.error.invalid-schematic"));
            }
            return false;
        }

        SchematicPlacementValidator.ValidationResult result = SchematicPlacementValidator.validate(s);
        if(!result.isValid()){
            String message = getLocalizedText(result.getMessageKey());
            Log.warn("[PackLoader] Schematic placement blocked (@): @", result, message);
            if(Vars.ui != null){
                Vars.ui.showErrorMessage(message);
            }
            return false;
        }

        try{
            Vars.control.input.useSchematic(s);
            if(onPlaced != null){
                onPlaced.run();
            }
            if(Vars.ui != null){
                String name = s.name() != null && !s.name().isEmpty() ? s.name() : entry.displayName;
                Vars.ui.showInfoToast(getLocalizedText("packloader.schematic.placed.details", name, s.width, s.height), 3.5f);
            }
            Log.info("[PackLoader] Schematic placement activated for '@' (@x@)", s.name(), s.width, s.height);
            return true;
        }catch(Throwable t){
            Log.err("[PackLoader] Exception activating native schematic placement:", t);
            if(Vars.ui != null){
                Vars.ui.showErrorMessage(getLocalizedText("packloader.placement.error.failed", t.getMessage()));
            }
            return false;
        }
    }
}

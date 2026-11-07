package packloader.ui;

import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.game.Team;

/**
 * Validates whether in-game schematic placement is permitted based on game state,
 * player status, building capabilities, and server/match rules.
 */
public final class SchematicPlacementValidator{

    public enum ValidationResult{
        VALID(""),
        INVALID_SCHEMATIC("packloader.placement.error.invalid-schematic"),
        NOT_IN_GAME("packloader.placement.error.not-in-game"),
        GAME_OVER("packloader.placement.error.game-over"),
        SCHEMATICS_BANNED("packloader.placement.error.schematics-banned"),
        NO_PLAYER("packloader.placement.error.no-player"),
        PLAYER_DEAD("packloader.placement.error.player-dead"),
        NO_UNIT("packloader.placement.error.no-unit"),
        UNIT_CANNOT_BUILD("packloader.placement.error.cannot-build"),
        SPECTATOR("packloader.placement.error.spectator"),
        NO_INPUT_HANDLER("packloader.placement.error.no-input");

        private final String messageKey;

        ValidationResult(String messageKey){
            this.messageKey = messageKey;
        }

        public String getMessageKey(){
            return messageKey;
        }

        public boolean isValid(){
            return this == VALID;
        }
    }

    private SchematicPlacementValidator(){}

    /**
     * Checks if the given schematic can be safely placed in the current game context.
     *
     * @param schematic the schematic to validate
     * @return {@link ValidationResult#VALID} if all requirements are satisfied, or an error code
     */
    public static ValidationResult validate(Schematic schematic){
        if(schematic == null || schematic.tiles == null || schematic.tiles.isEmpty()){
            return ValidationResult.INVALID_SCHEMATIC;
        }

        if(Vars.state == null || !Vars.state.isGame()){
            return ValidationResult.NOT_IN_GAME;
        }

        if(Vars.state.gameOver){
            return ValidationResult.GAME_OVER;
        }

        if(Vars.state.rules != null && !Vars.state.rules.schematicsAllowed){
            return ValidationResult.SCHEMATICS_BANNED;
        }

        if(Vars.player == null){
            return ValidationResult.NO_PLAYER;
        }

        if(Vars.player.dead()){
            return ValidationResult.PLAYER_DEAD;
        }

        if(Vars.player.unit() == null){
            return ValidationResult.NO_UNIT;
        }

        if(!Vars.player.unit().canBuild()){
            return ValidationResult.UNIT_CANNOT_BUILD;
        }

        if(Vars.player.team() != null && Vars.player.team() == Team.derelict){
            return ValidationResult.SPECTATOR;
        }

        if(Vars.control == null || Vars.control.input == null){
            return ValidationResult.NO_INPUT_HANDLER;
        }

        return ValidationResult.VALID;
    }
}

package packloader.ui;

import arc.Core;
import arc.struct.Seq;
import arc.struct.StringMap;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.Control;
import mindustry.core.GameState;
import mindustry.game.Rules;
import mindustry.game.Schematic;
import mindustry.game.Team;
import mindustry.gen.Player;
import mindustry.gen.UnitEntity;
import mindustry.input.InputHandler;
import mindustry.net.Net;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.TestHelper;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

public class SchematicPlacementValidatorTest{

    private GameState previousState;
    private Player previousPlayer;
    private Control previousControl;
    private ContentLoader previousContent;
    private Net previousNet;

    private static class TestUnit extends UnitEntity{
        public boolean valid = true;
        public boolean buildCapability = true;

        public TestUnit(){
            this.plans = new arc.struct.Queue<>();
        }

        @Override
        public boolean isValid(){
            return valid;
        }

        @Override
        public boolean canBuild(){
            return buildCapability;
        }
    }

    @BeforeEach
    public void setup(){
        TestHelper.initBundle(Locale.ENGLISH);
        previousState = Vars.state;
        previousPlayer = Vars.player;
        previousControl = Vars.control;
        previousContent = Vars.content;
        previousNet = Vars.net;
        Vars.headless = true;
        Net.NetProvider provider = new Net.NetProvider(){
            @Override public void connectClient(String s, int i, Runnable r){}
            @Override public void sendClient(Object o, boolean b){}
            @Override public void disconnectClient(){}
            @Override public void discoverServers(arc.func.Cons<mindustry.net.Host> c, Runnable r){}
            @Override public void pingHost(String s, int i, arc.func.Cons<mindustry.net.Host> c, arc.func.Cons<Exception> e){}
            @Override public void hostServer(int i){}
            @Override public Iterable<? extends mindustry.net.NetConnection> getConnections(){ return new Seq<>(); }
            @Override public void closeServer(){}
        };
        Vars.net = new Net(provider);
        Vars.content = new ContentLoader();
        for(int i = 0; i < mindustry.ctype.ContentType.all.length; i++){
            Vars.content.getContentMap()[i] = new Seq<>();
        }
    }

    @AfterEach
    public void teardown(){
        Vars.state = previousState;
        Vars.player = previousPlayer;
        Vars.control = previousControl;
        Vars.content = previousContent;
        Vars.net = previousNet;
    }

    private Schematic createDummySchematic(){
        Seq<Schematic.Stile> tiles = new Seq<>();
        tiles.add(new Schematic.Stile(null, (short)0, (short)0, null, (byte)0));
        return new Schematic(tiles, new StringMap(), 2, 2);
    }

    @Test
    public void testInvalidSchematic(){
        assertEquals(SchematicPlacementValidator.ValidationResult.INVALID_SCHEMATIC,
            SchematicPlacementValidator.validate(null));

        Schematic empty = new Schematic(new Seq<>(), new StringMap(), 0, 0);
        assertEquals(SchematicPlacementValidator.ValidationResult.INVALID_SCHEMATIC,
            SchematicPlacementValidator.validate(empty));
    }

    @Test
    public void testNotInGame(){
        Schematic s = createDummySchematic();

        Vars.state = null;
        assertEquals(SchematicPlacementValidator.ValidationResult.NOT_IN_GAME,
            SchematicPlacementValidator.validate(s));

        Vars.state = new GameState();
        Vars.state.set(GameState.State.menu);
        assertEquals(SchematicPlacementValidator.ValidationResult.NOT_IN_GAME,
            SchematicPlacementValidator.validate(s));
    }

    @Test
    public void testGameOver(){
        Schematic s = createDummySchematic();

        Vars.state = new GameState();
        Vars.state.set(GameState.State.playing);
        Vars.state.gameOver = true;

        assertEquals(SchematicPlacementValidator.ValidationResult.GAME_OVER,
            SchematicPlacementValidator.validate(s));
    }

    @Test
    public void testSchematicsBanned(){
        Schematic s = createDummySchematic();

        Vars.state = new GameState();
        Vars.state.set(GameState.State.playing);
        Vars.state.rules = new Rules();
        Vars.state.rules.schematicsAllowed = false;

        assertEquals(SchematicPlacementValidator.ValidationResult.SCHEMATICS_BANNED,
            SchematicPlacementValidator.validate(s));
    }

    @Test
    public void testNoPlayerOrPlayerDead(){
        Schematic s = createDummySchematic();

        Vars.state = new GameState();
        Vars.state.set(GameState.State.playing);
        Vars.state.rules = new Rules();
        Vars.state.rules.schematicsAllowed = true;

        Vars.player = null;
        assertEquals(SchematicPlacementValidator.ValidationResult.NO_PLAYER,
            SchematicPlacementValidator.validate(s));

        Player deadPlayer = Player.create();
        TestUnit deadUnit = new TestUnit();
        deadUnit.valid = false;
        deadPlayer.unit(deadUnit);
        Vars.player = deadPlayer;

        assertTrue(deadPlayer.dead());
        assertEquals(SchematicPlacementValidator.ValidationResult.PLAYER_DEAD,
            SchematicPlacementValidator.validate(s));
    }

    @Test
    public void testUnitCannotBuild(){
        Schematic s = createDummySchematic();

        Vars.state = new GameState();
        Vars.state.set(GameState.State.playing);
        Vars.state.rules = new Rules();
        Vars.state.rules.schematicsAllowed = true;

        Player player = Player.create();
        player.unit(null);
        Vars.player = player;

        assertEquals(SchematicPlacementValidator.ValidationResult.PLAYER_DEAD,
            SchematicPlacementValidator.validate(s));

        // Player has alive unit, but unit cannot build
        TestUnit nonBuilder = new TestUnit();
        nonBuilder.valid = true;
        nonBuilder.buildCapability = false;
        player.unit(nonBuilder);

        assertEquals(SchematicPlacementValidator.ValidationResult.UNIT_CANNOT_BUILD,
            SchematicPlacementValidator.validate(s));
    }

    @Test
    public void testSpectatorBanned(){
        Schematic s = createDummySchematic();

        Vars.state = new GameState();
        Vars.state.set(GameState.State.playing);
        Vars.state.rules = new Rules();
        Vars.state.rules.schematicsAllowed = true;

        Player player = Player.create();
        player.team(Team.derelict);

        TestUnit builder = new TestUnit();
        builder.valid = true;
        builder.buildCapability = true;
        player.unit(builder);
        Vars.player = player;

        assertEquals(SchematicPlacementValidator.ValidationResult.SPECTATOR,
            SchematicPlacementValidator.validate(s));
    }

    @Test
    public void testNoControlInput(){
        Schematic s = createDummySchematic();

        Vars.state = new GameState();
        Vars.state.set(GameState.State.playing);
        Vars.state.rules = new Rules();
        Vars.state.rules.schematicsAllowed = true;

        Player player = Player.create();
        player.team(Team.sharded);

        TestUnit builder = new TestUnit();
        builder.valid = true;
        builder.buildCapability = true;
        player.unit(builder);
        Vars.player = player;

        Vars.control = null;
        assertEquals(SchematicPlacementValidator.ValidationResult.NO_INPUT_HANDLER,
            SchematicPlacementValidator.validate(s));
    }

    @Test
    public void testValidPlacement(){
        Schematic s = createDummySchematic();

        Vars.state = new GameState();
        Vars.state.set(GameState.State.playing);
        Vars.state.rules = new Rules();
        Vars.state.rules.schematicsAllowed = true;

        Player player = Player.create();
        player.team(Team.sharded);

        TestUnit builder = new TestUnit();
        builder.valid = true;
        builder.buildCapability = true;
        player.unit(builder);
        Vars.player = player;

        sun.misc.Unsafe unsafe;
        try{
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (sun.misc.Unsafe)f.get(null);
            Vars.control = (Control)unsafe.allocateInstance(Control.class);
        }catch(Throwable t){
            throw new RuntimeException("Failed to allocate Control", t);
        }
        Vars.control.input = new InputHandler(){
            @Override
            public void useSchematic(Schematic s, boolean b){}
        };

        SchematicPlacementValidator.ValidationResult res = SchematicPlacementValidator.validate(s);
        assertEquals(SchematicPlacementValidator.ValidationResult.VALID, res);
        assertTrue(res.isValid());
    }

    @Test
    public void testValidationMessageKeysExistInBundle(){
        for(SchematicPlacementValidator.ValidationResult res : SchematicPlacementValidator.ValidationResult.values()){
            if(res == SchematicPlacementValidator.ValidationResult.VALID) continue;
            assertNotNull(res.getMessageKey(), "Message key must not be null: " + res);
            assertFalse(res.getMessageKey().isEmpty(), "Message key must not be empty: " + res);
            assertTrue(Core.bundle.has(res.getMessageKey()),
                "Bundle missing key for " + res + ": " + res.getMessageKey());
        }
    }

    @Test
    public void testPlacementHelperSuccessFlow(){
        Schematic s = createDummySchematic();
        packloader.model.PackEntry entry = new packloader.model.PackEntry(
            packloader.model.PackType.SCHEMATIC, "test.msch", "test.msch", 100L
        );
        entry.displayName = "Test Schem";
        entry.schematic = s;
        entry.isValid = true;

        Vars.state = new GameState();
        Vars.state.set(GameState.State.playing);
        Vars.state.rules = new Rules();
        Vars.state.rules.schematicsAllowed = true;

        Player player = Player.create();
        player.team(Team.sharded);

        TestUnit builder = new TestUnit();
        builder.valid = true;
        builder.buildCapability = true;
        player.unit(builder);
        Vars.player = player;

        sun.misc.Unsafe unsafe;
        try{
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (sun.misc.Unsafe)f.get(null);
            Vars.control = (Control)unsafe.allocateInstance(Control.class);
        }catch(Throwable t){
            throw new RuntimeException(t);
        }

        final Schematic[] usedSchematic = new Schematic[1];
        Vars.control.input = new InputHandler(){
            @Override
            public void useSchematic(Schematic used, boolean b){
                usedSchematic[0] = used;
            }

            @Override
            public void useSchematic(Schematic used){
                usedSchematic[0] = used;
            }
        };

        boolean[] callbackExecuted = {false};
        boolean success = SchematicPlacementHelper.placeSchematic(entry, () -> callbackExecuted[0] = true);

        assertTrue(success, "Placement should succeed under valid game conditions");
        assertTrue(callbackExecuted[0], "onPlaced callback should be executed on success");
        assertSame(s, usedSchematic[0], "Schematic passed to input handler should match entry schematic");
    }

    @Test
    public void testPlacementHelperBlockedWhenConditionsNotMet(){
        Schematic s = createDummySchematic();
        packloader.model.PackEntry entry = new packloader.model.PackEntry(
            packloader.model.PackType.SCHEMATIC, "blocked.msch", "blocked.msch", 100L
        );
        entry.displayName = "Blocked Schem";
        entry.schematic = s;
        entry.isValid = true;

        // Condition 1: Not in game
        Vars.state = null;
        boolean[] callbackExecuted = {false};
        boolean success = SchematicPlacementHelper.placeSchematic(entry, () -> callbackExecuted[0] = true);
        assertFalse(success);
        assertFalse(callbackExecuted[0]);

        // Condition 2: Schematics banned
        Vars.state = new GameState();
        Vars.state.set(GameState.State.playing);
        Vars.state.rules = new Rules();
        Vars.state.rules.schematicsAllowed = false;

        success = SchematicPlacementHelper.placeSchematic(entry, () -> callbackExecuted[0] = true);
        assertFalse(success);
        assertFalse(callbackExecuted[0]);
    }

    @Test
    public void testPlacementHelperNullOrInvalidEntry(){
        boolean[] callbackExecuted = {false};
        assertFalse(SchematicPlacementHelper.placeSchematic(null, () -> callbackExecuted[0] = true));
        assertFalse(callbackExecuted[0]);

        packloader.model.PackEntry invalidEntry = new packloader.model.PackEntry(
            packloader.model.PackType.SCHEMATIC, "invalid.msch", "invalid.msch", 100L
        );
        invalidEntry.isValid = false;
        invalidEntry.schematic = null;

        assertFalse(SchematicPlacementHelper.placeSchematic(invalidEntry, () -> callbackExecuted[0] = true));
        assertFalse(callbackExecuted[0]);
    }
}

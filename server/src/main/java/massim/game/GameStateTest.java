package massim.game;

import massim.config.TeamConfig;
import massim.game.environment.Terrain;
import massim.protocol.data.Position;
import massim.protocol.data.Thing;
import massim.protocol.messages.scenario.Actions;
import massim.protocol.messages.scenario.StepPercept;
import massim.util.RNG;
import org.json.JSONObject;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class GameStateTest {

    private static final JSONObject CONFIG = new JSONObject("{\n" +
            "      \"steps\" : 500,\n" +
            "      \"randomSeed\" : 17,\n" +
            "      \"randomFail\" : 1,\n" +
            "      \"entities\" : [{\"standard\" : 10}],\n" +
            "      \"clearSteps\" : 3,\n" +
            "      \"clearEnergyCost\" : 50,\n" +
            "      \"disableDuration\" : 4,\n" +
            "      \"maxEnergy\" : 300,\n" +
            "      \"attachLimit\" : 10,\n" +
            "\n" +
            "      \"grid\" : {\n" +
            "        \"height\" : 40,\n" +
            "        \"width\" : 40,\n" +
            "        \"file\" : \"conf/maps/test40x40.bmp\"\n" +
            "      },\n" +
            "\n" +
            "      \"blockTypes\" : [3, 3],\n" +
            "      \"dispensers\" : [2, 3],\n" +
            "\n" +
            "      \"tasks\" : {\n" +
            "        \"size\" : [2, 4],\n" +
            "        \"duration\" : [100, 200],\n" +
            "        \"probability\" : 0.05\n" +
            "      },\n" +
            "\n" +
            "      \"events\" : {\n" +
            "        \"chance\" : 10,\n" +
            "        \"radius\" : [3, 5],\n" +
            "        \"warning\" : 5,\n" +
            "        \"create\" : [5, 10]\n" +
            "      }\n" +
            "    }");

    private GameState state;

    @org.junit.Before
    public void setUp() {
        RNG.initialize(17);

        var team = new TeamConfig("A");
        for (var i = 1; i <= 10; i++) team.addAgent("A" + i, "1");
        state = new GameState(CONFIG, Set.of(team));
    }

    @org.junit.Test
    public void handleRequestAction() {
        var blockTypes = state.getBlockTypes();
        var dispenserPos = Position.of(3, 3);
        Entity a1 = state.getEntityByName("A1");
        assert a1 != null;
        assert state.createDispenser(dispenserPos, blockTypes.iterator().next());
        assert state.teleport("A1", dispenserPos.moved("s", 2));

        // too far away -> fail
        assert state.handleRequestAction(a1, "n").equals(Actions.RESULT_F_TARGET);
        //move closer
        assert state.handleMoveAction(a1, "n").equals(Actions.RESULT_SUCCESS);
        // wrong param -> fail
        assert state.handleRequestAction(a1, "w").equals(Actions.RESULT_F_TARGET);
        // everything correct -> success
        assert state.handleRequestAction(a1, "n").equals(Actions.RESULT_SUCCESS);
        // repeat -> fail
        assert state.handleRequestAction(a1, "n").equals(Actions.RESULT_F_BLOCKED);
        // another try
        assert state.createDispenser(a1.getPosition().moved("e", 1), blockTypes.iterator().next());
        assert state.handleRequestAction(a1, "e").equals(Actions.RESULT_SUCCESS);
    }

    @org.junit.Test
    public void handleSubmitAction() {
        state.setTerrain(Position.of(15,15), Terrain.GOAL);
        assert(state.teleport("A1", Position.of(15,15)));
        String blockType = state.getBlockTypes().iterator().next();
        assert(state.createBlock(Position.of(15,16), blockType) != null);
        assert(state.createBlock(Position.of(14,16), blockType) != null);
        assert(state.createTask("testTask1", 10,
                Map.of(Position.of(0, 1), blockType, Position.of(-1, 1), blockType)) != null);
        assert(state.attach(Position.of(15,15), Position.of(15,16)));
        assert(state.attach(Position.of(15,16), Position.of(14,16)));
        assert(state.handleSubmitAction(state.getEntityByName("A1"), "testTask1").equals(Actions.RESULT_SUCCESS));
    }

    @org.junit.Test
    public void getStepPercepts() {
        var a1 = state.getEntityByName("A1");
        var a2 = state.getEntityByName("A2");
        state.teleport("A1", Position.of(3, 2));
        state.teleport("A2", Position.of(3, 3));
        var block = state.createBlock(Position.of(3, 4), "b1");
        assert(a1.getPosition().equals(Position.of(3, 2)));
        assert(a2.getPosition().equals(Position.of(3, 3)));
        assert(block != null);
        state.attach(a1.getPosition(), a2.getPosition());
        state.attach(a2.getPosition(), block.getPosition());

        var percept = new StepPercept(((StepPercept)state.getStepPercepts().get("A1")).toJson().getJSONObject("content"));
        assert(percept.attachedThings.contains(a2.getPosition().toLocal(a1.getPosition())));
        assert(percept.attachedThings.contains(block.getPosition().toLocal(a1.getPosition())));
    }

    @org.junit.Test
    public void clearArea() {
        var a1 = state.getEntityByName("A1");
        state.teleport("A1", Position.of(10, 10));
        var block1 = state.createBlock(Position.of(10,11), "b1");
        var block2 = state.createBlock(Position.of(10,12), "b1");
        state.setTerrain(Position.of(11,10), Terrain.OBSTACLE);

        state.clearArea(Position.of(10,10), 1);

        assert(a1.isDisabled());
        assert(state.getThingsAt(block1.getPosition()).size() == 0);
        assert(state.getThingsAt(block2.getPosition()).size() == 1);
    }

    @org.junit.Test
    public void handleClearAction() {
        var a1 = state.getEntityByName("A1");
        var a2 = state.getEntityByName("A2");
        var posA2 = Position.of(22, 20);
        state.teleport(a1.getAgentName(), Position.of(20, 20));
        state.teleport("A2", posA2);
        var block = state.createBlock(Position.of(21, 20), "b1");

        assert(!a2.isDisabled());
        int i;
        for (i = 0; i < state.clearSteps - 1; i++) {
            state.prepareStep(i);
            assert(state.handleClearAction(a1, Position.of(2, 0)).equals(Actions.RESULT_SUCCESS));
            var percept = (StepPercept)state.getStepPercepts().get("A1");
            assert(containsThing(percept.things, Thing.TYPE_MARKER, Position.of(2, 0)));
        }
        state.prepareStep(i++);
        assert(state.handleClearAction(a1, Position.of(2, 0)).equals(Actions.RESULT_SUCCESS));
        assert(!state.getThingsAt(block.getPosition()).contains(block));
        assert(a2.isDisabled());
        for (var j = 0; j < Entity.disableDuration; j++) {
            assert(a2.isDisabled());
            state.prepareStep(i + j);
        }
        assert(!a2.isDisabled());
    }

    private static boolean containsThing(Collection<Thing> things, String type, Position pos) {
        return things.stream().anyMatch(t -> t.type.equals(type) && t.x == pos.x && t.y == pos.y);
    }
}
/*
 * Copyright (C) 2014-2015 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.state;

import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.asset.AssetManager;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityData;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.console.ConsoleState;
import toniarts.openkeeper.game.controller.player.PlayerCreatureControl;
import toniarts.openkeeper.game.controller.player.PlayerCreatureControl.CreatureUIState;
import toniarts.openkeeper.game.controller.player.PlayerGoldControl;
import toniarts.openkeeper.game.controller.player.PlayerRoomControl;
import toniarts.openkeeper.game.controller.player.PlayerSpell;
import toniarts.openkeeper.game.controller.player.PlayerSpellControl;
import toniarts.openkeeper.game.controller.player.PlayerStatsControl;
import toniarts.openkeeper.game.data.GameResult;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.listener.PlayerListener;
import toniarts.openkeeper.game.map.MapTile;
import toniarts.openkeeper.tools.convert.map.Creature;
import toniarts.openkeeper.tools.convert.map.Door;
import toniarts.openkeeper.tools.convert.map.KwdFile;
import toniarts.openkeeper.tools.convert.map.Player;
import toniarts.openkeeper.tools.convert.map.Room;
import toniarts.openkeeper.tools.convert.map.Trap;
import toniarts.openkeeper.tools.convert.map.TriggerAction;
import toniarts.openkeeper.view.PlayerCameraState;
import toniarts.openkeeper.view.PlayerInteractionState;
import toniarts.openkeeper.view.PlayerInteractionState.InteractionState;
import toniarts.openkeeper.view.PossessionCameraState;
import toniarts.openkeeper.view.PossessionInteractionState;
import toniarts.openkeeper.view.SystemMessageState;
import toniarts.openkeeper.world.MapLoader;
import toniarts.openkeeper.world.WorldState;
import toniarts.openkeeper.world.creature.CreatureControl;
import toniarts.openkeeper.world.object.GoldObjectControl;
import toniarts.openkeeper.world.room.GenericRoom;
import toniarts.openkeeper.world.room.RoomInstance;

/**
 * The player state! GUI, camera, etc. Player interactions. TODO: shouldn't
 * really be persistent, only persistent due to Nifty screen, these screen
 * controllers could be just the persistent ones
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public class PlayerState extends AbstractAppState implements PlayerListener {

    protected Main app;

    protected AssetManager assetManager;
    protected AppStateManager stateManager;

    private short playerId;
    private KwdFile kwdFile;
    private EntityData entityData;

    private boolean paused = false;

    private final List<AbstractPauseAwareState> appStates = new ArrayList<>();
    protected PlayerInteractionState interactionState;
    private PossessionInteractionState possessionState;
    protected PlayerCameraState cameraState;
    private PossessionCameraState possessionCameraState;

    private boolean transitionEnd = true;
    private PlayerScreenController screen;

    private static final Logger LOGGER = Logger.getLogger(PlayerState.class.getName());

    public PlayerState(int playerId, Main app) {
        this.playerId = (short) playerId;

        screen = new PlayerScreenController(this, app.getNifty());
        app.getNifty().registerScreenController(screen);
    }

    public PlayerState(int playerId, boolean enabled, Main app) {
        this(playerId, app);
        super.setEnabled(enabled);
    }

    @Override
    public void initialize(final AppStateManager stateManager, final Application app) {
        super.initialize(stateManager, app);

        this.app = (Main) app;
        assetManager = this.app.getAssetManager();
        this.stateManager = stateManager;
    }

    @Override
    public void cleanup() {

        // Detach states
        for (AbstractAppState state : appStates) {
            stateManager.detach(state);
        }

        super.cleanup();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);

        if (!isInitialized()) {
            return;
        }

        if (enabled) {

            // Get the game state
            final GameClientState gameState = stateManager.getState(GameClientState.class);
            screen.initHud(gameState.getLevelData().getGameLevel().getTextTableId().getLevelDictFile());

            // Cursor
            app.getInputManager().setCursorVisible(true);

            // Set the pause state
            paused = false;
            screen.setPause(paused);

            stateManager.attach(new ConsoleState());

            // Create app states
            Player player = gameState.getLevelData().getPlayer(playerId);

            possessionCameraState = new PossessionCameraState(false);
            possessionState = new PossessionInteractionState(false) {
                @Override
                protected void onExit() {
                    // Enable states
                    for (AbstractAppState state : appStates) {
                        if (state instanceof PossessionInteractionState
                                || state instanceof PossessionCameraState) {
                            continue;
                        }
                        state.setEnabled(true);
                    }

                    screen.goToScreen(PlayerScreenController.HUD_SCREEN_ID);
                }

                @Override
                protected void onActionChange(PossessionInteractionState.Action action) {
                    PlayerState.this.screen.updatePossessionSelectedItem(action);
                }
            };
            appStates.add(possessionCameraState);
            appStates.add(possessionState);

            cameraState = new PlayerCameraState(player);
            interactionState = new PlayerInteractionState(player, kwdFile, entityData) {
                @Override
                protected void onInteractionStateChange(InteractionState interactionState) {
                    PlayerState.this.screen.updateSelectedItem(interactionState);
                }

                @Override
                protected void onPossession(CreatureControl creature) {
                    // Disable states
                    for (AbstractAppState state : appStates) {
                        if (state instanceof PossessionInteractionState
                                || state instanceof PossessionCameraState) {
                            continue;
                        }
                        state.setEnabled(false);
                    }
                    // Enable state
                    possessionState.setTarget(creature);
                    possessionState.setEnabled(true);

                    screen.goToScreen(PlayerScreenController.POSSESSION_SCREEN_ID);
                }
            };
            appStates.add(cameraState);
            appStates.add(interactionState);
            // FIXME add to appStates or directly to stateManager
            appStates.add(new SystemMessageState(screen.getMessages()));
            // Load the state
            for (AbstractAppState state : appStates) {
                stateManager.attach(state);
            }
        } else {

            // Detach states
            for (AbstractAppState state : appStates) {
                stateManager.detach(state);
            }
            stateManager.detach(stateManager.getState(ConsoleState.class));

            appStates.clear();
            screen.goToScreen(PlayerScreenController.SCREEN_EMPTY_ID);
        }
    }

    public KwdFile getKwdFile() {
        return kwdFile;
    }

    public void setKwdFile(KwdFile kwdFile) {
        this.kwdFile = kwdFile;
    }

    public EntityData getEntityData() {
        return entityData;
    }

    public void setEntityData(EntityData entityData) {
        this.entityData = entityData;
    }

    public PlayerScreenController getScreen() {
        return screen;
    }

    /**
     * Get this player
     *
     * @return this player
     */
    public Keeper getPlayer() {
        GameClientState gs = stateManager.getState(GameClientState.class);
        if (gs != null) {
            return gs.getPlayer(playerId);
        }
        return null;
    }

    public PlayerStatsControl getStatsControl() {
        Keeper keeper = getPlayer();
//        if (keeper != null) {
//            return keeper.getStatsControl();
//        }
        return null;
    }

    public PlayerGoldControl getGoldControl() {
        Keeper keeper = getPlayer();
//        if (keeper != null) {
//            return keeper.getGoldControl();
//        }
        return null;
    }

    public PlayerCreatureControl getCreatureControl() {
        Keeper keeper = getPlayer();
//        if (keeper != null) {
//            return keeper.getCreatureControl();
//        }
        return null;
    }

    public PlayerRoomControl getRoomControl() {
        Keeper keeper = getPlayer();
//        if (keeper != null) {
//            return keeper.getRoomControl();
//        }
        return null;
    }

    public PlayerSpellControl getSpellControl() {
        Keeper keeper = getPlayer();
//        if (keeper != null) {
//            return keeper.getSpellControl();
//        }
        return null;
    }

    public void setTransitionEnd(boolean value) {
        transitionEnd = value;
    }

    public boolean isTransitionEnd() {
        return transitionEnd;
    }

    public void setWideScreen(boolean enable) {
        if (interactionState.isInitialized()) {
            interactionState.setEnabled(!enable);
        }

        if (enable) {
            screen.goToScreen(PlayerScreenController.CINEMATIC_SCREEN_ID);
        } else {
            screen.goToScreen(PlayerScreenController.HUD_SCREEN_ID);
        }
    }

    public void setText(int textId, boolean introduction, int pathId) {
        screen.setCinematicText(textId, introduction, pathId);
    }

    public void setGeneralText(int textId) {
        screen.setCinematicText(textId);
    }

    public void flashButton(int id, TriggerAction.MakeType type, boolean enabled, int time) {
        // TODO make flash button
    }

    protected List<Room> getAvailableRoomsToBuild() {

        // TODO: cache, or something, maybe add the listeners here
        GameClientState gameState = stateManager.getState(GameClientState.class);
        Keeper keeper = getPlayer();
        List<Room> rooms = new ArrayList<>();
        keeper.getAvailableRooms().stream().forEach((id) -> {
            rooms.add(gameState.getLevelData().getRoomById(id));
        });
        return rooms;
    }

    protected List<Door> getAvailableDoors() {
        GameClientState gameState = stateManager.getState(GameClientState.class);
        List<Door> doors = gameState.getLevelData().getDoors();
        return doors;
    }

    protected List<Trap> getAvailableTraps() {
        GameClientState gameState = stateManager.getState(GameClientState.class);
        List<Trap> traps = new ArrayList<>();
        for (Trap trap : gameState.getLevelData().getTraps()) {
            if (trap.getGuiIcon() == null) {
                continue;
            }
            traps.add(trap);
        }
        return traps;
    }

    public void setPaused(boolean paused) {

        // Pause / unpause
        // TODO: We should give the client to these states to self register to the client service listener...
        // But PlayerState is persistent.. I don't want to have the reference here
        if (paused) {
            stateManager.getState(GameClientState.class).getGameClientService().pauseGame();
        } else {
            stateManager.getState(GameClientState.class).getGameClientService().resumeGame();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public void onPaused(boolean paused) {

        // Pause state
        this.paused = paused;

        // Delegate to the screen
        screen.onPaused(paused);

        for (AbstractPauseAwareState state : appStates) {
            if (state.isPauseable()) {
                state.setEnabled(!paused);
            }
        }
    }

    public void quitToMainMenu() {

        // Disable us, detach game and enable start
        stateManager.getState(GameState.class).detach();
        setEnabled(false);
        stateManager.getState(MainMenuState.class).setEnabled(true);
    }

    public void quitToDebriefing() {
        // TODO copy results of game from GameState
        GameState gs = stateManager.getState(GameState.class);
        GameResult result = gs.getGameResult();
        gs.detach();
        setEnabled(false);
        stateManager.getState(MainMenuState.class).doDebriefing(result);
    }

    public void quitToOS() {
        app.stop();
    }

    public void zoomToDungeon() {
        MapLoader mapLoader = stateManager.getState(WorldState.class).getMapLoader();
        for (Map.Entry<RoomInstance, GenericRoom> en : mapLoader.getRoomActuals().entrySet()) {
            RoomInstance key = en.getKey();
            GenericRoom value = en.getValue();
            if (value.isDungeonHeart() && key.getOwnerId() == playerId) {
                cameraState.setCameraLookAt(key.getCenter());
                break;
            }
        }
    }

    public void zoomToCreature(short creatureId, CreatureUIState uiState) {
        Creature creature = stateManager.getState(GameState.class).getLevelData().getCreature(creatureId);
        CreatureControl creatureControl = getCreatureControl().getNextCreature(creature, uiState);

        zoomToCreature(creatureControl);
    }

    /**
     * Zoom to given creature
     *
     * @param creature creature to zoom to
     */
    public void zoomToCreature(CreatureControl creature) {
        cameraState.setCameraLookAt(creature.getSpatial());
    }

    void pickUpCreature(short creatureId, CreatureUIState uiState) {
        Creature creature = stateManager.getState(GameState.class).getLevelData().getCreature(creatureId);
        CreatureControl creatureControl = getCreatureControl().getCreature(creature, uiState);

        //interactionState.pickupObject(creatureControl);
    }

    public short getPlayerId() {
        return playerId;
    }

    public void setPlayerId(short playerId) {
        this.playerId = playerId;
    }

    protected Creature getPossessionCreature() {
        return possessionState.getTarget().getCreature();
    }

    protected InteractionState getInteractionState() {
        return interactionState.getInteractionState();
    }

    public void rotateViewAroundPoint(Vector3f point, boolean relative, int angle, int time) {
        cameraState.rotateAroundPoint(point, relative, angle, time);
    }

    public void showMessage(int textId) {
        stateManager.getState(SystemMessageState.class).addMessage(SystemMessageState.MessageType.INFO, String.format("${level.%d}", textId));
    }

    public void zoomToPoint(Vector3f point) {
        cameraState.zoomToPoint(point);
    }

    protected void grabGold(int amount) {
        if (!interactionState.isKeeperHandFull()) {
            WorldState ws = stateManager.getState(WorldState.class);
            int left = ws.substractGoldFromPlayer(amount, playerId);
            int goldSubstracted = amount - left;

            // FIXME: questionable way of creating gold
            GoldObjectControl goc = ws.getThingLoader().addRoomGold(new Point(0, 0),
                    playerId, goldSubstracted, goldSubstracted);
            if (goc != null) {
                //interactionState.pickupObject(goc);
            }
        }
    }

    /**
     * End the game for the player
     *
     * @param win did we win or not
     */
    protected void endGame(boolean win) {

        // Disable us to get rid of all interaction
        setEnabled(false);
        stateManager.attach(new EndGameState(this));
    }

    String getTooltipText(String text) {
        int dungeonHealth = 0;
        int paydayCost = 0;
        MapLoader mapLoader = stateManager.getState(WorldState.class).getMapLoader();
        for (Map.Entry<RoomInstance, GenericRoom> en : mapLoader.getRoomActuals().entrySet()) {
            RoomInstance key = en.getKey();
            GenericRoom value = en.getValue();
            if (value.isDungeonHeart() && key.getOwnerId() == playerId) {
                dungeonHealth = key.getHealthPercentage();
                break;
            }
        }
        // TODO payday cost calculator
        return text.replaceAll("%19%", String.valueOf(dungeonHealth))
                .replaceAll("%20", String.valueOf(paydayCost));
    }

    @Override
    public void onAdded(PlayerSpell spell) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void onRemoved(PlayerSpell spell) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void onResearchStatusChanged(PlayerSpell spell) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void onGoldChange(short keeperId, int gold) {
        screen.setGold(gold);
    }

    @Override
    public void onManaChange(short keeperId, int mana, int manaLoose, int manaGain) {
        screen.setMana(mana, manaLoose, manaGain);
    }

    @Override
    public void onBuild(short keeperId, List<MapTile> tiles) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void onSold(short keeperId, List<MapTile> tiles) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
}

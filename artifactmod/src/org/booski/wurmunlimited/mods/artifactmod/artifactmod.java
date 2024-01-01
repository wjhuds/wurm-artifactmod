package org.booski.wurmunlimited.mods.artifactmod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javassist.CtClass;
import javassist.CtPrimitiveType;
import javassist.bytecode.Descriptor;

import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.classhooks.InvocationHandlerFactory;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.PlayerMessageListener;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;

import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Constants;
import com.wurmonline.server.DbConnector;
import com.wurmonline.server.HistoryManager;
import com.wurmonline.server.Items;
import com.wurmonline.server.NoSuchItemException;
import com.wurmonline.server.NoSuchPlayerException;
import com.wurmonline.server.Server;
import com.wurmonline.server.Servers;
import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.NoSuchCreatureException;
import com.wurmonline.server.endgames.EndGameItem;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.NotOwnedException;
import com.wurmonline.server.utils.DbUtilities;
import com.wurmonline.server.zones.FocusZone;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;

public class artifactmod implements WurmServerMod, Configurable, Initable, PlayerMessageListener {

	private int decayPerWeek = 10;
	private Logger logger = Logger.getLogger(this.getClass().getName());
	
	//overriding EndGameItems.class functionality
	private static final Map<Long, EndGameItem> artifacts = new HashMap<Long, EndGameItem>();
	
	public String getVersion() {
		return "v0.1";
	}
	
	@Override
  	public void configure(Properties properties) {
  		decayPerWeek = Integer.parseInt(properties.getProperty("decayPerWeek", Integer.toString(decayPerWeek)));
  		logger.log(Level.INFO, "decayPerWeek: " + decayPerWeek);
  	}
	
	@Override
	public boolean onPlayerMessage(Communicator communicator, String message) {
		if (message != null && message.startsWith("/modtest")) {
			communicator.sendSafeServerMessage("artifactmod test: " + message.replace("/modtest ", ""));
			return true;
		}
		return false;
	}

	@Override
	public void init() {
		if (decayPerWeek != 10) {
			try {
				InvocationHandlerFactory invoker = new InvocationHandlerFactory() {
					@Override
					public InvocationHandler createInvocationHandler() {
						return new InvocationHandler() {
							@Override
							public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {	//replacement code goes here
								loadEndGameItems();
								pollAll();
								logger.info("Successfully replaced pollAll");
								return null;
							}
						};
					}
				};
				
				CtClass[] paramTypes = {};	//empty because the pollAll method uses no args
				HookManager.getInstance().registerHook(
					"com.wurmonline.server.endgames.EndGameItems",
					"pollAll",
					Descriptor.ofMethod(CtPrimitiveType.voidType, paramTypes),
					invoker
				);
				
			} catch (Exception ex) {
				logger.severe("error initializing artifactmod! error: " + ex.getMessage());
			}
		}
	}
	
	private void loadEndGameItems() {
		logger.info("Loading End Game Items (artifactmod).");
		
		if (Servers.localServer.id == 3 || Servers.localServer.id == 12 || Servers.localServer.isChallengeServer() || (
			Server.getInstance().isPS() && Constants.loadEndGameItems)) {
			
			Connection dbcon = null;
			PreparedStatement ps = null;
			ResultSet rs = null;
			
			try {
				dbcon = DbConnector.getItemDbCon();
				ps = dbcon.prepareStatement("SELECT * FROM ENDGAMEITEMS");
				rs = ps.executeQuery();
				long iid = -10L;
				boolean holy = true;
				short type = 0;
				long lastMoved = 0L;
				while (rs.next()) {
					
					iid = rs.getLong("WURMID");
					holy = rs.getBoolean("HOLY");
					type = rs.getShort("TYPE");
					lastMoved = rs.getLong("LASTMOVED");
					
					try {
						Item item = Items.getItem(iid);
				        EndGameItem eg = new EndGameItem(item, holy, type, false);
				        eg.setLastMoved(lastMoved);
				        if (type == 69) {
				          artifacts.put(new Long(iid), eg);
				          if (logger.isLoggable(Level.FINE))
				          {
				            logger.fine("Loaded Artifact, ID: " + iid + ", " + eg);
				          }
				          continue;
				        } 
					}
					catch (NoSuchItemException nsi) {
				    } 
				} 
				DbUtilities.closeDatabaseObjects(ps, rs);
			}
			catch (SQLException sqx) {
				logger.log(Level.WARNING, "Failed to load item datas: " + sqx.getMessage(), sqx);
			}
			finally {
				DbUtilities.closeDatabaseObjects(ps, rs);
				DbConnector.returnConnection(dbcon);
			} 
		} 
	}
	
	public final void pollAll() {
		logger.info("running pollAll() with replaced invocation");
		EndGameItem[] arts = (EndGameItem[])artifacts.values().toArray(new EndGameItem[artifacts.size()]);
		
		for (EndGameItem lArt : arts) {
			if (lArt.isInWorld() &&
				//System.currentTimeMillis() - lArt.getLastMoved() > (Servers.isThisATestServer() ? 60000L : 604800000L))
				System.currentTimeMillis() - lArt.getLastMoved() > (Servers.isThisATestServer() ? 60000L : 3600000L))
			{
				lArt.setLastMoved(System.currentTimeMillis());
				
				Item artifact = lArt.getItem();
				if (artifact.getAuxData() <= 0) {
					moveArtifact(artifact);
				}
				else {
					artifact.setAuxData((byte)Math.max(0, artifact.getAuxData() - decayPerWeek));
					
					try {
						if (artifact.getOwner() != -10L)
						{
							Creature owner = Server.getInstance().getCreature(artifact.getOwner());
							owner.getCommunicator().sendNormalServerMessage(artifact.getName() + " vibrates faintly.");
						}
					}
					catch (NoSuchCreatureException noSuchCreatureException) {}
					catch (NoSuchPlayerException noSuchPlayerException) {}
					catch (NotOwnedException notOwnedException) {}
				}
			}
		}
	}
	
	private final void moveArtifact(Item artifact) {
		String act;
		
		try {
			if (artifact.getOwner() != -10L)
			{
				Creature owner = Server.getInstance().getCreature(artifact.getOwner());
				owner.getCommunicator().sendNormalServerMessage(
					artifact.getName() + " disappears. It has fulfilled its mission."
				);
			}
		}
		catch (NoSuchCreatureException noSuchCreatureException) {}
		catch (NoSuchPlayerException noSuchPlayerException) {}
		catch (NotOwnedException notOwnedException) {}
		
		switch (Server.rand.nextInt(6)) {
			case 0:
				act = "is reported to have disappeared.";
				break;
			case 1:
				act = "is gone missing.";
				break;
			case 2:
				act = "returned to the depths.";
				break;
			case 3:
				act = "seems to have decided to leave.";
				break;
			case 4:
				act = "has found a new location.";
				break;
			default:
				act = "has vanished.";
				break;
		}
		
		HistoryManager.addHistory("The " + artifact.getName(), act);
		artifact.putInVoid();
		placeArtifact(artifact);
	}
	
	public final void placeArtifact(Item artifact) {
		boolean found = false;
		
		while (!found) {
			int x = Server.rand.nextInt(Zones.worldTileSizeX);
			int y = Server.rand.nextInt(Zones.worldTileSizeX);
			int tile = Server.surfaceMesh.getTile(x, y);
			int rocktile = Server.rockMesh.getTile(x, y);
			float th = Tiles.decodeHeightAsFloat(tile);
			float rh = Tiles.decodeHeightAsFloat(rocktile);

			FocusZone hoderZone = FocusZone.getHotaZone();
			assert hoderZone != null;
			float seth = 0.0F;
			
			if (th > 4.0F && rh > 4.0F) {
				if (th - rh >= 1.0F)
					seth = Math.max(1, Server.rand.nextInt((int)(th * 10.0F - 5.0F - rh * 10.0F)));
				if (seth > 0.0F) {
					VolaTile t = Zones.getTileOrNull(x, y, true);
					if (t == null || (t.getStructure() == null && t.getVillage() == null && t.getZone() != hoderZone)) {
						seth /= 10.0F;
						found = true;
						artifact.setPosXYZ(((x << 2) + 2), ((y << 2) + 2), rh + seth);
						artifact.setAuxData((byte)30);
						logger.log(Level.INFO, "Placed " + artifact.getName() + " at " + x + "," + y + " at height " + (rh + seth) + " rockheight=" + rh + " tileheight=" + th);
					}
				}
			}
		}
	}
}
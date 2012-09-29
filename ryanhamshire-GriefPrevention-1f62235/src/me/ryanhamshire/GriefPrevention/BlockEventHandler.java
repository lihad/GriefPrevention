/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2012 Ryan Hamshire

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ryanhamshire.GriefPrevention;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

//event handlers related to blocks
public class BlockEventHandler implements Listener 
{
	//convenience reference to singleton datastore
	private DataStore dataStore;
	
	private ArrayList<Material> trashBlocks;
	
	//constructor
	public BlockEventHandler(DataStore dataStore)
	{
		this.dataStore = dataStore;
		
		//create the list of blocks which will not trigger a warning when they're placed outside of land claims
		this.trashBlocks = new ArrayList<Material>();
		this.trashBlocks.add(Material.COBBLESTONE);
		this.trashBlocks.add(Material.TORCH);
		this.trashBlocks.add(Material.DIRT);
		this.trashBlocks.add(Material.SAPLING);
		this.trashBlocks.add(Material.GRAVEL);
		this.trashBlocks.add(Material.SAND);
		this.trashBlocks.add(Material.TNT);
		this.trashBlocks.add(Material.WORKBENCH);
	}
	
	//when a block is damaged...
	@EventHandler(ignoreCancelled = true)
	public void onBlockDamaged(BlockDamageEvent event)
	{
		//if placing items in protected chests isn't enabled, none of this code needs to run
		if(!GriefPrevention.instance.config_addItemsToClaimedChests) return;
		
		Block block = event.getBlock();
		Player player = event.getPlayer();
		
		//only care about player-damaged blocks
		if(player == null) return;
		
		//FEATURE: players may add items to a chest they don't have permission for by hitting it
		
		//if it's a chest
		if(block.getType() == Material.CHEST)
		{
			//only care about non-creative mode players, since those would outright break the box in one hit
			if(player.getGameMode() == GameMode.CREATIVE) return;
			
			//only care if the player has an itemstack in hand
			PlayerInventory playerInventory = player.getInventory();
			ItemStack stackInHand = playerInventory.getItemInHand();
			if(stackInHand == null || stackInHand.getType() == Material.AIR) return;
			
			//only care if the chest is in a claim, and the player does not have access to the chest
			Claim claim = this.dataStore.getClaimAt(block.getLocation(), false, null);
			if(claim == null || claim.allowContainers(player) == null) return;
			
			//if the player is under siege, he can't give away items
			PlayerData playerData = this.dataStore.getPlayerData(event.getPlayer().getName());
			if(playerData.siegeData != null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.SiegeNoDrop);
				event.setCancelled(true);
				return;
			}
			
			//NOTE: to eliminate accidental give-aways, first hit on a chest displays a confirmation message
			//subsequent hits donate item to the chest
			
			//if first time damaging this chest, show confirmation message
			if(playerData.lastChestDamageLocation == null || !block.getLocation().equals(playerData.lastChestDamageLocation))
			{
				//remember this location
				playerData.lastChestDamageLocation = block.getLocation();
				
				//give the player instructions
				GriefPrevention.sendMessage(player, TextMode.Instr, Messages.DonateItemsInstruction);
			}
			
			//otherwise, try to donate the item stack in hand
			else
			{
				//look for empty slot in chest
				Chest chest = (Chest)block.getState();
				Inventory chestInventory = chest.getInventory();
				int availableSlot = chestInventory.firstEmpty();
				
				//if there isn't one
				if(availableSlot < 0)
				{
					//tell the player and stop here
					GriefPrevention.sendMessage(player, TextMode.Err, Messages.ChestFull);
					
					return;
				}
				
				//otherwise, transfer item stack from player to chest
				//NOTE: Inventory.addItem() is smart enough to add items to existing stacks, making filling a chest with garbage as a grief very difficult
				chestInventory.addItem(stackInHand);
				playerInventory.setItemInHand(new ItemStack(Material.AIR));
				
				//and confirm for the player
				GriefPrevention.sendMessage(player, TextMode.Success, Messages.DonationSuccess);
			}
		}
	}
	
	//when a player breaks a block...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onBlockBreak(BlockBreakEvent breakEvent)
	{
		Player player = breakEvent.getPlayer();
		Block block = breakEvent.getBlock();		
		
		//make sure the player is allowed to break at the location
		String noBuildReason = GriefPrevention.instance.allowBreak(player, block.getLocation());
		if(noBuildReason != null)
		{
			GriefPrevention.sendMessage(player, TextMode.Err, noBuildReason);
			breakEvent.setCancelled(true);
			return;
		}
		
		PlayerData playerData = this.dataStore.getPlayerData(player.getName());
		Claim claim = this.dataStore.getClaimAt(block.getLocation(), true, playerData.lastClaim);
		
		//if there's a claim here
		if(claim != null)
		{
			//if breaking UNDER the claim and the player has permission to build in the claim
			if(block.getY() < claim.lesserBoundaryCorner.getBlockY() && claim.allowBuild(player) == null)
			{
				//extend the claim downward beyond the breakage point
				this.dataStore.extendClaim(claim, claim.getLesserBoundaryCorner().getBlockY() - GriefPrevention.instance.config_claims_claimsExtendIntoGroundDistance);
			}
		}
	}
	
	//when a player places a sign...
	@EventHandler(ignoreCancelled = true)
	public void onSignChanged(SignChangeEvent event)
	{
		Player player = event.getPlayer();
		if(player == null) return;
		
		StringBuilder lines = new StringBuilder();
		boolean notEmpty = false;
		for(int i = 0; i < event.getLines().length; i++)
		{
			if(event.getLine(i).length() != 0) notEmpty = true;
			lines.append(event.getLine(i) + ";");
		}
		
		String signMessage = lines.toString();
		
		//if not empty and wasn't the same as the last sign, log it and remember it for later
		PlayerData playerData = this.dataStore.getPlayerData(player.getName());
		if(notEmpty && playerData.lastMessage != null && !playerData.lastMessage.equals(signMessage))
		{		
			GriefPrevention.AddLogEntry("[Sign Placement] <" + player.getName() + "> " + lines.toString() + " @ " + GriefPrevention.getfriendlyLocationString(event.getBlock().getLocation()));
			playerData.lastMessage = signMessage;
		}
	}
	
	//when a player places a block...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
	public void onBlockPlace(BlockPlaceEvent placeEvent)
	{
		Player player = placeEvent.getPlayer();
		Block block = placeEvent.getBlock();
		
		//FEATURE: limit fire placement, to prevent PvP-by-fire
		
		//if placed block is fire and pvp is off, apply rules for proximity to other players 
		if(block.getType() == Material.FIRE && !block.getWorld().getPVP() && !player.hasPermission("griefprevention.lava"))
		{
			List<Player> players = block.getWorld().getPlayers();
			for(int i = 0; i < players.size(); i++)
			{
				Player otherPlayer = players.get(i);
				Location location = otherPlayer.getLocation();
				if(!otherPlayer.equals(player) && location.distanceSquared(block.getLocation()) < 9)
				{
					GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerTooCloseForFire, otherPlayer.getName());
					placeEvent.setCancelled(true);
					return;
				}					
			}
		}
		
		//make sure the player is allowed to build at the location
		String noBuildReason = GriefPrevention.instance.allowBuild(player, block.getLocation());
		if(noBuildReason != null)
		{
			GriefPrevention.sendMessage(player, TextMode.Err, noBuildReason);
			placeEvent.setCancelled(true);
			return;
		}
		
		//if the block is being placed within an existing claim
		PlayerData playerData = this.dataStore.getPlayerData(player.getName());
		Claim claim = this.dataStore.getClaimAt(block.getLocation(), true, playerData.lastClaim);
		if(claim != null)
		{
			//if the player has permission for the claim and he's placing UNDER the claim
			if(block.getY() < claim.lesserBoundaryCorner.getBlockY() && claim.allowBuild(player) == null)
			{
				//extend the claim downward
				this.dataStore.extendClaim(claim, claim.getLesserBoundaryCorner().getBlockY() - GriefPrevention.instance.config_claims_claimsExtendIntoGroundDistance);
			}
			
			//reset the counter for warning the player when he places outside his claims
			playerData.unclaimedBlockPlacementsUntilWarning = 1;
		}
		
		//FEATURE: automatically create a claim when a player who has no claims places a chest
		
		//otherwise if there's no claim, the player is placing a chest, and new player automatic claims are enabled
		else if(block.getType() == Material.CHEST && GriefPrevention.instance.config_claims_automaticClaimsForNewPlayersRadius > -1 && GriefPrevention.instance.claimsEnabledForWorld(block.getWorld()))
		{			
			//if the chest is too deep underground, don't create the claim and explain why
			if(GriefPrevention.instance.config_claims_preventTheft && block.getY() < GriefPrevention.instance.config_claims_maxDepth)
			{
				GriefPrevention.sendMessage(player, TextMode.Warn, Messages.TooDeepToClaim);
				return;
			}
			
			int radius = GriefPrevention.instance.config_claims_automaticClaimsForNewPlayersRadius;
			
			//if the player doesn't have any claims yet, automatically create a claim centered at the chest
			if(playerData.claims.size() == 0)
			{
				//radius == 0 means protect ONLY the chest
				if(GriefPrevention.instance.config_claims_automaticClaimsForNewPlayersRadius == 0)
				{					
					this.dataStore.createClaim(block.getWorld(), block.getX(), block.getX(), block.getY(), block.getY(), block.getZ(), block.getZ(), player.getName(), null, null);
					GriefPrevention.sendMessage(player, TextMode.Success, Messages.ChestClaimConfirmation);						
				}
				
				//otherwise, create a claim in the area around the chest
				else
				{
					//as long as the automatic claim overlaps another existing claim, shrink it
					//note that since the player had permission to place the chest, at the very least, the automatic claim will include the chest
					while(radius >= 0 && !this.dataStore.createClaim(block.getWorld(), 
							block.getX() - radius, block.getX() + radius, 
							block.getY() - GriefPrevention.instance.config_claims_claimsExtendIntoGroundDistance, block.getY(), 
							block.getZ() - radius, block.getZ() + radius, 
							player.getName(), 
							null, null).succeeded)
					{
						radius--;
					}
					
					//notify and explain to player
					GriefPrevention.sendMessage(player, TextMode.Success, Messages.AutomaticClaimNotification);
					
					//show the player the protected area
					Claim newClaim = this.dataStore.getClaimAt(block.getLocation(), false, null);
					Visualization visualization = Visualization.FromClaim(newClaim, block.getY(), VisualizationType.Claim, player.getLocation());
					Visualization.Apply(player, visualization);
				}
				
				//instructions for using /trust
				GriefPrevention.sendMessage(player, TextMode.Instr, Messages.TrustCommandAdvertisement);
				
				//unless special permission is required to create a claim with the shovel, educate the player about the shovel
				if(!GriefPrevention.instance.config_claims_creationRequiresPermission)
				{
					GriefPrevention.sendMessage(player, TextMode.Instr, Messages.GoldenShovelAdvertisement);
				}
			}
			
			//check to see if this chest is in a claim, and warn when it isn't
			if(GriefPrevention.instance.config_claims_preventTheft && this.dataStore.getClaimAt(block.getLocation(), false, playerData.lastClaim) == null)
			{
				GriefPrevention.sendMessage(player, TextMode.Warn, Messages.UnprotectedChestWarning);				
			}
		}
		
		//FEATURE: limit wilderness tree planting to grass, or dirt with more blocks beneath it
		else if(block.getType() == Material.SAPLING && GriefPrevention.instance.config_blockSkyTrees)
		{
			Block earthBlock = placeEvent.getBlockAgainst();
			if(earthBlock.getType() != Material.GRASS)
			{
				if(earthBlock.getRelative(BlockFace.DOWN).getType() == Material.AIR || 
				   earthBlock.getRelative(BlockFace.DOWN).getRelative(BlockFace.DOWN).getType() == Material.AIR)
				{
					placeEvent.setCancelled(true);
				}
			}
		}	
		
		//FEATURE: warn players when they're placing non-trash blocks outside of their claimed areas
		else if(GriefPrevention.instance.config_claims_warnOnBuildOutside && !this.trashBlocks.contains(block.getType()) && GriefPrevention.instance.claimsEnabledForWorld(block.getWorld()) && playerData.claims.size() > 0)
		{
			if(--playerData.unclaimedBlockPlacementsUntilWarning <= 0)
			{
				GriefPrevention.sendMessage(player, TextMode.Warn, Messages.BuildingOutsideClaims);
				playerData.unclaimedBlockPlacementsUntilWarning = 15;
				
				if(playerData.lastClaim != null && playerData.lastClaim.allowBuild(player) == null)
				{
					Visualization visualization = Visualization.FromClaim(playerData.lastClaim, block.getY(), VisualizationType.Claim, player.getLocation());
					Visualization.Apply(player, visualization);
				}
			}
		}
	}
	
	//blocks "pushing" other players' blocks around (pistons)
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onBlockPistonExtend (BlockPistonExtendEvent event)
	{		
		List<Block> blocks = event.getBlocks();
		
		//if no blocks moving, then only check to make sure we're not pushing into a claim from outside
		//this avoids pistons breaking non-solids just inside a claim, like torches, doors, and touchplates
		if(blocks.size() == 0)
		{
			Block pistonBlock = event.getBlock();
			Block invadedBlock = pistonBlock.getRelative(event.getDirection());
			
			if(	this.dataStore.getClaimAt(pistonBlock.getLocation(), false, null) == null && 
				this.dataStore.getClaimAt(invadedBlock.getLocation(), false, null) != null)
			{
				event.setCancelled(true);				
			}
			
			return;
		}
		
		//who owns the piston, if anyone?
		String pistonClaimOwnerName = "_";
		Claim claim = this.dataStore.getClaimAt(event.getBlock().getLocation(), false, null);
		if(claim != null) pistonClaimOwnerName = claim.getOwnerName();
		
		//which blocks are being pushed?
		for(int i = 0; i < blocks.size(); i++)
		{
			//if ANY of the pushed blocks are owned by someone other than the piston owner, cancel the event
			Block block = blocks.get(i);
			claim = this.dataStore.getClaimAt(block.getLocation(), false, null);
			if(claim != null && !claim.getOwnerName().equals(pistonClaimOwnerName))
			{
				event.setCancelled(true);
				event.getBlock().getWorld().createExplosion(event.getBlock().getLocation(), 0);
				event.getBlock().getWorld().dropItem(event.getBlock().getLocation(), new ItemStack(event.getBlock().getType()));
				event.getBlock().setType(Material.AIR);
				return;
			}
		}
		
		//which direction?  note we're ignoring vertical push
		int xchange = 0;
		int zchange = 0;
		
		Block piston = event.getBlock();
		Block firstBlock = blocks.get(0);
		
		if(firstBlock.getX() > piston.getX())
		{
			xchange = 1;
		}
		else if(firstBlock.getX() < piston.getX())
		{
			xchange = -1;
		}
		else if(firstBlock.getZ() > piston.getZ())
		{
			zchange = 1;
		}
		else if(firstBlock.getZ() < piston.getZ())
		{
			zchange = -1; 
		}
		
		//if horizontal movement
		if(xchange != 0 || zchange != 0)
		{
			for(int i = 0; i < blocks.size(); i++)
			{
				Block block = blocks.get(i);
				Claim originalClaim = this.dataStore.getClaimAt(block.getLocation(), false, null);
				String originalOwnerName = "";
				if(originalClaim != null)
				{
					originalOwnerName = originalClaim.getOwnerName();
				}
				
				Claim newClaim = this.dataStore.getClaimAt(block.getLocation().add(xchange, 0, zchange), false, null);
				String newOwnerName = "";
				if(newClaim != null)
				{
					newOwnerName = newClaim.getOwnerName();
				}
				
				//if pushing this block will change ownership, cancel the event and take away the piston (for performance reasons)
				if(!newOwnerName.equals(originalOwnerName))
				{
					event.setCancelled(true);
					event.getBlock().getWorld().createExplosion(event.getBlock().getLocation(), 0);
					event.getBlock().getWorld().dropItem(event.getBlock().getLocation(), new ItemStack(event.getBlock().getType()));
					event.getBlock().setType(Material.AIR);
					return;
				}
				
			}
		}
	}
	
	//blocks theft by pulling blocks out of a claim (again pistons)
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onBlockPistonRetract (BlockPistonRetractEvent event)
	{
		//we only care about sticky pistons
		if(!event.isSticky()) return;
				
		//who owns the moving block, if anyone?
		String movingBlockOwnerName = "_";
		Claim movingBlockClaim = this.dataStore.getClaimAt(event.getRetractLocation(), false, null);
		if(movingBlockClaim != null) movingBlockOwnerName = movingBlockClaim.getOwnerName();
		
		//who owns the piston, if anyone?
		String pistonOwnerName = "_";
		Location pistonLocation = event.getBlock().getLocation();		
		Claim pistonClaim = this.dataStore.getClaimAt(pistonLocation, false, null);
		if(pistonClaim != null) pistonOwnerName = pistonClaim.getOwnerName();
		
		//if there are owners for the blocks, they must be the same player
		//otherwise cancel the event
		if(!pistonOwnerName.equals(movingBlockOwnerName))
		{
			event.setCancelled(true);
		}		
	}
	
	//blocks are ignited ONLY by flint and steel (not by being near lava, open flames, etc), unless configured otherwise
	@EventHandler(priority = EventPriority.LOWEST)
	public void onBlockIgnite (BlockIgniteEvent igniteEvent)
	{
		if(igniteEvent.getCause() != IgniteCause.FLINT_AND_STEEL  && !GriefPrevention.instance.config_fireSpreads) igniteEvent.setCancelled(true);
	}
	
	//fire doesn't spread unless configured to, but other blocks still do (mushrooms and vines, for example)
	@EventHandler(priority = EventPriority.LOWEST)
	public void onBlockSpread (BlockSpreadEvent spreadEvent)
	{
		if(spreadEvent.getSource().getType() == Material.FIRE && !GriefPrevention.instance.config_fireSpreads) spreadEvent.setCancelled(true);
	}
	
	//blocks are not destroyed by fire, unless configured to do so
	@EventHandler(priority = EventPriority.LOWEST)
	public void onBlockBurn (BlockBurnEvent burnEvent)
	{
		if(!GriefPrevention.instance.config_fireDestroys)
		{
			burnEvent.setCancelled(true);
		}
		
		//never burn claimed blocks, regardless of settings
		if(this.dataStore.getClaimAt(burnEvent.getBlock().getLocation(), false, null) != null)
		{
			burnEvent.setCancelled(true);
		}
	}
	
	//ensures fluids don't flow out of claims, unless into another claim where the owner is trusted to build
	private Claim lastSpreadClaim = null;
	
	//ensures dispensers can't be used to dispense a block(like water or lava) or item across a claim boundary
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onDispense(BlockDispenseEvent dispenseEvent)
	{
		//from where?
		Block fromBlock = dispenseEvent.getBlock();
		
		//to where?
		Vector velocity = dispenseEvent.getVelocity();
		int xChange = 0;
		int zChange = 0;
		if(Math.abs(velocity.getX()) > Math.abs(velocity.getZ()))
		{
			if(velocity.getX() > 0) xChange = 1;
			else xChange = -1;				
		}
		else
		{
			if(velocity.getZ() > 0) zChange = 1;
			else zChange = -1;
		}
		
		Block toBlock = fromBlock.getRelative(xChange, 0, zChange);
		
		Claim fromClaim = this.dataStore.getClaimAt(fromBlock.getLocation(), false, null);
		Claim toClaim = this.dataStore.getClaimAt(toBlock.getLocation(), false, fromClaim);
		
		//into wilderness is NOT OK when surface buckets are limited
		Material materialDispensed = dispenseEvent.getItem().getType();
		if((materialDispensed == Material.WATER_BUCKET || materialDispensed == Material.LAVA_BUCKET) && GriefPrevention.instance.config_blockWildernessWaterBuckets && toClaim == null)
		{
			dispenseEvent.setCancelled(true);
			return;
		}
		
		//wilderness to wilderness is OK
		if(fromClaim == null && toClaim == null) return;
		
		//within claim is OK
		if(fromClaim == toClaim) return;
		
		//everything else is NOT OK
		dispenseEvent.setCancelled(true);
	}		
	
	@EventHandler(ignoreCancelled = true)
	public void onTreeGrow (StructureGrowEvent growEvent)
	{
		Location rootLocation = growEvent.getLocation();
		Claim rootClaim = this.dataStore.getClaimAt(rootLocation, false, null);
		String rootOwnerName = null;
		
		//who owns the spreading block, if anyone?
		if(rootClaim != null)
		{
			//tree growth in subdivisions is dependent on who owns the top level claim
			if(rootClaim.parent != null) rootClaim = rootClaim.parent;
			
			//if an administrative claim, just let the tree grow where it wants
			if(rootClaim.isAdminClaim()) return;
			
			//otherwise, note the owner of the claim
			rootOwnerName = rootClaim.getOwnerName();
		}
		
		//for each block growing
		for(int i = 0; i < growEvent.getBlocks().size(); i++)
		{
			BlockState block = growEvent.getBlocks().get(i);
			Claim blockClaim = this.dataStore.getClaimAt(block.getLocation(), false, rootClaim);
			
			//if it's growing into a claim
			if(blockClaim != null)
			{
				//if there's no owner for the new tree, or the owner for the new tree is different from the owner of the claim
				if(rootOwnerName == null  || !rootOwnerName.equals(blockClaim.getOwnerName()))
				{
					growEvent.getBlocks().remove(i--);
				}
			}
		}
	}
}

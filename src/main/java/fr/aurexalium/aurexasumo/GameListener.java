package fr.aurexalium.aurexasumo;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public class GameListener implements Listener {

    private final AurexaSumo plugin;

    public GameListener(AurexaSumo plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Arena a = plugin.getArena(e.getPlayer());
        if (a != null) a.leave(e.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Arena a = plugin.getArena(e.getPlayer());
        if (a != null) a.handleMove(e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        Arena a = plugin.getArena(p);
        if (a != null) a.handleDamage(e, p);
    }

    // ---- rien ne se pose, ne se casse, ne tombe, ne se deplace dans l'inventaire ----

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.getArena(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (plugin.getArena(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player && plugin.getArena((Player) e.getEntity()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (plugin.getArena(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        if (plugin.getArena(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (plugin.getArena((Player) e.getWhoClicked()) != null) e.setCancelled(true);
    }

    // ---- panneaux ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreakSign(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!plugin.isRegisteredSign(b)) return;
        if (e.getPlayer().hasPermission("aurexasumo.admin")) {
            plugin.unregisterSign(b);
            plugin.msg(e.getPlayer(), "&ePanneau AurexaSumo supprimé.");
        } else {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_BLOCK && act != Action.RIGHT_CLICK_AIR) return;

        // clic droit sur un panneau
        if (act == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock().getState() instanceof Sign) {
            Block b = e.getClickedBlock();

            String pending = plugin.takePendingSign(p.getUniqueId());
            if (pending != null) {
                e.setCancelled(true);
                plugin.registerSign(b, pending);
                plugin.msg(p, "&aPanneau lié à l'arène &6" + pending + "&a.");
                return;
            }

            Arena target = plugin.getSignArena(b);
            if (target != null) {
                e.setCancelled(true);
                if (plugin.getArena(p) != null) {
                    plugin.msg(p, "&cTu es déjà dans une partie.");
                    return;
                }
                target.join(p);
                return;
            }
        }

        // objet "quitter la partie"
        ItemStack it = e.getItem();
        if (it != null && it.getType() == Material.BED && it.hasItemMeta()
                && it.getItemMeta().hasDisplayName()
                && it.getItemMeta().getDisplayName().equals(plugin.color(AurexaSumo.LEAVE_NAME))) {
            Arena a = plugin.getArena(p);
            if (a != null) {
                e.setCancelled(true);
                a.leave(p);
                plugin.msg(p, "&eTu as quitté la partie.");
            }
        }
    }
}

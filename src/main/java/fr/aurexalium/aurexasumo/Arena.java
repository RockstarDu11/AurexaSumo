package fr.aurexalium.aurexasumo;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Arena {

    public enum State { WAITING, STARTING, PLAYING, ENDING }

    private static class Saved {
        Location location;
        ItemStack[] contents;
        ItemStack[] armor;
        GameMode mode;
        double health;
        int food;
        int level;
        float exp;
        boolean allowFlight;
    }

    private final AurexaSumo plugin;
    private final String name;
    private int minPlayers = 2;
    private int maxPlayers = 16;

    private Location lobby, spawn1, spawn2, spec;
    private Cuboid region;

    private State state = State.WAITING;
    private final Set<UUID> members = new LinkedHashSet<UUID>();
    private final Map<UUID, Saved> saved = new HashMap<UUID, Saved>();

    // tournoi
    private final List<UUID> queue = new ArrayList<UUID>();       // joueurs en vie qui attendent leur combat
    private final List<UUID> eliminated = new ArrayList<UUID>();  // dans l'ordre d'elimination
    private UUID f1, f2;                                          // les deux combattants du round en cours
    private UUID matchA, matchB;                                  // les 2 joueurs du match en cours (plusieurs rounds)
    private int winsA, winsB, roundNum, roundsNeeded;
    private boolean frozen = false;
    private int countdownLeft;
    private int timeLeft;

    private BukkitTask countdownTask;
    private BukkitTask roundTask;
    private BukkitTask nextRoundTask;
    private BukkitTask endTask;
    private int countdown;

    public Arena(AurexaSumo plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    // ------------------------------------------------------------
    //  Configuration de l'arene
    // ------------------------------------------------------------

    public String getName() { return name; }
    public int getMinPlayers() { return minPlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getPlayerCount() { return members.size(); }
    public State getState() { return state; }
    public String getStateName() { return state.name(); }

    public void setPlayers(int min, int max) { this.minPlayers = min; this.maxPlayers = max; }
    public void setLobby(Location l) { this.lobby = l; }
    public void setSpawn(int n, Location l) { if (n == 1) spawn1 = l; else spawn2 = l; }
    public void setSpec(Location l) { this.spec = l; }
    public void setRegion(Cuboid c) { this.region = c; }

    public boolean isConfigured() {
        return lobby != null && spawn1 != null && spawn2 != null && spec != null && region != null;
    }

    public List<String> problems() {
        List<String> p = new ArrayList<String>();
        if (!isConfigured()) return p;
        if (spawn1.getY() <= region.getMinY() || spawn2.getY() <= region.getMinY()) {
            p.add("Le bas de la zone de jeu doit etre EN DESSOUS de la plateforme (refais /sumo setregion).");
        }
        return p;
    }

    public String info() {
        StringBuilder err = new StringBuilder();
        for (String s : problems()) err.append("\n&c✘ ").append(s);
        return "&7Joueurs : &f" + minPlayers + "-" + maxPlayers + "\n"
                + "&7Lobby : " + ok(lobby != null) + " &7Spawn 1 : " + ok(spawn1 != null)
                + " &7Spawn 2 : " + ok(spawn2 != null) + " &7Spectateurs : " + ok(spec != null) + "\n"
                + "&7Zone de jeu : " + ok(region != null)
                + err.toString();
    }

    private String ok(boolean b) { return b ? "&a✔" : "&c✘"; }

    public void save(ConfigurationSection s) {
        s.set("min", minPlayers);
        s.set("max", maxPlayers);
        s.set("lobby", lobby);
        s.set("spawn1", spawn1);
        s.set("spawn2", spawn2);
        s.set("spec", spec);
        if (region != null) region.save(s.createSection("region"));
    }

    public static Arena load(AurexaSumo plugin, String name, ConfigurationSection s) {
        Arena a = new Arena(plugin, name);
        a.minPlayers = s.getInt("min", 2);
        a.maxPlayers = s.getInt("max", 16);
        a.lobby = (Location) s.get("lobby");
        a.spawn1 = (Location) s.get("spawn1");
        a.spawn2 = (Location) s.get("spawn2");
        a.spec = (Location) s.get("spec");
        a.region = Cuboid.load(s.getConfigurationSection("region"));
        return a;
    }

    // ------------------------------------------------------------
    //  Rejoindre / quitter
    // ------------------------------------------------------------

    public void join(Player p) {
        if (!isConfigured()) {
            plugin.msg(p, "&cCette arène n'est pas encore configurée.");
            return;
        }
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            plugin.msg(p, "&cCette arène est mal configurée, préviens un admin.");
            if (p.hasPermission("aurexasumo.admin")) {
                for (String s : problems) plugin.msg(p, "&c" + s);
            }
            return;
        }
        if (state != State.WAITING && state != State.STARTING) {
            plugin.msg(p, "&cLe tournoi est déjà en cours.");
            return;
        }
        if (members.size() >= maxPlayers) {
            plugin.msg(p, "&cL'arène est pleine.");
            return;
        }

        Saved s = new Saved();
        s.location = p.getLocation();
        s.contents = p.getInventory().getContents();
        s.armor = p.getInventory().getArmorContents();
        s.mode = p.getGameMode();
        s.health = p.getHealth();
        s.food = p.getFoodLevel();
        s.level = p.getLevel();
        s.exp = p.getExp();
        s.allowFlight = p.getAllowFlight();
        saved.put(p.getUniqueId(), s);

        members.add(p.getUniqueId());
        plugin.registerPlayer(p.getUniqueId(), this);

        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setLevel(0);
        p.setExp(0f);
        p.setFireTicks(0);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.teleport(lobby);
        p.getInventory().setItem(8, plugin.leaveItem());   // objet "quitter" en case 9
        p.updateInventory();

        broadcast("&6" + p.getName() + " &erejoint le Sumo &7(" + members.size() + "/" + maxPlayers + ")");

        if (state == State.WAITING && members.size() >= minPlayers) {
            startCountdown();
        }
        updateScoreboards();
        refreshSigns();
    }

    public void leave(Player p) {
        UUID u = p.getUniqueId();
        if (!members.contains(u)) return;

        boolean wasFighter = u.equals(matchA) || u.equals(matchB);
        members.remove(u);
        queue.remove(u);
        plugin.unregisterPlayer(u);
        restore(p);
        p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());

        broadcast("&6" + p.getName() + " &equitte le Sumo.");

        if (members.isEmpty()) {
            reset();
            return;
        }

        if (state == State.STARTING && members.size() < minPlayers) {
            cancelCountdown();
            state = State.WAITING;
            broadcast("&cPas assez de joueurs, le décompte est annulé.");
        } else if (state == State.PLAYING) {
            if (wasFighter) {
                UUID other = u.equals(matchA) ? matchB : matchA;
                stopRoundTask();
                cancelNextRound();
                f1 = null;
                f2 = null;
                matchA = null;
                matchB = null;
                frozen = false;
                if (other != null && members.contains(other)) {
                    Player o = Bukkit.getPlayer(other);
                    if (o != null) {
                        toSpec(o);
                        broadcast("&6" + o.getName() + " &egagne par forfait.");
                    }
                    queue.add(other);
                }
                afterRound();
            } else if (matchA == null && matchB == null) {
                afterRound();
            }
        }
        updateScoreboards();
        refreshSigns();
    }

    public void shutdown() {
        for (UUID u : new ArrayList<UUID>(members)) {
            Player p = Bukkit.getPlayer(u);
            plugin.unregisterPlayer(u);
            if (p != null) {
                restore(p);
                p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            }
        }
        reset();
    }

    // ------------------------------------------------------------
    //  Deroulement du tournoi
    // ------------------------------------------------------------

    private void startCountdown() {
        state = State.STARTING;
        refreshSigns();
        countdown = plugin.getConfig().getInt("start-countdown", 20);
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() {
                if (countdown <= 0) {
                    cancelCountdown();
                    startGame();
                    return;
                }
                if (countdown <= 5 || countdown % 10 == 0) {
                    broadcast("&eDébut dans &6" + countdown + "&e seconde(s)...");
                    playSound(Sound.NOTE_PLING);
                }
                countdown--;
            }
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void startGame() {
        state = State.PLAYING;
        refreshSigns();
        queue.clear();
        queue.addAll(members);
        Collections.shuffle(queue);
        eliminated.clear();
        f1 = null;
        f2 = null;
        frozen = false;
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) toSpec(p);
        }
        broadcast("&aLe tournoi commence ! &7(" + members.size() + " joueurs)");
        playSound(Sound.LEVEL_UP);
        updateScoreboards();
        scheduleNextRound(60L);
    }

    private void scheduleNextRound(long delay) {
        cancelNextRound();
        nextRoundTask = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() { startRound(); }
        }, delay);
    }

    private void cancelNextRound() {
        if (nextRoundTask != null) {
            nextRoundTask.cancel();
            nextRoundTask = null;
        }
    }

    // lance un nouveau MATCH : 2 joueurs qui se battent sur plusieurs rounds
    private void startRound() {
        nextRoundTask = null;
        if (state != State.PLAYING) return;
        if (queue.size() < 2) {
            afterRound();
            return;
        }
        matchA = queue.remove(0);
        matchB = queue.remove(0);
        winsA = 0;
        winsB = 0;
        roundNum = 0;
        int rounds = plugin.getConfig().getInt("rounds-per-match", 5);
        roundsNeeded = rounds / 2 + 1;
        Player a = Bukkit.getPlayer(matchA);
        Player b = Bukkit.getPlayer(matchB);
        if (a == null || b == null) {
            // ne devrait pas arriver : on remet les joueurs presents dans la file
            if (a != null) queue.add(matchA);
            if (b != null) queue.add(matchB);
            matchA = null;
            matchB = null;
            afterRound();
            return;
        }
        broadcast("&eMatch : &6" + a.getName() + " &evs &6" + b.getName()
                + " &7(premier à " + roundsNeeded + " rounds)");
        beginRound();
    }

    private void scheduleBeginRound(long delay) {
        cancelNextRound();
        nextRoundTask = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() { beginRound(); }
        }, delay);
    }

    // lance un round du match en cours
    private void beginRound() {
        nextRoundTask = null;
        if (state != State.PLAYING || matchA == null || matchB == null) return;
        Player a = Bukkit.getPlayer(matchA);
        Player b = Bukkit.getPlayer(matchB);
        if (a == null || b == null) return;
        roundNum++;
        f1 = matchA;
        f2 = matchB;
        prepareFighter(a, spawn1);
        prepareFighter(b, spawn2);
        frozen = true;
        countdownLeft = plugin.getConfig().getInt("fight-countdown", 3);
        timeLeft = plugin.getConfig().getInt("round-time", 60);
        broadcast("&eRound &6" + roundNum + " &7(" + winsA + " - " + winsB + ")");
        updateScoreboards();

        roundTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() {
                if (frozen) {
                    if (countdownLeft > 0) {
                        broadcast("&6" + countdownLeft + "...");
                        playSound(Sound.NOTE_PLING);
                        countdownLeft--;
                    } else {
                        frozen = false;
                        broadcast("&a&lCombattez !");
                        playSound(Sound.LEVEL_UP);
                    }
                    return;
                }
                timeLeft--;
                if (timeLeft <= 0) timeout();
            }
        }, 0L, 20L);
    }

    private void prepareFighter(Player p, Location l) {
        p.teleport(l);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setVelocity(new Vector(0, 0, 0));
        p.setFlying(false);
        p.setAllowFlight(false);
    }

    private void toSpec(Player p) {
        p.setHealth(p.getMaxHealth());
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setVelocity(new Vector(0, 0, 0));
        p.setAllowFlight(true);
        p.teleport(spec);
    }

    // le temps est ecoule : celui qui est le plus loin du centre perd
    private void timeout() {
        if (f1 == null || f2 == null) return;
        Player a = Bukkit.getPlayer(f1);
        Player b = Bukkit.getPlayer(f2);
        if (a == null || b == null) return;
        broadcast("&eTemps écoulé !");
        double da = region.distanceSquared(a.getLocation());
        double db = region.distanceSquared(b.getLocation());
        endRound(da > db ? f1 : f2);
    }

    public void fighterFell(Player p) {
        UUID u = p.getUniqueId();
        if (state != State.PLAYING || frozen) return;
        if (!u.equals(f1) && !u.equals(f2)) return;
        endRound(u);
    }

    private void endRound(UUID loser) {
        UUID winner = loser.equals(matchA) ? matchB : matchA;
        stopRoundTask();
        f1 = null;
        f2 = null;
        frozen = false;
        if (winner == null) {
            afterRound();
            return;
        }
        Player lp = Bukkit.getPlayer(loser);
        Player wp = Bukkit.getPlayer(winner);
        String wn = wp != null ? wp.getName() : "?";
        String ln = lp != null ? lp.getName() : "?";

        int winnerWins;
        if (winner.equals(matchA)) {
            winsA++;
            winnerWins = winsA;
        } else {
            winsB++;
            winnerWins = winsB;
        }
        if (lp != null) toSpec(lp);
        if (wp != null) toSpec(wp);

        if (winnerWins >= roundsNeeded) {
            // match termine : le perdant est elimine, le gagnant retourne dans la file
            eliminated.add(loser);
            queue.add(winner);
            matchA = null;
            matchB = null;
            broadcast("&6" + wn + " &egagne le match contre &6" + ln + " &7(" + winsA + " - " + winsB
                    + ") &e! &7(Restants : " + alive() + ")");
            if (lp != null) {
                plugin.msg(lp, "&cTu es éliminé ! Tu peux regarder la suite ou quitter avec le lit.");
            }
            updateScoreboards();
            afterRound();
        } else {
            broadcast("&6" + wn + " &emarque le round &7(" + winsA + " - " + winsB + ")");
            updateScoreboards();
            scheduleBeginRound(40L);
        }
    }

    private void afterRound() {
        if (state != State.PLAYING) return;
        if (queue.size() == 1) {
            win(queue.get(0));
            return;
        }
        if (queue.isEmpty()) {
            broadcast("&cPlus assez de joueurs, le tournoi est annulé.");
            finish();
            return;
        }
        scheduleNextRound(60L);
    }

    private void stopRoundTask() {
        if (roundTask != null) {
            roundTask.cancel();
            roundTask = null;
        }
    }

    private int alive() {
        return queue.size() + (matchA != null ? 1 : 0) + (matchB != null ? 1 : 0);
    }

    private void win(UUID winner) {
        state = State.ENDING;
        refreshSigns();
        stopRoundTask();
        cancelNextRound();
        f1 = null;
        f2 = null;
        matchA = null;
        matchB = null;
        frozen = false;

        Player wp = Bukkit.getPlayer(winner);
        broadcast("&6&l★ &e" + (wp != null ? wp.getName() : "?") + " &egagne le tournoi Sumo !");
        playSound(Sound.LEVEL_UP);

        UUID second = eliminated.isEmpty() ? null : eliminated.get(eliminated.size() - 1);
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            if (u.equals(winner)) giveCoins(p, plugin.getConfig().getInt("coins.win", 100));
            else if (u.equals(second)) giveCoins(p, plugin.getConfig().getInt("coins.second", 40));
            else giveCoins(p, plugin.getConfig().getInt("coins.participation", 10));
        }
        updateScoreboards();
        endTask = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() { finish(); }
        }, 100L);
    }

    private void finish() {
        for (UUID u : new ArrayList<UUID>(members)) {
            Player p = Bukkit.getPlayer(u);
            plugin.unregisterPlayer(u);
            if (p != null) {
                restore(p);
                p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            }
        }
        reset();
    }

    private void reset() {
        cancelCountdown();
        stopRoundTask();
        cancelNextRound();
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
        members.clear();
        queue.clear();
        eliminated.clear();
        saved.clear();
        f1 = null;
        f2 = null;
        matchA = null;
        matchB = null;
        winsA = 0;
        winsB = 0;
        roundNum = 0;
        frozen = false;
        state = State.WAITING;
        refreshSigns();
    }

    private void giveCoins(Player p, int amount) {
        if (amount <= 0) return;
        String cmd = plugin.getConfig().getString("coins-command", "");
        if (cmd == null || cmd.trim().isEmpty()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                cmd.replace("{player}", p.getName()).replace("{amount}", String.valueOf(amount)));
        p.sendMessage(plugin.getPrefix() + plugin.color("&a+" + amount + " points"));
    }

    // ------------------------------------------------------------
    //  Evenements
    // ------------------------------------------------------------

    public void handleMove(PlayerMoveEvent e) {
        if (state != State.PLAYING) return;
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        Location to = e.getTo();
        boolean fighter = u.equals(f1) || u.equals(f2);

        if (fighter) {
            if (frozen) {
                Location from = e.getFrom();
                if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
                    Location t = from.clone();
                    t.setYaw(to.getYaw());
                    t.setPitch(to.getPitch());
                    e.setTo(t);
                }
                return;
            }
            Material m = to.getBlock().getType();
            if (to.getY() < region.getMinY()
                    || m == Material.WATER || m == Material.STATIONARY_WATER
                    || m == Material.LAVA || m == Material.STATIONARY_LAVA) {
                fighterFell(p);
            }
        } else if (to.getY() < region.getMinY() - 5) {
            // un spectateur qui tombe est remis sur la zone des spectateurs
            toSpec(p);
        }
    }

    public void handleDamage(EntityDamageEvent e, Player victim) {
        UUID v = victim.getUniqueId();
        if (state != State.PLAYING || frozen || !(v.equals(f1) || v.equals(f2))) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent) {
            Entity d = ((EntityDamageByEntityEvent) e).getDamager();
            if (d instanceof Player) {
                UUID du = ((Player) d).getUniqueId();
                if ((du.equals(f1) || du.equals(f2)) && !du.equals(v)) {
                    // pas de degats : on garde seulement le recul
                    e.setCancelled(false);   // un joueur invincible (op, god, mode staff) peut etre repousse
                    e.setDamage(0.0);
                    return;
                }
            }
        }
        e.setCancelled(true);
    }

    // ------------------------------------------------------------
    //  Divers
    // ------------------------------------------------------------

    private void restore(Player p) {
        restoreInventory(p);
        String cmd = plugin.getConfig().getString("end-command", "invreset {player}");
        if (cmd != null && !cmd.trim().isEmpty()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
        }
    }

    private void restoreInventory(Player p) {
        Saved s = saved.remove(p.getUniqueId());
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setFireTicks(0);
        p.setFlying(false);
        p.setAllowFlight(false);
        if (s == null) return;
        p.getInventory().setContents(s.contents);
        p.getInventory().setArmorContents(s.armor);
        p.setGameMode(s.mode);
        p.setAllowFlight(s.allowFlight);
        p.setHealth(Math.min(s.health, p.getMaxHealth()));
        p.setFoodLevel(s.food);
        p.setLevel(s.level);
        p.setExp(s.exp);
        p.teleport(s.location);
        p.updateInventory();
    }

    private void refreshSigns() {
        plugin.updateSigns(this);
    }

    private void broadcast(String s) {
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(plugin.getPrefix() + plugin.color(s));
        }
    }

    private void playSound(Sound sound) {
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.playSound(p.getLocation(), sound, 1f, 1f);
        }
    }

    private String shortName(String n, int max) {
        return n.length() > max ? n.substring(0, max) : n;
    }

    private String fighterName(UUID u) {
        if (u == null) return "-";
        Player p = Bukkit.getPlayer(u);
        return p == null ? "-" : shortName(p.getName(), 12);
    }

    private void updateScoreboards() {
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;

            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = sb.registerNewObjective("aurexasumo", "dummy");
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "AurexaSumo");

            List<String> lines = new ArrayList<String>();
            if (state == State.WAITING || state == State.STARTING) {
                lines.add(ChatColor.GRAY + "Arène :");
                lines.add(ChatColor.WHITE + shortName(name, 14));
                lines.add(ChatColor.RESET.toString());
                lines.add(ChatColor.GRAY + "Joueurs :");
                lines.add(ChatColor.YELLOW + "" + members.size() + "/" + maxPlayers);
            } else {
                lines.add(ChatColor.GRAY + "Restants: " + ChatColor.WHITE + alive());
                lines.add(ChatColor.RESET.toString());
                lines.add(ChatColor.GRAY + "Combat :");
                lines.add(ChatColor.YELLOW + fighterName(matchA));
                lines.add(ChatColor.GRAY + "vs");
                lines.add(ChatColor.YELLOW + fighterName(matchB) + ChatColor.RESET);
                if (matchA != null) {
                    int rounds = plugin.getConfig().getInt("rounds-per-match", 5);
                    lines.add(ChatColor.RESET.toString() + ChatColor.RESET);
                    lines.add(ChatColor.GRAY + "Round: " + ChatColor.WHITE + roundNum + "/" + rounds);
                    lines.add(ChatColor.GRAY + "Score: " + ChatColor.WHITE + winsA + "-" + winsB);
                }
            }
            int score = lines.size();
            for (String l : lines) {
                o.getScore(l).setScore(score--);
            }
            p.setScoreboard(sb);
        }
    }
}

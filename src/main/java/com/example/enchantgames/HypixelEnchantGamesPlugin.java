package com.example.enchantgames;

import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.user.SkillsUser;
import dev.aurelium.auraskills.api.skill.Skills;
import org.bukkit.*;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

public class HypixelEnchantGamesPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private AuraSkillsApi aura;

    private final Map<UUID, GameSession> sessions = new ConcurrentHashMap<>();

// Marker holders to reliably identify our GUIs (prevents item grabbing even if a session is missing)
private interface EGHolder extends InventoryHolder {
    @Override default Inventory getInventory() { return null; }
}
private record SelectorHolder() implements EGHolder {}
private record SuperpairsHolder() implements EGHolder {}
private record SequencerHolder() implements EGHolder {}
private record ChronoHolder() implements EGHolder {}


    // Sounds (enum only; config accepts enum names)
    private Sound sUiOpen, sUiClick;
    private Sound spReveal, spMatch, spMismatch, spComplete;
    private Sound seqShow, seqGood, seqBad, seqComplete;
    private Sound chrShow, chrGood, chrBad, chrComplete;

    @Override
    public void onEnable() {
        aura = AuraSkillsApi.get();
        saveDefaultConfig();
        loadSounds();
        loadAttempts();

        Objects.requireNonNull(getCommand("enchantgames")).setExecutor(this);
        Objects.requireNonNull(getCommand("enchantgames")).setTabCompleter(this);

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("HypixelEnchantGames enabled.");
    }

    @Override
    public void onDisable() {
        saveAttempts();
        for (GameSession s : sessions.values()) {
            s.close(false);
        }
        sessions.clear();
    }

    private void loadSounds() {
        sUiOpen = soundFrom("sounds.ui_open", Sound.BLOCK_ENCHANTMENT_TABLE_USE);
        sUiClick = soundFrom("sounds.ui_click", Sound.UI_BUTTON_CLICK);

        spReveal = soundFrom("sounds.superpairs_reveal", Sound.BLOCK_AMETHYST_BLOCK_HIT);
        spMatch = soundFrom("sounds.superpairs_match", Sound.BLOCK_NOTE_BLOCK_CHIME);
        spMismatch = soundFrom("sounds.superpairs_mismatch", Sound.BLOCK_NOTE_BLOCK_BASS);
        spComplete = soundFrom("sounds.superpairs_complete", Sound.UI_TOAST_CHALLENGE_COMPLETE);

        seqShow = soundFrom("sounds.sequencer_show", Sound.BLOCK_NOTE_BLOCK_PLING);
        seqGood = soundFrom("sounds.sequencer_click_good", Sound.BLOCK_NOTE_BLOCK_BIT);
        seqBad = soundFrom("sounds.sequencer_click_bad", Sound.BLOCK_NOTE_BLOCK_BASS);
        seqComplete = soundFrom("sounds.sequencer_complete", Sound.UI_TOAST_CHALLENGE_COMPLETE);

        chrShow = soundFrom("sounds.chronomatron_show", Sound.BLOCK_NOTE_BLOCK_PLING);
        chrGood = soundFrom("sounds.chronomatron_click_good", Sound.BLOCK_NOTE_BLOCK_BIT);
        chrBad = soundFrom("sounds.chronomatron_click_bad", Sound.BLOCK_NOTE_BLOCK_BASS);
        chrComplete = soundFrom("sounds.chronomatron_complete", Sound.UI_TOAST_CHALLENGE_COMPLETE);
    }

    private Sound soundFrom(String path, Sound fallback) {
        String key = getConfig().getString(path, null);
        if (key == null || key.isBlank()) return fallback;
        try {
            return Sound.valueOf(key.toUpperCase(Locale.ROOT).replace(".", "_"));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    // ===== Commands =====
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("enchantgames")) return false;
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Player only.");
            return true;
        }
        if (!p.hasPermission("enchantgames.open")) {
            p.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
if (args.length >= 1 && args[0].equalsIgnoreCase("tries")) {
    if (!p.hasPermission("enchantgames.admin")) {
        p.sendMessage(ChatColor.RED + "No permission.");
        return true;
    }
    if (args.length >= 3 && args[1].equalsIgnoreCase("reset")) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(args[2]);
        UUID id = op.getUniqueId();
        if (id == null) {
            p.sendMessage(ChatColor.RED + "Unknown player.");
            return true;
        }
        attempts.put(id, new AttemptData(System.currentTimeMillis(), 0));
        saveAttempts();
        p.sendMessage(color("&aReset attempts for &e" + op.getName() + "&a."));
        return true;
    }
    p.sendMessage(color("&cUsage: /enchantgames tries reset <player>"));
    return true;
}

openSelector(p);
return true;
}

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    // ===== Enchanting table open =====
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (!getConfig().getBoolean("settings.open_on_enchanting_table", true)) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.ENCHANTING_TABLE) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("enchantgames.open")) return;
        openSelector(p);
    }

    // ===== Inventory events =====
    
@EventHandler(priority = EventPriority.HIGHEST)
public void onInvClick(InventoryClickEvent e) {
    if (!(e.getWhoClicked() instanceof Player p)) return;

    Inventory top = e.getView().getTopInventory();
    if (top != null && top.getHolder() instanceof EGHolder) {
        if (getConfig().getBoolean("settings.lock_inventories", true)) {
            e.setCancelled(true);
        }
    }

    GameSession s = sessions.get(p.getUniqueId());
    if (s == null) return;
    s.onClick(e);
}

    
@EventHandler
public void onInvClose(InventoryCloseEvent e) {
    if (!(e.getPlayer() instanceof Player p)) return;

    GameSession s = sessions.get(p.getUniqueId());
    if (s == null) return;

    if (!s.isOurInventory(e.getInventory())) return;

    if (!s.allowClose) {
        // Re-open next tick (unclosable during active game / selector)
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline()) p.openInventory(s.inv);
        });
        return;
    }

    // closed legitimately
    sessions.remove(p.getUniqueId());
}

    // ===== Selector GUI =====
    private void openSelector(Player p) {
        closeExisting(p);

        Inventory inv = Bukkit.createInventory(new SelectorHolder(), 27, color("&5Enchanting Minigames"));
        fill(inv, pane(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));

        boolean sp = getConfig().getBoolean("minigames.superpairs.enabled", true);
        boolean us = getConfig().getBoolean("minigames.ultrasequencer.enabled", true);
        boolean ch = getConfig().getBoolean("minigames.chronomatron.enabled", true);

        int triesLeft = attemptsLeft(p.getUniqueId());
        String triesLine = getConfig().getString("messages.tries_left", "&7Attempts left today: &e{tries_left}&7/3").replace("{tries_left}", String.valueOf(triesLeft));

        inv.setItem(11, button(Material.PURPLE_STAINED_GLASS, "&dSuperpairs", List.of(
                "&7Match identical rewards.",
                "&7Memory & speed.",
                "",
                triesLine,
                (sp ? "&aClick to Play" : "&cDisabled")
        )));
        inv.setItem(13, button(Material.BLUE_STAINED_GLASS, "&aUltrasequencer", List.of(
                "&7Repeat the shown sequence.",
                "&7Increasing difficulty.",
                "",
                triesLine,
                (us ? "&aClick to Play" : "&cDisabled")
        )));
        inv.setItem(15, button(Material.CYAN_STAINED_GLASS, "&bChronomatron", List.of(
                "&7Memorize patterns under time.",
                "&7Increasing difficulty.",
                "",
                triesLine,
                (ch ? "&aClick to Play" : "&cDisabled")
        )));

        sessions.put(p.getUniqueId(), new SelectorSession(p, inv));
        p.openInventory(inv);
        play(p, sUiOpen);
    }

    private void closeExisting(Player p) {
        GameSession old = sessions.remove(p.getUniqueId());
        if (old != null) old.close(false);
    }

    
private void endToSelector(Player p) {
    GameSession s = sessions.get(p.getUniqueId());
    if (s != null) s.allowClose = true;
    Bukkit.getScheduler().runTaskLater(this, () -> {
        if (p.isOnline()) openSelector(p);
    }, 20L);
}

// ===== Utilities =====
    private void fill(Inventory inv, ItemStack it) {
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, it);
    }

    private ItemStack pane(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(color(name));
            im.setLore(colorList(lore));
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack button(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(color(name));
            im.setLore(colorList(lore));
            im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(im);
        }
        return it;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private List<String> colorList(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(color(s));
        return out;
    }

    private void play(Player p, Sound s) {
        if (s == null) return;
        p.playSound(p.getLocation(), s, 1f, 1f);
    }

    // ===== AuraSkills XP =====
    private void giveEnchantingXp(Player p, double amount) {
        if (amount <= 0) return;
        try {
            SkillsUser user = aura.getUser(p.getUniqueId());
            user.addSkillXp(Skills.ENCHANTING, amount);
        } catch (Throwable t) {
            getLogger().warning("Failed to give AuraSkills XP: " + t.getMessage());
        }
    }

    // ===== Rewards =====
    private record RewardEntry(String id, ItemStack display, double xp, List<String> commands, int weight) {}

    private RewardEntry parseReward(ConfigurationSection sec) {
        String id = sec.getString("id", "unknown");
        int weight = Math.max(1, sec.getInt("weight", 1));
        double xp = Math.max(0, sec.getDouble("xp", 0));
        List<String> commands = sec.getStringList("commands");

        ConfigurationSection item = sec.getConfigurationSection("item");
        Material mat = Material.PAPER;
        String name = "&fReward";
        List<String> lore = List.of();
        if (item != null) {
            Material mm = Material.matchMaterial(item.getString("material", "PAPER"));
            if (mm != null) mat = mm;
            name = item.getString("name", name);
            lore = item.getStringList("lore");
        }
        return new RewardEntry(id, button(mat, name, lore), xp, commands, weight);
    }

    private List<RewardEntry> loadSuperpairsPool() {
        List<RewardEntry> out = new ArrayList<>();
        ConfigurationSection root = getConfig().getConfigurationSection("rewards.superpairs");
        if (root == null) return out;

        for (Map<?, ?> m : root.getMapList("default_pool")) {
            out.add(parseReward(mapToSection(m)));
        }
        for (Map<?, ?> m : root.getMapList("custom_pool")) {
            out.add(parseReward(mapToSection(m)));
        }
        return out;
    }

    private ConfigurationSection mapToSection(Map<?, ?> map) {
        MemoryConfiguration mc = new MemoryConfiguration();
        putMap(mc, "", map);
        return mc;
    }

    @SuppressWarnings("unchecked")
    private void putMap(MemoryConfiguration mc, String prefix, Map<?, ?> map) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null) continue;
            String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
            Object val = e.getValue();
            if (val instanceof Map<?, ?> mm) {
                putMap(mc, key, (Map<?, ?>) mm);
            } else {
                mc.set(key, val);
            }
        }
    }

    private RewardEntry weightedPick(List<RewardEntry> pool, Random rng) {
        int total = 0;
        for (RewardEntry e : pool) total += e.weight;
        int r = rng.nextInt(Math.max(1, total));
        int cur = 0;
        for (RewardEntry e : pool) {
            cur += e.weight;
            if (r < cur) return e;
        }
        return pool.get(0);
    }

    private void runCommands(Player p, List<String> cmds) {
        for (String c : cmds) {
            if (c == null || c.isBlank()) continue;
            String cmd = c.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    // ===== Sessions =====
    private abstract class GameSession {
        final Player p;
        final Inventory inv;
        BukkitTask task;
        boolean allowClose = false;

        GameSession(Player p, Inventory inv) {
            this.p = p;
            this.inv = inv;
        }

        boolean isOurInventory(Inventory other) {
            return other != null && other.equals(inv);
        }

        void close(boolean reopenSelector) {
            allowClose = true;
            if (task != null) task.cancel();
            if (reopenSelector && p.isOnline()) {
                Bukkit.getScheduler().runTask(HypixelEnchantGamesPlugin.this, () -> openSelector(p));
            }
        }

        abstract void onClick(InventoryClickEvent e);
    }

    private class SelectorSession extends GameSession {
        SelectorSession(Player p, Inventory inv) { super(p, inv); this.allowClose = true; }

        @Override
        void onClick(InventoryClickEvent e) {
            if (e.getClickedInventory() == null) return;
            if (!isOurInventory(e.getView().getTopInventory())) return;

            int slot = e.getRawSlot();
            if (slot == 11) {
                play(p, sUiClick);

int left = attemptsLeft(p.getUniqueId());
if (left <= 0) {
    String msg = getConfig().getString("messages.no_tries_left", "&cYou have no minigame attempts left. Try again in &e{time_left}&c.");
    p.sendMessage(color(msg.replace("{time_left}", formatDuration(timeLeftMs(p.getUniqueId())))));
    return;
}
if (!consumeAttempt(p)) return;
                if (getConfig().getBoolean("minigames.superpairs.enabled", true)) startSuperpairs(p);
            } else if (slot == 13) {
                play(p, sUiClick);

int left = attemptsLeft(p.getUniqueId());
if (left <= 0) {
    String msg = getConfig().getString("messages.no_tries_left", "&cYou have no minigame attempts left. Try again in &e{time_left}&c.");
    p.sendMessage(color(msg.replace("{time_left}", formatDuration(timeLeftMs(p.getUniqueId())))));
    return;
}
if (!consumeAttempt(p)) return;
                if (getConfig().getBoolean("minigames.ultrasequencer.enabled", true)) startUltrasequencer(p);
            } else if (slot == 15) {
                play(p, sUiClick);

int left = attemptsLeft(p.getUniqueId());
if (left <= 0) {
    String msg = getConfig().getString("messages.no_tries_left", "&cYou have no minigame attempts left. Try again in &e{time_left}&c.");
    p.sendMessage(color(msg.replace("{time_left}", formatDuration(timeLeftMs(p.getUniqueId())))));
    return;
}
if (!consumeAttempt(p)) return;
                if (getConfig().getBoolean("minigames.chronomatron.enabled", true)) startChronomatron(p);
            }
        }
    }

    // ===== Superpairs =====
    private record Card(RewardEntry reward, boolean matched) {}

    private void startSuperpairs(Player p) {
        closeExisting(p);

        int rows = Math.min(6, Math.max(3, getConfig().getInt("minigames.superpairs.rows", 6)));
        int size = rows * 9;

        Inventory inv = Bukkit.createInventory(new SuperpairsHolder(), size, color("&dSuperpairs"));

        ItemStack border = pane(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        ItemStack hidden = pane(Material.GRAY_STAINED_GLASS_PANE, "&7?", List.of("&8Click to reveal"));

        List<Integer> playSlots = buildBorderedPlaySlots(rows);
        for (int i = 0; i < size; i++) inv.setItem(i, border);
        for (int s : playSlots) inv.setItem(s, hidden);

        List<RewardEntry> pool = loadSuperpairsPool();
        if (pool.isEmpty()) {
            pool = List.of(new RewardEntry("fallback", button(Material.LIME_DYE, "&a+100 Enchanting XP", List.of()), 100, List.of(), 1));
        }

        int pairs = playSlots.size() / 2;
        Random rng = new Random();

        List<Card> cards = new ArrayList<>();
        
// Prefer unique reward ids per pair (Hypixel-like variety)
List<RewardEntry> bag = new ArrayList<>();
for (RewardEntry re : pool) {
    for (int w = 0; w < Math.max(1, re.weight); w++) bag.add(re);
}
Collections.shuffle(bag, rng);
Set<String> used = new HashSet<>();
int bagIdx = 0;

for (int i = 0; i < pairs; i++) {
    RewardEntry re = null;

    // try pick a not-yet-used id from weighted bag
    while (bagIdx < bag.size()) {
        RewardEntry cand = bag.get(bagIdx++);
        if (used.add(cand.id)) { re = cand; break; }
    }
    // if not enough unique entries, fall back to any weighted pick
    if (re == null) re = weightedPick(pool, rng);

    cards.add(new Card(re, false));
    cards.add(new Card(re, false));
}
        Collections.shuffle(cards, rng);

        sessions.put(p.getUniqueId(), new SuperpairsSession(p, inv, hidden, playSlots, cards));
        p.openInventory(inv);
        play(p, sUiOpen);
    }

    private List<Integer> buildBorderedPlaySlots(int rows) {
        List<Integer> list = new ArrayList<>();
        if (rows < 3) return list;
        for (int r = 1; r < rows - 1; r++) {
            for (int c = 1; c < 8; c++) {
                list.add(r * 9 + c);
            }
        }
        if (list.size() % 2 == 1) list.remove(list.size() - 1);
        return list;
    }

    private class SuperpairsSession extends GameSession {
        final ItemStack hidden;
        final List<Integer> playSlots;
        final List<Card> cards; // index aligned with playSlots
        Integer firstIdx = null;
        boolean locking = false;
        int matchedPairs = 0;
        double pendingXp = 0;

        SuperpairsSession(Player p, Inventory inv, ItemStack hidden, List<Integer> playSlots, List<Card> cards) {
            super(p, inv);
            this.hidden = hidden;
            this.playSlots = playSlots;
            this.cards = cards;
        }

        @Override
        void onClick(InventoryClickEvent e) {
            if (e.getClickedInventory() == null) return;
            if (!isOurInventory(e.getView().getTopInventory())) return;

            int slot = e.getRawSlot();
            int idx = playSlots.indexOf(slot);
            if (idx < 0) return;
            if (locking) return;

            Card c = cards.get(idx);
            if (c.matched) return;

            inv.setItem(slot, c.reward.display.clone());
            play(p, spReveal);

            if (firstIdx == null) {
                firstIdx = idx;
                return;
            }
            if (firstIdx == idx) return;

            int a = firstIdx;
            int b = idx;
            firstIdx = null;

            Card c1 = cards.get(a);
            Card c2 = cards.get(b);

            int slotA = playSlots.get(a);
            int slotB = playSlots.get(b);

            if (c1.reward.id.equals(c2.reward.id)) {
                cards.set(a, new Card(c1.reward, true));
                cards.set(b, new Card(c2.reward, true));
                matchedPairs++;

                pendingXp += c1.reward.xp;
                runCommands(p, c1.reward.commands);

                play(p, spMatch);

                if (matchedPairs >= (playSlots.size() / 2)) {
                    // award total at end
                    ConfigurationSection sp = getConfig().getConfigurationSection("rewards.superpairs");
                    double completionXp = sp != null ? sp.getDouble("completion_xp", 0) : 0;
                    List<String> cmds = sp != null ? sp.getStringList("completion_commands") : List.of();

                    pendingXp += Math.max(0, completionXp);
                    giveEnchantingXp(p, pendingXp);
                    runCommands(p, cmds);

                    play(p, spComplete);

                    String msg = getConfig().getString("messages.superpairs_complete", "&dSuperpairs &7completed! &a+{xp} Enchanting XP");
                    p.sendMessage(color(msg.replace("{xp}", String.valueOf((int)Math.round(pendingXp)))));

                    p.sendTitle(color("&dSuperpairs"), color("&aCompleted!"), 5, 35, 10);

                    locking = true;
                    Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> {
                        if (p.isOnline()) {
                        endToSelector(p);
                    }
                }, 1L);
                }
                return;
            }

            // mismatch
            play(p, spMismatch);
            locking = true;
            int delay = Math.max(1, getConfig().getInt("minigames.superpairs.mismatch_flip_delay_ticks", 18));
            Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> {
                inv.setItem(slotA, hidden);
                inv.setItem(slotB, hidden);
                locking = false;
            }, delay);
        }
    }

    // ===== Ultrasequencer =====
    private void startUltrasequencer(Player p) {
        closeExisting(p);

        Inventory inv = Bukkit.createInventory(new SequencerHolder(), 45, color("&aUltrasequencer"));
        fill(inv, pane(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));

        UltrasequencerSession us = new UltrasequencerSession(p, inv);
        sessions.put(p.getUniqueId(), us);

        p.openInventory(inv);
        play(p, sUiOpen);
        us.startRound();
    }

    private class UltrasequencerSession extends GameSession {
        final Random rng = new Random();
        final List<Integer> sequence = new ArrayList<>();
        int inputIndex = 0;
        int round = 0;
        double pendingXp = 0;

        final int rounds = Math.max(1, getConfig().getInt("minigames.ultrasequencer.rounds", 7));
        final int startLen = Math.max(1, getConfig().getInt("minigames.ultrasequencer.start_length", 3));
        final int addPer = Math.max(0, getConfig().getInt("minigames.ultrasequencer.add_per_round", 1));
        final int showInterval = Math.max(2, getConfig().getInt("minigames.ultrasequencer.show_interval_ticks", 8));

        final ItemStack idle = pane(Material.BLUE_STAINED_GLASS_PANE, "&7", List.of());
        final ItemStack active = pane(Material.LIME_STAINED_GLASS_PANE, "&a", List.of());
        final ItemStack wrong = pane(Material.RED_STAINED_GLASS_PANE, "&c", List.of());

        boolean locked = false;

        UltrasequencerSession(Player p, Inventory inv) {
            super(p, inv);
            drawBoard();
        }

        List<Integer> allSlots() {
            List<Integer> slots = new ArrayList<>();
            for (int r = 1; r <= 3; r++) {
                for (int c = 2; c <= 6; c++) slots.add(r * 9 + c);
            }
            return slots;
        }

        void drawBoard() {
            for (int r = 1; r <= 3; r++) {
                for (int c = 2; c <= 6; c++) inv.setItem(r * 9 + c, idle);
            }
            inv.setItem(4, button(Material.ENCHANTING_TABLE, "&aUltrasequencer", List.of(
                    "&7Repeat the shown sequence.",
                    "",
                    "&7Round: &f" + (round + 1) + "&7/&f" + rounds
            )));
        }

        void startRound() {
            round++;
            if (round > rounds) {
                complete();
                return;
            }
            drawBoard();
            sequence.clear();
            inputIndex = 0;

            int len = startLen + (round - 1) * addPer;
            List<Integer> slots = allSlots();
            Collections.shuffle(slots, rng);
            for (int i = 0; i < len; i++) sequence.add(slots.get(i % slots.size()));

            showSequence(0);
        }

        void showSequence(int idx) {
            if (!p.isOnline()) return;
            locked = true;

            if (idx >= sequence.size()) {
                locked = false;
                return;
            }

            int slot = sequence.get(idx);
            inv.setItem(slot, active);
            play(p, seqShow);

            Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> {
                inv.setItem(slot, idle);
                Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> showSequence(idx + 1), showInterval);
            }, showInterval);
        }

        @Override
        void onClick(InventoryClickEvent e) {
            if (e.getClickedInventory() == null) return;
            if (!isOurInventory(e.getView().getTopInventory())) return;
            if (locked) return;

            int slot = e.getRawSlot();
            if (!allSlots().contains(slot)) return;

            int expected = sequence.get(inputIndex);
            if (slot == expected) {
                play(p, seqGood);
                inv.setItem(slot, active);

                inputIndex++;
                double perRound = Math.max(0, getConfig().getDouble("rewards.ultrasequencer.per_round_xp", 0));
                if (perRound > 0) pendingXp += perRound;

                if (inputIndex >= sequence.size()) {
                    Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, this::startRound, 16L);
                }
            } else {
                play(p, seqBad);
                inv.setItem(slot, wrong);
                p.sendTitle(color("&aUltrasequencer"), color("&cFailed!"), 5, 25, 10);
                Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> {
                    if (p.isOnline()) {
                        endToSelector(p);
                    }
                }, 1L);
            }
        }

        void complete() {
            play(p, seqComplete);
            p.sendTitle(color("&aUltrasequencer"), color("&aCompleted!"), 5, 35, 10);

            double xp = Math.max(0, getConfig().getDouble("rewards.ultrasequencer.completion_xp", 0));
            pendingXp += xp;
            giveEnchantingXp(p, pendingXp);

            String msg = getConfig().getString("messages.sequencer_complete", "&aUltrasequencer &7completed! &a+{xp} Enchanting XP");
            p.sendMessage(color(msg.replace("{xp}", String.valueOf((int)Math.round(pendingXp)))));

            runCommands(p, getConfig().getStringList("rewards.ultrasequencer.completion_commands"));

            Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> {
                if (p.isOnline()) {
                        endToSelector(p);
                    }
                }, 1L);
        }
    }

    // ===== Chronomatron =====
    private void startChronomatron(Player p) {
        closeExisting(p);

        Inventory inv = Bukkit.createInventory(new ChronoHolder(), 45, color("&bChronomatron"));
        fill(inv, pane(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));

        ChronomatronSession cs = new ChronomatronSession(p, inv);
        sessions.put(p.getUniqueId(), cs);

        p.openInventory(inv);
        play(p, sUiOpen);
        cs.startRound();
    }

    private class ChronomatronSession extends GameSession {
        final Random rng = new Random();
        final List<Integer> sequence = new ArrayList<>();
        int inputIndex = 0;
        int round = 0;
        double pendingXp = 0;

        final int rounds = Math.max(1, getConfig().getInt("minigames.chronomatron.rounds", 7));
        final int startLen = Math.max(1, getConfig().getInt("minigames.chronomatron.start_length", 3));
        final int addPer = Math.max(0, getConfig().getInt("minigames.chronomatron.add_per_round", 1));
        final int showInterval = Math.max(2, getConfig().getInt("minigames.chronomatron.show_interval_ticks", 8));
        final int inputLimit = Math.max(40, getConfig().getInt("minigames.chronomatron.input_time_limit_ticks", 140));

        final ItemStack idle = pane(Material.BLUE_STAINED_GLASS_PANE, "&7", List.of());
        final ItemStack active = pane(Material.CYAN_STAINED_GLASS_PANE, "&b", List.of());
        final ItemStack wrong = pane(Material.RED_STAINED_GLASS_PANE, "&c", List.of());

        boolean locked = false;
        BukkitTask timeoutTask;

        ChronomatronSession(Player p, Inventory inv) {
            super(p, inv);
            drawBoard();
        }

        List<Integer> allSlots() {
            List<Integer> slots = new ArrayList<>();
            for (int r = 1; r <= 3; r++) {
                for (int c = 2; c <= 6; c++) slots.add(r * 9 + c);
            }
            return slots;
        }

        void drawBoard() {
            for (int r = 1; r <= 3; r++) {
                for (int c = 2; c <= 6; c++) inv.setItem(r * 9 + c, idle);
            }
            inv.setItem(4, button(Material.CLOCK, "&bChronomatron", List.of(
                    "&7Remember the pattern.",
                    "&7Repeat it before time runs out.",
                    "",
                    "&7Round: &f" + (round + 1) + "&7/&f" + rounds
            )));
        }

        void startRound() {
            round++;
            if (round > rounds) {
                complete();
                return;
            }
            drawBoard();
            sequence.clear();
            inputIndex = 0;

            int len = startLen + (round - 1) * addPer;
            List<Integer> slots = allSlots();
            for (int i = 0; i < len; i++) sequence.add(slots.get(rng.nextInt(slots.size())));

            showSequence(0);
        }

        void showSequence(int idx) {
            if (!p.isOnline()) return;
            locked = true;
            if (timeoutTask != null) timeoutTask.cancel();

            if (idx >= sequence.size()) {
                locked = false;
                startTimeout();
                return;
            }

            int slot = sequence.get(idx);
            inv.setItem(slot, active);
            play(p, chrShow);

            Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> {
                inv.setItem(slot, idle);
                Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> showSequence(idx + 1), showInterval);
            }, showInterval);
        }

        void startTimeout() {
            if (timeoutTask != null) timeoutTask.cancel();
            timeoutTask = Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> fail("&cTime's up!"), inputLimit);
        }

        void fail(String msg) {
            play(p, chrBad);
            p.sendTitle(color("&bChronomatron"), color(msg), 5, 25, 10);
            if (timeoutTask != null) timeoutTask.cancel();
            Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> {
                if (p.isOnline()) {
                        endToSelector(p);
                    }
                }, 1L);
        }

        @Override
        void onClick(InventoryClickEvent e) {
            if (e.getClickedInventory() == null) return;
            if (!isOurInventory(e.getView().getTopInventory())) return;
            if (locked) return;

            int slot = e.getRawSlot();
            if (!allSlots().contains(slot)) return;

            int expected = sequence.get(inputIndex);
            if (slot == expected) {
                play(p, chrGood);
                inv.setItem(slot, active);
                Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> inv.setItem(slot, idle), 8L);

                inputIndex++;
                double perRound = Math.max(0, getConfig().getDouble("rewards.chronomatron.per_round_xp", 0));
                if (perRound > 0) pendingXp += perRound;

                if (inputIndex >= sequence.size()) {
                    if (timeoutTask != null) timeoutTask.cancel();
                    Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, this::startRound, 18L);
                } else {
                    startTimeout();
                }
            } else {
                inv.setItem(slot, wrong);
                fail("&cIncorrect!");
            }
        }

        void complete() {
            play(p, chrComplete);
            p.sendTitle(color("&bChronomatron"), color("&aCompleted!"), 5, 35, 10);

            double xp = Math.max(0, getConfig().getDouble("rewards.chronomatron.completion_xp", 0));
            pendingXp += xp;
            giveEnchantingXp(p, pendingXp);

            String msg = getConfig().getString("messages.chronomatron_complete", "&bChronomatron &7completed! &a+{xp} Enchanting XP");
            p.sendMessage(color(msg.replace("{xp}", String.valueOf((int)Math.round(pendingXp)))));

            runCommands(p, getConfig().getStringList("rewards.chronomatron.completion_commands"));

            Bukkit.getScheduler().runTaskLater(HypixelEnchantGamesPlugin.this, () -> {
                if (p.isOnline()) {
                        endToSelector(p);
                    }
                }, 1L);
        }
    }
}
// ===== Attempts (3 per 24h) =====
private void loadAttempts() {
    try {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        attemptsFile = new java.io.File(getDataFolder(), "attempts.yml");
        if (!attemptsFile.exists()) attemptsFile.createNewFile();
        attemptsCfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(attemptsFile);

        attempts.clear();
        org.bukkit.configuration.ConfigurationSection sec = attemptsCfg.getConfigurationSection("attempts");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                long ws = sec.getLong(k + ".windowStartMs", 0L);
                int used = sec.getInt(k + ".used", 0);
                attempts.put(id, new AttemptData(ws, used));
            } catch (Exception ignored) {}
        }
    } catch (Exception ex) {
        getLogger().warning("Failed to load attempts.yml: " + ex.getMessage());
    }
}

private void saveAttempts() {
    try {
        if (attemptsCfg == null || attemptsFile == null) return;
        attemptsCfg.set("attempts", null);
        for (Map.Entry<UUID, AttemptData> e : attempts.entrySet()) {
            String k = e.getKey().toString();
            attemptsCfg.set("attempts." + k + ".windowStartMs", e.getValue().windowStartMs);
            attemptsCfg.set("attempts." + k + ".used", e.getValue().used);
        }
        attemptsCfg.save(attemptsFile);
    } catch (Exception ex) {
        getLogger().warning("Failed to save attempts.yml: " + ex.getMessage());
    }
}

private AttemptData normalize(UUID id) {
    int max = Math.max(0, getConfig().getInt("tries.max_per_24h", 3));
    long windowHours = Math.max(1, getConfig().getLong("tries.window_hours", 24));
    long windowMs = windowHours * 60L * 60L * 1000L;

    AttemptData d = attempts.getOrDefault(id, new AttemptData(System.currentTimeMillis(), 0));
    long now = System.currentTimeMillis();
    if (now - d.windowStartMs >= windowMs) {
        d.windowStartMs = now;
        d.used = 0;
    }
    if (d.used < 0) d.used = 0;
    if (max > 0 && d.used > max) d.used = max;
    attempts.put(id, d);
    return d;
}

private int attemptsLeft(UUID id) {
    int max = Math.max(0, getConfig().getInt("tries.max_per_24h", 3));
    AttemptData d = normalize(id);
    return Math.max(0, max - d.used);
}

private long timeLeftMs(UUID id) {
    long windowHours = Math.max(1, getConfig().getLong("tries.window_hours", 24));
    long windowMs = windowHours * 60L * 60L * 1000L;
    AttemptData d = normalize(id);
    long now = System.currentTimeMillis();
    long end = d.windowStartMs + windowMs;
    return Math.max(0L, end - now);
}

private boolean consumeAttempt(Player p) {
    UUID id = p.getUniqueId();
    if (attemptsLeft(id) <= 0) return false;
    AttemptData d = normalize(id);
    d.used += 1;
    attempts.put(id, d);
    saveAttempts();
    return true;
}

private String formatDuration(long ms) {
    long sec = ms / 1000L;
    long h = sec / 3600L;
    sec %= 3600L;
    long m = sec / 60L;
    long s = sec % 60L;
    if (h > 0) return h + "h " + m + "m";
    if (m > 0) return m + "m " + s + "s";
    return s + "s";
}



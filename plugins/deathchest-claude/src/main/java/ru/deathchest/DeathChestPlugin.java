package ru.deathchest;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public final class DeathChestPlugin extends JavaPlugin implements Listener {

    private static final BlockFace[] FACES = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
    };

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private NamespacedKey ownerKey;
    private NamespacedKey xpKey;
    private NamespacedKey timeKey;
    private NamespacedKey openedKey;

    private final Object logLock = new Object();

    private record Spot(Block a, Block b, BlockFace facing) {
    }

    private record Layout(ItemStack[] slots, boolean dbl, List<ItemStack> overflow) {
    }

    /** Данные сундука смерти, записанные в самом блоке. */
    private record ChestInfo(String owner, long created, boolean opened) {
    }

    private static final class Result {
        Block first;
        List<ItemStack> remaining;
        int xpLeft;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ownerKey = new NamespacedKey(this, "owner");
        xpKey = new NamespacedKey(this, "xp");
        timeKey = new NamespacedKey(this, "time");
        openedKey = new NamespacedKey(this, "opened");
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ------------------------------------------------------------------
    // Сообщения
    // ------------------------------------------------------------------

    private String msg(String path, String... kv) {
        String s = getConfig().getString("messages." + path, "");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace(kv[i], kv[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ------------------------------------------------------------------
    // Смерть игрока
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Block chest = null;
        try {
            chest = processDeath(e);
        } finally {
            // Смерть логируется всегда, даже если сундук не создавался
            logDeath(e, chest);
        }
    }

    /** @return первый созданный сундук или null */
    private Block processDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!p.hasPermission("deathchest.use")) {
            return null;
        }

        List<ItemStack> drops = new ArrayList<>();
        if (!e.getKeepInventory()) {
            for (ItemStack it : e.getDrops()) {
                if (it != null && !it.getType().isAir()) {
                    drops.add(it.clone());
                }
            }
        }

        boolean storeXp = getConfig().getBoolean("store-xp", true) && !e.getKeepLevel();
        int xp = 0;
        if (storeXp) {
            xp = getConfig().getBoolean("store-all-xp", true)
                    ? p.getTotalExperience()
                    : e.getDroppedExp();
        }

        if (drops.isEmpty() && xp <= 0) {
            return null;
        }

        try {
            Result result = createChests(p, drops, xp);

            e.getDrops().clear();
            e.getDrops().addAll(result.remaining);
            if (storeXp) {
                e.setDroppedExp(result.xpLeft);
            }

            if (result.first != null) {
                p.sendMessage(msg("death",
                        "%x%", String.valueOf(result.first.getX()),
                        "%y%", String.valueOf(result.first.getY()),
                        "%z%", String.valueOf(result.first.getZ()),
                        "%world%", result.first.getWorld().getName()));
            }
            if (result.first == null || !result.remaining.isEmpty() || result.xpLeft > 0) {
                p.sendMessage(msg("no-space"));
            }
            return result.first;
        } catch (Exception ex) {
            // Ничего не трогаем в событии - вещи выпадут как обычно
            getLogger().log(Level.SEVERE, "Не удалось создать сундук смерти для " + p.getName(), ex);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Лог смертей
    // ------------------------------------------------------------------

    private void logDeath(PlayerDeathEvent e, Block chest) {
        if (!getConfig().getBoolean("log-deaths", true)) {
            return;
        }
        try {
            Player p = e.getEntity();
            Location l = p.getLocation();

            String cause = "UNKNOWN";
            String killer = "-";
            EntityDamageEvent last = p.getLastDamageCause();
            if (last != null) {
                cause = last.getCause().name();
                if (last instanceof EntityDamageByEntityEvent byEntity) {
                    killer = byEntity.getDamager().getType().name();
                }
            }
            Player killerPlayer = p.getKiller();
            if (killerPlayer != null) {
                killer = killerPlayer.getName();
            }

            String chestText = chest == null
                    ? "none"
                    : chest.getWorld().getName() + " " + chest.getX() + " " + chest.getY() + " " + chest.getZ();

            String line = String.format("[%s] %s (%s) died in %s at X=%d Y=%d Z=%d | cause=%s | killer=%s | chest=%s",
                    LocalDateTime.now().format(LOG_TIME),
                    p.getName(),
                    p.getUniqueId(),
                    l.getWorld() == null ? "?" : l.getWorld().getName(),
                    l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                    cause, killer, chestText);

            File dir = getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) {
                getLogger().warning("Не удалось создать папку плагина для лога смертей");
                return;
            }
            Path file = new File(dir, "deaths.log").toPath();
            synchronized (logLock) {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Не удалось записать лог смерти", ex);
        }
    }

    // ------------------------------------------------------------------
    // Создание сундуков
    // ------------------------------------------------------------------

    private Result createChests(Player p, List<ItemStack> drops, int xp) {
        Result res = new Result();
        List<ItemStack> remaining = new ArrayList<>(drops);
        int xpLeft = xp;
        Location origin = p.getLocation();

        boolean preserveLayout = getConfig().getBoolean("preserve-layout", true);
        boolean breakBlocks = getConfig().getBoolean("break-blocks", true);
        boolean firstChest = true;

        int guard = 0;
        while ((!remaining.isEmpty() || xpLeft > 0) && guard++ < 20) {
            ItemStack[] contents = new ItemStack[54];
            List<ItemStack> overflow = new ArrayList<>();
            boolean dbl;

            if (firstChest && preserveLayout && !remaining.isEmpty()) {
                // Первый сундук - с сохранением раскладки инвентаря
                Layout layout = buildLayout(p, remaining);
                contents = layout.slots();
                dbl = layout.dbl();
                overflow.addAll(layout.overflow());
            } else {
                // Компактная укладка: одинаковые стаки сливаются, лишнее уходит в следующий сундук
                int n = 0;
                if (!remaining.isEmpty()) {
                    Inventory tmp = Bukkit.createInventory(null, 54);
                    Map<Integer, ItemStack> left = tmp.addItem(remaining.toArray(new ItemStack[0]));
                    overflow.addAll(left.values());
                    for (ItemStack it : tmp.getContents()) {
                        if (it != null && !it.getType().isAir()) {
                            contents[n++] = it;
                        }
                    }
                }
                dbl = n > 27;
            }

            // Сначала ищем свободное место, и только потом - место, где придётся ломать блоки
            Spot spot = findSpot(origin, dbl, false);
            if (spot == null && breakBlocks) {
                spot = findSpot(origin, dbl, true);
            }
            if (spot == null) {
                break;
            }

            List<ItemStack> notFit = placeChest(spot, dbl, contents, p, xpLeft, breakBlocks);
            xpLeft = 0;
            overflow.addAll(notFit);
            remaining = overflow;
            firstChest = false;

            if (res.first == null) {
                res.first = spot.a();
            }
        }

        res.remaining = remaining;
        res.xpLeft = xpLeft;
        return res;
    }

    // ------------------------------------------------------------------
    // Раскладка инвентаря
    // ------------------------------------------------------------------

    /**
     * Слот инвентаря игрока -> слот сундука.
     * Ряды 1-3: основной инвентарь, ряд 4: хотбар,
     * ряд 5: шлем, нагрудник, поножи, ботинки (слоты 36-39) и вторая рука (слот 41).
     */
    private static int layoutSlot(int invSlot) {
        if (invSlot >= 9 && invSlot <= 35) {
            return invSlot - 9;
        }
        if (invSlot >= 0 && invSlot <= 8) {
            return 27 + invSlot;
        }
        return switch (invSlot) {
            case 39 -> 36; // шлем
            case 38 -> 37; // нагрудник
            case 37 -> 38; // поножи
            case 36 -> 39; // ботинки
            case 40 -> 41; // вторая рука
            default -> -1;
        };
    }

    private static int indexOfSame(List<ItemStack> pool, ItemStack it) {
        for (int i = 0; i < pool.size(); i++) {
            ItemStack c = pool.get(i);
            if (c.getAmount() == it.getAmount() && c.isSimilar(it)) {
                return i;
            }
        }
        return -1;
    }

    private Layout buildLayout(Player p, List<ItemStack> drops) {
        ItemStack[] slots = new ItemStack[54];
        List<ItemStack> pool = new ArrayList<>(drops);
        boolean dbl = false;

        // Во время PlayerDeathEvent инвентарь игрока ещё не очищен.
        // Каждый предмет инвентаря сопоставляем с предметом из списка дропа.
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i <= 40; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) {
                continue;
            }
            int slot = layoutSlot(i);
            if (slot < 0) {
                continue;
            }
            int idx = indexOfSame(pool, it);
            if (idx < 0) {
                continue; // предмет не выпал бы (проклятие утраты и т.п.)
            }
            slots[slot] = pool.remove(idx);
            if (slot >= 27) {
                dbl = true;
            }
        }

        // Предметы, которых нет в инвентаре (моды, другие плагины) - в свободные слоты
        List<ItemStack> overflow = new ArrayList<>();
        if (!pool.isEmpty()) {
            int free = 0;
            for (int i = 0; i < 27; i++) {
                if (slots[i] == null) {
                    free++;
                }
            }
            int limit = 27;
            if (dbl || pool.size() > free) {
                dbl = true;
                limit = 54;
            }
            for (ItemStack it : pool) {
                boolean placed = false;
                for (int i = 0; i < limit; i++) {
                    if (slots[i] == null) {
                        slots[i] = it;
                        placed = true;
                        break;
                    }
                }
                if (!placed) {
                    overflow.add(it);
                }
            }
        }

        return new Layout(slots, dbl, overflow);
    }

    // ------------------------------------------------------------------
    // Поиск места и установка сундука
    // ------------------------------------------------------------------

    private boolean inBounds(Block b) {
        World w = b.getWorld();
        return b.getY() >= w.getMinHeight() && b.getY() < w.getMaxHeight();
    }

    private boolean canReplace(Block b) {
        if (!inBounds(b)) {
            return false;
        }
        Material m = b.getType();
        return m.isAir() || m == Material.WATER || isReplaceablePlant(m);
    }

    private static boolean isReplaceablePlant(Material m) {
        // По именам, чтобы работало и на старых, и на новых версиях API
        return switch (m.name()) {
            case "GRASS", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN",
                 "DEAD_BUSH", "SEAGRASS", "TALL_SEAGRASS", "VINE" -> true;
            default -> false;
        };
    }

    private static boolean isUnbreakable(Material m) {
        String n = m.name();
        if (n.endsWith("COMMAND_BLOCK")
                || n.equals("BEDROCK")
                || n.equals("BARRIER")
                || n.equals("LIGHT")
                || n.equals("END_PORTAL_FRAME")
                || n.equals("END_PORTAL")
                || n.equals("END_GATEWAY")
                || n.equals("NETHER_PORTAL")
                || n.equals("REINFORCED_DEEPSLATE")
                || n.equals("STRUCTURE_BLOCK")
                || n.equals("JIGSAW")) {
            return true;
        }
        try {
            return m.isBlock() && m.getHardness() < 0;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Можно ли сломать блок, чтобы освободить место под сундук. */
    private boolean canBreak(Block b) {
        if (!inBounds(b)) {
            return false;
        }
        if (isUnbreakable(b.getType())) {
            return false;
        }
        // Чужие (и свои) сундуки смерти не ломаем, чтобы не рассыпать вещи
        return !isDeathChest(b);
    }

    private boolean canUse(Block b, boolean breaking) {
        return canReplace(b) || (breaking && canBreak(b));
    }

    private static BlockFace clockwise(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    private Spot findSpot(Location origin, boolean dbl, boolean breaking) {
        World w = origin.getWorld();
        int x = origin.getBlockX();
        int z = origin.getBlockZ();
        int y = Math.max(w.getMinHeight() + 1, Math.min(w.getMaxHeight() - 2, origin.getBlockY()));
        int radius = Math.max(0, getConfig().getInt("search-radius", 4));
        // При поломке блоков место ищем только вплотную к точке смерти
        if (breaking) {
            radius = Math.min(radius, 1);
        }

        for (int d = 0; d <= radius; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dy = -d; dy <= d; dy++) {
                    for (int dz = -d; dz <= d; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != d) {
                            continue;
                        }
                        Block a = w.getBlockAt(x + dx, y + dy, z + dz);
                        if (!canUse(a, breaking)) {
                            continue;
                        }
                        if (!dbl) {
                            return new Spot(a, null, BlockFace.NORTH);
                        }
                        // Если блок b уже свободен - выбираем такой вариант в первую очередь
                        if (breaking) {
                            for (BlockFace f : FACES) {
                                Block b = a.getRelative(clockwise(f));
                                if (canReplace(b)) {
                                    return new Spot(a, b, f);
                                }
                            }
                        }
                        for (BlockFace f : FACES) {
                            Block b = a.getRelative(clockwise(f));
                            if (canUse(b, breaking)) {
                                return new Spot(a, b, f);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private void clearBlock(Block b, boolean breakBlocks) {
        if (b == null || !breakBlocks || canReplace(b) || b.isLiquid()) {
            return;
        }
        // Блок ломается как обычно: сам блок и содержимое выпадают предметами
        b.breakNaturally();
    }

    private void setChestData(Block b, BlockFace face, org.bukkit.block.data.type.Chest.Type type) {
        b.setType(Material.CHEST, false);
        org.bukkit.block.data.type.Chest data = (org.bukkit.block.data.type.Chest) b.getBlockData();
        data.setFacing(face);
        data.setType(type);
        b.setBlockData(data, false);
    }

    /**
     * Ставит сундук (одинарный или двойной), помечает его и кладёт вещи по слотам.
     *
     * @return предметы, которые не поместились
     */
    private List<ItemStack> placeChest(Spot s, boolean dbl, ItemStack[] contents,
                                       Player owner, int xp, boolean breakBlocks) {
        Block a = s.a();
        Block b = s.b();
        BlockFace f = s.facing();

        clearBlock(a, breakBlocks);
        if (dbl) {
            clearBlock(b, breakBlocks);
        }

        if (dbl) {
            // a - правая половина, b - левая (если смотреть на сундук спереди)
            setChestData(a, f, org.bukkit.block.data.type.Chest.Type.RIGHT);
            setChestData(b, f, org.bukkit.block.data.type.Chest.Type.LEFT);
            // Самопроверка: если сундуки не слились, меняем половины местами
            if (!(((Chest) a.getState()).getInventory() instanceof DoubleChestInventory)) {
                setChestData(a, f, org.bukkit.block.data.type.Chest.Type.LEFT);
                setChestData(b, f, org.bukkit.block.data.type.Chest.Type.RIGHT);
            }
        } else {
            setChestData(a, f, org.bukkit.block.data.type.Chest.Type.SINGLE);
        }

        // Метки и имя (делаем до заполнения, чтобы update() не перезаписал предметы)
        tag(a, owner, xp);
        if (dbl) {
            tag(b, owner, 0);
        }

        Inventory inv = ((Chest) a.getState()).getInventory();
        List<ItemStack> notFit = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) {
                continue;
            }
            if (i < inv.getSize()) {
                inv.setItem(i, it);
            } else {
                notFit.add(it);
            }
        }
        return notFit;
    }

    private void tag(Block block, Player owner, int xp) {
        Chest c = (Chest) block.getState();
        c.setCustomName(msg("chest-name", "%player%", owner.getName()));
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        pdc.set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        pdc.set(timeKey, PersistentDataType.LONG, System.currentTimeMillis());
        pdc.set(openedKey, PersistentDataType.BYTE, (byte) 0);
        if (xp > 0) {
            pdc.set(xpKey, PersistentDataType.INTEGER, xp);
        }
        c.update();
    }

    // ------------------------------------------------------------------
    // Данные и защита сундука смерти
    // ------------------------------------------------------------------

    private ChestInfo infoOf(Chest c) {
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        String owner = pdc.get(ownerKey, PersistentDataType.STRING);
        if (owner == null) {
            return null;
        }
        Long created = pdc.get(timeKey, PersistentDataType.LONG);
        Byte opened = pdc.get(openedKey, PersistentDataType.BYTE);
        return new ChestInfo(owner, created == null ? 0L : created, opened != null && opened == 1);
    }

    /** @return данные сундука смерти или null, если блок - не сундук смерти */
    private ChestInfo info(Block b) {
        if (b.getType() != Material.CHEST) {
            return null;
        }
        return b.getState() instanceof Chest c ? infoOf(c) : null;
    }

    private boolean isDeathChest(Block b) {
        return info(b) != null;
    }

    /** Защищён ли сундук: владелец ещё ни разу его не открывал (и не истёк таймаут). */
    private boolean isProtected(ChestInfo i) {
        if (i == null || i.opened()) {
            return false;
        }
        if (!getConfig().getBoolean("protect-until-opened", true)) {
            return false;
        }
        int minutes = getConfig().getInt("unopened-timeout-minutes", 0);
        return minutes <= 0 || System.currentTimeMillis() - i.created() < minutes * 60_000L;
    }

    private boolean isProtectedBlock(Block b) {
        return isProtected(info(b));
    }

    private boolean isProtectedHolder(InventoryHolder h) {
        if (h instanceof Chest c) {
            return isProtected(infoOf(c));
        }
        if (h instanceof DoubleChest dc) {
            return (dc.getLeftSide() instanceof Chest l && isProtected(infoOf(l)))
                    || (dc.getRightSide() instanceof Chest r && isProtected(infoOf(r)));
        }
        return false;
    }

    private boolean isOwner(ChestInfo i, Player p) {
        return i != null && i.owner().equals(p.getUniqueId().toString());
    }

    /** Может ли игрок обращаться с защищённым сундуком. */
    private boolean mayAccess(ChestInfo i, Player p) {
        return isOwner(i, p) || p.hasPermission("deathchest.bypass");
    }

    private long protectionLeftMs(ChestInfo i, Player p) {
        int secs = getConfig().getInt("protection-seconds", 0);
        if (secs <= 0 || i == null || p.hasPermission("deathchest.bypass") || isOwner(i, p)) {
            return 0;
        }
        return Math.max(0, i.created() + secs * 1000L - System.currentTimeMillis());
    }

    private List<Block> deathChestBlocks(Inventory inv) {
        List<Block> result = new ArrayList<>();
        InventoryHolder holder = inv.getHolder();
        if (holder instanceof DoubleChest dc) {
            if (dc.getLeftSide() instanceof Chest l) {
                result.add(l.getBlock());
            }
            if (dc.getRightSide() instanceof Chest r) {
                result.add(r.getBlock());
            }
        } else if (holder instanceof Chest c) {
            result.add(c.getBlock());
        }
        result.removeIf(b -> !isDeathChest(b));
        return result;
    }

    private int takeXp(Collection<Block> blocks) {
        int total = 0;
        for (Block b : blocks) {
            if (!(b.getState() instanceof Chest c)) {
                continue;
            }
            PersistentDataContainer pdc = c.getPersistentDataContainer();
            Integer xp = pdc.get(xpKey, PersistentDataType.INTEGER);
            if (xp != null && xp > 0) {
                total += xp;
                pdc.remove(xpKey);
                c.update();
            }
        }
        return total;
    }

    private void markOpened(Collection<Block> blocks) {
        for (Block b : blocks) {
            if (!(b.getState() instanceof Chest c)) {
                continue;
            }
            c.getPersistentDataContainer().set(openedKey, PersistentDataType.BYTE, (byte) 1);
            c.update();
        }
    }

    /** Ломает сундук смерти: выпадают только вещи (и опыт достаётся игроку), сам сундук - нет. */
    private void destroyChest(Block b, Player p) {
        int xp = takeXp(List.of(b));
        if (xp > 0) {
            p.giveExp(xp);
            p.sendMessage(msg("xp", "%xp%", String.valueOf(xp)));
        }

        if (b.getState() instanceof Chest c) {
            Inventory inv = c.getBlockInventory();
            ItemStack[] contents = inv.getContents();
            inv.clear();
            Location at = b.getLocation().add(0.5, 0.5, 0.5);
            for (ItemStack it : contents) {
                if (it != null && !it.getType().isAir()) {
                    b.getWorld().dropItemNaturally(at, it);
                }
            }
        }
        b.setType(Material.AIR);
    }

    // ------------------------------------------------------------------
    // События сундука
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) {
            return;
        }
        List<Block> blocks = deathChestBlocks(e.getInventory());
        if (blocks.isEmpty()) {
            return;
        }

        // Пока владелец не открыл сундук, открыть его может только он (и админы с bypass)
        for (Block b : blocks) {
            ChestInfo i = info(b);
            if (isProtected(i) && !mayAccess(i, p)) {
                e.setCancelled(true);
                p.sendMessage(msg("locked"));
                return;
            }
        }

        ChestInfo first = info(blocks.get(0));
        long left = protectionLeftMs(first, p);
        if (left > 0) {
            e.setCancelled(true);
            p.sendMessage(msg("protected", "%seconds%", String.valueOf((left + 999) / 1000)));
            return;
        }

        int xp = takeXp(blocks);
        if (xp > 0) {
            p.giveExp(xp);
            p.sendMessage(msg("xp", "%xp%", String.valueOf(xp)));
        }

        // Первое открытие владельцем снимает неуязвимость
        if (isOwner(first, p)) {
            markOpened(blocks);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClose(InventoryCloseEvent e) {
        if (!getConfig().getBoolean("remove-empty-chest", true)) {
            return;
        }
        Inventory inv = e.getInventory();
        List<Block> blocks = deathChestBlocks(inv);
        if (blocks.isEmpty()) {
            return;
        }
        for (ItemStack it : inv.getContents()) {
            if (it != null && !it.getType().isAir()) {
                return;
            }
        }
        Bukkit.getScheduler().runTask(this, () -> {
            for (Block b : blocks) {
                if (isDeathChest(b)) {
                    b.setType(Material.AIR);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        ChestInfo i = info(b);
        if (i == null) {
            return;
        }
        Player p = e.getPlayer();

        if (isProtected(i) && !mayAccess(i, p)) {
            e.setCancelled(true);
            p.sendMessage(msg("locked"));
            return;
        }
        long left = protectionLeftMs(i, p);
        if (left > 0) {
            e.setCancelled(true);
            p.sendMessage(msg("protected", "%seconds%", String.valueOf((left + 999) / 1000)));
            return;
        }

        // Ломаем сами, чтобы сундук не выпадал предметом (защита от фарма сундуков)
        e.setCancelled(true);
        destroyChest(b, p);
    }

    /** Нельзя приставить свой сундук к защищённому: двойной сундук открыл бы чужие вещи. */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block placed = e.getBlockPlaced();
        if (placed.getType() != Material.CHEST) {
            return;
        }
        Player p = e.getPlayer();
        for (BlockFace f : FACES) {
            ChestInfo i = info(placed.getRelative(f));
            if (isProtected(i) && !mayAccess(i, p)) {
                e.setCancelled(true);
                p.sendMessage(msg("locked"));
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Защита от разрушения и воровства
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isProtectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isProtectedBlock);
    }

    /** Иссушитель, эндермены и прочие мобы, меняющие блоки. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (isProtectedBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (isProtectedBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (b.getType() == Material.CHEST && isProtectedBlock(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (b.getType() == Material.CHEST && isProtectedBlock(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Воронки и вагонетки с воронкой не могут ни вытянуть вещи, ни положить их в защищённый сундук. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (isProtectedHolder(e.getSource().getHolder())
                || isProtectedHolder(e.getDestination().getHolder())) {
            e.setCancelled(true);
        }
    }
}

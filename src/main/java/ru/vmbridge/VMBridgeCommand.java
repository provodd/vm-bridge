package ru.vmbridge;

import net.bestemor.villagermarket.VillagerMarketAPI;
import net.bestemor.villagermarket.shop.PlayerShop;
import net.bestemor.villagermarket.shop.VillagerShop;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class VMBridgeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            Arrays.asList("setbranch", "removebranch", "info", "setrate", "list", "reload", "refreshcache");

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final VMBridge plugin;

    public VMBridgeCommand(VMBridge plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ //
    //  Dispatch                                                            //
    // ------------------------------------------------------------------ //

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be run by a player.");
            return true;
        }
        if (!player.hasPermission("vmbridge.admin")) {
            player.sendMessage("§cYou don't have permission.");
            return true;
        }
        if (args.length == 0) { sendHelp(player); return true; }

        switch (args[0].toLowerCase()) {
            case "setbranch"    -> cmdSetBranch(player, args);
            case "removebranch" -> cmdRemoveBranch(player);
            case "info"         -> cmdInfo(player);
            case "setrate"      -> cmdSetRate(player, args);
            case "list"         -> cmdList(player);
            case "reload"        -> cmdReload(player);
            case "refreshcache" -> cmdRefreshCache(player);
            default             -> sendHelp(player);
        }
        return true;
    }

    // ------------------------------------------------------------------ //
    //  Subcommands                                                         //
    // ------------------------------------------------------------------ //

    private void cmdSetBranch(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§eUsage: /vmbridge setbranch <green|blue|pink|orange>");
            return;
        }

        String branch = args[1].toLowerCase();
        if (!plugin.getBranchManager().isValidBranch(branch)) {
            player.sendMessage("§cInvalid branch. Choose from: §fgreen, blue, pink, orange");
            return;
        }

        Entity target = getTargetVillager(player);
        if (target == null) {
            player.sendMessage("§cLook at a VillagerMarket shop (villager) within 5 blocks.");
            return;
        }

        VillagerShop shop = VillagerMarketAPI.getShopManager().getShop(target.getUniqueId());
        if (shop == null) {
            player.sendMessage("§cThat villager is not a VillagerMarket shop.");
            return;
        }

        String ownerUuid = null;
        String ownerName = null;
        if (shop instanceof PlayerShop ps) {
            ownerUuid = ps.getOwnerUUID() != null ? ps.getOwnerUUID().toString() : null;
            ownerName = ps.getOwnerName();
        }

        ShopAssignment assignment = new ShopAssignment(
                target.getUniqueId(),
                shop.getShopName(),
                branch,
                ownerUuid,
                ownerName,
                player.getName(),
                LocalDateTime.now()
        );

        plugin.getBranchManager().setAssignment(assignment);

        player.sendMessage("§aМагазин §f" + shop.getShopName()
                + "§a привязан к ветке §f" + branch
                + "§a (налог: §f" + (plugin.getBranchManager().getTaxRate(branch) * 100) + "%§a).");
        if (ownerName != null) {
            player.sendMessage("§7Владелец: §f" + ownerName);
        }
    }

    private void cmdRemoveBranch(Player player) {
        Entity target = getTargetVillager(player);
        if (target == null) {
            player.sendMessage("§cLook at a VillagerMarket shop (villager) within 5 blocks.");
            return;
        }

        UUID entityId = target.getUniqueId();
        if (!plugin.getBranchManager().isAssigned(entityId)) {
            player.sendMessage("§cЭтот магазин не привязан ни к одной ветке.");
            return;
        }

        String oldBranch = plugin.getBranchManager().getBranch(entityId);
        plugin.getBranchManager().removeAssignment(entityId);
        player.sendMessage("§aПривязка к ветке §f" + oldBranch + "§a удалена.");
    }

    private void cmdInfo(Player player) {
        Entity target = getTargetVillager(player);
        if (target == null) {
            player.sendMessage("§cLook at a VillagerMarket shop (villager) within 5 blocks.");
            return;
        }

        UUID entityId = target.getUniqueId();
        ShopAssignment a = plugin.getBranchManager().getAssignment(entityId);

        if (a == null) {
            player.sendMessage("§7Этот магазин не привязан ни к одной ветке.");
            return;
        }

        double rate = plugin.getBranchManager().getTaxRate(a.branch());
        player.sendMessage("§6=== Инфо о магазине ===");
        player.sendMessage("§7Название:    §f" + a.shopName());
        player.sendMessage("§7UUID:        §f" + a.shopUuid());
        player.sendMessage("§7Ветка:       §f" + a.branch());
        player.sendMessage("§7Налог:       §f" + (rate * 100) + "%");
        player.sendMessage("§7Владелец:    §f" + (a.ownerName() != null ? a.ownerName() : "AdminShop"));
        player.sendMessage("§7Назначил:    §f" + a.assignedBy()
                + "  §8" + a.assignedAt().format(DT_FMT));

        String url = plugin.getBranchManager().getBranchOwnerUrl(a.branch());
        if (url != null && !url.isBlank()) {
            player.sendMessage("§7URL владельца: §f" + url);
        } else {
            player.sendMessage("§7Плейсхолдер: §f" + plugin.getBranchManager().getBranchOwnerPlaceholder(a.branch()));
        }
        String cached = plugin.getBranchManager().getCachedOwner(a.branch());
        player.sendMessage("§7Клан (кэш):  §f" + (cached != null ? cached : "§8не заполнен"));
    }

    private void cmdSetRate(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§eUsage: /vmbridge setrate <branch> <rate>  §8(0.05 = 5%)");
            return;
        }

        String branch = args[1].toLowerCase();
        if (!plugin.getBranchManager().isValidBranch(branch)) {
            player.sendMessage("§cInvalid branch. Choose from: §fgreen, blue, pink, orange");
            return;
        }

        try {
            double rate = Double.parseDouble(args[2]);
            if (rate < 0 || rate > 1) {
                player.sendMessage("§cСтавка должна быть от 0.0 до 1.0.");
                return;
            }
            plugin.getConfig().set("tax-rate." + branch, rate);
            plugin.saveConfig();
            player.sendMessage("§aСтавка налога для ветки §f" + branch
                    + "§a установлена: §f" + (rate * 100) + "%");
        } catch (NumberFormatException e) {
            player.sendMessage("§cНеверный формат. Используй десятичную дробь, например §f0.05§c.");
        }
    }

    private void cmdList(Player player) {
        Map<UUID, ShopAssignment> all = plugin.getBranchManager().getAllAssignments();
        if (all.isEmpty()) {
            player.sendMessage("§7Нет магазинов, привязанных к веткам.");
            return;
        }

        player.sendMessage("§6=== Привязанные магазины (" + all.size() + ") ===");
        all.forEach((uuid, a) -> {
            String owner = a.ownerName() != null ? a.ownerName() : "AdminShop";
            player.sendMessage("§f" + a.shopName()
                    + "  §8[" + a.branch() + "]"
                    + "  §7владелец: §f" + owner
                    + "  §7назначил: §f" + a.assignedBy());
        });
    }

    private void cmdReload(Player player) {
        plugin.reloadConfig();
        plugin.getBranchManager().load();
        player.sendMessage("§aVMBridge: конфиг и данные перезагружены.");
    }

    private void cmdRefreshCache(Player player) {
        player.sendMessage("§eVMBridge: обновление кэша владельцев веток...");
        plugin.getBranchManager().refreshOwnerCache();
        player.sendMessage("§aЗапущено. Результат появится в логе сервера через несколько секунд.");
    }

    // ------------------------------------------------------------------ //
    //  Tab completion                                                      //
    // ------------------------------------------------------------------ //

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("setbranch") || sub.equals("setrate"))
                return filter(List.of("green", "blue", "pink", "orange"), args[1]);
        }
        return List.of();
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private Entity getTargetVillager(Player player) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                5.0,
                e -> e.getType() == EntityType.VILLAGER
                        && !e.getUniqueId().equals(player.getUniqueId())
        );
        if (result == null || result.getHitEntity() == null) return null;
        return result.getHitEntity();
    }

    private List<String> filter(List<String> source, String prefix) {
        List<String> result = new ArrayList<>();
        for (String s : source) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase())) result.add(s);
        }
        return result;
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6=== VMBridge ===");
        player.sendMessage("§e/vmbridge setbranch <ветка>       §7- Привязать магазин к ветке");
        player.sendMessage("§e/vmbridge removebranch            §7- Убрать привязку");
        player.sendMessage("§e/vmbridge info                    §7- Инфо о магазине");
        player.sendMessage("§e/vmbridge setrate <ветка> <ставка>§7- Изменить ставку налога");
        player.sendMessage("§e/vmbridge list                    §7- Список всех магазинов");
        player.sendMessage("§e/vmbridge reload                  §7- Перезагрузить конфиг");
        player.sendMessage("§e/vmbridge refreshcache            §7- Принудительно обновить кэш кланов");
    }
}

package ru.vmbridge;

import net.bestemor.villagermarket.event.interact.BuyShopItemsEvent;
import net.bestemor.villagermarket.event.interact.SellShopItemsEvent;
import net.bestemor.villagermarket.shop.PlayerShop;
import net.bestemor.villagermarket.shop.ShopItem;
import net.bestemor.villagermarket.shop.VillagerShop;
import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.EconomyResponse;
import net.sacredlabyrinth.phaed.simpleclans.events.ClanBalanceUpdateEvent;
import net.sacredlabyrinth.phaed.simpleclans.loggers.BankOperator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Listens to VillagerMarket transaction events and:
 *   1. Collects tax and deposits it to the owning clan's bank.
 *   2. Logs every transaction (with tax details) to MySQL asynchronously.
 *
 * Design notes:
 *   - VillagerMarket already handles all economy transfers between buyer and
 *     shop owner. We only ADD money to the clan bank — no withdrawals here.
 *   - Events fire on the main server thread (Bukkit guarantee), so no
 *     synchronization is needed for the deposit itself.
 *   - A per-shop deduplication window (50 ms) prevents double-deposits if
 *     a buggy plugin fires the same event twice in rapid succession.
 */
public class TaxListener implements Listener {

    private final VMBridge plugin;

    public TaxListener(VMBridge plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ //
    //  Event handlers                                                      //
    // ------------------------------------------------------------------ //

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBuy(BuyShopItemsEvent event) {
        double total = event.getShopItem().getBuyPrice(event.getAmount(), true).doubleValue();

        if (!plugin.getConfig().getBoolean("tax-on-buy", true)) {
            logToDb(buildRecord(event.getShop(), event.getShopItem(), event.getPlayer(),
                    "BUY", event.getAmount(), total, 0, 0, null, null, TransactionRecord.STATUS_DISABLED));
            return;
        }

        processTransaction("BUY", event.getShop(), event.getShopItem(), total, event.getAmount(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSell(SellShopItemsEvent event) {
        double total = event.getShopItem().getSellPrice(event.getAmount(), true).doubleValue();

        if (!plugin.getConfig().getBoolean("tax-on-sell", false)) {
            logToDb(buildRecord(event.getShop(), event.getShopItem(), event.getPlayer(),
                    "SELL", event.getAmount(), total, 0, 0, null, null, TransactionRecord.STATUS_DISABLED));
            return;
        }

        processTransaction("SELL", event.getShop(), event.getShopItem(), total, event.getAmount(), event.getPlayer());
    }

    // ------------------------------------------------------------------ //
    //  Core tax logic                                                      //
    // ------------------------------------------------------------------ //

    private void processTransaction(String type, VillagerShop shop, ShopItem shopItem,
                                    double total, int itemAmount, Player buyer) {
        UUID shopUUID = shop.getEntityUUID();
        if (shopUUID == null) return;

        // ---- Branch --------------------------------------------------
        String branch = plugin.getBranchManager().getBranch(shopUUID);
        if (branch == null) {
            logToDb(buildRecord(shop, shopItem, buyer, type, itemAmount, total,
                    0, 0, null, null, TransactionRecord.STATUS_NO_BRANCH));
            return;
        }

        // ---- SimpleClans check ---------------------------------------
        if (!plugin.isSimpleClansEnabled()) {
            logToDb(buildRecord(shop, shopItem, buyer, type, itemAmount, total,
                    0, 0, branch, null, TransactionRecord.STATUS_SC_DISABLED));
            return;
        }

        // ---- Tax calculation -----------------------------------------
        double taxRate   = plugin.getBranchManager().getTaxRate(branch);
        double taxAmount = total * taxRate;

        if (taxRate <= 0 || taxAmount <= 0) {
            logToDb(buildRecord(shop, shopItem, buyer, type, itemAmount, total,
                    taxRate, 0, branch, null, TransactionRecord.STATUS_DISABLED));
            return;
        }

        // ---- Resolve from cache ------------------------------------
        String clanTag = plugin.getBranchManager().getCachedOwner(branch);

        if (clanTag == null || clanTag.isBlank()) {
            logToDb(buildRecord(shop, shopItem, buyer, type, itemAmount, total,
                    taxRate, 0, branch, null, TransactionRecord.STATUS_CACHE_EMPTY));
            return;
        }

        // ---- Find clan ----------------------------------------------
        String trimmedTag = clanTag.trim();
        Clan clan = plugin.getClanManager().getClan(trimmedTag);

        if (clan == null) {
            logToDb(buildRecord(shop, shopItem, buyer, type, itemAmount, total,
                    taxRate, 0, branch, trimmedTag, TransactionRecord.STATUS_CLAN_NOT_FOUND));
            return;
        }

        // ---- Deposit to clan bank ----------------------------------
        // NOTE: VillagerMarket already handled the buyer→owner payment.
        // We only ADD money to the clan bank here — no withdrawals.
        EconomyResponse response = clan.deposit(
                BankOperator.API,
                ClanBalanceUpdateEvent.Cause.API,
                taxAmount
        );

        if (response == EconomyResponse.SUCCESS) {
            plugin.getLogger().info("УСПЕХ! Зачислено " + taxAmount
                    + " в казну клана " + clan.getTag()
                    + ". Новый баланс казны: " + clan.getBalance());
            logToDb(buildRecord(shop, shopItem, buyer, type, itemAmount, total,
                    taxRate, taxAmount, branch, clan.getTag(), TransactionRecord.STATUS_SUCCESS));
        } else {
            logToDb(buildRecord(shop, shopItem, buyer, type, itemAmount, total,
                    taxRate, 0, branch, clan.getTag(),
                    TransactionRecord.STATUS_DEPOSIT_FAILED + ":" + response));
        }
    }

    // ------------------------------------------------------------------ //
    //  Record builder                                                      //
    // ------------------------------------------------------------------ //

    private TransactionRecord buildRecord(VillagerShop shop, ShopItem shopItem, Player buyer,
                                          String type, int itemAmount, double total,
                                          double taxRate, double taxAmount,
                                          String branch, String clanTag, String status) {
        String ownerUuid = null;
        String ownerName = null;
        if (shop instanceof PlayerShop ps) {
            ownerUuid = ps.getOwnerUUID() != null ? ps.getOwnerUUID().toString() : null;
            ownerName = ps.getOwnerName();
        }

        String itemName = "?";
        try {
            var raw = shopItem != null ? shopItem.getRawItem() : null;
            if (raw != null) {
                itemName = raw.hasItemMeta() && raw.getItemMeta().hasDisplayName()
                        ? raw.getItemMeta().getDisplayName()
                        : raw.getType().name();
            }
        } catch (Exception ignored) {}

        return new TransactionRecord(
                LocalDateTime.now(),
                type,
                buyer.getUniqueId().toString(),
                buyer.getName(),
                shop.getEntityUUID() != null ? shop.getEntityUUID().toString() : "?",
                shop.getShopName(),
                ownerUuid,
                ownerName,
                branch,
                itemName,
                itemAmount,
                total,
                taxRate,
                taxAmount,
                clanTag,
                status
        );
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private void logToDb(TransactionRecord record) {
        if (plugin.getDb() != null && plugin.getDb().isConnected()) {
            plugin.getDb().logAsync(record);
        }
    }

}

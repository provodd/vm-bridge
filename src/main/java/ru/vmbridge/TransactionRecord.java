package ru.vmbridge;

import java.time.LocalDateTime;

/**
 * Immutable snapshot of a single VillagerMarket trade event,
 * used for async MySQL logging.
 */
public record TransactionRecord(
        LocalDateTime timestamp,
        String eventType,         // BUY | SELL
        String buyerUuid,
        String buyerName,
        String shopUuid,
        String shopName,
        String shopOwnerUuid,     // null for AdminShop
        String shopOwnerName,     // null for AdminShop
        String branch,            // null if shop has no branch assigned
        String itemName,
        int itemAmount,
        double transactionValue,  // total price of the transaction
        double taxRate,           // 0.0 if no branch / no tax
        double taxAmount,         // actual amount deposited to clan bank
        String taxClan,           // clan tag, or null
        String taxStatus          // see TaxStatus constants
) {
    public static final String STATUS_SUCCESS       = "SUCCESS";
    public static final String STATUS_NO_BRANCH     = "NO_BRANCH";
    public static final String STATUS_NO_PLACEHOLDER= "NO_PLACEHOLDER";
    public static final String STATUS_PAPI_UNRESOLVED = "PAPI_UNRESOLVED";
    public static final String STATUS_CLAN_NOT_FOUND= "CLAN_NOT_FOUND";
    public static final String STATUS_SC_DISABLED   = "SIMPLECLANS_DISABLED";
    public static final String STATUS_DEPOSIT_FAILED= "DEPOSIT_FAILED";
    public static final String STATUS_DISABLED      = "TAX_DISABLED";
    public static final String STATUS_CACHE_EMPTY   = "CACHE_EMPTY";
}

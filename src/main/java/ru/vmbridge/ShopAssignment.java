package ru.vmbridge;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable snapshot of a single shop-to-branch assignment,
 * stored in MySQL and cached in memory.
 */
public record ShopAssignment(
        UUID   shopUuid,
        String shopName,
        String branch,
        String ownerUuid,   // null for AdminShop
        String ownerName,   // null for AdminShop
        String assignedBy,  // name of the admin who ran /vmbridge setbranch
        LocalDateTime assignedAt
) {}

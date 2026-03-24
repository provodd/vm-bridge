package ru.vmbridge;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.sacredlabyrinth.phaed.simpleclans.Clan;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Registers PlaceholderAPI placeholders for VMBridge.
 *
 * Available placeholders:
 *   %vmbridge_<branch>_clan%         — plain clan tag (e.g. "warriors")
 *   %vmbridge_<branch>_clan_colored% — colored clan tag (e.g. "§6warriors")
 *
 * Where <branch> is one of: green, blue, pink, orange.
 * Values are served from the in-memory owner cache — no I/O on every call.
 */
public class VMBridgePlaceholders extends PlaceholderExpansion {

    private final VMBridge plugin;

    public VMBridgePlaceholders(VMBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "vmbridge";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** Keep registration alive across /papi reload */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        for (String branch : BranchManager.VALID_BRANCHES) {
            if (params.equals(branch + "_clan")) {
                return getPlain(branch);
            }
            if (params.equals(branch + "_clan_colored")) {
                return getColored(branch);
            }
        }
        return null; // unknown placeholder — let PAPI handle it
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private String getPlain(String branch) {
        String tag = plugin.getBranchManager().getCachedOwner(branch);
        return tag != null ? tag : "";
    }

    private String getColored(String branch) {
        String tag = plugin.getBranchManager().getCachedOwner(branch);
        if (tag == null || tag.isBlank()) return "";

        if (plugin.isSimpleClansEnabled()) {
            Clan clan = plugin.getClanManager().getClan(tag);
            if (clan != null) {
                return clan.getColorTag();
            }
        }

        return tag;
    }
}

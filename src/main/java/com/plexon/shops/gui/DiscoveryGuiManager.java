package com.plexon.shops.gui;

import com.plexon.shops.config.GuiLayout;
import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.integrations.VaultEconomyHook;
import com.plexon.shops.messages.MessageService;
import com.plexon.shops.models.Category;
import com.plexon.shops.models.DirectoryFilter;
import com.plexon.shops.models.Shop;
import com.plexon.shops.models.ShopStatus;
import com.plexon.shops.services.ChatPromptService;
import com.plexon.shops.services.DiscoveryService;
import com.plexon.shops.services.ShopAvailabilityResolver;
import com.plexon.shops.services.ShopService;
import com.plexon.shops.services.TeleportService;
import com.plexon.shops.util.ItemStackCodec;
import com.plexon.shops.util.MainThread;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Discovery-first Phase 2 browser layered around the mature 2.2.1 management engine. */
public final class DiscoveryGuiManager {
    private final JavaPlugin plugin;
    private final ShopService shops;
    private final DiscoveryService discovery;
    private final ShopAvailabilityResolver availability;
    private final TeleportService teleports;
    private final ChatPromptService prompts;
    private final VaultEconomyHook economy;
    private final GuiManager legacy;
    private final Supplier<PluginConfig> config;
    private final Supplier<MessageService> messages;
    private final ItemFactory items = new ItemFactory();

    public DiscoveryGuiManager(
            JavaPlugin plugin,
            ShopService shops,
            DiscoveryService discovery,
            ShopAvailabilityResolver availability,
            TeleportService teleports,
            ChatPromptService prompts,
            VaultEconomyHook economy,
            GuiManager legacy,
            Supplier<PluginConfig> config,
            Supplier<MessageService> messages
    ) {
        this.plugin = plugin;
        this.shops = shops;
        this.discovery = discovery;
        this.availability = availability;
        this.teleports = teleports;
        this.prompts = prompts;
        this.economy = economy;
        this.legacy = legacy;
        this.config = config;
        this.messages = messages;
    }

    public void openHub(Player player) {
        withDiscovery(player, () -> renderHub(player));
    }

    public void openBrowse(Player player) {
        withDiscovery(player, () -> renderBrowse(player, 0, DirectoryFilter.ALL));
    }

    public void openAdmin(Player player) {
        if (!player.hasPermission("plexonshops.admin")) {
            messages.get().send(player, "no-permission");
            return;
        }
        withDiscovery(player, () -> renderList(player, DiscoveryGuiType.ADMIN, 0, "", shops.directory(DirectoryFilter.ALL)));
    }

    public void handleClick(InventoryClickEvent event, DiscoveryGuiHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.35F, 1.15F);
        switch (holder.type()) {
            case HUB -> handleHub(player, slot);
            case BROWSE -> handleBrowse(player, holder, slot);
            case PROFILE -> handleProfile(player, holder, slot);
            case FEATURED, FAVORITES, RECENT, SEARCH_RESULTS, ADMIN -> handleList(player, holder, slot);
            case ADMIN_PROFILE -> handleAdminProfile(player, holder, slot);
        }
    }

    private void renderHub(Player player) {
        GuiLayout layout = config.get().gui("discovery-hub", 27);
        DiscoveryGuiHolder holder = holder(DiscoveryGuiType.HUB, null, 0, DirectoryFilter.ALL, "");
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Shop Discovery</bold></gradient>"));
        put(inventory, layout.slot("featured", 10), items.create(
                Material.GOLD_INGOT,
                messages.get().raw("<gold><bold>Featured</bold></gold>"),
                List.of(messages.get().raw("<gray>Curated shops:</gray> <white>" + discovery.featuredShops().size() + "</white>")),
                false));
        put(inventory, layout.slot("browse", 11), items.create(
                Material.COMPASS,
                messages.get().raw("<aqua><bold>Browse</bold></aqua>"),
                List.of(messages.get().raw("<gray>Explore the player shop directory.</gray>")),
                false));
        put(inventory, layout.slot("categories", 12), items.create(
                Material.CHEST,
                messages.get().raw("<white><bold>Categories</bold></white>"),
                List.of(messages.get().raw("<gray>Filter by shop category.</gray>")),
                false));
        put(inventory, layout.slot("search", 13), items.create(
                Material.SPYGLASS,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Search</bold></gradient>"),
                List.of(messages.get().raw("<gray>Find by shop, owner, or category.</gray>")),
                false));
        put(inventory, layout.slot("favorites", 14), items.create(
                Material.NETHER_STAR,
                messages.get().raw("<yellow><bold>Favorites</bold></yellow>"),
                List.of(messages.get().raw("<gray>Saved shops:</gray> <white>" + discovery.favoriteCount(player.getUniqueId()) + "</white>")),
                false));
        put(inventory, layout.slot("recent", 15), items.create(
                Material.CLOCK,
                messages.get().raw("<blue><bold>Recently Visited</bold></blue>"),
                List.of(messages.get().raw("<gray>Return to successful visits.</gray>")),
                false));
        put(inventory, layout.slot("my-shop", 16), items.create(
                Material.ENDER_CHEST,
                messages.get().raw("<green><bold>My Shops</bold></green>"),
                List.of(messages.get().raw("<gray>Create, configure, monitor, and open/close.</gray>")),
                false));
        put(inventory, layout.slot("help", 22), items.create(
                Material.KNOWLEDGE_BOOK,
                messages.get().raw("<gray><bold>Help</bold></gray>"),
                List.of(messages.get().raw("<gray>Discovery and owner controls.</gray>")),
                false));
        Material lastMaterial = player.hasPermission("plexonshops.admin") ? Material.COMMAND_BLOCK : Material.BARRIER;
        Component lastName = player.hasPermission("plexonshops.admin")
                ? messages.get().raw("<red><bold>Administration</bold></red>")
                : messages.get().raw("<red><bold>Close</bold></red>");
        put(inventory, layout.slot("admin-or-close", 26), items.create(lastMaterial, lastName, List.of(), false));
        player.openInventory(inventory);
    }

    private void renderBrowse(Player player, int requestedPage, DirectoryFilter filter) {
        GuiLayout layout = config.get().gui("discovery-browse", 54);
        List<Integer> contentSlots = contentSlots(layout, 9, 45);
        ShopService.DirectoryPage page = shops.directoryPage(filter, requestedPage, contentSlots.size());
        DiscoveryGuiHolder holder = holder(DiscoveryGuiType.BROWSE, null, page.page(), filter, "");
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Browse Shops</bold></gradient>"));
        for (DirectoryFilter candidate : DirectoryFilter.values()) {
            int slot = candidate.ordinal();
            put(inventory, slot, items.create(
                    candidate.icon(),
                    messages.get().get("gui.filter-" + candidate.key()),
                    List.of(),
                    candidate == filter));
        }
        put(inventory, layout.slot("home", 8), button(Material.DARK_OAK_DOOR, "<gray><bold>Home</bold></gray>"));
        for (int i = 0; i < page.items().size(); i++) {
            int slot = contentSlots.get(i);
            Shop shop = page.items().get(i);
            holder.target(slot, shop.id());
            put(inventory, slot, shopCard(player, shop));
        }
        put(inventory, layout.slot("previous", 45), button(Material.ARROW, "<aqua><bold>Previous</bold></aqua>"));
        put(inventory, layout.slot("page", 49), items.create(Material.PAPER,
                messages.get().raw("<white><bold>Page " + (page.page() + 1) + "/" + page.pages() + "</bold></white>"),
                List.of(), false));
        put(inventory, layout.slot("search", 50), button(Material.SPYGLASS, "<aqua><bold>Search</bold></aqua>"));
        put(inventory, layout.slot("next", 53), button(Material.ARROW, "<aqua><bold>Next</bold></aqua>"));
        player.openInventory(inventory);
    }

    private void openProfile(Player player, UUID shopId) {
        withDiscovery(player, () -> renderProfile(player, shopId));
    }

    private void renderProfile(Player player, UUID shopId) {
        Shop shop = shops.find(shopId).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            renderHub(player);
            return;
        }
        ShopAvailabilityResolver.Resolution state = availability.resolve(shop);
        GuiLayout layout = config.get().gui("shop-profile", 27);
        DiscoveryGuiHolder holder = holder(DiscoveryGuiType.PROFILE, shop.id(), 0, DirectoryFilter.ALL, "");
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Shop Profile</bold></gradient>"));

        ItemStack base = ItemStackCodec.decode(shop.displayIconData()).orElseGet(() -> ownerHead(shop));
        List<Component> lore = new ArrayList<>();
        for (String line : shop.description()) {
            lore.add(messages.get().stored(line));
        }
        if (!lore.isEmpty()) {
            lore.add(Component.empty());
        }
        lore.add(messages.get().raw("<gray>Owner:</gray> <white>" + escapePlain(shop.ownerName()) + "</white>"));
        lore.add(messages.get().raw("<gray>Category:</gray> <aqua>" + categoryNames(shop.categories()) + "</aqua>"));
        lore.add(messages.get().raw("<gray>Status:</gray> ").append(availabilityComponent(state)));
        lore.add(messages.get().raw("<gray>World:</gray> <white>" + escapePlain(shop.location().worldName()) + "</white>"));
        lore.add(messages.get().raw(String.format(Locale.ROOT,
                "<gray>Rating:</gray> <gold>%.2f/5</gold> <dark_gray>•</dark_gray> <gray>Visits:</gray> <white>%d</white>",
                shop.averageRating(), shop.visitors().totalVisits())));
        lore.add(messages.get().raw("<gray>Teleport fee:</gray> <green>" + economy.format(shop.teleportFee()) + "</green>"));
        if (discovery.isFeatured(shop.id())) {
            lore.add(messages.get().raw("<gold>★ Featured shop</gold>"));
        }
        put(inventory, layout.slot("info", 4), items.decorate(base,
                messages.get().get("gui.shop-name", messages.get().storedTag("shop", shop.name())), lore, state.teleportable()));

        Material teleportMaterial = state.teleportable() ? Material.ENDER_PEARL : Material.BARRIER;
        put(inventory, layout.slot("teleport", 10), items.create(
                teleportMaterial,
                state.teleportable()
                        ? messages.get().raw("<green><bold>Teleport</bold></green>")
                        : messages.get().raw("<red><bold>Unavailable</bold></red>"),
                List.of(messages.get().raw("<gray>Availability:</gray> ").append(availabilityComponent(state))),
                state.teleportable()));
        boolean favorite = discovery.isFavorite(player.getUniqueId(), shop.id());
        put(inventory, layout.slot("favorite", 12), items.create(
                favorite ? Material.NETHER_STAR : Material.GRAY_DYE,
                favorite
                        ? messages.get().raw("<yellow><bold>Favorited</bold></yellow>")
                        : messages.get().raw("<gray><bold>Add Favorite</bold></gray>"),
                List.of(messages.get().raw("<gray>Save this shop for quick return.</gray>")),
                favorite));
        put(inventory, layout.slot("rate", 14), items.create(
                Material.NETHER_STAR,
                messages.get().raw("<gold><bold>Rate Shop</bold></gold>"),
                List.of(messages.get().raw("<gray>One authoritative rating per player.</gray>")),
                false));
        if (shop.ownerUuid().equals(player.getUniqueId()) && player.hasPermission("plexonshops.create")) {
            put(inventory, layout.slot("context", 16), items.create(
                    Material.CHEST,
                    messages.get().raw("<aqua><bold>Manage This Shop</bold></aqua>"),
                    List.of(), false));
        } else {
            put(inventory, layout.slot("context", 16), items.create(
                    Material.ITEM_FRAME,
                    messages.get().raw("<blue><bold>Showcased Items</bold></blue>"),
                    List.of(messages.get().raw("<gray>Listings:</gray> <white>" + shop.labeledItems().size() + "</white>")),
                    false));
        }
        put(inventory, layout.slot("back", 22), button(Material.DARK_OAK_DOOR, "<gray><bold>Back to Browse</bold></gray>"));
        put(inventory, layout.slot("home", 26), button(Material.COMPASS, "<aqua><bold>Discovery Home</bold></aqua>"));
        player.openInventory(inventory);
    }

    private void renderList(Player player, DiscoveryGuiType type, int requestedPage, String query, List<Shop> values) {
        GuiLayout layout = config.get().gui("discovery-list", 54);
        List<Integer> contentSlots = contentSlots(layout, 0, 45);
        PageSlice<Shop> page = slice(values, requestedPage, contentSlots.size());
        DiscoveryGuiHolder holder = holder(type, null, page.page(), DirectoryFilter.ALL, query);
        Inventory inventory = createInventory(holder, layout, listTitle(type));
        for (int i = 0; i < page.items().size(); i++) {
            int slot = contentSlots.get(i);
            Shop shop = page.items().get(i);
            holder.target(slot, shop.id());
            put(inventory, slot, shopCard(player, shop));
        }
        put(inventory, layout.slot("previous", 45), button(Material.ARROW, "<aqua><bold>Previous</bold></aqua>"));
        put(inventory, layout.slot("home", 49), button(Material.COMPASS, "<aqua><bold>Discovery Home</bold></aqua>"));
        put(inventory, layout.slot("next", 53), button(Material.ARROW, "<aqua><bold>Next</bold></aqua>"));
        player.openInventory(inventory);
    }

    private void renderAdminProfile(Player player, UUID shopId) {
        if (!player.hasPermission("plexonshops.admin")) {
            messages.get().send(player, "no-permission");
            return;
        }
        Shop shop = shops.find(shopId).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            openAdmin(player);
            return;
        }
        ShopAvailabilityResolver.Resolution state = availability.resolve(shop);
        GuiLayout layout = config.get().gui("admin-profile", 27);
        DiscoveryGuiHolder holder = holder(DiscoveryGuiType.ADMIN_PROFILE, shop.id(), 0, DirectoryFilter.ALL, "");
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<red><bold>Shop Administration</bold></red>"));
        put(inventory, layout.slot("info", 4), shopCard(player, shop));
        put(inventory, layout.slot("featured", 10), items.create(
                discovery.isFeatured(shop.id()) ? Material.GOLD_BLOCK : Material.GOLD_NUGGET,
                discovery.isFeatured(shop.id())
                        ? messages.get().raw("<gold><bold>Unfeature</bold></gold>")
                        : messages.get().raw("<gold><bold>Feature Shop</bold></gold>"),
                List.of(messages.get().raw("<gray>Administrative discovery state.</gray>")),
                discovery.isFeatured(shop.id())));
        put(inventory, layout.slot("status", 12), items.create(
                shop.status().icon(),
                messages.get().raw("<yellow><bold>Cycle Status</bold></yellow>"),
                List.of(messages.get().raw("<gray>Current:</gray> ").append(availabilityComponent(state))),
                shop.status() == ShopStatus.OPEN));
        put(inventory, layout.slot("profile", 14), button(Material.BOOK, "<aqua><bold>Public Profile</bold></aqua>"));
        put(inventory, layout.slot("diagnostic", 16), items.create(
                Material.COMPARATOR,
                messages.get().raw("<blue><bold>Teleport Diagnostic</bold></blue>"),
                List.of(
                        messages.get().raw("<gray>State:</gray> ").append(availabilityComponent(state)),
                        messages.get().raw("<gray>Reason:</gray> <white>" + state.reason() + "</white>"),
                        messages.get().raw("<gray>Shop ID:</gray> <white>" + shop.id() + "</white>")),
                false));
        put(inventory, layout.slot("back", 22), button(Material.DARK_OAK_DOOR, "<gray><bold>Back</bold></gray>"));
        player.openInventory(inventory);
    }

    private void handleHub(Player player, int slot) {
        GuiLayout layout = config.get().gui("discovery-hub", 27);
        if (slot == layout.slot("featured", 10)) {
            renderList(player, DiscoveryGuiType.FEATURED, 0, "", discovery.featuredShops());
        } else if (slot == layout.slot("browse", 11) || slot == layout.slot("categories", 12)) {
            renderBrowse(player, 0, DirectoryFilter.ALL);
        } else if (slot == layout.slot("search", 13)) {
            beginSearch(player);
        } else if (slot == layout.slot("favorites", 14)) {
            renderList(player, DiscoveryGuiType.FAVORITES, 0, "", discovery.favorites(player.getUniqueId()));
        } else if (slot == layout.slot("recent", 15)) {
            renderList(player, DiscoveryGuiType.RECENT, 0, "", discovery.recent(player.getUniqueId()));
        } else if (slot == layout.slot("my-shop", 16)) {
            if (!player.hasPermission("plexonshops.create")) {
                messages.get().send(player, "no-permission");
            } else {
                legacy.openOwnerManagement(player);
            }
        } else if (slot == layout.slot("help", 22)) {
            player.closeInventory();
            player.sendMessage(messages.get().prefix().append(messages.get().raw(
                    "<gray><white>/pshops</white> discover • <white>/pshops browse</white> browse • "
                            + "<white>/pshops manage</white> owner controls.</gray>")));
        } else if (slot == layout.slot("admin-or-close", 26)) {
            if (player.hasPermission("plexonshops.admin")) {
                openAdmin(player);
            } else {
                player.closeInventory();
            }
        }
    }

    private void handleBrowse(Player player, DiscoveryGuiHolder holder, int slot) {
        GuiLayout layout = config.get().gui("discovery-browse", 54);
        for (DirectoryFilter filter : DirectoryFilter.values()) {
            if (slot == filter.ordinal()) {
                renderBrowse(player, 0, filter);
                return;
            }
        }
        if (slot == layout.slot("home", 8)) {
            renderHub(player);
        } else if (slot == layout.slot("previous", 45)) {
            renderBrowse(player, holder.page() - 1, holder.filter());
        } else if (slot == layout.slot("next", 53)) {
            renderBrowse(player, holder.page() + 1, holder.filter());
        } else if (slot == layout.slot("search", 50)) {
            beginSearch(player);
        } else {
            holder.target(slot).ifPresent(shopId -> openProfile(player, shopId));
        }
    }

    private void handleProfile(Player player, DiscoveryGuiHolder holder, int slot) {
        Shop shop = shops.find(holder.shopId()).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            renderHub(player);
            return;
        }
        GuiLayout layout = config.get().gui("shop-profile", 27);
        if (slot == layout.slot("teleport", 10)) {
            player.closeInventory();
            teleports.request(player, shop);
        } else if (slot == layout.slot("favorite", 12)) {
            finish(player, discovery.toggleFavorite(player.getUniqueId(), shop.id()), ignored -> renderProfile(player, shop.id()));
        } else if (slot == layout.slot("rate", 14)) {
            legacy.openRating(player, shop.id());
        } else if (slot == layout.slot("context", 16)
                && shop.ownerUuid().equals(player.getUniqueId())
                && player.hasPermission("plexonshops.create")) {
            legacy.openManage(player, shop.id());
        } else if (slot == layout.slot("back", 22)) {
            renderBrowse(player, 0, DirectoryFilter.ALL);
        } else if (slot == layout.slot("home", 26)) {
            renderHub(player);
        }
    }

    private void handleList(Player player, DiscoveryGuiHolder holder, int slot) {
        GuiLayout layout = config.get().gui("discovery-list", 54);
        if (slot == layout.slot("home", 49)) {
            renderHub(player);
            return;
        }
        if (slot == layout.slot("previous", 45) || slot == layout.slot("next", 53)) {
            int targetPage = holder.page() + (slot == layout.slot("next", 53) ? 1 : -1);
            switch (holder.type()) {
                case FEATURED -> renderList(player, holder.type(), targetPage, "", discovery.featuredShops());
                case FAVORITES -> renderList(player, holder.type(), targetPage, "", discovery.favorites(player.getUniqueId()));
                case RECENT -> renderList(player, holder.type(), targetPage, "", discovery.recent(player.getUniqueId()));
                case SEARCH_RESULTS -> renderList(player, holder.type(), targetPage, holder.query(), search(holder.query()));
                case ADMIN -> renderList(player, holder.type(), targetPage, "", shops.directory(DirectoryFilter.ALL));
                default -> { }
            }
            return;
        }
        holder.target(slot).ifPresent(shopId -> {
            if (holder.type() == DiscoveryGuiType.ADMIN) {
                renderAdminProfile(player, shopId);
            } else {
                openProfile(player, shopId);
            }
        });
    }

    private void handleAdminProfile(Player player, DiscoveryGuiHolder holder, int slot) {
        if (!player.hasPermission("plexonshops.admin")) {
            messages.get().send(player, "no-permission");
            return;
        }
        Shop shop = shops.find(holder.shopId()).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            openAdmin(player);
            return;
        }
        GuiLayout layout = config.get().gui("admin-profile", 27);
        if (slot == layout.slot("featured", 10)) {
            boolean next = !discovery.isFeatured(shop.id());
            finish(player, discovery.setFeatured(shop.id(), next), ignored -> renderAdminProfile(player, shop.id()));
        } else if (slot == layout.slot("status", 12)) {
            ShopStatus next = shop.status().next();
            if (next == ShopStatus.OPEN && !availability.resolveDestination(shop).teleportable()) {
                messages.get().send(player, "invalid-world");
                return;
            }
            finish(player, shops.setStatus(shop.id(), next), ignored -> renderAdminProfile(player, shop.id()));
        } else if (slot == layout.slot("profile", 14)) {
            renderProfile(player, shop.id());
        } else if (slot == layout.slot("back", 22)) {
            openAdmin(player);
        }
    }

    private void beginSearch(Player player) {
        prompts.begin(player, "prompt-search", input -> {
            String query = input.strip();
            if (query.isBlank() || query.length() > 64) {
                messages.get().send(player, "invalid-text");
                renderHub(player);
                return;
            }
            renderList(player, DiscoveryGuiType.SEARCH_RESULTS, 0, query, search(query));
        });
    }

    private List<Shop> search(String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return shops.directory(DirectoryFilter.ALL).stream()
                .filter(shop -> messages.get().plainStored(shop.name()).toLowerCase(Locale.ROOT).contains(needle)
                        || shop.ownerName().toLowerCase(Locale.ROOT).contains(needle)
                        || shop.categories().stream().map(Category::key).anyMatch(key -> key.contains(needle)))
                .toList();
    }

    private ItemStack shopCard(Player viewer, Shop shop) {
        ItemStack base = ItemStackCodec.decode(shop.displayIconData()).orElseGet(() -> ownerHead(shop));
        ShopAvailabilityResolver.Resolution state = availability.resolve(shop);
        List<Component> lore = new ArrayList<>();
        if (!shop.description().isEmpty()) {
            lore.add(messages.get().stored(shop.description().getFirst()));
            lore.add(Component.empty());
        }
        lore.add(messages.get().raw("<gray>Owner:</gray> <white>" + escapePlain(shop.ownerName()) + "</white>"));
        lore.add(messages.get().raw("<gray>Category:</gray> <aqua>" + categoryNames(shop.categories()) + "</aqua>"));
        lore.add(messages.get().raw("<gray>Status:</gray> ").append(availabilityComponent(state)));
        lore.add(messages.get().raw(String.format(Locale.ROOT,
                "<gray>Rating:</gray> <gold>%.2f/5</gold> <dark_gray>•</dark_gray> <gray>Visits:</gray> <white>%d</white>",
                shop.averageRating(), shop.visitors().totalVisits())));
        lore.add(messages.get().raw("<gray>Fee:</gray> <green>" + economy.format(shop.teleportFee()) + "</green>"));
        if (discovery.isFeatured(shop.id())) {
            lore.add(messages.get().raw("<gold>★ Featured</gold>"));
        }
        if (discovery.isFavorite(viewer.getUniqueId(), shop.id())) {
            lore.add(messages.get().raw("<yellow>★ Favorite</yellow>"));
        }
        lore.add(Component.empty());
        lore.add(messages.get().raw("<yellow>Click</yellow> <gray>to review this shop</gray>"));
        return items.decorate(base,
                messages.get().get("gui.shop-name", messages.get().storedTag("shop", shop.name())),
                lore,
                state.teleportable());
    }

    private Component availabilityComponent(ShopAvailabilityResolver.Resolution state) {
        return switch (state.state()) {
            case OPEN -> messages.get().raw("<green><bold>OPEN</bold></green>");
            case CLOSED -> messages.get().raw("<red><bold>CLOSED</bold></red>");
            case MAINTENANCE -> messages.get().raw("<gold><bold>MAINTENANCE</bold></gold>");
            case UNAVAILABLE -> messages.get().raw("<red><bold>UNAVAILABLE</bold></red>");
        };
    }

    private Component listTitle(DiscoveryGuiType type) {
        return switch (type) {
            case FEATURED -> messages.get().raw("<gold><bold>Featured Shops</bold></gold>");
            case FAVORITES -> messages.get().raw("<yellow><bold>Favorite Shops</bold></yellow>");
            case RECENT -> messages.get().raw("<blue><bold>Recently Visited</bold></blue>");
            case SEARCH_RESULTS -> messages.get().raw("<aqua><bold>Search Results</bold></aqua>");
            case ADMIN -> messages.get().raw("<red><bold>Shop Administration</bold></red>");
            default -> messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Player Shops</bold></gradient>");
        };
    }

    private void withDiscovery(Player player, Runnable success) {
        if (discovery.isLoaded(player.getUniqueId())) {
            success.run();
            return;
        }
        MainThread.whenComplete(plugin, discovery.preload(player.getUniqueId()), ignored -> success.run(), error -> {
            plugin.getLogger().log(Level.WARNING, "Could not load player discovery state", error);
            messages.get().send(player, "storage-error");
        });
    }

    private <T> void finish(Player player, CompletableFuture<T> future, Consumer<T> success) {
        MainThread.whenComplete(plugin, future, success, error -> {
            plugin.getLogger().log(Level.SEVERE, "PlexonShops discovery operation failed", error);
            messages.get().send(player, "storage-error");
        });
    }

    private DiscoveryGuiHolder holder(
            DiscoveryGuiType type,
            UUID shopId,
            int page,
            DirectoryFilter filter,
            String query
    ) {
        return new DiscoveryGuiHolder(type, shopId, page, filter, query);
    }

    private Inventory createInventory(DiscoveryGuiHolder holder, GuiLayout layout, Component title) {
        Inventory inventory = Bukkit.createInventory(holder, layout.size(), title);
        holder.attach(inventory);
        ItemStack filler = items.create(Material.GRAY_STAINED_GLASS_PANE,
                messages.get().get("gui.filler"), List.of(), false);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        return inventory;
    }

    private ItemStack button(Material material, String miniMessage) {
        return items.create(material, messages.get().raw(miniMessage), List.of(), false);
    }

    private void put(Inventory inventory, int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    private List<Integer> contentSlots(GuiLayout layout, int start, int endExclusive) {
        return layout.contentSlots().isEmpty()
                ? IntStream.range(start, Math.min(endExclusive, layout.size())).boxed().toList()
                : layout.contentSlots();
    }

    private ItemStack ownerHead(Shop shop) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        Player online = Bukkit.getPlayer(shop.ownerUuid());
        OfflinePlayer owner = online == null ? Bukkit.getOfflinePlayer(shop.ownerUuid()) : online;
        meta.setOwningPlayer(owner);
        head.setItemMeta(meta);
        return head;
    }

    private String categoryNames(Set<Category> categories) {
        return categories.stream().map(Category::key).map(this::titleCase).collect(Collectors.joining(", "));
    }

    private String titleCase(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String escapePlain(String value) {
        return value == null ? "" : value.replace("<", "").replace(">", "");
    }

    private static <T> PageSlice<T> slice(List<T> values, int requestedPage, int pageSize) {
        int safePageSize = Math.max(1, pageSize);
        int pages = Math.max(1, (int) Math.ceil(values.size() / (double) safePageSize));
        int page = Math.clamp(requestedPage, 0, pages - 1);
        int from = Math.min(values.size(), page * safePageSize);
        int to = Math.min(values.size(), from + safePageSize);
        return new PageSlice<>(page, pages, List.copyOf(values.subList(from, to)));
    }

    private record PageSlice<T>(int page, int pages, List<T> items) {
    }
}

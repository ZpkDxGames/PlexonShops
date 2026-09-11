package com.plexon.shops.gui;

import com.plexon.shops.config.GuiLayout;
import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.gui.view.ShopCardViewModel;
import com.plexon.shops.gui.view.ShopDirectoryViewModel;
import com.plexon.shops.gui.view.ShopProfileViewModel;
import com.plexon.shops.gui.view.ShopUiPolicy;
import com.plexon.shops.gui.view.TeleportPreviewViewModel;
import com.plexon.shops.integrations.VaultEconomyHook;
import com.plexon.shops.messages.MessageService;
import com.plexon.shops.models.Category;
import com.plexon.shops.models.DirectoryFilter;
import com.plexon.shops.models.LabeledItem;
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
import net.kyori.adventure.text.format.TextDecoration;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.IntStream;

/** Phase 3 marketplace-directory UX layered over the Phase 2 cache and transaction authorities. */
public final class DiscoveryGuiManager {
    private static final List<Integer> DEFAULT_RECOMMENDED_SLOTS = List.of(28, 30, 32, 34);
    private static final List<Integer> DEFAULT_SHOWCASE_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    );

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
    private final Map<TemplateKey, ItemStack> staticTemplates = new HashMap<>();
    private final Map<UUID, CachedShopIcon> shopIconCache = new HashMap<>();
    private final Map<UUID, ItemStack> showcaseItemCache = new HashMap<>();

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
        withDiscovery(player, () -> renderList(
                player,
                DiscoveryGuiType.ADMIN,
                0,
                "",
                DirectoryFilter.ALL,
                shops.directory(DirectoryFilter.ALL)
        ));
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
            case HUB -> handleHub(player, holder, slot);
            case BROWSE -> handleBrowse(player, holder, slot);
            case CATEGORIES -> handleCategories(player, slot);
            case PROFILE -> handleProfile(player, holder, slot);
            case SHOWCASE -> handleShowcase(player, holder, slot);
            case TELEPORT_PREVIEW -> handleTeleportPreview(player, holder, slot);
            case RATING -> handleRating(player, holder, slot);
            case MY_SHOPS, FEATURED, FAVORITES, RECENT, SEARCH_RESULTS, ADMIN -> handleList(player, holder, slot);
            case OWNER_PROFILE -> handleOwnerProfile(player, holder, slot);
            case HELP -> handleHelp(player, slot);
            case ADMIN_PROFILE -> handleAdminProfile(player, holder, slot);
        }
    }

    private void renderHub(Player player) {
        GuiLayout layout = config.get().gui("discovery-hub", 54);
        DiscoveryGuiHolder holder = holder(DiscoveryGuiType.HUB, null, 0, DirectoryFilter.ALL, "");
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Plexon Shop Directory</bold></gradient>"));
        boolean compact = layout.size() < 45;

        if (!compact) {
            List<Shop> owned = shops.ownedBy(player.getUniqueId());
            String ownedStatus = owned.isEmpty()
                    ? "No shop"
                    : ShopUiPolicy.status(availability.resolve(owned.getFirst()).state(), availability.resolve(owned.getFirst()).reason()).label();
            put(inventory, layout.slot("summary", 4), items.create(
                    Material.PLAYER_HEAD,
                    messages.get().raw("<white><bold>Your Directory</bold></white>"),
                    List.of(
                            messages.get().raw("<gray>Favorites:</gray> <white>" + discovery.favoriteCount(player.getUniqueId()) + "</white>"),
                            messages.get().raw("<gray>Recent visits:</gray> <white>" + discovery.recent(player.getUniqueId()).size() + "</white>"),
                            messages.get().raw("<gray>Owned shop:</gray> <white>" + escapePlain(ownedStatus) + "</white>")
                    ),
                    false
            ));
        }

        put(inventory, layout.slot("featured", 10), route(
                Material.GOLD_INGOT, "<gold><bold>Featured</bold></gold>",
                "Curated shops worth checking out.", discovery.featuredShops().size()));
        put(inventory, layout.slot("browse", 11), route(
                Material.COMPASS, "<aqua><bold>Browse All</bold></aqua>",
                "Explore every listed player shop.", shops.totalCount()));
        put(inventory, layout.slot("categories", 12), route(
                Material.CHEST, "<white><bold>Categories</bold></white>",
                "Browse by shop specialization.", Category.values().length));
        put(inventory, layout.slot("search", 13), items.create(
                Material.SPYGLASS,
                messages.get().raw("<aqua><bold>Search</bold></aqua>"),
                List.of(messages.get().raw("<gray>Find a shop by name, owner, category, or showcased item.</gray>")),
                false));
        put(inventory, layout.slot("favorites", 14), route(
                Material.NETHER_STAR, "<yellow><bold>Favorites</bold></yellow>",
                "Return to shops you saved.", discovery.favoriteCount(player.getUniqueId())));
        put(inventory, layout.slot("recent", 15), route(
                Material.CLOCK, "<blue><bold>Recent</bold></blue>",
                "Return to shops you successfully visited.", discovery.recent(player.getUniqueId()).size()));
        put(inventory, layout.slot("my-shop", 16), items.create(
                Material.ENDER_CHEST,
                messages.get().raw("<green><bold>My Shops</bold></green>"),
                List.of(messages.get().raw("<gray>Review and manage your directory listings.</gray>")),
                false));
        put(inventory, layout.slot("help", compact ? 22 : 50), button(Material.KNOWLEDGE_BOOK,
                "<gray><bold>Help</bold></gray>"));

        if (!compact) {
            List<Integer> recommendationSlots = layout.contentSlots().isEmpty()
                    ? DEFAULT_RECOMMENDED_SLOTS
                    : layout.contentSlots();
            List<Shop> recommended = recommendedShops();
            for (int index = 0; index < Math.min(recommendationSlots.size(), recommended.size()); index++) {
                Shop shop = recommended.get(index);
                int slot = recommendationSlots.get(index);
                holder.target(slot, shop.id());
                put(inventory, slot, shopCard(player, shop));
            }
            if (recommended.isEmpty() && !recommendationSlots.isEmpty()) {
                put(inventory, recommendationSlots.get(recommendationSlots.size() / 2), emptyItem(
                        Material.GRAY_DYE,
                        "<gray><bold>No recommendations yet</bold></gray>",
                        "Browse All to explore the directory."
                ));
            }
        }

        int finalSlot = layout.slot("admin-or-close", compact ? 26 : 52);
        if (player.hasPermission("plexonshops.admin")) {
            put(inventory, finalSlot, button(Material.COMMAND_BLOCK, "<red><bold>Administration</bold></red>"));
        } else {
            put(inventory, finalSlot, button(Material.BARRIER, "<red><bold>Close</bold></red>"));
        }
        player.openInventory(inventory);
    }

    private void renderBrowse(Player player, int requestedPage, DirectoryFilter filter) {
        GuiLayout layout = config.get().gui("discovery-browse", 54);
        List<Integer> contentSlots = contentSlots(layout, 9, 45);
        List<Shop> values = shops.directory(filter);
        ShopDirectoryViewModel model = directoryModel(player, filterLabel(filter), "", values, requestedPage, contentSlots.size());
        DiscoveryGuiHolder holder = holder(DiscoveryGuiType.BROWSE, null, model.page(), filter, "");
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Browse Shops</bold></gradient> <dark_gray>•</dark_gray> <gray>" + escapePlain(filterLabel(filter)) + "</gray>"));

        for (DirectoryFilter candidate : DirectoryFilter.values()) {
            int slot = candidate.ordinal();
            put(inventory, slot, items.create(
                    candidate.icon(),
                    messages.get().raw((candidate == filter ? "<aqua><bold>" : "<white>")
                            + escapePlain(filterLabel(candidate)) + (candidate == filter ? "</bold></aqua>" : "</white>")),
                    List.of(messages.get().raw("<gray>" + shops.directory(candidate).size() + " shops</gray>")),
                    candidate == filter
            ));
        }
        put(inventory, layout.slot("home", 8), button(Material.COMPASS, "<gray><bold>Directory Home</bold></gray>"));
        renderDirectoryCards(inventory, holder, contentSlots, model);
        put(inventory, layout.slot("previous", 45), button(Material.ARROW, "<gray><bold>Previous</bold></gray>"));
        if (layout.slots().containsKey("back")) {
            put(inventory, layout.slot("back", 48), button(Material.DARK_OAK_DOOR, "<gray><bold>Back</bold></gray>"));
        }
        put(inventory, layout.slot("page", 49), pageItem(model));
        put(inventory, layout.slot("search", 50), button(Material.SPYGLASS, "<aqua><bold>Search</bold></aqua>"));
        if (layout.slots().containsKey("close")) {
            put(inventory, layout.slot("close", 52), button(Material.BARRIER, "<red><bold>Close</bold></red>"));
        }
        put(inventory, layout.slot("next", 53), button(Material.ARROW, "<gray><bold>Next</bold></gray>"));
        player.openInventory(inventory);
    }

    private void renderCategories(Player player) {
        GuiLayout layout = config.get().gui("directory-categories", 27);
        DiscoveryGuiHolder holder = holder(DiscoveryGuiType.CATEGORIES, null, 0, DirectoryFilter.ALL, "");
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Shop Categories</bold></gradient>"));
        int[] fallbacks = {10, 11, 12, 14, 15, 16};
        int index = 0;
        for (Category category : Category.values()) {
            DirectoryFilter filter = DirectoryFilter.valueOf(category.name());
            int count = shops.directory(filter).size();
            put(inventory, layout.slot(category.key(), fallbacks[index++]), items.create(
                    category.icon(),
                    messages.get().raw("<aqua><bold>" + escapePlain(ShopUiPolicy.categoryLabel(category)) + "</bold></aqua>"),
                    List.of(
                            messages.get().raw("<gray>Available shops:</gray> <white>" + count + "</white>"),
                            Component.empty(),
                            messages.get().raw("<yellow>Click:</yellow> <gray>Browse this category</gray>")
                    ),
                    false
            ));
        }
        put(inventory, layout.slot("back", 22), button(Material.DARK_OAK_DOOR, "<gray><bold>Back</bold></gray>"));
        put(inventory, layout.slot("close", 26), button(Material.BARRIER, "<red><bold>Close</bold></red>"));
        player.openInventory(inventory);
    }

    private void renderProfile(
            Player player,
            UUID shopId,
            DiscoveryGuiType returnType,
            int returnPage,
            DirectoryFilter returnFilter,
            String returnQuery
    ) {
        Shop shop = shops.find(shopId).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            returnTo(player, returnType, returnPage, returnFilter, returnQuery);
            return;
        }
        ShopProfileViewModel model = profileModel(player, shop);
        GuiLayout layout = config.get().gui("shop-profile", 54);
        boolean compact = layout.size() < 45;
        DiscoveryGuiHolder holder = holder(
                DiscoveryGuiType.PROFILE, shop.id(), 0, DirectoryFilter.ALL, "",
                returnType, returnPage, returnFilter, returnQuery, shop.updatedAtEpochSecond());
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Shop Profile</bold></gradient>"));

        if (compact) {
            put(inventory, layout.slot("info", 4), compactProfileInfo(player, shop, model));
            put(inventory, layout.slot("teleport", 10), visitButton(player, shop));
            put(inventory, layout.slot("favorite", 12), favoriteButton(model));
            put(inventory, layout.slot("rate", 14), model.ownerView()
                    ? items.create(Material.GRAY_DYE, messages.get().raw("<gray><bold>Your Shop</bold></gray>"),
                    List.of(messages.get().raw("<gray>You cannot rate your own shop.</gray>")), false)
                    : ratingButton(model));
            put(inventory, layout.slot("context", 16), model.ownerView()
                    ? button(Material.CHEST, "<aqua><bold>Manage This Shop</bold></aqua>")
                    : showcaseButton(model));
            put(inventory, layout.slot("back", 22), button(Material.DARK_OAK_DOOR, "<gray><bold>Back</bold></gray>"));
            put(inventory, layout.slot("home", 26), button(Material.COMPASS, "<aqua><bold>Directory Home</bold></aqua>"));
        } else {
            put(inventory, layout.slot("info", 13), profileIdentity(shop, model));
            put(inventory, layout.slot("stats", 20), profileStats(model));
            put(inventory, layout.slot("status", 22), statusItem(model.status(), model.statusDetail()));
            put(inventory, layout.slot("location", 24), items.create(
                    Material.RECOVERY_COMPASS,
                    messages.get().raw("<white><bold>Location</bold></white>"),
                    List.of(messages.get().raw("<gray>" + escapePlain(model.location()) + "</gray>")),
                    false));
            put(inventory, layout.slot("showcase", 31), showcaseButton(model));
            put(inventory, layout.slot("favorite", 39), favoriteButton(model));
            put(inventory, layout.slot("rate", 41), model.ownerView()
                    ? items.create(Material.GRAY_DYE, messages.get().raw("<gray><bold>Your Rating</bold></gray>"),
                    List.of(messages.get().raw("<gray>You cannot rate your own shop.</gray>")), false)
                    : ratingButton(model));
            if (model.ownerView()) {
                put(inventory, layout.slot("owner-edit", 43), button(Material.CHEST, "<aqua><bold>Edit Listing</bold></aqua>"));
            }
            put(inventory, layout.slot("back", 48), button(Material.DARK_OAK_DOOR, "<gray><bold>Back</bold></gray>"));
            put(inventory, layout.slot("visit", 49), visitButton(player, shop));
            put(inventory, layout.slot("home", 51), button(Material.COMPASS, "<aqua><bold>Directory Home</bold></aqua>"));
            put(inventory, layout.slot("close", 52), button(Material.BARRIER, "<red><bold>Close</bold></red>"));
        }
        player.openInventory(inventory);
    }

    private void renderShowcase(Player player, DiscoveryGuiHolder profileHolder, Shop shop, int requestedPage) {
        GuiLayout layout = config.get().gui("showcase", 54);
        List<Integer> contentSlots = layout.contentSlots().isEmpty() ? DEFAULT_SHOWCASE_SLOTS : layout.contentSlots();
        ShopUiPolicy.PageSlice<LabeledItem> page = ShopUiPolicy.page(shop.labeledItems(), requestedPage, contentSlots.size());
        DiscoveryGuiHolder holder = holder(
                DiscoveryGuiType.SHOWCASE, shop.id(), page.page(), DirectoryFilter.ALL, "",
                profileHolder.returnType(), profileHolder.returnPage(), profileHolder.returnFilter(), profileHolder.returnQuery(),
                shop.updatedAtEpochSecond());
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Showcased Items</bold></gradient>"));
        put(inventory, layout.slot("info", 4), items.create(
                Material.ITEM_FRAME,
                messages.get().raw("<white><bold>What this shop offers</bold></white>"),
                List.of(
                        messages.get().raw("<gray>These are examples showcased by the owner.</gray>"),
                        messages.get().raw("<gray>Visit the shop to trade.</gray>"),
                        Component.empty(),
                        messages.get().raw("<gray>Items shown:</gray> <white>" + shop.labeledItems().size() + "</white>")
                ),
                false));
        if (page.items().isEmpty()) {
            put(inventory, contentSlots.get(contentSlots.size() / 2), emptyItem(
                    Material.GRAY_DYE,
                    "<gray><bold>No showcased items</bold></gray>",
                    "The owner has not added showcase examples yet."
            ));
        } else {
            for (int index = 0; index < page.items().size(); index++) {
                LabeledItem labeled = page.items().get(index);
                ItemStack display = showcaseItem(labeled);
                put(inventory, contentSlots.get(index), items.decorate(
                        display,
                        messages.get().stored(labeled.label()),
                        List.of(
                                messages.get().raw("<gray>Showcase item</gray>"),
                                Component.empty(),
                                messages.get().raw("<gray>Example of what this shop offers.</gray>"),
                                messages.get().raw("<yellow>Visit the shop to trade.</yellow>")
                        ),
                        false
                ));
            }
        }
        put(inventory, layout.slot("previous", 45), button(Material.ARROW, "<gray><bold>Previous</bold></gray>"));
        put(inventory, layout.slot("back", 48), button(Material.DARK_OAK_DOOR, "<gray><bold>Back</bold></gray>"));
        put(inventory, layout.slot("visit", 49), visitButton(player, shop));
        put(inventory, layout.slot("page", 51), items.create(
                Material.PAPER,
                messages.get().raw("<white><bold>Page " + (page.page() + 1) + "/" + page.pages() + "</bold></white>"),
                List.of(messages.get().raw("<gray>" + page.total() + " showcased items</gray>")),
                false));
        put(inventory, layout.slot("close", 52), button(Material.BARRIER, "<red><bold>Close</bold></red>"));
        put(inventory, layout.slot("next", 53), button(Material.ARROW, "<gray><bold>Next</bold></gray>"));
        player.openInventory(inventory);
    }

    private void renderTeleportPreview(Player player, DiscoveryGuiHolder sourceHolder, Shop shop) {
        ShopAvailabilityResolver.Resolution resolved = availability.resolve(shop);
        TeleportPreviewViewModel model = teleportModel(player, shop, resolved);
        GuiLayout layout = config.get().gui("teleport-preview", 27);
        DiscoveryGuiHolder holder = holder(
                DiscoveryGuiType.TELEPORT_PREVIEW, shop.id(), 0, DirectoryFilter.ALL, "",
                sourceHolder.returnType(), sourceHolder.returnPage(), sourceHolder.returnFilter(), sourceHolder.returnQuery(),
                shop.updatedAtEpochSecond());
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Visit Shop</bold></gradient>"));
        put(inventory, layout.slot("info", 4), items.decorate(
                shopIcon(shop),
                messages.get().get("gui.shop-name", messages.get().storedTag("shop", shop.name())),
                List.of(
                        messages.get().raw("<gray>Owner:</gray> <white>" + escapePlain(shop.ownerName()) + "</white>"),
                        messages.get().raw("<gray>Destination:</gray> <white>" + escapePlain(shop.location().worldName()) + "</white>"),
                        messages.get().raw("<gray>Teleport fee:</gray> <green>" + escapePlain(model.fee()) + "</green>"),
                        messages.get().raw("<gray>Your balance:</gray> <white>" + escapePlain(model.balance()) + "</white>"),
                        Component.empty(),
                        statusComponent(model.status()),
                        messages.get().raw("<gray>" + escapePlain(model.detail()) + "</gray>")
                ),
                false));
        put(inventory, layout.slot("confirm", 11), items.create(
                model.ready() ? Material.LIME_CONCRETE : Material.BARRIER,
                model.ready()
                        ? messages.get().raw("<green><bold>Visit Shop</bold></green>")
                        : messages.get().raw("<red><bold>Visit Unavailable</bold></red>"),
                model.ready()
                        ? List.of(
                        messages.get().raw("<gray>Fee:</gray> <white>" + escapePlain(model.fee()) + "</white>"),
                        messages.get().raw("<gray>Balance:</gray> <white>" + escapePlain(model.balance()) + "</white>"),
                        Component.empty(),
                        messages.get().raw("<green>Click: Confirm visit</green>"))
                        : List.of(messages.get().raw("<red>" + escapePlain(model.detail()) + "</red>")),
                model.ready()));
        put(inventory, layout.slot("destination", 13), items.create(
                Material.RECOVERY_COMPASS,
                messages.get().raw("<white><bold>Destination</bold></white>"),
                List.of(
                        messages.get().raw("<gray>World:</gray> <white>" + escapePlain(shop.location().worldName()) + "</white>"),
                        messages.get().raw("<gray>Location:</gray> <white>" + escapePlain(locationLabel(player, shop)) + "</white>")
                ),
                false));
        put(inventory, layout.slot("cancel", 15), button(Material.RED_CONCRETE, "<red><bold>Cancel</bold></red>"));
        put(inventory, layout.slot("close", 26), button(Material.BARRIER, "<red><bold>Close</bold></red>"));
        player.openInventory(inventory);
    }

    private void renderRating(Player player, DiscoveryGuiHolder sourceHolder, Shop shop) {
        GuiLayout layout = config.get().gui("rating", 27);
        DiscoveryGuiHolder holder = holder(
                DiscoveryGuiType.RATING, shop.id(), 0, DirectoryFilter.ALL, "",
                sourceHolder.returnType(), sourceHolder.returnPage(), sourceHolder.returnFilter(), sourceHolder.returnQuery(),
                shop.updatedAtEpochSecond());
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Rate Shop</bold></gradient>"));
        int current = shop.ratingFrom(player.getUniqueId());
        put(inventory, layout.slot("summary", 4), items.create(
                Material.BOOK,
                messages.get().raw("<white><bold>Rating Summary</bold></white>"),
                List.of(
                        messages.get().raw("<gray>Average:</gray> <gold>" + ratingText(shop.averageRating()) + "</gold>"),
                        messages.get().raw("<gray>Ratings:</gray> <white>" + shop.ratings().size() + "</white>"),
                        messages.get().raw("<gray>Your rating:</gray> <white>" + (current == 0 ? "Not rated" : current + "/5") + "</white>"),
                        Component.empty(),
                        messages.get().raw("<gray>Choosing another rating replaces your previous rating.</gray>")
                ),
                false));
        for (int stars = 1; stars <= 5; stars++) {
            ItemStack star = items.create(
                    Material.NETHER_STAR,
                    messages.get().raw("<gold><bold>Rate " + stars + (stars == 1 ? " Star" : " Stars") + "</bold></gold>"),
                    List.of(
                            messages.get().raw("<gray>Set your rating to</gray> <white>" + stars + "/5</white>"),
                            current == stars
                                    ? messages.get().raw("<green>Current rating</green>")
                                    : messages.get().raw("<yellow>Click: Choose this rating</yellow>")
                    ),
                    current == stars
            );
            star.setAmount(stars);
            put(inventory, layout.slot("star-" + stars, 9 + stars), star);
        }
        put(inventory, layout.slot("back", 22), button(Material.DARK_OAK_DOOR, "<gray><bold>Back</bold></gray>"));
        put(inventory, layout.slot("close", 26), button(Material.BARRIER, "<red><bold>Close</bold></red>"));
        player.openInventory(inventory);
    }

    private void renderList(
            Player player,
            DiscoveryGuiType type,
            int requestedPage,
            String query,
            DirectoryFilter filter,
            List<Shop> values
    ) {
        GuiLayout layout = config.get().gui("discovery-list", 54);
        List<Integer> contentSlots = contentSlots(layout, layout.slots().containsKey("summary") ? 9 : 0, 45);
        ShopDirectoryViewModel model = directoryModel(player, listContext(type, filter), query, values, requestedPage, contentSlots.size());
        DiscoveryGuiHolder holder = holder(type, null, model.page(), filter, query);
        Inventory inventory = createInventory(holder, layout, listTitle(type));
        boolean modern = layout.slots().containsKey("back") || layout.slots().containsKey("summary");

        if (modern) {
            List<Component> summaryLore = new ArrayList<>();
            summaryLore.add(messages.get().raw("<gray>Results:</gray> <white>" + model.total() + "</white>"));
            if (!model.query().isBlank()) {
                summaryLore.add(messages.get().raw("<gray>Search:</gray> <white>\"" + escapePlain(model.query()) + "\"</white>"));
            }
            summaryLore.add(messages.get().raw("<gray>Page:</gray> <white>" + (model.page() + 1) + "/" + model.pages() + "</white>"));
            put(inventory, layout.slot("summary", 4), items.create(
                    Material.BOOK,
                    messages.get().raw("<white><bold>" + escapePlain(model.context()) + "</bold></white>"),
                    summaryLore,
                    false));
        }

        renderDirectoryCards(inventory, holder, contentSlots, model);
        put(inventory, layout.slot("previous", 45), button(Material.ARROW, "<gray><bold>Previous</bold></gray>"));
        int backSlot = modern ? layout.slot("back", 48) : layout.slot("home", 49);
        put(inventory, backSlot, button(Material.DARK_OAK_DOOR, "<gray><bold>Back</bold></gray>"));
        if (modern) {
            put(inventory, layout.slot("page", 49), pageItem(model));
            if (type == DiscoveryGuiType.SEARCH_RESULTS) {
                put(inventory, layout.slot("action", 50), button(Material.SPYGLASS, "<aqua><bold>Search Again</bold></aqua>"));
            } else if (type == DiscoveryGuiType.MY_SHOPS) {
                put(inventory, layout.slot("action", 50), button(Material.CHEST, "<green><bold>Manage / Create</bold></green>"));
            }
            put(inventory, layout.slot("close", 52), button(Material.BARRIER, "<red><bold>Close</bold></red>"));
        }
        put(inventory, layout.slot("next", 53), button(Material.ARROW, "<gray><bold>Next</bold></gray>"));
        player.openInventory(inventory);
    }

    private void renderOwnerProfile(Player player, Shop shop, int returnPage) {
        GuiLayout layout = config.get().gui("owner-profile", 54);
        ShopProfileViewModel model = profileModel(player, shop);
        DiscoveryGuiHolder holder = holder(
                DiscoveryGuiType.OWNER_PROFILE, shop.id(), 0, DirectoryFilter.ALL, "",
                DiscoveryGuiType.MY_SHOPS, returnPage, DirectoryFilter.ALL, "", shop.updatedAtEpochSecond());
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>My Shop Listing</bold></gradient>"));
        put(inventory, layout.slot("info", 13), profileIdentity(shop, model));
        put(inventory, layout.slot("status", 20), statusItem(model.status(), model.statusDetail()));
        put(inventory, layout.slot("categories", 22), items.create(
                shop.primaryCategory().icon(),
                messages.get().raw("<white><bold>Categories</bold></white>"),
                List.of(messages.get().raw("<gray>" + escapePlain(model.categories()) + "</gray>")), false));
        put(inventory, layout.slot("location", 24), items.create(
                Material.RECOVERY_COMPASS,
                messages.get().raw("<white><bold>Location</bold></white>"),
                List.of(messages.get().raw("<gray>" + escapePlain(model.location()) + "</gray>")), false));
        put(inventory, layout.slot("showcase", 31), showcaseButton(model));
        put(inventory, layout.slot("fee", 33), items.create(
                Material.GOLD_INGOT,
                messages.get().raw("<white><bold>Teleport Fee</bold></white>"),
                List.of(messages.get().raw("<gray>Visitors see:</gray> <green>" + escapePlain(model.teleportFee()) + "</green>")), false));
        put(inventory, layout.slot("public-profile", 47), button(Material.BOOK, "<aqua><bold>View Public Profile</bold></aqua>"));
        put(inventory, layout.slot("back", 48), button(Material.DARK_OAK_DOOR, "<gray><bold>Back</bold></gray>"));
        put(inventory, layout.slot("edit", 49), items.create(
                Material.CHEST,
                messages.get().raw("<green><bold>Edit Listing</bold></green>"),
                List.of(messages.get().raw("<gray>Name, description, categories, showcase, location, fee, icon, and visibility.</gray>")),
                false));
        put(inventory, layout.slot("close", 52), button(Material.BARRIER, "<red><bold>Close</bold></red>"));
        player.openInventory(inventory);
    }

    private void renderHelp(Player player) {
        GuiLayout layout = config.get().gui("directory-help", 27);
        DiscoveryGuiHolder holder = holder(DiscoveryGuiType.HELP, null, 0, DirectoryFilter.ALL, "");
        Inventory inventory = createInventory(holder, layout,
                messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Directory Help</bold></gradient>"));
        put(inventory, layout.slot("browse", 10), helpItem(Material.COMPASS, "Browse", "Explore shops and filter by specialization."));
        put(inventory, layout.slot("favorite", 12), helpItem(Material.NETHER_STAR, "Favorites", "Save a shop and return later without searching again."));
        put(inventory, layout.slot("rating", 14), helpItem(Material.GOLD_INGOT, "Ratings", "Rate shops from 1–5 stars; a new rating replaces your old one."));
        put(inventory, layout.slot("showcase", 16), helpItem(Material.ITEM_FRAME, "Showcase", "Showcased items are examples only. Visit the shop to trade."));
        put(inventory, layout.slot("back", 22), button(Material.DARK_OAK_DOOR, "<gray><bold>Back</bold></gray>"));
        put(inventory, layout.slot("close", 26), button(Material.BARRIER, "<red><bold>Close</bold></red>"));
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
        DiscoveryGuiHolder holder = holder(
                DiscoveryGuiType.ADMIN_PROFILE, shop.id(), 0, DirectoryFilter.ALL, "",
                DiscoveryGuiType.ADMIN, 0, DirectoryFilter.ALL, "", shop.updatedAtEpochSecond());
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
                List.of(messages.get().raw("<gray>Current:</gray> ").append(statusComponent(
                        ShopUiPolicy.status(state.state(), state.reason()).label()))),
                shop.status() == ShopStatus.OPEN));
        put(inventory, layout.slot("profile", 14), button(Material.BOOK, "<aqua><bold>Public Profile</bold></aqua>"));
        put(inventory, layout.slot("diagnostic", 16), items.create(
                Material.COMPARATOR,
                messages.get().raw("<blue><bold>Teleport Diagnostic</bold></blue>"),
                List.of(
                        messages.get().raw("<gray>State:</gray> <white>" + state.state() + "</white>"),
                        messages.get().raw("<gray>Reason:</gray> <white>" + escapePlain(state.reason()) + "</white>"),
                        messages.get().raw("<gray>Shop ID:</gray> <white>" + shop.id() + "</white>")),
                false));
        put(inventory, layout.slot("back", 22), button(Material.DARK_OAK_DOOR, "<gray><bold>Back</bold></gray>"));
        player.openInventory(inventory);
    }

    private void handleHub(Player player, DiscoveryGuiHolder holder, int slot) {
        GuiLayout layout = config.get().gui("discovery-hub", 54);
        boolean compact = layout.size() < 45;
        if (slot == layout.slot("featured", 10)) {
            renderList(player, DiscoveryGuiType.FEATURED, 0, "", DirectoryFilter.ALL, discovery.featuredShops());
        } else if (slot == layout.slot("browse", 11)) {
            renderBrowse(player, 0, DirectoryFilter.ALL);
        } else if (slot == layout.slot("categories", 12)) {
            renderCategories(player);
        } else if (slot == layout.slot("search", 13)) {
            beginSearch(player);
        } else if (slot == layout.slot("favorites", 14)) {
            renderList(player, DiscoveryGuiType.FAVORITES, 0, "", DirectoryFilter.ALL,
                    discovery.favorites(player.getUniqueId()));
        } else if (slot == layout.slot("recent", 15)) {
            renderList(player, DiscoveryGuiType.RECENT, 0, "", DirectoryFilter.ALL,
                    discovery.recent(player.getUniqueId()));
        } else if (slot == layout.slot("my-shop", 16)) {
            if (!player.hasPermission("plexonshops.create")) {
                messages.get().send(player, "no-permission");
            } else {
                renderList(player, DiscoveryGuiType.MY_SHOPS, 0, "", DirectoryFilter.ALL,
                        shops.ownedBy(player.getUniqueId()));
            }
        } else if (slot == layout.slot("help", compact ? 22 : 50)) {
            renderHelp(player);
        } else if (slot == layout.slot("admin-or-close", compact ? 26 : 52)) {
            if (player.hasPermission("plexonshops.admin")) {
                openAdmin(player);
            } else {
                player.closeInventory();
            }
        } else {
            holder.target(slot).ifPresent(shopId -> renderProfile(
                    player, shopId, DiscoveryGuiType.HUB, 0, DirectoryFilter.ALL, ""));
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
        if (slot == layout.slot("home", 8) || layout.slots().containsKey("back") && slot == layout.slot("back", 48)) {
            renderHub(player);
        } else if (slot == layout.slot("previous", 45)) {
            renderBrowse(player, holder.page() - 1, holder.filter());
        } else if (slot == layout.slot("next", 53)) {
            renderBrowse(player, holder.page() + 1, holder.filter());
        } else if (slot == layout.slot("search", 50)) {
            beginSearch(player);
        } else if (layout.slots().containsKey("close") && slot == layout.slot("close", 52)) {
            player.closeInventory();
        } else {
            holder.target(slot).ifPresent(shopId -> renderProfile(
                    player, shopId, DiscoveryGuiType.BROWSE, holder.page(), holder.filter(), ""));
        }
    }

    private void handleCategories(Player player, int slot) {
        GuiLayout layout = config.get().gui("directory-categories", 27);
        int[] fallbacks = {10, 11, 12, 14, 15, 16};
        int index = 0;
        for (Category category : Category.values()) {
            if (slot == layout.slot(category.key(), fallbacks[index++])) {
                renderBrowse(player, 0, DirectoryFilter.valueOf(category.name()));
                return;
            }
        }
        if (slot == layout.slot("back", 22)) {
            renderHub(player);
        } else if (slot == layout.slot("close", 26)) {
            player.closeInventory();
        }
    }

    private void handleProfile(Player player, DiscoveryGuiHolder holder, int slot) {
        Shop shop = currentShop(player, holder, true);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("shop-profile", 54);
        boolean compact = layout.size() < 45;
        int visitSlot = compact ? layout.slot("teleport", 10) : layout.slot("visit", 49);
        int favoriteSlot = layout.slot("favorite", compact ? 12 : 39);
        int rateSlot = layout.slot("rate", compact ? 14 : 41);
        int showcaseSlot = compact ? layout.slot("context", 16) : layout.slot("showcase", 31);
        int ownerEditSlot = compact ? layout.slot("context", 16) : layout.slot("owner-edit", 43);
        int backSlot = layout.slot("back", compact ? 22 : 48);
        int homeSlot = layout.slot("home", compact ? 26 : 51);

        if (slot == visitSlot) {
            beginVisit(player, holder, shop);
        } else if (slot == favoriteSlot) {
            if (!holder.trySubmit()) {
                return;
            }
            finish(player, discovery.toggleFavorite(player.getUniqueId(), shop.id()), ignored -> renderProfile(
                    player, shop.id(), holder.returnType(), holder.returnPage(), holder.returnFilter(), holder.returnQuery()));
        } else if (slot == rateSlot && !shop.ownerUuid().equals(player.getUniqueId())) {
            renderRating(player, holder, shop);
        } else if (slot == showcaseSlot && !(compact && shop.ownerUuid().equals(player.getUniqueId()))) {
            renderShowcase(player, holder, shop, 0);
        } else if (slot == ownerEditSlot && shop.ownerUuid().equals(player.getUniqueId())
                && player.hasPermission("plexonshops.create")) {
            legacy.openManage(player, shop.id());
        } else if (slot == backSlot) {
            returnTo(player, holder.returnType(), holder.returnPage(), holder.returnFilter(), holder.returnQuery());
        } else if (slot == homeSlot) {
            renderHub(player);
        } else if (!compact && slot == layout.slot("close", 52)) {
            player.closeInventory();
        }
    }

    private void handleShowcase(Player player, DiscoveryGuiHolder holder, int slot) {
        Shop shop = currentShop(player, holder, false);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("showcase", 54);
        if (slot == layout.slot("previous", 45)) {
            renderShowcase(player, holder, shop, holder.page() - 1);
        } else if (slot == layout.slot("next", 53)) {
            renderShowcase(player, holder, shop, holder.page() + 1);
        } else if (slot == layout.slot("back", 48)) {
            renderProfile(player, shop.id(), holder.returnType(), holder.returnPage(), holder.returnFilter(), holder.returnQuery());
        } else if (slot == layout.slot("visit", 49)) {
            beginVisit(player, holder, shop);
        } else if (slot == layout.slot("close", 52)) {
            player.closeInventory();
        }
    }

    private void handleTeleportPreview(Player player, DiscoveryGuiHolder holder, int slot) {
        Shop shop = currentShop(player, holder, false);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("teleport-preview", 27);
        if (slot == layout.slot("cancel", 15)) {
            renderProfile(player, shop.id(), holder.returnType(), holder.returnPage(), holder.returnFilter(), holder.returnQuery());
            return;
        }
        if (slot == layout.slot("close", 26)) {
            player.closeInventory();
            return;
        }
        if (slot != layout.slot("confirm", 11)) {
            return;
        }
        ShopAvailabilityResolver.Resolution resolved = availability.resolve(shop);
        TeleportPreviewViewModel model = teleportModel(player, shop, resolved);
        if (!model.ready()) {
            if (model.status().equals("INSUFFICIENT BALANCE")) {
                messages.get().send(player, "insufficient-funds");
            } else {
                messages.get().send(player, "economy-unavailable");
            }
            renderTeleportPreview(player, holder, shop);
            return;
        }
        if (!holder.trySubmit()) {
            return;
        }
        player.closeInventory();
        teleports.request(player, shop);
    }

    private void handleRating(Player player, DiscoveryGuiHolder holder, int slot) {
        Shop shop = currentShop(player, holder, false);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("rating", 27);
        if (slot == layout.slot("back", 22)) {
            renderProfile(player, shop.id(), holder.returnType(), holder.returnPage(), holder.returnFilter(), holder.returnQuery());
            return;
        }
        if (slot == layout.slot("close", 26)) {
            player.closeInventory();
            return;
        }
        if (shop.ownerUuid().equals(player.getUniqueId())) {
            messages.get().send(player, "cannot-rate-own");
            return;
        }
        int selected = 0;
        for (int stars = 1; stars <= 5; stars++) {
            if (slot == layout.slot("star-" + stars, 9 + stars)) {
                selected = stars;
                break;
            }
        }
        if (selected == 0 || !holder.trySubmit()) {
            return;
        }
        int stars = selected;
        finish(player, shops.rate(shop.id(), player.getUniqueId(), stars), updated -> {
            messages.get().send(player, "rating-saved",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("stars", Integer.toString(stars)));
            renderRating(player, holder, updated);
        });
    }

    private void handleList(Player player, DiscoveryGuiHolder holder, int slot) {
        GuiLayout layout = config.get().gui("discovery-list", 54);
        boolean modern = layout.slots().containsKey("back") || layout.slots().containsKey("summary");
        int backSlot = modern ? layout.slot("back", 48) : layout.slot("home", 49);
        if (slot == backSlot) {
            renderHub(player);
            return;
        }
        if (modern && slot == layout.slot("close", 52)) {
            player.closeInventory();
            return;
        }
        if (modern && slot == layout.slot("action", 50)) {
            if (holder.type() == DiscoveryGuiType.SEARCH_RESULTS) {
                beginSearch(player);
            } else if (holder.type() == DiscoveryGuiType.MY_SHOPS) {
                legacy.openOwnerManagement(player);
            }
            return;
        }
        if (slot == layout.slot("previous", 45) || slot == layout.slot("next", 53)) {
            int targetPage = holder.page() + (slot == layout.slot("next", 53) ? 1 : -1);
            rerenderList(player, holder, targetPage);
            return;
        }
        holder.target(slot).ifPresent(shopId -> {
            if (holder.type() == DiscoveryGuiType.ADMIN) {
                renderAdminProfile(player, shopId);
            } else if (holder.type() == DiscoveryGuiType.MY_SHOPS) {
                shops.find(shopId).ifPresent(shop -> renderOwnerProfile(player, shop, holder.page()));
            } else {
                renderProfile(player, shopId, holder.type(), holder.page(), holder.filter(), holder.query());
            }
        });
    }

    private void handleOwnerProfile(Player player, DiscoveryGuiHolder holder, int slot) {
        Shop shop = currentShop(player, holder, false);
        if (shop == null) {
            return;
        }
        if (!shop.ownerUuid().equals(player.getUniqueId()) || !player.hasPermission("plexonshops.create")) {
            messages.get().send(player, "no-permission");
            renderHub(player);
            return;
        }
        GuiLayout layout = config.get().gui("owner-profile", 54);
        if (slot == layout.slot("public-profile", 47)) {
            renderProfile(player, shop.id(), DiscoveryGuiType.MY_SHOPS, holder.returnPage(), DirectoryFilter.ALL, "");
        } else if (slot == layout.slot("back", 48)) {
            renderList(player, DiscoveryGuiType.MY_SHOPS, holder.returnPage(), "", DirectoryFilter.ALL,
                    shops.ownedBy(player.getUniqueId()));
        } else if (slot == layout.slot("edit", 49)) {
            legacy.openManage(player, shop.id());
        } else if (slot == layout.slot("close", 52)) {
            player.closeInventory();
        }
    }

    private void handleHelp(Player player, int slot) {
        GuiLayout layout = config.get().gui("directory-help", 27);
        if (slot == layout.slot("back", 22)) {
            renderHub(player);
        } else if (slot == layout.slot("close", 26)) {
            player.closeInventory();
        }
    }

    private void handleAdminProfile(Player player, DiscoveryGuiHolder holder, int slot) {
        if (!player.hasPermission("plexonshops.admin")) {
            messages.get().send(player, "no-permission");
            return;
        }
        Shop shop = currentShop(player, holder, false);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("admin-profile", 27);
        if (slot == layout.slot("featured", 10)) {
            if (!holder.trySubmit()) {
                return;
            }
            boolean next = !discovery.isFeatured(shop.id());
            finish(player, discovery.setFeatured(shop.id(), next), ignored -> renderAdminProfile(player, shop.id()));
        } else if (slot == layout.slot("status", 12)) {
            if (!holder.trySubmit()) {
                return;
            }
            ShopStatus next = shop.status().next();
            if (next == ShopStatus.OPEN && !availability.resolveDestination(shop).teleportable()) {
                messages.get().send(player, "invalid-world");
                renderAdminProfile(player, shop.id());
                return;
            }
            finish(player, shops.setStatus(shop.id(), next), ignored -> renderAdminProfile(player, shop.id()));
        } else if (slot == layout.slot("profile", 14)) {
            renderProfile(player, shop.id(), DiscoveryGuiType.ADMIN, 0, DirectoryFilter.ALL, "");
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
            List<Shop> results = search(query);
            renderList(player, DiscoveryGuiType.SEARCH_RESULTS, 0, query, DirectoryFilter.ALL, results);
        });
    }

    private List<Shop> search(String query) {
        return ShopUiPolicy.search(shops.directory(DirectoryFilter.ALL), query, messages.get()::plainStored);
    }

    private void beginVisit(Player player, DiscoveryGuiHolder sourceHolder, Shop shop) {
        ShopAvailabilityResolver.Resolution resolved = availability.resolve(shop);
        if (!resolved.teleportable()) {
            sendStateChanged(player);
            renderProfile(player, shop.id(), sourceHolder.returnType(), sourceHolder.returnPage(),
                    sourceHolder.returnFilter(), sourceHolder.returnQuery());
            return;
        }
        TeleportPreviewViewModel model = teleportModel(player, shop, resolved);
        if (model.paid()) {
            renderTeleportPreview(player, sourceHolder, shop);
            return;
        }
        if (!model.ready()) {
            messages.get().send(player, "economy-unavailable");
            return;
        }
        if (!sourceHolder.trySubmit()) {
            return;
        }
        player.closeInventory();
        teleports.request(player, shop);
    }

    private TeleportPreviewViewModel teleportModel(
            Player player,
            Shop shop,
            ShopAvailabilityResolver.Resolution resolved
    ) {
        PluginConfig.Teleport settings = config.get().teleport();
        boolean bypass = player.hasPermission("plexonshops.teleport.fee.bypass")
                || settings.ownersBypassFee() && shop.ownerUuid().equals(player.getUniqueId());
        double balance = economy.balance(player);
        return TeleportPreviewViewModel.create(
                resolved.teleportable(),
                settings.economyEnabled(),
                bypass,
                economy.available(),
                settings.failOpenWithoutVault(),
                shop.teleportFee(),
                balance,
                economy::format
        );
    }

    private Shop currentShop(Player player, DiscoveryGuiHolder holder, boolean refreshProfileOnStale) {
        Shop shop = shops.find(holder.shopId()).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            returnTo(player, holder.returnType(), holder.returnPage(), holder.returnFilter(), holder.returnQuery());
            return null;
        }
        if (!holder.matchesRevision(shop.updatedAtEpochSecond())) {
            sendStateChanged(player);
            if (refreshProfileOnStale) {
                renderProfile(player, shop.id(), holder.returnType(), holder.returnPage(), holder.returnFilter(), holder.returnQuery());
            } else {
                returnTo(player, holder.returnType(), holder.returnPage(), holder.returnFilter(), holder.returnQuery());
            }
            return null;
        }
        return shop;
    }

    private void rerenderList(Player player, DiscoveryGuiHolder holder, int targetPage) {
        switch (holder.type()) {
            case FEATURED -> renderList(player, holder.type(), targetPage, "", DirectoryFilter.ALL, discovery.featuredShops());
            case FAVORITES -> renderList(player, holder.type(), targetPage, "", DirectoryFilter.ALL,
                    discovery.favorites(player.getUniqueId()));
            case RECENT -> renderList(player, holder.type(), targetPage, "", DirectoryFilter.ALL,
                    discovery.recent(player.getUniqueId()));
            case SEARCH_RESULTS -> renderList(player, holder.type(), targetPage, holder.query(), DirectoryFilter.ALL,
                    search(holder.query()));
            case MY_SHOPS -> renderList(player, holder.type(), targetPage, "", DirectoryFilter.ALL,
                    shops.ownedBy(player.getUniqueId()));
            case ADMIN -> renderList(player, holder.type(), targetPage, "", DirectoryFilter.ALL,
                    shops.directory(DirectoryFilter.ALL));
            default -> renderHub(player);
        }
    }

    private void returnTo(Player player, DiscoveryGuiType type, int page, DirectoryFilter filter, String query) {
        switch (type) {
            case BROWSE -> renderBrowse(player, page, filter);
            case FEATURED -> renderList(player, type, page, "", DirectoryFilter.ALL, discovery.featuredShops());
            case FAVORITES -> renderList(player, type, page, "", DirectoryFilter.ALL, discovery.favorites(player.getUniqueId()));
            case RECENT -> renderList(player, type, page, "", DirectoryFilter.ALL, discovery.recent(player.getUniqueId()));
            case SEARCH_RESULTS -> renderList(player, type, page, query, DirectoryFilter.ALL, search(query));
            case MY_SHOPS -> renderList(player, type, page, "", DirectoryFilter.ALL, shops.ownedBy(player.getUniqueId()));
            case ADMIN -> openAdmin(player);
            default -> renderHub(player);
        }
    }

    private ShopDirectoryViewModel directoryModel(
            Player player,
            String context,
            String query,
            List<Shop> values,
            int requestedPage,
            int pageSize
    ) {
        ShopUiPolicy.PageSlice<Shop> slice = ShopUiPolicy.page(values, requestedPage, pageSize);
        List<ShopCardViewModel> cards = slice.items().stream().map(shop -> cardModel(player, shop)).toList();
        return new ShopDirectoryViewModel(context, query, slice.page(), slice.pages(), slice.total(), cards);
    }

    private ShopCardViewModel cardModel(Player viewer, Shop shop) {
        ShopAvailabilityResolver.Resolution resolved = availability.resolve(shop);
        ShopUiPolicy.StatusPresentation status = ShopUiPolicy.status(resolved.state(), resolved.reason());
        double average = shop.averageRating();
        return new ShopCardViewModel(
                shop.id(),
                shop.updatedAtEpochSecond(),
                messages.get().plainStored(shop.name()),
                shop.ownerName(),
                ShopUiPolicy.categorySummary(shop.categories()),
                ratingText(average),
                shop.ratings().size(),
                showcaseLabels(shop, 3),
                status.label(),
                status.explanation(),
                locationLabel(viewer, shop),
                feeLabel(viewer, shop),
                resolved.teleportable(),
                discovery.isFeatured(shop.id()),
                discovery.isFavorite(viewer.getUniqueId(), shop.id())
        );
    }

    private ShopProfileViewModel profileModel(Player viewer, Shop shop) {
        ShopAvailabilityResolver.Resolution resolved = availability.resolve(shop);
        ShopUiPolicy.StatusPresentation status = ShopUiPolicy.status(resolved.state(), resolved.reason());
        return new ShopProfileViewModel(
                shop.id(),
                shop.updatedAtEpochSecond(),
                messages.get().plainStored(shop.name()),
                shop.ownerName(),
                shop.description(),
                ShopUiPolicy.categorySummary(shop.categories()),
                status.label(),
                status.explanation(),
                ratingText(shop.averageRating()),
                shop.ratings().size(),
                shop.ratingFrom(viewer.getUniqueId()),
                showcaseLabels(shop, 3),
                shop.labeledItems().size(),
                locationLabel(viewer, shop),
                feeLabel(viewer, shop),
                resolved.teleportable(),
                discovery.isFeatured(shop.id()),
                discovery.isFavorite(viewer.getUniqueId(), shop.id()),
                shop.ownerUuid().equals(viewer.getUniqueId())
        );
    }

    private ItemStack shopCard(Player viewer, Shop shop) {
        ShopCardViewModel model = cardModel(viewer, shop);
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get().raw("<gray>by</gray> <white>" + escapePlain(model.owner()) + "</white>"));
        lore.add(Component.empty());
        lore.add(messages.get().raw("<aqua>" + escapePlain(model.categories()) + "</aqua>"));
        lore.add(messages.get().raw("<gray>Rating:</gray> <gold>" + escapePlain(model.rating()) + "</gold> <dark_gray>(" + model.ratingCount() + ")</dark_gray>"));
        lore.add(messages.get().raw("<gray>Showcase:</gray> <white>" + escapePlain(model.showcaseSummary()) + "</white>"));
        lore.add(messages.get().raw("<gray>Location:</gray> <white>" + escapePlain(model.location()) + "</white>"));
        lore.add(messages.get().raw("<gray>Visit fee:</gray> <green>" + escapePlain(model.teleportFee()) + "</green>"));
        lore.add(Component.empty());
        lore.add(statusComponent(model.status()));
        if (model.featured()) {
            lore.add(messages.get().raw("<gold>★ Featured</gold>"));
        }
        if (model.favorite()) {
            lore.add(messages.get().raw("<yellow>★ Favorited</yellow>"));
        }
        lore.add(messages.get().raw("<yellow>Click:</yellow> <gray>View shop</gray>"));
        return items.decorate(
                shopIcon(shop),
                messages.get().get("gui.shop-name", messages.get().storedTag("shop", shop.name())),
                lore,
                model.teleportable());
    }

    private ItemStack compactProfileInfo(Player player, Shop shop, ShopProfileViewModel model) {
        List<Component> lore = new ArrayList<>();
        for (String line : model.description()) {
            lore.add(messages.get().stored(line));
        }
        if (!model.description().isEmpty()) {
            lore.add(Component.empty());
        }
        lore.add(messages.get().raw("<gray>Owner:</gray> <white>" + escapePlain(model.owner()) + "</white>"));
        lore.add(messages.get().raw("<gray>Categories:</gray> <aqua>" + escapePlain(model.categories()) + "</aqua>"));
        lore.add(messages.get().raw("<gray>Rating:</gray> <gold>" + escapePlain(model.rating()) + "</gold> <dark_gray>(" + model.ratingCount() + ")</dark_gray>"));
        lore.add(messages.get().raw("<gray>Showcase:</gray> <white>" + escapePlain(model.showcaseSummary()) + "</white>"));
        lore.add(messages.get().raw("<gray>Location:</gray> <white>" + escapePlain(model.location()) + "</white>"));
        lore.add(messages.get().raw("<gray>Visit fee:</gray> <green>" + escapePlain(model.teleportFee()) + "</green>"));
        lore.add(Component.empty());
        lore.add(statusComponent(model.status()));
        return items.decorate(shopIcon(shop),
                messages.get().get("gui.shop-name", messages.get().storedTag("shop", shop.name())), lore, model.teleportable());
    }

    private ItemStack profileIdentity(Shop shop, ShopProfileViewModel model) {
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get().raw("<gray>Owner:</gray> <white>" + escapePlain(model.owner()) + "</white>"));
        for (String line : model.description()) {
            lore.add(messages.get().stored(line));
        }
        if (model.featured()) {
            lore.add(Component.empty());
            lore.add(messages.get().raw("<gold>★ Featured shop</gold>"));
        }
        return items.decorate(shopIcon(shop),
                messages.get().get("gui.shop-name", messages.get().storedTag("shop", shop.name())), lore, model.teleportable());
    }

    private ItemStack profileStats(ShopProfileViewModel model) {
        return items.create(
                Material.BOOK,
                messages.get().raw("<white><bold>Shop Overview</bold></white>"),
                List.of(
                        messages.get().raw("<gray>Categories:</gray> <aqua>" + escapePlain(model.categories()) + "</aqua>"),
                        messages.get().raw("<gray>Rating:</gray> <gold>" + escapePlain(model.rating()) + "</gold>"),
                        messages.get().raw("<gray>Ratings:</gray> <white>" + model.ratingCount() + "</white>"),
                        messages.get().raw("<gray>Showcased items:</gray> <white>" + model.showcaseCount() + "</white>"),
                        messages.get().raw("<gray>Visit fee:</gray> <green>" + escapePlain(model.teleportFee()) + "</green>")
                ),
                false);
    }

    private ItemStack statusItem(String status, String detail) {
        Material material = status.equals("OPEN") ? Material.LIME_DYE : status.contains("MAINTENANCE") ? Material.YELLOW_DYE : Material.RED_DYE;
        return items.create(
                material,
                statusComponent(status).decorate(TextDecoration.BOLD),
                List.of(messages.get().raw("<gray>" + escapePlain(detail) + "</gray>")),
                status.equals("OPEN"));
    }

    private ItemStack favoriteButton(ShopProfileViewModel model) {
        return items.create(
                model.favorite() ? Material.NETHER_STAR : Material.GRAY_DYE,
                model.favorite()
                        ? messages.get().raw("<yellow><bold>Favorited</bold></yellow>")
                        : messages.get().raw("<white><bold>Favorite Shop</bold></white>"),
                List.of(
                        messages.get().raw(model.favorite()
                                ? "<gray>This shop is saved for quick return.</gray>"
                                : "<gray>Save this shop for quick return.</gray>"),
                        messages.get().raw(model.favorite()
                                ? "<yellow>Click: Remove from favorites</yellow>"
                                : "<yellow>Click: Add to favorites</yellow>")
                ),
                model.favorite());
    }

    private ItemStack ratingButton(ShopProfileViewModel model) {
        return items.create(
                Material.NETHER_STAR,
                messages.get().raw("<gold><bold>Rate Shop</bold></gold>"),
                List.of(
                        messages.get().raw("<gray>Average:</gray> <gold>" + escapePlain(model.rating()) + "</gold>"),
                        messages.get().raw("<gray>Ratings:</gray> <white>" + model.ratingCount() + "</white>"),
                        messages.get().raw("<gray>Your rating:</gray> <white>" + (model.viewerRating() == 0 ? "Not rated" : model.viewerRating() + "/5") + "</white>"),
                        messages.get().raw("<yellow>Click: Choose 1–5 stars</yellow>")
                ),
                model.viewerRating() > 0);
    }

    private ItemStack showcaseButton(ShopProfileViewModel model) {
        return items.create(
                Material.ITEM_FRAME,
                messages.get().raw("<blue><bold>Showcased Items</bold></blue>"),
                List.of(
                        messages.get().raw("<gray>Examples:</gray> <white>" + escapePlain(model.showcaseSummary()) + "</white>"),
                        messages.get().raw("<gray>Total:</gray> <white>" + model.showcaseCount() + "</white>"),
                        Component.empty(),
                        messages.get().raw("<gray>These are examples, not purchase listings.</gray>"),
                        messages.get().raw("<yellow>Click: View showcase</yellow>")
                ),
                false);
    }

    private ItemStack visitButton(Player player, Shop shop) {
        ShopAvailabilityResolver.Resolution resolved = availability.resolve(shop);
        ShopUiPolicy.StatusPresentation status = ShopUiPolicy.status(resolved.state(), resolved.reason());
        TeleportPreviewViewModel teleport = teleportModel(player, shop, resolved);
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get().raw("<gray>Destination:</gray> <white>" + escapePlain(shop.location().worldName()) + "</white>"));
        lore.add(messages.get().raw("<gray>Teleport fee:</gray> <green>" + escapePlain(teleport.fee()) + "</green>"));
        lore.add(Component.empty());
        lore.add(statusComponent(teleport.ready() ? "READY" : status.label()));
        if (!teleport.ready()) {
            lore.add(messages.get().raw("<red>" + escapePlain(teleport.detail()) + "</red>"));
        } else if (teleport.paid()) {
            lore.add(messages.get().raw("<yellow>Click: Review paid visit</yellow>"));
        } else {
            lore.add(messages.get().raw("<green>Click: Visit shop</green>"));
        }
        return items.create(
                teleport.ready() ? Material.ENDER_PEARL : Material.BARRIER,
                teleport.ready()
                        ? messages.get().raw("<green><bold>Visit Shop</bold></green>")
                        : messages.get().raw("<red><bold>Visit Unavailable</bold></red>"),
                lore,
                teleport.ready());
    }

    private void renderDirectoryCards(
            Inventory inventory,
            DiscoveryGuiHolder holder,
            List<Integer> contentSlots,
            ShopDirectoryViewModel model
    ) {
        if (model.empty()) {
            if (!contentSlots.isEmpty()) {
                put(inventory, contentSlots.get(contentSlots.size() / 2), emptyState(model.context()));
            }
            return;
        }
        for (int index = 0; index < model.shops().size(); index++) {
            ShopCardViewModel card = model.shops().get(index);
            Shop shop = shops.find(card.shopId()).orElse(null);
            if (shop == null) {
                continue;
            }
            int slot = contentSlots.get(index);
            holder.target(slot, shop.id());
            put(inventory, slot, shopCardFromModel(shop, card));
        }
    }

    private ItemStack shopCardFromModel(Shop shop, ShopCardViewModel model) {
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get().raw("<gray>by</gray> <white>" + escapePlain(model.owner()) + "</white>"));
        lore.add(Component.empty());
        lore.add(messages.get().raw("<aqua>" + escapePlain(model.categories()) + "</aqua>"));
        lore.add(messages.get().raw("<gray>Rating:</gray> <gold>" + escapePlain(model.rating()) + "</gold> <dark_gray>(" + model.ratingCount() + ")</dark_gray>"));
        lore.add(messages.get().raw("<gray>Showcase:</gray> <white>" + escapePlain(model.showcaseSummary()) + "</white>"));
        lore.add(messages.get().raw("<gray>Location:</gray> <white>" + escapePlain(model.location()) + "</white>"));
        lore.add(messages.get().raw("<gray>Visit fee:</gray> <green>" + escapePlain(model.teleportFee()) + "</green>"));
        lore.add(Component.empty());
        lore.add(statusComponent(model.status()));
        if (model.featured()) lore.add(messages.get().raw("<gold>★ Featured</gold>"));
        if (model.favorite()) lore.add(messages.get().raw("<yellow>★ Favorited</yellow>"));
        lore.add(messages.get().raw("<yellow>Click:</yellow> <gray>View shop</gray>"));
        return items.decorate(shopIcon(shop),
                messages.get().get("gui.shop-name", messages.get().storedTag("shop", shop.name())), lore, model.teleportable());
    }

    private ItemStack pageItem(ShopDirectoryViewModel model) {
        return items.create(
                Material.PAPER,
                messages.get().raw("<white><bold>Page " + (model.page() + 1) + "/" + model.pages() + "</bold></white>"),
                List.of(messages.get().raw("<gray>Results:</gray> <white>" + model.total() + "</white>")),
                false);
    }

    private ItemStack emptyState(String context) {
        if (context.equals("Search Results")) {
            return emptyItem(Material.SPYGLASS, "<gray><bold>No shops found</bold></gray>",
                    "Try another shop name, owner, category, or showcased item.");
        }
        if (context.equals("Favorites")) {
            return emptyItem(Material.GRAY_DYE, "<gray><bold>No favorite shops</bold></gray>",
                    "Favorite a shop profile and it will appear here.");
        }
        if (context.equals("Recent Shops")) {
            return emptyItem(Material.CLOCK, "<gray><bold>No recent shops</bold></gray>",
                    "Visit a player shop and it will appear here.");
        }
        if (context.equals("My Shops")) {
            return emptyItem(Material.CHEST, "<gray><bold>No owned shops</bold></gray>",
                    "Use Manage / Create to publish your first listing.");
        }
        return emptyItem(Material.GRAY_DYE, "<gray><bold>No shops here yet</bold></gray>",
                "Try another directory section or filter.");
    }

    private ItemStack route(Material material, String name, String description, int count) {
        return items.create(
                material,
                messages.get().raw(name),
                List.of(
                        messages.get().raw("<gray>" + escapePlain(description) + "</gray>"),
                        messages.get().raw("<gray>Available:</gray> <white>" + count + "</white>")
                ),
                false);
    }

    private ItemStack helpItem(Material material, String title, String detail) {
        return items.create(material,
                messages.get().raw("<aqua><bold>" + escapePlain(title) + "</bold></aqua>"),
                List.of(messages.get().raw("<gray>" + escapePlain(detail) + "</gray>")), false);
    }

    private ItemStack emptyItem(Material material, String name, String detail) {
        return items.create(material, messages.get().raw(name),
                List.of(messages.get().raw("<gray>" + escapePlain(detail) + "</gray>")), false);
    }

    private List<Shop> recommendedShops() {
        LinkedHashMap<UUID, Shop> recommended = new LinkedHashMap<>();
        for (Shop shop : discovery.featuredShops()) {
            if (availability.resolve(shop).teleportable()) {
                recommended.put(shop.id(), shop);
            }
        }
        for (Shop shop : shops.directory(DirectoryFilter.ALL)) {
            if (recommended.size() >= 4) break;
            if (availability.resolve(shop).teleportable()) {
                recommended.putIfAbsent(shop.id(), shop);
            }
        }
        return recommended.values().stream().limit(4).toList();
    }

    private List<String> showcaseLabels(Shop shop, int maximum) {
        return shop.labeledItems().stream()
                .limit(Math.max(0, maximum))
                .map(LabeledItem::label)
                .map(messages.get()::plainStored)
                .map(this::safePlain)
                .toList();
    }

    private String feeLabel(Player viewer, Shop shop) {
        PluginConfig.Teleport settings = config.get().teleport();
        boolean bypass = viewer.hasPermission("plexonshops.teleport.fee.bypass")
                || settings.ownersBypassFee() && shop.ownerUuid().equals(viewer.getUniqueId());
        if (!settings.economyEnabled() || bypass || shop.teleportFee() <= 0.0D
                || !economy.available() && settings.failOpenWithoutVault()) {
            return "FREE";
        }
        return economy.format(shop.teleportFee());
    }

    private String locationLabel(Player viewer, Shop shop) {
        String world = safePlain(shop.location().worldName());
        if (viewer.getWorld() != null && shop.location().hasFiniteCoordinates()
                && (viewer.getWorld().getUID().equals(shop.location().worldUuid())
                || viewer.getWorld().getName().equalsIgnoreCase(shop.location().worldName()))) {
            double dx = viewer.getLocation().getX() - shop.location().x();
            double dz = viewer.getLocation().getZ() - shop.location().z();
            long distance = Math.round(Math.sqrt(dx * dx + dz * dz));
            return "~" + distance + " blocks • " + world;
        }
        return world.isBlank() ? "Location unavailable" : world;
    }

    private String ratingText(double average) {
        int filled = Math.clamp((int) Math.round(average), 0, 5);
        return "★".repeat(filled) + "☆".repeat(5 - filled) + " " + String.format(Locale.ROOT, "%.1f", average);
    }

    private String filterLabel(DirectoryFilter filter) {
        return switch (filter) {
            case ALL -> "All Shops";
            case LABELED -> "With Showcased Items";
            default -> ShopUiPolicy.categoryLabel(Category.valueOf(filter.name()));
        };
    }

    private String listContext(DiscoveryGuiType type, DirectoryFilter filter) {
        return switch (type) {
            case FEATURED -> "Featured Shops";
            case FAVORITES -> "Favorites";
            case RECENT -> "Recent Shops";
            case SEARCH_RESULTS -> "Search Results";
            case MY_SHOPS -> "My Shops";
            case ADMIN -> "Shop Administration";
            case BROWSE -> filterLabel(filter);
            default -> "Player Shops";
        };
    }

    private Component listTitle(DiscoveryGuiType type) {
        return switch (type) {
            case FEATURED -> messages.get().raw("<gold><bold>Featured Shops</bold></gold>");
            case FAVORITES -> messages.get().raw("<yellow><bold>Favorite Shops</bold></yellow>");
            case RECENT -> messages.get().raw("<blue><bold>Recent Shops</bold></blue>");
            case SEARCH_RESULTS -> messages.get().raw("<aqua><bold>Search Results</bold></aqua>");
            case MY_SHOPS -> messages.get().raw("<green><bold>My Shops</bold></green>");
            case ADMIN -> messages.get().raw("<red><bold>Shop Administration</bold></red>");
            default -> messages.get().raw("<gradient:#8CE6FF:#5BA8FF><bold>Player Shops</bold></gradient>");
        };
    }

    private Component statusComponent(String status) {
        if (status.equals("OPEN") || status.equals("READY")) {
            return messages.get().raw("<green>Status: " + escapePlain(status) + "</green>");
        }
        if (status.contains("MAINTENANCE") || status.contains("INSUFFICIENT")) {
            return messages.get().raw("<yellow>Status: " + escapePlain(status) + "</yellow>");
        }
        return messages.get().raw("<red>Status: " + escapePlain(status) + "</red>");
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
            plugin.getLogger().log(Level.SEVERE, "PlexonShops directory operation failed", error);
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

    private DiscoveryGuiHolder holder(
            DiscoveryGuiType type,
            UUID shopId,
            int page,
            DirectoryFilter filter,
            String query,
            DiscoveryGuiType returnType,
            int returnPage,
            DirectoryFilter returnFilter,
            String returnQuery,
            long shopRevision
    ) {
        return new DiscoveryGuiHolder(type, shopId, page, filter, query,
                returnType, returnPage, returnFilter, returnQuery, shopRevision);
    }

    private Inventory createInventory(DiscoveryGuiHolder holder, GuiLayout layout, Component title) {
        Inventory inventory = Bukkit.createInventory(holder, layout.size(), title);
        holder.attach(inventory);
        ItemStack filler = template(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        return inventory;
    }

    private ItemStack button(Material material, String miniMessage) {
        return template(material, miniMessage);
    }

    private ItemStack template(Material material, String miniMessage) {
        MessageService service = messages.get();
        if (staticTemplates.size() > 256) {
            staticTemplates.clear();
        }
        TemplateKey key = new TemplateKey(service, material, miniMessage);
        ItemStack template = staticTemplates.computeIfAbsent(key,
                ignored -> items.create(material, service.raw(miniMessage), List.of(), false));
        return template.clone();
    }

    private ItemStack shopIcon(Shop shop) {
        if (shopIconCache.size() > 1024) {
            shopIconCache.clear();
        }
        String data = shop.displayIconData();
        CachedShopIcon cached = shopIconCache.get(shop.id());
        if (cached != null && cached.data().equals(data)) {
            return cached.item().clone();
        }
        ItemStack item = ItemStackCodec.decode(data).orElseGet(() -> ownerHead(shop));
        shopIconCache.put(shop.id(), new CachedShopIcon(data, item.clone()));
        return item;
    }

    private ItemStack showcaseItem(LabeledItem labeled) {
        if (showcaseItemCache.size() > 2048) {
            showcaseItemCache.clear();
        }
        ItemStack item = showcaseItemCache.computeIfAbsent(labeled.id(), ignored ->
                ItemStackCodec.decode(labeled.itemData()).orElseGet(() -> new ItemStack(Material.BARRIER)));
        return item.clone();
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

    private void sendStateChanged(Player player) {
        player.sendMessage(messages.get().prefix().append(messages.get().raw(
                "<yellow>This shop changed while the menu was open. The latest listing has been loaded.</yellow>")));
    }

    private String safePlain(String value) {
        return value == null ? "" : value.replace("<", "").replace(">", "").replace('\n', ' ').replace('\r', ' ').strip();
    }

    private String escapePlain(String value) {
        return safePlain(value);
    }

    private record TemplateKey(MessageService service, Material material, String miniMessage) {
    }

    private record CachedShopIcon(String data, ItemStack item) {
    }
}

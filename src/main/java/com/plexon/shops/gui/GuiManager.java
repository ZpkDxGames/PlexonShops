package com.plexon.shops.gui;

import com.plexon.shops.config.GuiLayout;
import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.integrations.VaultEconomyHook;
import com.plexon.shops.messages.MessageService;
import com.plexon.shops.models.Category;
import com.plexon.shops.models.DirectoryFilter;
import com.plexon.shops.models.LabeledItem;
import com.plexon.shops.models.Shop;
import com.plexon.shops.models.ShopLocation;
import com.plexon.shops.models.ShopStatus;
import com.plexon.shops.services.ChatPromptService;
import com.plexon.shops.services.ShopService;
import com.plexon.shops.services.TeleportService;
import com.plexon.shops.util.ItemStackCodec;
import com.plexon.shops.util.MainThread;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Renders and routes every inventory-first player shop interaction. */
public final class GuiManager {
    private static final Map<DirectoryFilter, String> FILTER_SLOTS = new EnumMap<>(DirectoryFilter.class);

    static {
        for (DirectoryFilter filter : DirectoryFilter.values()) {
            FILTER_SLOTS.put(filter, "filter-" + filter.key());
        }
    }

    private final JavaPlugin plugin;
    private final ShopService shops;
    private final TeleportService teleports;
    private final ChatPromptService prompts;
    private final VaultEconomyHook economy;
    private final Supplier<PluginConfig> config;
    private final Supplier<MessageService> messages;
    private final ItemFactory items = new ItemFactory();

    public GuiManager(
            JavaPlugin plugin,
            ShopService shops,
            TeleportService teleports,
            ChatPromptService prompts,
            VaultEconomyHook economy,
            Supplier<PluginConfig> config,
            Supplier<MessageService> messages
    ) {
        this.plugin = plugin;
        this.shops = shops;
        this.teleports = teleports;
        this.prompts = prompts;
        this.economy = economy;
        this.config = config;
        this.messages = messages;
    }

    public void openDirectory(Player player) {
        openDirectory(player, 0, DirectoryFilter.ALL);
    }

    public void openDirectory(Player player, int requestedPage, DirectoryFilter filter) {
        GuiLayout layout = config.get().gui("directory", 54);
        List<Integer> contentSlots = contentSlots(layout, 9, Math.min(45, layout.size()));
        PageSlice<Shop> page = slice(shops.directory(filter), requestedPage, contentSlots.size());
        MessageService messageService = messages.get();
        PlexonGuiHolder holder = new PlexonGuiHolder(GuiType.DIRECTORY, null, page.page(), filter);
        Inventory inventory = createInventory(
                holder,
                layout,
                messageService.get("gui.directory-title",
                        TagResolver.resolver("filter", Tag.inserting(messageService.get("gui.filter-" + filter.key()))))
        );

        for (DirectoryFilter candidate : DirectoryFilter.values()) {
            int slot = layout.slot(FILTER_SLOTS.get(candidate), candidate.ordinal());
            put(inventory, slot, items.create(
                    candidate.icon(),
                    messageService.get("gui.filter-" + candidate.key()),
                    List.of(),
                    candidate == filter
            ));
        }
        put(inventory, layout.slot("close", 8), button(Material.BARRIER, "gui.close"));

        for (int index = 0; index < page.items().size(); index++) {
            int slot = contentSlots.get(index);
            Shop shop = page.items().get(index);
            holder.target(slot, shop.id());
            put(inventory, slot, directoryShopItem(shop));
        }

        put(inventory, layout.slot("previous", 45), button(Material.ARROW, "gui.previous"));
        put(inventory, layout.slot("page", 49), items.create(
                Material.PAPER,
                messageService.get("gui.page",
                        Placeholder.unparsed("page", Integer.toString(page.page() + 1)),
                        Placeholder.unparsed("pages", Integer.toString(page.pages()))),
                List.of(),
                false
        ));
        put(inventory, layout.slot("manage", 50), button(Material.CHEST, "gui.manage"));
        put(inventory, layout.slot("next", 53), button(Material.ARROW, "gui.next"));
        player.openInventory(inventory);
    }

    public void openOwnerManagement(Player player) {
        List<Shop> owned = shops.ownedBy(player.getUniqueId());
        if (owned.isEmpty()) {
            openCreate(player);
        } else if (owned.size() == 1) {
            openManage(player, owned.getFirst().id());
        } else {
            openOwnerSelector(player, 0);
        }
    }

    public void openOwnerSelector(Player player, int requestedPage) {
        GuiLayout layout = config.get().gui("owner-selector", 54);
        List<Integer> contentSlots = contentSlots(layout, 0, Math.min(45, layout.size()));
        PageSlice<Shop> page = slice(shops.ownedBy(player.getUniqueId()), requestedPage, contentSlots.size());
        PlexonGuiHolder holder = new PlexonGuiHolder(GuiType.OWNER_SELECTOR, null, page.page(), DirectoryFilter.ALL);
        Inventory inventory = createInventory(holder, layout, messages.get().get("gui.owner-selector-title"));

        for (int index = 0; index < page.items().size(); index++) {
            int slot = contentSlots.get(index);
            Shop shop = page.items().get(index);
            holder.target(slot, shop.id());
            put(inventory, slot, ownerShopItem(shop));
        }
        put(inventory, layout.slot("previous", 45), button(Material.ARROW, "gui.previous"));
        put(inventory, layout.slot("back", 48), button(Material.DARK_OAK_DOOR, "gui.back"));
        put(inventory, layout.slot("create", 49), items.create(
                Material.LIME_DYE,
                messages.get().get("gui.create"),
                messages.get().list("gui.create-lore"),
                false
        ));
        put(inventory, layout.slot("next", 53), button(Material.ARROW, "gui.next"));
        player.openInventory(inventory);
    }

    public void openCreate(Player player) {
        GuiLayout layout = config.get().gui("create", 27);
        PlexonGuiHolder holder = new PlexonGuiHolder(GuiType.CREATE, null, 0, DirectoryFilter.ALL);
        Inventory inventory = createInventory(holder, layout, messages.get().get("gui.create-title"));
        put(inventory, layout.slot("confirm", 11), items.create(
                Material.LIME_CONCRETE,
                messages.get().get("gui.confirm"),
                messages.get().list("gui.create-lore"),
                true
        ));
        put(inventory, layout.slot("cancel", 15), button(Material.RED_CONCRETE, "gui.cancel"));
        player.openInventory(inventory);
    }

    public void openManage(Player player, UUID shopId) {
        Shop shop = ownedShop(player, shopId, false);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("manage", 54);
        MessageService messageService = messages.get();
        PlexonGuiHolder holder = new PlexonGuiHolder(GuiType.MANAGE, shop.id(), 0, DirectoryFilter.ALL);
        Inventory inventory = createInventory(holder, layout, messageService.get(
                "gui.manage-title",
                TagResolver.resolver("shop", Tag.inserting(messageService.stored(shop.name())))
        ));

        put(inventory, layout.slot("status", 10), items.create(
                shop.status().icon(),
                messageService.get("gui.status", statusResolver(shop.status())),
                messageService.list("gui.status-lore"),
                shop.status() == ShopStatus.OPEN
        ));
        put(inventory, layout.slot("categories", 12), items.create(
                shop.primaryCategory().icon(),
                messageService.get("gui.categories"),
                messageService.list("gui.categories-lore",
                        Placeholder.unparsed("categories", categoryNames(shop.categories())),
                        Placeholder.unparsed("limit", Integer.toString(config.get().limits().maxCategoriesPerShop()))),
                false
        ));
        ItemStack displayIcon = ItemStackCodec.decode(shop.displayIconData()).orElseGet(() -> ownerHead(shop));
        put(inventory, layout.slot("icon", 14), items.decorate(
                displayIcon,
                messageService.get("gui.icon"),
                messageService.list("gui.icon-lore"),
                false
        ));
        put(inventory, layout.slot("location", 16), items.create(
                Material.ENDER_PEARL,
                messageService.get("gui.location"),
                messageService.list("gui.location-lore"),
                false
        ));
        put(inventory, layout.slot("rename", 28), button(Material.NAME_TAG, "gui.rename"));
        put(inventory, layout.slot("description", 30), button(Material.WRITABLE_BOOK, "gui.description"));
        put(inventory, layout.slot("labels", 32), items.create(
                Material.ITEM_FRAME,
                messageService.get("gui.labels"),
                List.of(messageService.get("gui.labels-count",
                        Placeholder.unparsed("count", Integer.toString(shop.labeledItems().size())),
                        Placeholder.unparsed("limit", Integer.toString(config.get().limits().maxLabeledItemsPerShop())))),
                false
        ));
        put(inventory, layout.slot("teleport-fee", 34), items.create(
                Material.GOLD_INGOT,
                messageService.get("gui.fee", Placeholder.unparsed("fee", economy.format(shop.teleportFee()))),
                List.of(),
                false
        ));
        put(inventory, layout.slot("delete", 45), items.create(
                Material.LAVA_BUCKET,
                messageService.get("gui.delete"),
                messageService.list("gui.delete-lore"),
                false
        ));
        put(inventory, layout.slot("back", 49), button(Material.DARK_OAK_DOOR, "gui.back"));
        player.openInventory(inventory);
    }

    public void openCategories(Player player, UUID shopId) {
        Shop shop = ownedShop(player, shopId, false);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("categories", 27);
        PlexonGuiHolder holder = new PlexonGuiHolder(GuiType.CATEGORIES, shop.id(), 0, DirectoryFilter.ALL);
        Inventory inventory = createInventory(holder, layout, messages.get().get("gui.categories-title"));
        for (Category category : Category.values()) {
            boolean selected = shop.categories().contains(category);
            int slot = layout.slot(category.key(), 10 + category.ordinal());
            List<Component> lore = new ArrayList<>();
            lore.add(messages.get().get("categories." + category.key() + ".description"));
            lore.add(Component.empty());
            lore.add(messages.get().get(selected ? "gui.category-selected" : "gui.category-unselected"));
            put(inventory, slot, items.create(
                    category.icon(),
                    messages.get().get("categories." + category.key() + ".name"),
                    lore,
                    selected
            ));
        }
        put(inventory, layout.slot("back", 22), button(Material.DARK_OAK_DOOR, "gui.back"));
        player.openInventory(inventory);
    }

    public void openIcon(Player player, UUID shopId) {
        Shop shop = ownedShop(player, shopId, false);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("icon", 27);
        PlexonGuiHolder holder = new PlexonGuiHolder(GuiType.ICON, shop.id(), 0, DirectoryFilter.ALL);
        ItemStackCodec.decode(shop.displayIconData()).ifPresent(holder::pendingIcon);
        Inventory inventory = createInventory(holder, layout, messages.get().get("gui.icon-title"));
        put(inventory, layout.slot("reset", 11), button(Material.PLAYER_HEAD, "gui.icon-reset"));
        updateIconPreview(holder, inventory, layout, shop);
        put(inventory, layout.slot("save", 15), button(Material.LIME_CONCRETE, "gui.icon-save"));
        put(inventory, layout.slot("back", 22), button(Material.DARK_OAK_DOOR, "gui.back"));
        player.openInventory(inventory);
    }

    public void openLabels(Player player, UUID shopId, int requestedPage) {
        Shop shop = ownedShop(player, shopId, false);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("labels", 54);
        List<Integer> contentSlots = contentSlots(layout, 0, Math.min(45, layout.size()));
        PageSlice<LabeledItem> page = slice(shop.labeledItems(), requestedPage, contentSlots.size());
        PlexonGuiHolder holder = new PlexonGuiHolder(GuiType.LABELS, shop.id(), page.page(), DirectoryFilter.ALL);
        Inventory inventory = createInventory(holder, layout, messages.get().get("gui.labels-title"));
        for (int index = 0; index < page.items().size(); index++) {
            int slot = contentSlots.get(index);
            LabeledItem labeled = page.items().get(index);
            ItemStack item = ItemStackCodec.decode(labeled.itemData()).orElseGet(() -> new ItemStack(Material.BARRIER));
            put(inventory, slot, items.decorate(
                    item,
                    messages.get().stored(labeled.label()),
                    messages.get().list("gui.label-lore", Placeholder.unparsed("label", labeled.label())),
                    false
            ));
            holder.target(slot, labeled.id());
        }
        put(inventory, layout.slot("previous", 45), button(Material.ARROW, "gui.previous"));
        put(inventory, layout.slot("back", 48), button(Material.DARK_OAK_DOOR, "gui.back"));
        put(inventory, layout.slot("add", 49), items.create(
                Material.LIME_DYE,
                messages.get().get("gui.add-label"),
                messages.get().list("gui.add-label-lore"),
                false
        ));
        put(inventory, layout.slot("next", 53), button(Material.ARROW, "gui.next"));
        player.openInventory(inventory);
    }

    public void openRating(Player player, UUID shopId) {
        Shop shop = shops.find(shopId).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            return;
        }
        GuiLayout layout = config.get().gui("rating", 27);
        PlexonGuiHolder holder = new PlexonGuiHolder(GuiType.RATING, shop.id(), 0, DirectoryFilter.ALL);
        Inventory inventory = createInventory(holder, layout, messages.get().get(
                "gui.rating-title",
                TagResolver.resolver("shop", Tag.inserting(messages.get().stored(shop.name())))
        ));
        int current = shop.ratingFrom(player.getUniqueId());
        for (int stars = 1; stars <= 5; stars++) {
            String key = stars == 1 ? "gui.star" : "gui.star-plural";
            ItemStack star = items.create(
                    Material.NETHER_STAR,
                    messages.get().get(key, Placeholder.unparsed("stars", Integer.toString(stars))),
                    List.of(),
                    current == stars
            );
            star.setAmount(stars);
            put(inventory, layout.slot("star-" + stars, 9 + stars), star);
        }
        put(inventory, layout.slot("back", 22), button(Material.DARK_OAK_DOOR, "gui.back"));
        player.openInventory(inventory);
    }

    public void openDeleteConfirm(Player player, UUID shopId) {
        Shop shop = ownedShop(player, shopId, false);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("delete-confirm", 27);
        PlexonGuiHolder holder = new PlexonGuiHolder(GuiType.DELETE_CONFIRM, shop.id(), 0, DirectoryFilter.ALL);
        Inventory inventory = createInventory(holder, layout, messages.get().get("gui.delete-title"));
        put(inventory, layout.slot("confirm", 11), items.create(
                Material.LAVA_BUCKET,
                messages.get().get("gui.confirm"),
                messages.get().list("gui.delete-lore"),
                true
        ));
        put(inventory, layout.slot("cancel", 15), button(Material.WATER_BUCKET, "gui.cancel"));
        player.openInventory(inventory);
    }

    public void handleClick(InventoryClickEvent event, PlexonGuiHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        playClick(player);
        switch (holder.type()) {
            case DIRECTORY -> handleDirectory(player, holder, slot, event.isRightClick());
            case OWNER_SELECTOR -> handleOwnerSelector(player, holder, slot);
            case CREATE -> handleCreate(player, slot);
            case MANAGE -> handleManage(player, holder, slot);
            case CATEGORIES -> handleCategories(player, holder, slot);
            case ICON -> handleIcon(player, holder, slot, event.getCursor());
            case LABELS -> handleLabels(player, holder, slot, event.isRightClick());
            case RATING -> handleRating(player, holder, slot);
            case DELETE_CONFIRM -> handleDelete(player, holder, slot);
        }
    }

    private void handleDirectory(Player player, PlexonGuiHolder holder, int slot, boolean rightClick) {
        GuiLayout layout = config.get().gui("directory", 54);
        for (DirectoryFilter filter : DirectoryFilter.values()) {
            if (slot == layout.slot(FILTER_SLOTS.get(filter), filter.ordinal())) {
                openDirectory(player, 0, filter);
                return;
            }
        }
        if (slot == layout.slot("close", 8)) {
            player.closeInventory();
        } else if (slot == layout.slot("previous", 45)) {
            openDirectory(player, holder.page() - 1, holder.filter());
        } else if (slot == layout.slot("next", 53)) {
            openDirectory(player, holder.page() + 1, holder.filter());
        } else if (slot == layout.slot("manage", 50)) {
            if (!player.hasPermission("plexonshops.create")) {
                messages.get().send(player, "no-permission");
                return;
            }
            openOwnerManagement(player);
        } else {
            holder.target(slot).flatMap(shops::find).ifPresent(shop -> {
                if (rightClick) {
                    openRating(player, shop.id());
                } else {
                    player.closeInventory();
                    teleports.request(player, shop);
                }
            });
        }
    }

    private void handleOwnerSelector(Player player, PlexonGuiHolder holder, int slot) {
        GuiLayout layout = config.get().gui("owner-selector", 54);
        if (slot == layout.slot("previous", 45)) {
            openOwnerSelector(player, holder.page() - 1);
        } else if (slot == layout.slot("next", 53)) {
            openOwnerSelector(player, holder.page() + 1);
        } else if (slot == layout.slot("back", 48)) {
            openDirectory(player);
        } else if (slot == layout.slot("create", 49)) {
            openCreateWithLimitCheck(player);
        } else {
            holder.target(slot).ifPresent(shopId -> openManage(player, shopId));
        }
    }

    private void handleCreate(Player player, int slot) {
        GuiLayout layout = config.get().gui("create", 27);
        if (slot == layout.slot("cancel", 15)) {
            openDirectory(player);
            return;
        }
        if (slot != layout.slot("confirm", 11)) {
            return;
        }
        int limit = shops.shopLimit(player);
        int current = shops.ownedBy(player.getUniqueId()).size();
        if (current >= limit) {
            messages.get().send(player, "shop-limit",
                    Placeholder.unparsed("current", Integer.toString(current)),
                    Placeholder.unparsed("limit", formatLimit(limit)));
            openOwnerManagement(player);
            return;
        }
        if (player.getWorld() == null || config.get().isWorldBlacklisted(player.getWorld().getName())) {
            messages.get().send(player, "invalid-world");
            return;
        }
        finish(player, shops.create(
                player.getUniqueId(),
                player.getName(),
                ShopLocation.from(player.getLocation())
        ), created -> {
            messages.get().send(player, "shop-created", Placeholder.unparsed("shop", created.name()));
            openManage(player, created.id());
        });
    }

    private void handleManage(Player player, PlexonGuiHolder holder, int slot) {
        Shop shop = ownedShop(player, holder.shopId(), true);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("manage", 54);
        if (slot == layout.slot("status", 10)) {
            ShopStatus next = shop.status().next();
            if (next == ShopStatus.OPEN && shop.location().resolve().map(location ->
                    location.getWorld() == null || config.get().isWorldBlacklisted(location.getWorld().getName())).orElse(true)) {
                messages.get().send(player, "invalid-world");
                return;
            }
            finish(player, shops.setStatus(shop.id(), next), updated -> openManage(player, updated.id()));
        } else if (slot == layout.slot("categories", 12)) {
            openCategories(player, shop.id());
        } else if (slot == layout.slot("icon", 14)) {
            openIcon(player, shop.id());
        } else if (slot == layout.slot("location", 16)) {
            if (config.get().isWorldBlacklisted(player.getWorld().getName())) {
                messages.get().send(player, "invalid-world");
                return;
            }
            finish(player, shops.setLocation(shop.id(), ShopLocation.from(player.getLocation())),
                    updated -> {
                        messages.get().send(player, "shop-updated");
                        openManage(player, updated.id());
                    });
        } else if (slot == layout.slot("rename", 28)) {
            beginRename(player, shop.id());
        } else if (slot == layout.slot("description", 30)) {
            beginDescription(player, shop.id());
        } else if (slot == layout.slot("labels", 32)) {
            openLabels(player, shop.id(), 0);
        } else if (slot == layout.slot("teleport-fee", 34)) {
            if (config.get().teleport().ownersCanSetFee()) {
                beginFee(player, shop.id());
            }
        } else if (slot == layout.slot("delete", 45)) {
            openDeleteConfirm(player, shop.id());
        } else if (slot == layout.slot("back", 49)) {
            List<Shop> owned = shops.ownedBy(player.getUniqueId());
            if (owned.size() > 1) {
                openOwnerSelector(player, 0);
            } else {
                openDirectory(player);
            }
        }
    }

    private void handleCategories(Player player, PlexonGuiHolder holder, int slot) {
        Shop shop = ownedShop(player, holder.shopId(), true);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("categories", 27);
        if (slot == layout.slot("back", 22)) {
            openManage(player, shop.id());
            return;
        }
        Category clicked = null;
        for (Category category : Category.values()) {
            if (slot == layout.slot(category.key(), 10 + category.ordinal())) {
                clicked = category;
                break;
            }
        }
        if (clicked == null) {
            return;
        }
        EnumSet<Category> updated = EnumSet.copyOf(shop.categories());
        if (updated.contains(clicked)) {
            if (updated.size() == 1) {
                messages.get().send(player, "category-required");
                return;
            }
            updated.remove(clicked);
        } else {
            int maximum = config.get().limits().maxCategoriesPerShop();
            if (updated.size() >= maximum) {
                messages.get().send(player, "category-limit",
                        Placeholder.unparsed("limit", Integer.toString(maximum)));
                return;
            }
            updated.add(clicked);
        }
        finish(player, shops.setCategories(shop.id(), updated), saved -> openCategories(player, saved.id()));
    }

    private void handleIcon(Player player, PlexonGuiHolder holder, int slot, ItemStack cursor) {
        Shop shop = ownedShop(player, holder.shopId(), true);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("icon", 27);
        if (slot == layout.slot("preview", 13)) {
            if (cursor != null && !cursor.getType().isAir()) {
                holder.pendingIcon(cursor);
                updateIconPreview(holder, holder.getInventory(), layout, shop);
            }
        } else if (slot == layout.slot("reset", 11)) {
            holder.pendingIcon(null);
            updateIconPreview(holder, holder.getInventory(), layout, shop);
        } else if (slot == layout.slot("save", 15)) {
            String encoded = ItemStackCodec.encode(holder.pendingIcon());
            finish(player, shops.setDisplayIcon(shop.id(), encoded), updated -> {
                messages.get().send(player, "shop-updated");
                openManage(player, updated.id());
            });
        } else if (slot == layout.slot("back", 22)) {
            openManage(player, shop.id());
        }
    }

    private void handleLabels(Player player, PlexonGuiHolder holder, int slot, boolean rightClick) {
        Shop shop = ownedShop(player, holder.shopId(), true);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("labels", 54);
        if (slot == layout.slot("previous", 45)) {
            openLabels(player, shop.id(), holder.page() - 1);
        } else if (slot == layout.slot("next", 53)) {
            openLabels(player, shop.id(), holder.page() + 1);
        } else if (slot == layout.slot("back", 48)) {
            openManage(player, shop.id());
        } else if (slot == layout.slot("add", 49)) {
            beginLabel(player, shop);
        } else if (rightClick) {
            holder.target(slot).ifPresent(itemId -> finish(
                    player,
                    shops.removeLabeledItem(shop.id(), itemId),
                    updated -> openLabels(player, updated.id(), holder.page())
            ));
        }
    }

    private void handleRating(Player player, PlexonGuiHolder holder, int slot) {
        Shop shop = shops.find(holder.shopId()).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            openDirectory(player);
            return;
        }
        GuiLayout layout = config.get().gui("rating", 27);
        if (slot == layout.slot("back", 22)) {
            openDirectory(player);
            return;
        }
        int selected = 0;
        for (int stars = 1; stars <= 5; stars++) {
            if (slot == layout.slot("star-" + stars, 9 + stars)) {
                selected = stars;
                break;
            }
        }
        if (selected == 0) {
            return;
        }
        if (shop.ownerUuid().equals(player.getUniqueId())) {
            messages.get().send(player, "cannot-rate-own");
            return;
        }
        int stars = selected;
        finish(player, shops.rate(shop.id(), player.getUniqueId(), stars), updated -> {
            messages.get().send(player, "rating-saved",
                    Placeholder.unparsed("stars", Integer.toString(stars)));
            openRating(player, updated.id());
        });
    }

    private void handleDelete(Player player, PlexonGuiHolder holder, int slot) {
        Shop shop = ownedShop(player, holder.shopId(), true);
        if (shop == null) {
            return;
        }
        GuiLayout layout = config.get().gui("delete-confirm", 27);
        if (slot == layout.slot("cancel", 15)) {
            openManage(player, shop.id());
        } else if (slot == layout.slot("confirm", 11)) {
            finish(player, shops.delete(shop.id()), deleted -> {
                messages.get().send(player, "shop-deleted", Placeholder.unparsed("shop", deleted.name()));
                openOwnerManagement(player);
            });
        }
    }

    private void beginRename(Player player, UUID shopId) {
        prompts.begin(player, "prompt-name", input -> {
            String value = messages.get().sanitizePlayerText(input, player.hasPermission("plexonshops.format"));
            int maximum = config.get().limits().shopNameLength();
            if (!validText(value, maximum)) {
                messages.get().send(player, "invalid-text");
                openManage(player, shopId);
                return;
            }
            finish(player, shops.rename(shopId, value), updated -> {
                messages.get().send(player, "shop-updated");
                openManage(player, updated.id());
            });
        });
    }

    private void beginDescription(Player player, UUID shopId) {
        prompts.begin(player, "prompt-description", input -> {
            String[] pieces = input.split("\\|", -1);
            PluginConfig.Limits limits = config.get().limits();
            if (pieces.length > limits.descriptionLines()) {
                messages.get().send(player, "invalid-text");
                openManage(player, shopId);
                return;
            }
            List<String> lines = new ArrayList<>();
            for (String piece : pieces) {
                String line = messages.get().sanitizePlayerText(piece, player.hasPermission("plexonshops.format"));
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            if (lines.isEmpty() || lines.stream().anyMatch(line -> !validText(line, limits.descriptionLineLength()))) {
                messages.get().send(player, "invalid-text");
                openManage(player, shopId);
                return;
            }
            finish(player, shops.setDescription(shopId, lines), updated -> {
                messages.get().send(player, "shop-updated");
                openManage(player, updated.id());
            });
        });
        messages.get().send(player, "description-format");
    }

    private void beginLabel(Player player, Shop shop) {
        if (shop.labeledItems().size() >= config.get().limits().maxLabeledItemsPerShop()) {
            messages.get().send(player, "label-limit");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            messages.get().send(player, "label-item-required");
            return;
        }
        ItemStack snapshot = held.clone();
        prompts.begin(player, "prompt-label", input -> {
            String label = messages.get().sanitizePlayerText(input, player.hasPermission("plexonshops.format"));
            if (!validText(label, config.get().limits().labelLength())) {
                messages.get().send(player, "invalid-text");
                openLabels(player, shop.id(), 0);
                return;
            }
            LabeledItem item = new LabeledItem(
                    UUID.randomUUID(),
                    label,
                    ItemStackCodec.encode(snapshot),
                    Instant.now().getEpochSecond()
            );
            finish(player, shops.addLabeledItem(shop.id(), item), updated -> {
                messages.get().send(player, "shop-updated");
                openLabels(player, updated.id(), 0);
            });
        });
    }

    private void beginFee(Player player, UUID shopId) {
        prompts.begin(player, "prompt-fee", input -> {
            double maximum = config.get().teleport().maximumOwnerFee();
            double value;
            try {
                value = Double.parseDouble(input.replace(',', '.'));
            } catch (NumberFormatException error) {
                value = Double.NaN;
            }
            if (!Double.isFinite(value) || value < 0.0D || value > maximum) {
                messages.get().send(player, "invalid-number",
                        Placeholder.unparsed("maximum", economy.format(maximum)));
                openManage(player, shopId);
                return;
            }
            finish(player, shops.setTeleportFee(shopId, value), updated -> {
                messages.get().send(player, "shop-updated");
                openManage(player, updated.id());
            });
        });
    }

    private ItemStack directoryShopItem(Shop shop) {
        MessageService messageService = messages.get();
        ItemStack base = ItemStackCodec.decode(shop.displayIconData()).orElseGet(() -> ownerHead(shop));
        List<Component> lore = new ArrayList<>();
        shop.description().forEach(line -> lore.add(messageService.stored(line)));
        if (!shop.description().isEmpty()) {
            lore.add(Component.empty());
        }
        lore.addAll(messageService.list(
                "gui.shop-lore",
                Placeholder.unparsed("owner", shop.ownerName()),
                Placeholder.unparsed("categories", categoryNames(shop.categories())),
                statusResolver(shop.status()),
                Placeholder.unparsed("rating", shop.starBar()),
                Placeholder.unparsed("average", String.format(Locale.ROOT, "%.2f", shop.averageRating())),
                Placeholder.unparsed("total", Long.toString(shop.visitors().totalVisits())),
                Placeholder.unparsed("unique", Integer.toString(shop.visitors().uniqueCount())),
                Placeholder.unparsed("fee", economy.format(shop.teleportFee()))
        ));
        return items.decorate(
                base,
                messageService.get("gui.shop-name",
                        TagResolver.resolver("shop", Tag.inserting(messageService.stored(shop.name())))),
                lore,
                shop.status() == ShopStatus.OPEN
        );
    }

    private ItemStack ownerShopItem(Shop shop) {
        ItemStack base = ItemStackCodec.decode(shop.displayIconData()).orElseGet(() -> ownerHead(shop));
        return items.decorate(
                base,
                messages.get().stored(shop.name()),
                List.of(
                        messages.get().raw("<gray>Status:</gray> <status>", statusResolver(shop.status())),
                        messages.get().raw("<gray>Categories:</gray> <white><categories></white>",
                                Placeholder.unparsed("categories", categoryNames(shop.categories()))),
                        Component.empty(),
                        messages.get().raw("<aqua>Click to manage</aqua>")
                ),
                false
        );
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

    private void updateIconPreview(PlexonGuiHolder holder, Inventory inventory, GuiLayout layout, Shop shop) {
        ItemStack preview = holder.pendingIcon();
        if (preview == null || preview.getType().isAir()) {
            preview = ownerHead(shop);
        }
        put(inventory, layout.slot("preview", 13), items.decorate(
                preview,
                messages.get().get("gui.icon-preview"),
                messages.get().list("gui.icon-preview-lore"),
                true
        ));
    }

    private void openCreateWithLimitCheck(Player player) {
        int current = shops.ownedBy(player.getUniqueId()).size();
        int limit = shops.shopLimit(player);
        if (current >= limit) {
            messages.get().send(player, "shop-limit",
                    Placeholder.unparsed("current", Integer.toString(current)),
                    Placeholder.unparsed("limit", formatLimit(limit)));
            return;
        }
        openCreate(player);
    }

    private Shop ownedShop(Player player, UUID shopId, boolean messageOnFailure) {
        Shop shop = shops.find(shopId).orElse(null);
        if (shop == null) {
            if (messageOnFailure) {
                messages.get().send(player, "shop-not-found");
            }
            return null;
        }
        if (!shop.ownerUuid().equals(player.getUniqueId())) {
            messages.get().send(player, "no-permission");
            player.closeInventory();
            return null;
        }
        return shop;
    }

    private boolean validText(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            return false;
        }
        try {
            messages.get().stored(value);
            return true;
        } catch (RuntimeException invalidMiniMessage) {
            return false;
        }
    }

    private <T> void finish(Player player, CompletableFuture<T> future, Consumer<T> success) {
        MainThread.whenComplete(plugin, future, success, error -> {
            plugin.getLogger().log(Level.SEVERE, "PlexonShops persistence operation failed", error);
            messages.get().send(player, "storage-error");
        });
    }

    private Inventory createInventory(PlexonGuiHolder holder, GuiLayout layout, Component title) {
        Inventory inventory = Bukkit.createInventory(holder, layout.size(), title);
        holder.attach(inventory);
        ItemStack filler = items.create(Material.GRAY_STAINED_GLASS_PANE,
                messages.get().get("gui.filler"), List.of(), false);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        return inventory;
    }

    private ItemStack button(Material material, String messageKey) {
        return items.create(material, messages.get().get(messageKey), List.of(), false);
    }

    private void put(Inventory inventory, int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    private List<Integer> contentSlots(GuiLayout layout, int defaultStart, int defaultEndExclusive) {
        if (!layout.contentSlots().isEmpty()) {
            return layout.contentSlots();
        }
        return IntStream.range(defaultStart, defaultEndExclusive).boxed().toList();
    }

    private String categoryNames(Set<Category> categories) {
        return categories.stream()
                .map(category -> titleCase(category.name()))
                .collect(Collectors.joining(", "));
    }

    private String titleCase(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private TagResolver statusResolver(ShopStatus status) {
        Component component = switch (status) {
            case OPEN -> messages.get().raw("<green>OPEN</green>");
            case CLOSED -> messages.get().raw("<red>CLOSED</red>");
            case MAINTENANCE -> messages.get().raw("<yellow>MAINTENANCE</yellow>");
        };
        return TagResolver.resolver("status", Tag.inserting(component));
    }

    private void playClick(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.35F, 1.15F);
    }

    private String formatLimit(int limit) {
        return limit == Integer.MAX_VALUE ? "∞" : Integer.toString(limit);
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

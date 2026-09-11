package com.plexon.shops.gui.view;

import java.util.List;

/** Immutable page snapshot for browse/search/favorites/recent/category lists. */
public record ShopDirectoryViewModel(
        String context,
        String query,
        int page,
        int pages,
        int total,
        List<ShopCardViewModel> shops
) {
    public ShopDirectoryViewModel {
        context = context == null ? "" : context;
        query = query == null ? "" : query;
        shops = List.copyOf(shops);
    }

    public static ShopDirectoryViewModel of(
            String context,
            String query,
            List<ShopCardViewModel> source,
            int requestedPage,
            int pageSize
    ) {
        ShopUiPolicy.PageSlice<ShopCardViewModel> slice = ShopUiPolicy.page(source, requestedPage, pageSize);
        return new ShopDirectoryViewModel(context, query, slice.page(), slice.pages(), slice.total(), slice.items());
    }

    public boolean empty() {
        return shops.isEmpty();
    }
}

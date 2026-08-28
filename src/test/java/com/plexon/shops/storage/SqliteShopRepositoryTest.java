package com.plexon.shops.storage;

import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.models.LabeledItem;
import com.plexon.shops.models.Shop;
import com.plexon.shops.models.ShopTest;
import com.plexon.shops.util.BoundedExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteShopRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsCompleteShopAggregateTransactionally() throws Exception {
        BoundedExecutor worker = new BoundedExecutor("repository-test", 1, 32);
        SqliteShopRepository repository = new SqliteShopRepository(
                temporaryDirectory.resolve("shops.db").toFile(),
                new PluginConfig.Database("shops.db", 1, 5_000L),
                worker,
                Logger.getAnonymousLogger()
        );
        try {
            repository.initialize().get(10, TimeUnit.SECONDS);
            UUID visitor = UUID.randomUUID();
            LabeledItem featured = new LabeledItem(UUID.randomUUID(), "Featured", "encoded", 302L);
            Shop expected = ShopTest.shop()
                    .withRating(visitor, 5, 300L)
                    .withVisit(visitor, 301L)
                    .withLabeledItem(featured, 302L);

            repository.save(expected).get(10, TimeUnit.SECONDS);
            List<Shop> loaded = repository.loadAll().get(10, TimeUnit.SECONDS);

            assertEquals(1, loaded.size());
            Shop actual = loaded.getFirst();
            assertEquals(expected.id(), actual.id());
            assertEquals(expected.name(), actual.name());
            assertEquals(5.0D, actual.averageRating());
            assertEquals(1L, actual.visitors().totalVisits());
            assertEquals(1, actual.labeledItems().size());
            assertEquals("Featured", actual.labeledItems().getFirst().label());

            Shop targetedUpdate = actual.withRating(visitor, 3, 400L)
                    .withVisit(visitor, 401L)
                    .withoutLabeledItem(featured.id(), 402L);
            repository.saveRating(targetedUpdate.id(), targetedUpdate.ratings().get(visitor))
                    .get(10, TimeUnit.SECONDS);
            repository.recordVisit(targetedUpdate, targetedUpdate.visitors().uniqueVisitors().get(visitor))
                    .get(10, TimeUnit.SECONDS);
            repository.syncLabeledItems(targetedUpdate).get(10, TimeUnit.SECONDS);

            Shop reloaded = repository.loadAll().get(10, TimeUnit.SECONDS).getFirst();
            assertEquals(3.0D, reloaded.averageRating());
            assertEquals(2L, reloaded.visitors().totalVisits());
            assertEquals(1, reloaded.visitors().uniqueCount());
            assertEquals(0, reloaded.labeledItems().size());
        } finally {
            repository.close().get(10, TimeUnit.SECONDS);
            worker.shutdown(Duration.ofSeconds(5));
        }
    }
}

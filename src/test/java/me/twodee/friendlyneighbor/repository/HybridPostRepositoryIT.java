package me.twodee.friendlyneighbor.repository;

import com.mongodb.client.MongoClients;
import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.IMongodConfig;
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.process.runtime.Network;
import me.twodee.friendlyneighbor.component.FnCoreConfig;
import me.twodee.friendlyneighbor.entity.Post;
import me.twodee.friendlyneighbor.entity.UserLocation;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridPostRepositoryIT
{
    private MongodExecutable mongodExecutable;
    private MongoTemplate mongoTemplate;
    private RedisServer redisServer;
    private JedisPool jedisPool;
    private static final String FEED_NAMESPACE = "FNCORE.FEED";


    void startMongo() throws IOException
    {
        String ip = "localhost";
        int port = 27017;

        IMongodConfig mongodConfig = new MongodConfigBuilder().version(Version.Main.PRODUCTION)
                .net(new Net(ip, port, Network.localhostIsIPv6()))
                .build();

        MongodStarter starter = MongodStarter.getDefaultInstance();
        mongodExecutable = starter.prepare(mongodConfig);
        mongodExecutable.start();
        mongoTemplate = new MongoTemplate(MongoClients.create(), "test");
    }

    void startRedis() throws IOException
    {
        redisServer = new RedisServer(6379);
        redisServer.start();
        jedisPool = new JedisPool("localhost", 6379);

    }

    @BeforeEach
    void setUp() throws IOException
    {
        startMongo();
        startRedis();
    }

    @AfterEach
    void cleanUp()
    {
        mongodExecutable.stop();
        redisServer.stop();
    }

    @Test
    void testFanoutOutOfCache()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        nearbyUsers.add(new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100));
        nearbyUsers.add(new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100));

        UserLocation currentUserLocation = new UserLocation("test", new UserLocation.Position(22.72, 87.34),
                                                            1100);
        repository.forwardToUsers(nearbyUsers, new Post("a", currentUserLocation, LocalDateTime.now()));

        try (Jedis jedis = jedisPool.getResource()) {
            assertTrue(jedis.exists(getKey("abc123")));
            assertTrue(jedis.exists(getKey("xyz")));

            assertThat(jedis.lpop(getKey("abc123")), equalTo("a"));
            assertThat(jedis.lpop(getKey("xyz")), equalTo("a"));
        }
    }

    @Test
    void testFanoutInCache_PostOnTopOfList()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        nearbyUsers.add(new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100));
        nearbyUsers.add(new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100));

        UserLocation currentUserLocation = new UserLocation("test", new UserLocation.Position(22.72, 87.34),
                                                            1100);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lpush(getKey("abc123"), "t1", "t2");

            repository.forwardToUsers(nearbyUsers, new Post("a", currentUserLocation, LocalDateTime.now()));

            assertTrue(jedis.exists(getKey("abc123")));
            assertTrue(jedis.exists(getKey("xyz")));
            assertThat(jedis.lpop(getKey("abc123")), equalTo("a"));
        }
    }

    @Test
    void testFanoutInCache_ListHasBeenUpdated()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        nearbyUsers.add(new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100));
        nearbyUsers.add(new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100));

        UserLocation currentUserLocation = new UserLocation("test", new UserLocation.Position(22.72, 87.34),
                                                            1100);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lpush(getKey("abc123"), "t1", "t2", "t3");

            repository.forwardToUsers(nearbyUsers, new Post("a", currentUserLocation, LocalDateTime.now()));

            assertThat(jedis.llen(getKey("abc123")), equalTo(4L));
            assertThat(jedis.lrange(getKey("abc123"), 0, -1), hasItems("a", "t1", "t2", "t3"));
        }
    }

    @Test
    void testFanoutInCache_IndexOccupiedByNonList()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        nearbyUsers.add(new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100));
        nearbyUsers.add(new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100));

        UserLocation currentUserLocation = new UserLocation("test", new UserLocation.Position(22.72, 87.34),
                                                            1100);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(getKey("abc123"), "evil");

            repository.forwardToUsers(nearbyUsers, new Post("a", currentUserLocation, LocalDateTime.now()));

            assertTrue(jedis.exists(getKey("abc123")));
            assertThat(jedis.lpop(getKey("abc123")), equalTo("a"));
        }
    }

    @Test
    void fetchPostsOutOfCache_Order()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        mongoTemplate.save(new Post("p1", l1, LocalDateTime.now()));
        mongoTemplate.save(new Post("p2", l2, LocalDateTime.now()));
        mongoTemplate.save(new Post("p3", l2, LocalDateTime.now()));

        List<Post> feed = repository.findAllForUser(
                new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100), nearbyUsers);
        Assertions.assertThat(feed).extracting("id").containsExactly("p3", "p2", "p1");
        Assertions.assertThat(feed).extracting("location").extracting("id")
                .containsExactly("xyz", "xyz", "abc123");
    }

    @Test
    void fetchPostsOutOfCache_DistanceSet()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        mongoTemplate.save(new Post("p1", l1, LocalDateTime.now()));
        mongoTemplate.save(new Post("p2", l2, LocalDateTime.now()));
        mongoTemplate.save(new Post("p3", l2, LocalDateTime.now()));

        List<Post> feed = repository.findAllForUser(
                new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100), nearbyUsers);
        Assertions.assertThat(feed).extracting("location").extracting("distance")
                .isNotNull();
    }

    @Test
    void fetchPostsOutOfCache_Rehydrate()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        mongoTemplate.save(new Post("p1", l1, LocalDateTime.now()));
        mongoTemplate.save(new Post("p2", l2, LocalDateTime.now()));
        mongoTemplate.save(new Post("p3", l2, LocalDateTime.now()));

        repository.findAllForUser(new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100),
                                  nearbyUsers);

        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.llen(getKey("test")), equalTo(3L));
            Assertions.assertThat(jedis.lrange(getKey("test"), 0, -1)).containsExactly("p3", "p2", "p1");
        }
    }

    @Test
    void fetchPostsOutOfCache_ExcludePosition()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        mongoTemplate.save(new Post("p1", l1, LocalDateTime.now()));
        mongoTemplate.save(new Post("p2", l2, LocalDateTime.now()));
        mongoTemplate.save(new Post("p3", l2, LocalDateTime.now()));

        List<Post> feed = repository.findAllForUser(
                new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100), nearbyUsers);
        Assertions.assertThat(feed).extracting("location")
                .extracting("position")
                .asList()
                .containsOnlyNulls();
    }

    @Test
    void fetchPostsInCache_ExpiryExtended()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        mongoTemplate.save(new Post("p1", l1, LocalDateTime.now()));
        mongoTemplate.save(new Post("p2", l2, LocalDateTime.now()));
        mongoTemplate.save(new Post("p3", l2, LocalDateTime.now()));
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lpush(getKey("test"), "p3", "p2", "p1");

            List<Post> feed = repository.findAllForUser(
                    new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100), nearbyUsers);
            assertThat(jedis.ttl(getKey("test")), equalTo(TimeUnit.DAYS.toSeconds(20)));
        }
    }

    @Test
    void fetchPostsInCache_CorrectPostData()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        mongoTemplate.save(new Post("p1", l1, LocalDateTime.now()));
        mongoTemplate.save(new Post("p2", l2, LocalDateTime.now()));
        mongoTemplate.save(new Post("p3", l2, LocalDateTime.now()));
        try (Jedis jedis = jedisPool.getResource()) {
            // Although we have 3 posts persisted, we should grab only 2 posts from the list
            jedis.lpush(getKey("test"), "p1");
            jedis.lpush(getKey("test"), "p2");

            List<Post> feed = repository.findAllForUser(
                    new UserLocation("test", new UserLocation.Position(22.507449, 84.34), 2100), nearbyUsers);
            assertThat(feed.size(), equalTo(2));
            Assertions.assertThat(feed).extracting("location").extracting("distance").isNotNull();
            Assertions.assertThat(feed).extracting("location").extracting("id").containsExactly("xyz", "abc123");
        }
    }

    @Test
    void fetchPostsInCache_ExcludePosition()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        mongoTemplate.save(new Post("p1", l1, LocalDateTime.now()));
        mongoTemplate.save(new Post("p2", l2, LocalDateTime.now()));
        mongoTemplate.save(new Post("p3", l2, LocalDateTime.now()));
        try (Jedis jedis = jedisPool.getResource()) {
            // Although we have 3 posts persisted, we should grab only 2 posts from the list
            jedis.lpush(getKey("test"), "p1");
            jedis.lpush(getKey("test"), "p2");

            List<Post> feed = repository.findAllForUser(
                    new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100), nearbyUsers);
            Assertions.assertThat(feed).extracting("location")
                    .extracting("position")
                    .asList()
                    .containsOnlyNulls();
        }
    }

    @Test
    void fetchPostsInCache_NotAListRehydrated()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(getKey("test"), "evil");

            mongoTemplate.save(new Post("p1", l1, LocalDateTime.now()));
            mongoTemplate.save(new Post("p2", l2, LocalDateTime.now()));
            mongoTemplate.save(new Post("p3", l2, LocalDateTime.now()));

            repository.findAllForUser(new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100),
                                      nearbyUsers);

            assertThat(jedis.llen(getKey("test")), equalTo(3L));
            Assertions.assertThat(jedis.lrange(getKey("test"), 0, -1)).containsExactly("p3", "p2", "p1");
        }
    }

    @Test
    void fetchPostsInCache_NotAListCorrectData()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(getKey("test"), "evil");

            mongoTemplate.save(new Post("p1", l1, LocalDateTime.now()));
            mongoTemplate.save(new Post("p2", l2, LocalDateTime.now()));
            mongoTemplate.save(new Post("p3", l2, LocalDateTime.now()));

            List<Post> feed = repository.findAllForUser(
                    new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100), nearbyUsers);
            Assertions.assertThat(feed).extracting("id").containsExactly("p3", "p2", "p1");
            Assertions.assertThat(feed).extracting("location").extracting("id")
                    .containsExactly("xyz", "xyz", "abc123");
        }
    }

    @Test
    void fetchPostsEmpty_ReturnsEmptyListNotNull()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        List<Post> feed = repository.findAllForUser(
                new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100), nearbyUsers);

        assertNotNull(feed);
        assertTrue(feed.isEmpty());
    }

    @Test
    void testInConsistentCache()
    {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        mongoTemplate.save(new Post("p1", l1, LocalDateTime.now()));
        mongoTemplate.save(new Post("p2", l2, LocalDateTime.now()));
        mongoTemplate.save(new Post("p3", l2, LocalDateTime.now()));
        try (Jedis jedis = jedisPool.getResource()) {
            // Although we have 3 posts persisted, we should grab only 2 posts from the list
            jedis.lpush(getKey("test"), "p1");
            jedis.lpush(getKey("test"), "invalid_post1");
            jedis.lpush(getKey("test"), "invalid_post2");
            jedis.lpush(getKey("test"), "p2");

            List<Post> feed = repository.findAllForUser(
                    new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100), nearbyUsers);

            Assertions.assertThat(feed).extracting("id").containsExactly("p2", "p1");
            Assertions.assertThat(feed).extracting("location").extracting("id")
                    .containsExactly("xyz", "abc123");
        }
    }

    @Test
    void testFullTextSearch_Success() {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        mongoTemplate.save(
                new Post("p1", l1, LocalDateTime.now(), Post.PostType.REQUEST, "Requiring umbrella for 20 rs"));
        mongoTemplate.save(
                new Post("p2", l2, LocalDateTime.now(), Post.PostType.OFFERING, "Requiring umbrella for 20 rs"));
        mongoTemplate.save(new Post("p3", l2, LocalDateTime.now(), Post.PostType.REQUEST, "Screwdriver"));

        List<Post> feed = repository.fetchMatchingNearbyPosts(
                new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100),
                nearbyUsers,
                new Post("a", new UserLocation("x", new UserLocation.Position(0, 0), 0), LocalDateTime.now(),
                         Post.PostType.OFFERING, "Umbrella")
        );

        assertThat(feed.size(), equalTo(1));
        assertThat(feed.get(0).getId(), equalTo("p1"));
    }

    @Test
    void testFullTextSearch_FalseExclusion() {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        mongoTemplate.save(new Post("p1", l1, LocalDateTime.now(), Post.PostType.REQUEST, "Apple watch"));
        mongoTemplate.save(new Post("p2", l2, LocalDateTime.now(), Post.PostType.OFFERING, "Samsung"));
        mongoTemplate.save(new Post("p3", l2, LocalDateTime.now(), Post.PostType.REQUEST, "Screwdriver"));

        List<Post> feed = repository.fetchMatchingNearbyPosts(
                new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100),
                nearbyUsers,
                new Post("a", new UserLocation("x", new UserLocation.Position(0, 0), 0), LocalDateTime.now(),
                         Post.PostType.OFFERING, "Umbrella")
        );

        Assertions.assertThat(feed).extracting("id")
                .doesNotContain("p3");
    }

    @Test
    void testFullTextSearch_ExcludingHomoType() {
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);
        List<UserLocation> nearbyUsers = new ArrayList<>();
        UserLocation l1 = new UserLocation("abc123", new UserLocation.Position(22.507449, 88.34), 2100);
        l1.setDistance(20);
        UserLocation l2 = new UserLocation("xyz", new UserLocation.Position(22.507449, 88.32), 2100);
        l2.setDistance(10);
        nearbyUsers.add(l1);
        nearbyUsers.add(l2);

        mongoTemplate.save(new Post("p1", l1, LocalDateTime.now(), Post.PostType.REQUEST, "Apple Watch"));
        mongoTemplate.save(new Post("p2", l2, LocalDateTime.now(), Post.PostType.OFFERING, "Selling Apple Watch"));
        mongoTemplate.save(new Post("p3", l2, LocalDateTime.now(), Post.PostType.REQUEST, "Screwdriver"));

        List<Post> feed = repository.fetchMatchingNearbyPosts(
                new UserLocation("test", new UserLocation.Position(22.507449, 88.34), 2100),
                nearbyUsers,
                new Post("a", new UserLocation("x", new UserLocation.Position(0, 0), 0), LocalDateTime.now(),
                         Post.PostType.OFFERING, "Watch")
        );
        feed.forEach(post -> System.out.println(post.getScore()));
        Assertions.assertThat(feed).extracting("id")
                .doesNotContain("p2");
    }

    @Test
    void testFetch_customLocation() {
        // 2 km
        mongoTemplate.save(new Post("p1", new UserLocation("x", new UserLocation.Position(22.62418527201904,
                                                                                          88.41448556631804), 10),
                                    LocalDateTime.now(), Post.PostType.REQUEST, "Apple watch"));
        // 10 km
        mongoTemplate.save(
                new Post("p2", new UserLocation("y", new UserLocation.Position(22.74, 88.42), 10), LocalDateTime.now(),
                         Post.PostType.OFFERING, "Samsung"));
        // should be ignored
        mongoTemplate.save(
                new Post("p3", new UserLocation("z", new UserLocation.Position(22.64, 88.42), 10), LocalDateTime.now(),
                         Post.PostType.REQUEST, "Screwdriver"));

        UserLocation currentUser = new UserLocation("z", new UserLocation.Position(22.643, 88.41), 10);
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);

        List<Post> feed = repository.findAllForUser(currentUser);
        feed.forEach(System.out::println);
        assertThat(feed.size(), equalTo(1));
        Assertions.assertThat(feed).extracting("id")
                .doesNotContain("p2");
        Assertions.assertThat(feed).extracting("id")
                .doesNotContain("p3");
    }

    @Test
    void testFetch_customLocation_DoesntIncludeOutOfRadiusSelfPreference() {
        // 2 km
        mongoTemplate.save(new Post("p1", new UserLocation("x", new UserLocation.Position(22.62418527201904,
                                                                                          88.41448556631804), 10),
                                    LocalDateTime.now(), Post.PostType.REQUEST, "Apple watch"));
        // 10 km
        mongoTemplate.save(
                new Post("p2", new UserLocation("y", new UserLocation.Position(22.74, 88.42), 10), LocalDateTime.now(),
                         Post.PostType.OFFERING, "Samsung"));
        // should be ignored
        mongoTemplate.save(
                new Post("p3", new UserLocation("z", new UserLocation.Position(22.64, 88.42), 10), LocalDateTime.now(),
                         Post.PostType.REQUEST, "Screwdriver"));

        UserLocation currentUser = new UserLocation("z", new UserLocation.Position(22.643, 88.41), 6);
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);

        List<Post> feed = repository.findAllForUser(currentUser);
        feed.forEach(System.out::println);
        Assertions.assertThat(feed).extracting("id")
                .doesNotContain("p2");
    }

    @Test
    void testFetch_customLocation_DoesntIncludeOutOfRadiusOtherPreference() {
        // 2 km
        mongoTemplate.save(new Post("p1", new UserLocation("x", new UserLocation.Position(22.62418527201904,
                                                                                          88.41448556631804), 10),
                                    LocalDateTime.now(), Post.PostType.REQUEST, "Apple watch"));
        // 10 km
        mongoTemplate.save(
                new Post("p2", new UserLocation("y", new UserLocation.Position(22.74, 88.42), 10), LocalDateTime.now(),
                         Post.PostType.OFFERING, "Samsung"));
        // should be ignored
        mongoTemplate.save(
                new Post("p3", new UserLocation("z", new UserLocation.Position(22.64, 88.42), 4), LocalDateTime.now(),
                         Post.PostType.REQUEST, "Screwdriver"));

        UserLocation currentUser = new UserLocation("z", new UserLocation.Position(22.643, 88.41), 10);
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);

        List<Post> feed = repository.findAllForUser(currentUser);
        feed.forEach(System.out::println);
        Assertions.assertThat(feed).extracting("id")
                .doesNotContain("p2");
    }

    @Test
    void testFetch_customLocation_DoesntIncludeSelf() {
        // 2 km
        mongoTemplate.save(new Post("p1", new UserLocation("x", new UserLocation.Position(22.62418527201904,
                                                                                          88.41448556631804), 10),
                                    LocalDateTime.now(), Post.PostType.REQUEST, "Apple watch"));
        // 10 km
        mongoTemplate.save(
                new Post("p2", new UserLocation("y", new UserLocation.Position(22.74, 88.42), 10), LocalDateTime.now(),
                         Post.PostType.OFFERING, "Samsung"));
        // should be ignored
        mongoTemplate.save(
                new Post("p3", new UserLocation("z", new UserLocation.Position(22.64, 88.42), 4), LocalDateTime.now(),
                         Post.PostType.REQUEST, "Screwdriver"));
        mongoTemplate.save(
                new Post("p4", new UserLocation("z", new UserLocation.Position(22.64, 88.42), 4), LocalDateTime.now(),
                         Post.PostType.REQUEST, "Screwdriver"));

        UserLocation currentUser = new UserLocation("z", new UserLocation.Position(22.643, 88.41), 10);
        FnCoreConfig config = FnCoreConfig.builder()
                .redisKeyspace("FNCORE")
                .feedCacheExpiry(20)
                .build();
        HybridPostRepository repository = new HybridPostRepository(mongoTemplate, jedisPool, config);

        List<Post> feed = repository.findAllForUser(currentUser);
        feed.forEach(System.out::println);
        Assertions.assertThat(feed).extracting("id")
                .doesNotContain("p3", "p4");
    }

    private String getKey(String id) {
        return FEED_NAMESPACE + ":" + id;
    }
}
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyLoadTester {

    private static final String PROXY_URL = "http://localhost:8080/api/v2/entries/en/";

    // Same pool of words
    private static final String[] WORDS = {
            "time", "time", "test1", "test1", "person", "year", "way", "day",
            "gaily", "eunuch", "world", "life", "hand",
            "part", "child", "lagoon", "guru", "place"
    };

    // private static final String[] WORDS = {
    // "time", "person", "year", "way", "day", "thing", "man", "world", "life",
    // "hand",
    // "part", "child", "eye", "woman", "place", "work", "week", "case", "point",
    // "government",
    // "company", "number", "group", "problem", "fact", "home", "water", "room",
    // "mother", "area",
    // "money", "story", "issue", "side", "kind", "head", "house", "service",
    // "friend", "father",
    // "power", "hour", "game", "line", "end", "member", "law", "car", "city",
    // "community",
    // "name", "president", "team", "minute", "idea", "kid", "body", "information",
    // "back", "parent",
    // "face", "others", "level", "office", "door", "health", "art", "war",
    // "history", "party",
    // "result", "change", "morning", "reason", "research", "girl", "guy", "moment",
    // "air", "teacher",
    // "force", "education", "foot", "boy", "age", "policy", "process", "music",
    // "market", "sense",
    // "nation", "plan", "college", "interest", "death", "experience", "effect",
    // "use", "class", "control",
    // "care", "field", "development", "role", "effort", "rate", "heart", "drug",
    // "leader", "light",
    // "voice", "wife", "police", "mind", "price", "report", "decision", "son",
    // "view", "relationship",
    // "town", "road", "arm", "difference", "value", "building", "action", "model",
    // "season", "society",
    // "tax", "director", "position", "player", "record", "paper", "space",
    // "ground", "form", "event",
    // "love", "computer", "phone", "internet", "science", "language", "flower",
    // "tree", "river", "mountain"
    // };

    private static final int THREADS = 10;
    private static final int RANDOM_REQUESTS = 20;

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // Metrics
    private static final AtomicLong success = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();

    private static final AtomicLong hitCount = new AtomicLong();
    private static final AtomicLong missCount = new AtomicLong();

    private static final AtomicLong totalTime = new AtomicLong();

    private static final AtomicLong minTime = new AtomicLong(Long.MAX_VALUE);
    private static final AtomicLong maxTime = new AtomicLong(0);

    public static void main(String[] args) throws Exception {

        long suiteStart = System.currentTimeMillis();

        System.out.println("\n======================================");
        System.out.println("      PROXY LOAD TEST STARTED");
        System.out.println("======================================");

        
        warmCache();
        
        //lruTest();        

        //ttlTest();

        randomLoadTest();

        long suiteEnd = System.currentTimeMillis();

        printResults(suiteEnd - suiteStart);
    }

    // ------------------------------------------------------------------------

    private static void warmCache() {

        System.out.println("\n========== PHASE 1 : CACHE WARM-UP ==========");

        for (String word : WORDS) {
            sendRequest(word);
        }
    }

    // ------------------------------------------------------------------------

    private static void randomLoadTest() throws Exception {

        System.out.println("\n========== PHASE 2 : CONCURRENT LOAD TEST ==========");

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);

        CountDownLatch latch = new CountDownLatch(RANDOM_REQUESTS);

        for (int i = 0; i < RANDOM_REQUESTS; i++) {

            executor.submit(() -> {

                try {

                    String word = WORDS[ThreadLocalRandom.current().nextInt(WORDS.length)];

                    sendRequest(word);

                } finally {

                    latch.countDown();
                }

            });
        }

        latch.await();

        executor.shutdown();
    }

    // ------------------------------------------------------------------------

    private static void lruTest() {

        System.out.println("\n========== PHASE 3 : LRU TEST ==========");

        // STEP 1
        // Fill the cache completely.

        System.out.println("\nStep 1: Filling cache with 20 entries...\n");

        for (int i = 0; i < 20; i++) {
            sendRequest("testword" + i);
        }

        // STEP 2
        // Access the first five again.
        // They should become the MOST recently used.

        System.out.println("\nStep 2: Accessing testword0-4 again (should all be HIT)...\n");

        for (int i = 0; i < 5; i++) {
            sendRequest("testword" + i);
        }

        // STEP 3
        // Insert five NEW entries.
        // Cache is already full, so five entries must be evicted.
        // Since 0-4 were just accessed,
        // the LRU entries should be 5-9.

        System.out.println("\nStep 3: Adding 5 new entries...\n");

        for (int i = 20; i < 25; i++) {
            sendRequest("testword" + i);
        }

        // STEP 4
        // These SHOULD have been evicted.

        System.out.println("\nStep 4: Checking entries expected to be evicted...\n");

        for (int i = 5; i < 10; i++) {
            sendRequest("testword" + i);
        }

        // STEP 5
        // These SHOULD still be present because we touched them recently.

        System.out.println("\nStep 5: Checking recently used entries...\n");

        for (int i = 0; i < 5; i++) {
            sendRequest("testword" + i);
        }

        // STEP 6
        // These were never touched after insertion.
        // They should still exist because they weren't the oldest.

        System.out.println("\nStep 6: Checking untouched but expected-to-remain entries...\n");

        for (int i = 10; i < 20; i++) {
            sendRequest("testword" + i);
        }
    }

    // ------------------------------------------------------------------------

    private static void ttlTest() throws Exception {

        System.out.println("\n========== PHASE 4 : TTL TEST ==========");

        sendRequest("time");

        System.out.println("\nWaiting 65 seconds for cache expiry...\n");

        Thread.sleep(65000);

        sendRequest("time");
    }

    // ------------------------------------------------------------------------

    private static void sendRequest(String word) {

        try {

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PROXY_URL + word))
                    .GET()
                    .build();

            long start = System.currentTimeMillis();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            long duration = System.currentTimeMillis() - start;

            success.incrementAndGet();

            totalTime.addAndGet(duration);

            minTime.accumulateAndGet(duration, Math::min);

            maxTime.accumulateAndGet(duration, Math::max);

            String cacheStatus = response.headers()
                    .firstValue("X-Cache")
                    .orElse("UNKNOWN");

            if ("HIT".equals(cacheStatus))
                hitCount.incrementAndGet();

            if ("MISS".equals(cacheStatus))
                missCount.incrementAndGet();

            System.out.printf(
                    "%-12s | %-4s | %3d ms | HTTP %d%n",
                    word,
                    cacheStatus,
                    duration,
                    response.statusCode());

        } catch (Exception e) {

            failed.incrementAndGet();

            System.out.println(word + " -> FAILED : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------

    private static void printResults(long wallClock) {

        System.out.println("\n======================================");
        System.out.println("             RESULTS");
        System.out.println("======================================");

        System.out.println("Successful Requests : " + success.get());
        System.out.println("Failed Requests     : " + failed.get());

        System.out.println();

        System.out.println("Cache Hits          : " + hitCount.get());
        System.out.println("Cache Misses        : " + missCount.get());

        double hitRate = success.get() == 0
                ? 0
                : (100.0 * hitCount.get() / success.get());

        System.out.printf("Hit Rate            : %.2f %%\n", hitRate);

        System.out.println();

        if (success.get() > 0) {

            double avg = (double) totalTime.get() / success.get();

            System.out.printf("Average Response    : %.2f ms%n", avg);

            System.out.println("Minimum Response    : " + minTime.get() + " ms");

            System.out.println("Maximum Response    : " + maxTime.get() + " ms");

            System.out.println("Total Response Time : " + totalTime.get() + " ms");
        }

        System.out.println();

        System.out.println("Wall Clock Time     : " + wallClock + " ms");

        System.out.println("======================================");
    }
}
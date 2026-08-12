import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom; // Added for thread-safe random selection
import java.util.concurrent.atomic.AtomicLong; // Added for thread-safe time accumulation

public class MultiClient {

    // Configuration for the load test
    private static final int TOTAL_CLIENTS = 500; // Total number of clients to simulate
    private static final int CONCURRENT_THREADS = 10;
    static AtomicLong totalTime = new AtomicLong(0); // Variable to track total time taken for all requests

    // 1. Array of words to choose from randomly
    private static final String[] WORD_POOL = {
            "time", "person", "year", "way", "day",
            "gaily", "eunuch", "world", "life", "hand",
            "part", "child", "lagoon", "guru", "place"
    };


    public static void main(String[] args) {
        // 1. Initialize metrics
        long testStartTime = System.currentTimeMillis();

        // 2. Setup your SSL context and HTTP client cleanly upfront
        HttpClient client;
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            client = HttpClient.newBuilder().sslContext(sslContext).build();
        } catch (Exception e) {
            System.err.println("Failed to initialize HTTP Client: " + e.getMessage());
            return; // Exit early if setup fails; no threads were ever started
        }

        // 3. Use try-with-resources for the Executor.
        // Java automatically calls shutdown() and awaits termination when exiting this
        // block!
        System.out.println("Starting load test with " + TOTAL_CLIENTS + " clients...");

        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS)) {

            for (int i = 1; i <= TOTAL_CLIENTS; i++) {
                final int clientId = i;
                final HttpClient finalClient = client;
                executor.submit(() -> {
                    int randomIndex = ThreadLocalRandom.current().nextInt(WORD_POOL.length);
                    String randomWord = WORD_POOL[randomIndex];
                    sendRequest(finalClient, randomWord, clientId);
                });
            }

        } // <--- Thread pool implicitly SHUTDOWNS and BLOCKS here until all tasks finish.

        // 4. Clean, unblocked execution of your final metrics reports
        long testEndTime = System.currentTimeMillis();
        long totalWallClockTime = testEndTime - testStartTime;

        System.out.println("\n--- Load Test Results ---");
        System.out.println("Sum of all individual response times: " + totalTime.get() + " ms");
        System.out.println("Actual elapsed time for the entire test: " + totalWallClockTime + " ms");
    }

    private static void sendRequest(HttpClient client, String query, int clientId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/api/v2/entries/en/" + query))
                    .GET()
                    .build();

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            totalTime.addAndGet(duration); // Accumulate total time

            // 3. Updated print statement to show which word was queried
            System.out.println("Client #" + clientId + " [Word: " + query + "] -> Status: " + response.statusCode()
                    + " | Time: " + duration + "ms");

        } catch (Exception e) {
            System.err.println("Client #" + clientId + " [Word: " + query + "] failed: " + e.getMessage());
        }
    }
}

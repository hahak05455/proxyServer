import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class Cache {

    // ---------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------

    private static final int DEFAULT_PORT = 3000;

    private static int PORT;
    private static String TARGET_SERVER;

    private static final long CACHE_EXPIRY = 60 * 1000; // 1 minute

    private static final int MAX_CACHE_SIZE = 100;

    // ---------------------------------------------------------
    // Cache
    // ---------------------------------------------------------

    private static final ConcurrentHashMap<String, Node> cache = new ConcurrentHashMap<>();

    private static Node head;
    private static Node tail;

    private static final Object cacheLock = new Object();

    // ---------------------------------------------------------
    // Thread Pool
    // ---------------------------------------------------------

    private static final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    // ---------------------------------------------------------
    // HTTP Client
    // ---------------------------------------------------------

    private static final HttpClient httpClient;

    static {
        try {
            httpClient = createHttpClient();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================
    // MAIN
    // =========================================================

    public static void main(String[] args) {

        if (args.length == 0) {
            printHelp();
            return;
        }

        // -----------------------------------------
        // --help
        // -----------------------------------------

        if (contains(args, "--help")) {
            printHelp();
            return;
        }

        // -----------------------------------------
        // --clear-cache
        // -----------------------------------------

        if (contains(args, "--clear-cache")) {

            if (args.length != 1) {
                System.out.println("Error: --clear-cache cannot be combined with other arguments.\n");

                printHelp();
                return;
            }

            clearCache();

            System.out.println("Cache cleared.");
            return;
        }

        // -----------------------------------------
        // Parse arguments
        // -----------------------------------------

        String origin = null;
        int port = DEFAULT_PORT;

        for (int i = 0; i < args.length; i++) {

            String argument = args[i];

            switch (argument) {

                case "--port":

                    if (i + 1 >= args.length) {
                        System.out.println(
                                "Error: --port requires a number.\n");
                        printHelp();
                        return;
                    }

                    try {
                        port = Integer.parseInt(args[++i]);

                        if (port < 1 || port > 65535) {
                            System.out.println(
                                    "Error: port must be between 1 and 65535.\n");
                            printHelp();
                            return;
                        }

                    } catch (NumberFormatException e) {

                        System.out.println(
                                "Error: invalid port number.\n");

                        printHelp();
                        return;
                    }

                    break;

                case "--origin":

                    if (i + 1 >= args.length) {
                        System.out.println(
                                "Error: --origin requires a URL.\n");
                        printHelp();
                        return;
                    }

                    origin = args[++i];

                    if (!isValidOrigin(origin)) {

                        System.out.println(
                                "Error: invalid origin URL.\n");

                        printHelp();
                        return;
                    }

                    break;

                default:

                    System.out.println(
                            "Error: unknown argument: " + argument + "\n");

                    printHelp();
                    return;
            }
        }

        // -----------------------------------------
        // Origin is required
        // -----------------------------------------

        if (origin == null) {

            System.out.println(
                    "Error: --origin is required.\n");

            printHelp();
            return;
        }

        // -----------------------------------------
        // Set configuration
        // -----------------------------------------

        PORT = port;
        TARGET_SERVER = removeTrailingSlash(origin);
        

        startServer();
    }

    // =========================================================
    // SERVER
    // =========================================================

    private static void startServer() {

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {

            System.out.println();
            System.out.println("Caching Proxy Started");
            System.out.println("---------------------");
            System.out.println("Port   : " + PORT);
            System.out.println("Origin : " + TARGET_SERVER);
            System.out.println();

            while (true) {

                Socket clientSocket = serverSocket.accept();

                System.out.println("Client Connected");

                threadPool.submit(() -> {

                    try {

                        handleClient(clientSocket);

                    } catch (Exception e) {

                        e.printStackTrace();

                    } finally {

                        try {
                            clientSocket.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }

        } catch (IOException e) {

            System.out.println(
                    "Could not start proxy on port " + PORT);

            e.printStackTrace();
        }
    }

    // =========================================================
    // HANDLE CLIENT
    // =========================================================

    private static void handleClient(Socket clientSocket)
            throws Exception {

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        clientSocket.getInputStream()));

        OutputStream clientOut = clientSocket.getOutputStream();

        // -----------------------------------------
        // Read request line
        // -----------------------------------------

        String requestLine = reader.readLine();

        if (requestLine == null) {
            return;
        }

        System.out.println("Incoming Request:");
        System.out.println(requestLine);

        // -----------------------------------------
        // Read headers
        // -----------------------------------------

        String line;

        while ((line = reader.readLine()) != null
                && !line.isEmpty()) {

            System.out.println(line);
        }

        // -----------------------------------------
        // Parse request
        // -----------------------------------------

        String[] parts = requestLine.split(" ");

        if (parts.length < 3) {

            sendError(
                    clientOut,
                    400,
                    "Bad Request");

            return;
        }

        String method = parts[0];
        String path = parts[1];

        // -----------------------------------------
        // Currently cache GET requests
        // -----------------------------------------

        if (!method.equalsIgnoreCase("GET")) {

            sendError(
                    clientOut,
                    405,
                    "Method Not Allowed");

            return;
        }

        // -----------------------------------------
        // Build origin URL
        // -----------------------------------------

        String targetUrl = TARGET_SERVER + path;

        String cacheKey = method.toUpperCase() + ":" + targetUrl;

        // -----------------------------------------
        // Check cache
        // -----------------------------------------

        Node node = cache.get(cacheKey);

        if (node != null) {

            long age = System.currentTimeMillis()
                    - node.value.timestamp;

            // -------------------------------------
            // Valid cache entry
            // -------------------------------------

            if (age < CACHE_EXPIRY) {

                System.out.println("CACHE HIT");

                synchronized (cacheLock) {

                    moveToFront(node);
                }

                sendResponse(
                        clientOut,
                        node.value,
                        "HIT");

                return;
            }

            // -------------------------------------
            // Expired entry
            // -------------------------------------

            System.out.println("CACHE EXPIRED");

            synchronized (cacheLock) {

                cache.remove(cacheKey);
                removeNode(node);
            }
        }

        // -----------------------------------------
        // Cache MISS
        // -----------------------------------------

        System.out.println("CACHE MISS");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofByteArray());

        // -----------------------------------------
        // Store response headers
        // -----------------------------------------

        Map<String, List<String>> responseHeaders = response.headers().map();

        CacheEntry entry = new CacheEntry(
                response.statusCode(),
                response.body(),
                responseHeaders);

        // -----------------------------------------
        // Add to cache
        // -----------------------------------------

        Node newNode = new Node(cacheKey, entry);

        synchronized (cacheLock) {

            Node existing = cache.get(cacheKey);

            if (existing == null) {

                cache.put(
                        cacheKey,
                        newNode);

                addToFront(newNode);

                System.out.println(
                        "Inserted : " + cacheKey);

                System.out.println(
                        "Cache size : " + cache.size());

                // ---------------------------------
                // LRU eviction
                // ---------------------------------

                if (cache.size() > MAX_CACHE_SIZE) {

                    Node victim = removeTail();

                    if (victim != null) {

                        cache.remove(victim.key);

                        System.out.println(
                                "Evicted : " + victim.key);
                    }
                }

            } else {

                moveToFront(existing);
            }
        }

        // -----------------------------------------
        // Send MISS response
        // -----------------------------------------

        sendResponse(
                clientOut,
                entry,
                "MISS");
    }

    // =========================================================
    // SEND RESPONSE
    // =========================================================

    private static void sendResponse(
            OutputStream clientOut,
            CacheEntry entry,
            String cacheStatus)
            throws IOException {

        PrintWriter writer = new PrintWriter(clientOut);

        // -----------------------------------------
        // Status
        // -----------------------------------------

        writer.println(
                "HTTP/1.1 "
                        + entry.statusCode
                        + " "
                        + getReasonPhrase(entry.statusCode));

        // -----------------------------------------
        // Original headers
        // -----------------------------------------

        for (Map.Entry<String, List<String>> header : entry.headers.entrySet()) {

            String name = header.getKey();

            // Skip hop-by-hop headers
            if (isHopByHopHeader(name)) {
                continue;
            }

            // We calculate Content-Length ourselves
            if (name.equalsIgnoreCase("Content-Length")) {
                continue;
            }

            for (String value : header.getValue()) {

                writer.println(
                        name + ": " + value);
            }
        }

        // -----------------------------------------
        // Proxy headers
        // -----------------------------------------

        writer.println(
                "Content-Length: "
                        + entry.body.length);

        writer.println(
                "X-Cache: " + cacheStatus);

        writer.println(
                "Connection: close");

        writer.println();

        writer.flush();

        // -----------------------------------------
        // Body
        // -----------------------------------------

        clientOut.write(entry.body);

        clientOut.flush();
    }

    // =========================================================
    // ERROR RESPONSE
    // =========================================================

    private static void sendError(
            OutputStream output,
            int statusCode,
            String message)
            throws IOException {

        byte[] body = message.getBytes();

        PrintWriter writer = new PrintWriter(output);

        writer.println(
                "HTTP/1.1 "
                        + statusCode
                        + " "
                        + message);

        writer.println(
                "Content-Type: text/plain");

        writer.println(
                "Content-Length: "
                        + body.length);

        writer.println(
                "Connection: close");

        writer.println();

        writer.flush();

        output.write(body);
        output.flush();
    }

    // =========================================================
    // CACHE
    // =========================================================

    private static void clearCache() {

        synchronized (cacheLock) {

            cache.clear();

            head = null;
            tail = null;
        }
    }

    private static void addToFront(Node node) {

        node.prev = null;
        node.next = head;

        if (head != null) {
            head.prev = node;
        }

        head = node;

        if (tail == null) {
            tail = node;
        }
    }

    private static void removeNode(Node node) {

        if (node.prev != null) {

            node.prev.next = node.next;

        } else {

            head = node.next;
        }

        if (node.next != null) {

            node.next.prev = node.prev;

        } else {

            tail = node.prev;
        }

        node.prev = null;
        node.next = null;
    }

    private static void moveToFront(Node node) {

        if (node == head) {
            return;
        }

        removeNode(node);
        addToFront(node);
    }

    private static Node removeTail() {

        if (tail == null) {
            return null;
        }

        Node old = tail;

        removeNode(old);

        return old;
    }

    // =========================================================
    // HTTP CLIENT
    // =========================================================

    private static HttpClient createHttpClient()
            throws Exception {

        TrustManager[] trustAll = new TrustManager[] {

                new X509TrustManager() {

                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    public void checkClientTrusted(
                            X509Certificate[] certs,
                            String authType) {
                    }

                    public void checkServerTrusted(
                            X509Certificate[] certs,
                            String authType) {
                    }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");

        sslContext.init(
                null,
                trustAll,
                new SecureRandom());

        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
    }

    // =========================================================
    // CLI HELP
    // =========================================================

    private static void printHelp() {

        System.out.println();
        System.out.println("Caching Proxy");
        System.out.println();
        System.out.println("Usage:");
        System.out.println(
                "  caching-proxy --port <number> --origin <url>");
        System.out.println(
                "  caching-proxy --origin <url>");
        System.out.println(
                "  caching-proxy --clear-cache");
        System.out.println(
                "  caching-proxy --help");
        System.out.println();

        System.out.println("Options:");
        System.out.println(
                "  --port <number>     Port on which the proxy will run.");
        System.out.println(
                "                      Default: 3000");

        System.out.println("  --origin <url>      Origin server to forward requests to.");

        System.out.println(
                "  --clear-cache       Clear the proxy cache.");

        System.out.println(
                "  --help              Show this help message.");

        System.out.println();

        System.out.println("Examples:");

        System.out.println(
                "  caching-proxy --port 3000 --origin http://dummyjson.com");

        System.out.println(
                "  caching-proxy --origin http://dummyjson.com");

        System.out.println(
                "  caching-proxy --clear-cache");

        System.out.println();
    }

    // =========================================================
    // CLI HELPERS
    // =========================================================

    private static boolean contains(
            String[] args,
            String value) {

        for (String arg : args) {

            if (arg.equals(value)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isValidOrigin(
            String origin) {

        try {

            URI uri = URI.create(origin);

            return uri.getScheme() != null
                    && uri.getHost() != null
                    && (uri.getScheme().equalsIgnoreCase("http")
                            || uri.getScheme().equalsIgnoreCase("https"));

        } catch (Exception e) {

            return false;
        }
    }

    private static String removeTrailingSlash(
            String url) {

        while (url.endsWith("/")) {

            url = url.substring(
                    0,
                    url.length() - 1);
        }

        return url;
    }

    // =========================================================
    // HTTP HELPERS
    // =========================================================

    private static boolean isHopByHopHeader(
            String header) {

        return header.equalsIgnoreCase("Connection")
                || header.equalsIgnoreCase("Keep-Alive")
                || header.equalsIgnoreCase("Proxy-Authenticate")
                || header.equalsIgnoreCase("Proxy-Authorization")
                || header.equalsIgnoreCase("TE")
                || header.equalsIgnoreCase("Trailer")
                || header.equalsIgnoreCase("Transfer-Encoding")
                || header.equalsIgnoreCase("Upgrade");
    }

    private static String getReasonPhrase(
            int statusCode) {

        switch (statusCode) {

            case 200:
                return "OK";

            case 201:
                return "Created";

            case 204:
                return "No Content";

            case 301:
                return "Moved Permanently";

            case 302:
                return "Found";

            case 304:
                return "Not Modified";

            case 400:
                return "Bad Request";

            case 401:
                return "Unauthorized";

            case 403:
                return "Forbidden";

            case 404:
                return "Not Found";

            case 405:
                return "Method Not Allowed";

            case 500:
                return "Internal Server Error";

            case 502:
                return "Bad Gateway";

            case 503:
                return "Service Unavailable";

            default:
                return "";
        }
    }

    // =========================================================
    // CACHE ENTRY
    // =========================================================

    static class CacheEntry {

        int statusCode;

        byte[] body;

        Map<String, List<String>> headers;

        long timestamp;

        CacheEntry(
                int statusCode,
                byte[] body,
                Map<String, List<String>> headers) {

            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // =========================================================
    // LRU NODE
    // =========================================================

    static class Node {

        String key;

        CacheEntry value;

        Node prev;

        Node next;

        Node(
                String key,
                CacheEntry value) {

            this.key = key;
            this.value = value;
        }
    }
}
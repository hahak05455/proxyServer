
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class CacheV3 {

    private static final int PORT = 8080;
    private static final ConcurrentHashMap<String, Node> cache = new ConcurrentHashMap<>();
    private static final long CACHE_EXPIRY = 60 * 1000; // 1 minute
    private static final HttpClient httpClient;

    // private static final int MAX_CACHE_SIZE = 10; // 100 entries
    private static final int MAX_CACHE_SIZE = 100; // 100 entries

    private static Node head;
    private static Node tail;

    private static final Object cacheLock = new Object();

    static {
        try {
            httpClient = createHttpClient();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    private static final String TARGET_SERVER = "https://api.dictionaryapi.dev";

    public static void main(String[] args) {

        try {

            ServerSocket serverSocket = new ServerSocket(PORT);

            System.out.println("Proxy running on port " + PORT);

            while (true) {

                Socket clientSocket = serverSocket.accept();

                System.out.println();
                System.out.println("Client Connected");

                threadPool.submit(() -> {

                    try {
                        handleClient(clientSocket);
                    } finally {
                        try {
                            clientSocket.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                });
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket clientSocket) {

        try {

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));

            OutputStream clientOut = clientSocket.getOutputStream();

            // -----------------------------------------
            // Read HTTP Request
            // -----------------------------------------

            String requestLine = reader.readLine();

            if (requestLine == null)
                return;

            System.out.println("Incoming Request:");
            System.out.println(requestLine);

            String line;

            while (!(line = reader.readLine()).isEmpty()) {
                System.out.println(line);
            }

            // -----------------------------------------
            // Parse Request Line
            // -----------------------------------------

            // Example:
            // GET /api/v2/entries/en/hello HTTP/1.1

            String[] parts = requestLine.split(" ");

            String method = parts[0];
            String path = parts[1];

            // -----------------------------------------
            // Forward request
            // -----------------------------------------

            String targetUrl = TARGET_SERVER + path;
            String cacheKey = method + ":" + targetUrl;

            Node node = cache.get(cacheKey);

            if (node != null) {

                if (System.currentTimeMillis() - node.value.timestamp < CACHE_EXPIRY) {

                    System.out.println("CACHE HIT");

                    synchronized (cacheLock) {
                        moveToFront(node);
                    }

                    sendResponse(clientOut, node.value, "HIT");
                    return;
                }

                synchronized (cacheLock) {
                    cache.remove(cacheKey);
                    removeNode(node);
                }
            }

            System.out.println("CACHE MISS");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());

            CacheEntry entry = new CacheEntry(
                    response.statusCode(),
                    response.body(),
                    response.headers()
                            .firstValue("Content-Type")
                            .orElse("application/json"));

            Node newNode = new Node(cacheKey, entry);

            synchronized (cacheLock) {

                Node existing = cache.get(cacheKey);

                if (existing == null) {

                    cache.put(cacheKey, newNode);

                    System.out.println("Inserted : " + cacheKey);
                    System.out.println("Cache size : " + cache.size());

                    addToFront(newNode);

                    if (cache.size() > MAX_CACHE_SIZE) {

                        Node victim = removeTail();

                        if (victim != null) {
                            cache.remove(victim.key);
                        }
                    }

                } else {

                    moveToFront(existing);

                    newNode = existing;
                }
            }

            // -----------------------------------------
            // Send response back
            // -----------------------------------------

            sendResponse(clientOut, entry, "MISS");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static HttpClient createHttpClient() throws Exception {

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

        sslContext.init(null, trustAll, new SecureRandom());

        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
    }

    private static void sendResponse(OutputStream clientOut,
            CacheEntry entry,
            String cacheStatus) throws IOException {

        PrintWriter writer = new PrintWriter(clientOut);

        writer.println("HTTP/1.1 " + entry.statusCode + " OK");
        writer.println("Content-Type: " + entry.contentType);
        writer.println("Content-Length: " + entry.body.length);
        writer.println("X-Cache: " + cacheStatus);
        writer.println("Connection: close");
        writer.println();

        writer.flush();

        clientOut.write(entry.body);

        clientOut.flush();

    }

    private static void addToFront(Node node) {

        node.prev = null;
        node.next = head;

        if (head != null)
            head.prev = node;

        head = node;

        if (tail == null)
            tail = node;
    }

    private static void removeNode(Node node) {

        if (node.prev != null)
            node.prev.next = node.next;
        else
            head = node.next;

        if (node.next != null)
            node.next.prev = node.prev;
        else
            tail = node.prev;

        node.prev = null;
        node.next = null;
    }

    private static void moveToFront(Node node) {

        if (node == head)
            return;

        removeNode(node);

        addToFront(node);
    }

    private static Node removeTail() {

        if (tail == null)
            return null;

        Node old = tail;

        removeNode(old);

        return old;
    }

    static class CacheEntry {

        int statusCode;
        byte[] body;
        String contentType;
        long timestamp;

        public CacheEntry(int statusCode,
                byte[] body,
                String contentType) {

            this.statusCode = statusCode;
            this.body = body;
            this.contentType = contentType;
            this.timestamp = System.currentTimeMillis();
        }
    }

    static class Node {

        String key;

        CacheEntry value;

        Node prev;

        Node next;

        Node(String key, CacheEntry value) {
            this.key = key;
            this.value = value;
        }
    }
}

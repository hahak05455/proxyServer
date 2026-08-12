import java.io.*;
import java.net.*;
import java.net.http.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;

import javax.net.ssl.*;

public class CacheV1 {

    private static final int PORT = 8080;
    private static final HashMap<String, CacheEntry> cache = new HashMap<>();
    private static final long CACHE_EXPIRY = 60 * 1000; // 1 minute

    private static final String TARGET_SERVER =
            "https://api.dictionaryapi.dev";

    public static void main(String[] args) {

        try {

            ServerSocket serverSocket = new ServerSocket(PORT);

            System.out.println("Proxy running on port " + PORT);

            while (true) {

                Socket clientSocket = serverSocket.accept();

                System.out.println("Client Connected");

                handleClient(clientSocket);

                clientSocket.close();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket clientSocket) {

        try {

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(clientSocket.getInputStream()));

            OutputStream clientOut = clientSocket.getOutputStream();

            //-----------------------------------------
            // Read HTTP Request
            //-----------------------------------------

            String requestLine = reader.readLine();

            if (requestLine == null)
                return;

            System.out.println("Incoming Request:");
            System.out.println(requestLine);

            String line;

            while (!(line = reader.readLine()).isEmpty()) {
                System.out.println(line);
            }

            //-----------------------------------------
            // Parse Request Line
            //-----------------------------------------

            // Example:
            // GET /api/v2/entries/en/hello HTTP/1.1

            String[] parts = requestLine.split(" ");

            String method = parts[0];
            String path = parts[1];

            //-----------------------------------------
            // Forward request
            //-----------------------------------------

            String targetUrl = TARGET_SERVER + path;
            String cacheKey = method + ":" + targetUrl;

            CacheEntry cached = cache.get(cacheKey);

            if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_EXPIRY){

                System.out.println("CACHE HIT");
                System.out.println();

                sendResponse(clientOut, cached);

                return;
            }

            System.out.println("CACHE MISS");
            System.out.println();

            HttpClient httpClient = createHttpClient();

            HttpRequest request = HttpRequest.newBuilder()
                                    .uri(URI.create(targetUrl))
                                    .GET()
                                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            CacheEntry entry = new CacheEntry(
                                response.statusCode(),
                                response.body(),
                                response.headers()
                                    .firstValue("Content-Type")
                                    .orElse("application/json"));

            cache.put(cacheKey, entry);

            //-----------------------------------------
            // Send response back
            //-----------------------------------------

            sendResponse(clientOut, entry);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static HttpClient createHttpClient() throws Exception {

        TrustManager[] trustAll = new TrustManager[]{
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
                                 CacheEntry entry) throws IOException {

        PrintWriter writer = new PrintWriter(clientOut);

        writer.println("HTTP/1.1 " + entry.statusCode + " OK");
        writer.println("Content-Type: " + entry.contentType);
        writer.println("Content-Length: " + entry.body.length);
        writer.println("Connection: close");
        writer.println();

        writer.flush();

        clientOut.write(entry.body);

        clientOut.flush();
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
}
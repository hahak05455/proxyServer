import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import java.util.Scanner;


public class MyClient {
    public static void main(String[] args) {
        try {

            TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };

        // 2. Initialize the SSLContext with the lenient trust manager
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            // 1. Create the HTTP Client
            HttpClient client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();

            String query = "";
            // 1. Read user input
            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter a word to search: ");
            query = scanner.nextLine();
            scanner.close();

            // 2. Build the Request           
                
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/api/v2/entries/en/" + query))
                    .GET() // Optional, GET is the default
                    .build();

            // 3. Send the Request and handle the Response
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 4. Print results
            System.out.println("Status Code: " + response.statusCode());
            System.out.println("Response Body: " + response.body());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

package jobhunter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Minimal polite HTTP client (JDK only): timeouts, a clear User-Agent and a few retries. */
class Http {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    String get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = base(url, headers).GET();
        return send(b.build(), url);
    }

    String postJson(String url, Map<String, String> headers, String json) throws IOException, InterruptedException {
        HttpRequest.Builder b = base(url, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        return send(b.build(), url);
    }

    private HttpRequest.Builder base(String url, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "RemoteJobHunter/1.0 (personal job-search tool)")
                .header("Accept", "application/json, application/rss+xml, text/xml, */*");
        if (headers != null) headers.forEach(b::header);
        return b;
    }

    private String send(HttpRequest req, String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int sc = r.statusCode();
                if (sc >= 200 && sc < 300) return r.body();
                last = new IOException("HTTP " + sc + " from " + req.uri().getHost());
                if (sc != 429 && sc < 500) throw last;      // client errors will not improve on retry
            } catch (IOException e) {
                last = e;
                if (e.getMessage() != null && e.getMessage().startsWith("HTTP 4") && !e.getMessage().startsWith("HTTP 429")) throw e;
            }
            Thread.sleep(1500L << attempt);
        }
        throw last != null ? last : new IOException("Request failed: " + req.uri().getHost());   // never the full URL: it can hold an API key
    }
}

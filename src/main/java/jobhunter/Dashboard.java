package jobhunter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local review page at http://localhost:8080 (only reachable from this computer).
 * You see ranked jobs with a ready draft; one click approves, opens the apply page and copies the draft.
 */
final class Dashboard {
    private final Settings settings;
    private final Profile profile;
    private final Store store;
    private final Pipeline pipeline;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int port;

    Dashboard(Settings settings, Profile profile, Store store, Pipeline pipeline) {
        this.settings = settings;
        this.profile = profile;
        this.store = store;
        this.pipeline = pipeline;
    }

    HttpServer start(int port) throws IOException {
        // bind to the loopback address only: nobody else on the network can reach it
        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.port = srv.getAddress().getPort();
        srv.createContext("/", this::handle);
        srv.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "dashboard");
            t.setDaemon(true);                 // never keep the program alive just for the web server
            return t;
        }));
        srv.start();
        return srv;
    }

    /** Starts a background run unless one is already going. */
    boolean triggerRun() {
        if (!running.compareAndSet(false, true)) return false;
        Thread t = new Thread(() -> {
            try {
                pipeline.run();
            } finally {
                running.set(false);
            }
        }, "job-run");
        t.setDaemon(true);
        t.start();
        return true;
    }

    boolean isRunning() { return running.get(); }

    // ---------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!hostOk(ex)) { send(ex, 403, "text/plain", "Forbidden"); return; }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            // browsers cannot add this header to a cross-site request, so it blocks other web pages from driving the tool
            if (method.equals("POST") && !"1".equals(ex.getRequestHeaders().getFirst("X-JobHunter"))) {
                send(ex, 403, "text/plain", "Missing header");
                return;
            }
            if (method.equals("GET") && path.equals("/")) {
                send(ex, 200, "text/html; charset=utf-8", resource("/web/index.html"));
            } else if (method.equals("GET") && path.equals("/api/state")) {
                json(ex, state());
            } else if (method.equals("GET") && path.equals("/api/jobs")) {
                json(ex, jobs());
            } else if (method.equals("GET") && path.equals("/api/export.csv")) {
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"applications.csv\"");
                send(ex, 200, "text/csv; charset=utf-8", Csv.applications(store.all()));
            } else if (method.equals("GET") && path.equals("/api/summary")) {
                json(ex, Summary.weekly(store.all(), Instant.now(), 7,
                        j -> profile.cv == null ? List.of() : profile.cv.report(j, profile).missing()));
            } else if (method.equals("GET") && path.equals("/api/platforms")) {
                json(ex, platforms());
            } else if (method.equals("GET") && path.equals("/api/targets")) {
                json(ex, targets());
            } else if (method.equals("GET") && path.startsWith("/api/jobs/") && path.endsWith("/cv")) {
                tailoredCv(ex, path.substring("/api/jobs/".length(), path.length() - "/cv".length()));
            } else if (method.equals("GET") && path.startsWith("/api/jobs/") && path.endsWith("/cover")) {
                coverLetter(ex, path.substring("/api/jobs/".length(), path.length() - "/cover".length()));
            } else if (method.equals("POST") && path.equals("/api/manual")) {
                Map<String, Object> b = body(ex);
                Job j = new Job();
                j.source = "Pasted"; j.sourceType = "BOARD";
                j.title = nz(Json.str(b.get("title"))); j.company = nz(Json.str(b.get("company")));
                j.url = nz(Json.str(b.get("url"))); j.location = nz(Json.str(b.get("location")));
                j.description = nz(Json.str(b.get("description")));
                if (j.title.isBlank() || j.description.isBlank()) { send(ex, 400, "text/plain", "Title and description are needed"); return; }
                if (!j.url.isBlank() && !j.url.matches("(?i)^https?://.*")) { send(ex, 400, "text/plain", "Link must start with http"); return; }
                if (j.url.toLowerCase(Locale.ROOT).contains("linkedin.com")) j.source = "LinkedIn";
                else if (j.url.toLowerCase(Locale.ROOT).contains("indeed.")) j.source = "Indeed";
                Job added = pipeline.addManual(j);
                store.save();
                json(ex, view(added));
            } else if (method.equals("POST") && path.equals("/api/run")) {
                json(ex, Map.of("started", triggerRun()));
            } else if (method.equals("POST") && path.equals("/api/block")) {
                Map<String, Object> body = body(ex);
                int n = store.blockCompany(Json.str(body.get("company")));
                store.save();
                json(ex, Map.of("hidden", n));
            } else if (method.equals("POST") && path.startsWith("/api/platforms/") && path.endsWith("/toggle")) {
                String id = path.substring("/api/platforms/".length(), path.length() - "/toggle".length());
                boolean now = store.toggleSignedUp(id);
                store.save();
                json(ex, Map.of("signedUp", now));
            } else if (method.equals("POST") && path.startsWith("/api/jobs/")) {
                jobAction(ex, path);
            } else {
                send(ex, 404, "text/plain", "Not found");
            }
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", String.valueOf(e.getMessage()));
            send(ex, 500, "application/json", Json.write(err));
        }
    }

    private void jobAction(HttpExchange ex, String path) throws Exception {
        String[] parts = path.split("/");           // "", "api", "jobs", id, action
        if (parts.length != 5) { send(ex, 404, "text/plain", "Not found"); return; }
        String id = parts[3], action = parts[4];
        Job j = store.get(id);
        if (j == null) { send(ex, 404, "text/plain", "No such job"); return; }
        Map<String, Object> body = body(ex);
        switch (action) {
            case "status" -> {
                String st = Json.str(body.get("status"));
                if (!Set.of(Job.NEW, Job.APPROVED, Job.APPLIED, Job.REPLIED, Job.REJECTED).contains(st)) {
                    send(ex, 400, "text/plain", "Bad status");
                    return;
                }
                store.setStatus(id, st);
            }
            case "followed" -> store.markFollowedUp(id);
            case "stopreminder" -> store.stopReminders(id);
            case "draft" -> store.setDraft(id, Json.str(body.get("draft")) == null ? "" : Json.str(body.get("draft")));
            case "redraft" -> store.setDraft(id, pipeline.redraft(j, Json.bool(body.get("claude"), false)));
            default -> { send(ex, 404, "text/plain", "Unknown action"); return; }
        }
        store.save();
        json(ex, view(store.get(id)));
    }

    // ---------------------------------------------------------------------

    private Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", profile.name);
        m.put("threshold", profile.scoreThreshold);
        m.put("running", running.get());
        m.put("lastRun", store.lastRun());
        m.put("stats", store.stats());
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("brave", settings.braveKey() != null);
        keys.put("serper", settings.serperKey() != null);
        keys.put("claude", settings.anthropicKey() != null);
        keys.put("serpapi", settings.serpapiKey() != null);
        m.put("keys", keys);
        return m;
    }

    private List<Object> jobs() {
        List<Job> all = store.all();
        all.sort((a, b) -> b.score != a.score ? b.score - a.score : b.foundAt.compareTo(a.foundAt));
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < Math.min(all.size(), 1500); i++) out.add(view(all.get(i)));
        return out;
    }

    /** A job as the page sees it, plus how well your CV covers its keywords. */
    private Map<String, Object> view(Job j) {
        Map<String, Object> m = j.view();
        if (profile.cv != null) m.put("cv", profile.cv.report(j, profile).toMap());
        if (Job.APPLIED.equals(j.status)) {
            Instant now = Instant.now();
            m.put("followDue", FollowUp.isDue(j, now, FollowUp.DAYS));
            m.put("daysSince", FollowUp.daysSince(j, now));
            m.put("followDraft", FollowUp.draft(j, profile));
        }
        return m;
    }

    private void tailoredCv(HttpExchange ex, String id) throws IOException {
        Job j = store.get(id);
        if (j == null) { send(ex, 404, "text/plain", "No such job"); return; }
        if (profile.cv == null) { send(ex, 404, "text/plain", "No config/cv.json yet: copy config/cv.example.json to config/cv.json and fill it in."); return; }
        send(ex, 200, "text/html; charset=utf-8", profile.cv.tailor(j, profile, pipeline.scoreOf(j).topGroups()));
    }

    /** A small page with the cover letter in an editable box and a Copy button. Nothing is sent anywhere. */
    private void coverLetter(HttpExchange ex, String id) throws IOException {
        Job j = store.get(id);
        if (j == null) { send(ex, 404, "text/plain", "No such job"); return; }
        if (profile.cv == null) { send(ex, 404, "text/plain", "No config/cv.json yet: copy config/cv.example.json to config/cv.json and fill it in."); return; }
        String text = profile.cv.coverLetter(j, profile, pipeline.scoreOf(j).topGroups());
        String page = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Cover letter - " + Cv.esc(j.title) + "</title>"
                + "<style>body{font:15px system-ui,sans-serif;max-width:760px;margin:24px auto;padding:0 16px}textarea{width:100%;height:480px;font:inherit;padding:10px}"
                + "button{padding:8px 14px;margin:8px 8px 0 0;cursor:pointer}</style></head><body>"
                + "<h3>Cover letter for " + Cv.esc(j.title) + (j.company.isBlank() ? "" : " at " + Cv.esc(j.company)) + "</h3>"
                + "<p>Built only from your CV data. Read it, edit it, then send it yourself.</p>"
                + "<textarea id=\"t\">" + Cv.esc(text) + "</textarea><br>"
                + "<button onclick=\"navigator.clipboard.writeText(document.getElementById('t').value)\">Copy</button>"
                + "</body></html>";
        send(ex, 200, "text/html; charset=utf-8", page);
    }

    private List<Object> targets() {
        List<Object> out = new ArrayList<>();
        for (Profile.Target t : profile.targets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", t.title()); m.put("fit", t.fit()); m.put("group", t.group());
            m.put("keywords", t.keywords());
            m.put("links", Sources.boardLinks(t));
            out.add(m);
        }
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private List<Object> platforms() throws IOException {
        Set<String> signed = store.signedUp();
        List<Object> out = new ArrayList<>();
        for (Object o : Json.arr(Json.parse(resource("/platforms.json")))) {
            Map<String, Object> m = new LinkedHashMap<>(Json.obj(o));
            m.put("signedUp", signed.contains(Json.str(m.get("id"))));
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------------

    private boolean hostOk(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) return false;
        return host.equals("localhost:" + port) || host.equals("127.0.0.1:" + port);   // blocks DNS-rebinding tricks
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(1_000_000);
            String s = new String(bytes, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? new LinkedHashMap<>() : Json.obj(Json.parse(s));
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = Dashboard.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void json(HttpExchange ex, Object o) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", Json.write(o));
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}

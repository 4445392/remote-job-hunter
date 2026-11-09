package jobhunter;

import com.sun.net.httpserver.HttpServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Command line entry point. Run with no arguments to see the commands. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            usage();
            return;
        }
        String cmd = args[0];
        Map<String, String> opts = options(args);
        Path home = Path.of(opts.getOrDefault("home", ".")).toAbsolutePath().normalize();
        Settings settings = Settings.fromEnv(home);
        if (!Files.exists(settings.profileFile())) {
            System.err.println("Cannot find " + settings.profileFile() + ".\nRun this from the project folder (or pass --home <folder>). First time? Copy config/profile.example.json to config/profile.json and fill in your details.");
            System.exit(1);
        }
        Profile profile = Profile.load(settings.profileFile());
        Store store = Store.load(settings.stateFile());
        Http http = new Http();
        Pipeline pipeline = new Pipeline(settings, profile, store, http);

        switch (cmd) {
            case "run" -> {
                Map<String, Object> sum = pipeline.run();
                System.out.println(Json.pretty(sum));
                System.out.println("\nNext: java -jar jobhunter.jar serve   (then open http://localhost:8080)");
            }
            case "serve" -> {
                int port = Integer.parseInt(opts.getOrDefault("port", "8080"));
                Dashboard d = new Dashboard(settings, profile, store, pipeline);
                HttpServer srv = d.start(port);
                System.out.println("Dashboard: http://localhost:" + port + "   (Ctrl+C to stop)");
                Thread.currentThread().join();
                srv.stop(0);
            }
            case "daemon" -> {
                int port = Integer.parseInt(opts.getOrDefault("port", "8080"));
                double hours = Double.parseDouble(opts.getOrDefault("every-hours", "3"));
                Dashboard d = new Dashboard(settings, profile, store, pipeline);
                d.start(port);
                ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
                ses.scheduleWithFixedDelay(() -> {
                    try {
                        if (d.triggerRun()) System.out.println("[" + java.time.LocalTime.now().withNano(0) + "] search started");
                    } catch (Exception e) {
                        System.err.println("Run failed: " + e.getMessage());
                    }
                }, 0, Math.max(1, (long) (hours * 60)), TimeUnit.MINUTES);
                System.out.println("Searching every " + hours + " hours. Dashboard: http://localhost:" + port + "   (Ctrl+C to stop)");
                Thread.currentThread().join();
            }
            case "gigs" -> {
                GigPipeline gp = new GigPipeline(settings, profile, store, http);
                Map<String, Object> sum = gp.run();
                System.out.println(Json.pretty(sum));
                List<Gig> best = new ArrayList<>();
                for (Gig g : store.allGigs()) if (!g.status.equals(Gig.REJECTED)) best.add(g);
                best.sort((a, b) -> b.rank() - a.rank());
                System.out.println("\nBest simple gigs (fit, risk, South Africa):");
                for (Gig g : best.subList(0, Math.min(Integer.parseInt(opts.getOrDefault("n", "15")), best.size())))
                    System.out.printf("  fit %3d  risk %2d/10  SA:%-9s %s  %s%n", g.fit, g.risk, g.eligibility, g.name, g.url);
                System.out.println("\nReview them in the dashboard: java -jar jobhunter.jar serve   (Simple Gigs tab)");
            }
            case "top" -> top(store, Integer.parseInt(opts.getOrDefault("n", "15")));
            case "doctor" -> doctor(settings, profile, http);
            default -> {
                System.err.println("Unknown command: " + cmd);
                usage();
                System.exit(2);
            }
        }
    }

    private static void top(Store store, int n) {
        List<Job> all = store.all();
        all.removeIf(j -> !Job.NEW.equals(j.status) && !Job.APPROVED.equals(j.status));
        all.sort((a, b) -> b.score - a.score);
        if (all.isEmpty()) { System.out.println("Nothing in the queue yet. Run: java -jar jobhunter.jar run"); return; }
        for (int i = 0; i < Math.min(n, all.size()); i++) {
            Job j = all.get(i);
            System.out.printf("%3d  %-12s %-45s %s%n", j.score, j.workType, Text.truncate(j.title, 45), j.url);
        }
    }

    private static void doctor(Settings s, Profile p, Http http) {
        System.out.println("Profile: " + p.name + " | threshold " + p.scoreThreshold + " | " + p.groups.size() + " skill groups | " + p.searchQueries.size() + " search queries");
        System.out.println("CV file: " + (Files.exists(s.cvFile(p)) ? "found (" + p.cvFile + ")" : "MISSING: put your CV at " + p.cvFile));
        System.out.println("Brave key:  " + (s.braveKey() != null ? "set" : "not set (optional: BRAVE_API_KEY)"));
        System.out.println("Serper key: " + (s.serperKey() != null ? "set" : "not set (optional: SERPER_API_KEY)"));
        System.out.println("SerpApi key: " + (s.serpapiKey() != null ? "set (Google Jobs on)" : "not set (optional: SERPAPI_KEY, turns on Google Jobs)"));
        System.out.println("Claude key: " + (s.anthropicKey() != null ? "set" : "not set (optional: ANTHROPIC_API_KEY)"));
        System.out.println();
        String[][] checks = {
                {"RemoteOK", "https://remoteok.com/api"},
                {"Jobicy", "https://jobicy.com/api/v2/remote-jobs?count=1"},
                {"Remotive", "https://remotive.com/api/remote-jobs?limit=1"},
                {"Himalayas", "https://himalayas.app/jobs/api?limit=1"},
                {"WeWorkRemotely", "https://weworkremotely.com/remote-jobs.rss"},
                {"Working Nomads", "https://www.workingnomads.com/api/exposed_jobs/"}};
        for (String[] c : checks) {
            try {
                String body = http.get(c[1], Map.of());
                System.out.printf("%-16s OK (%d bytes)%n", c[0], body.length());
            } catch (Exception e) {
                System.out.printf("%-16s FAILED: %s%n", c[0], e.getMessage());
            }
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                String v = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
                m.put(k, v);
            }
        }
        return m;
    }

    private static void usage() {
        System.out.println("""
                Remote Job Hunter

                  java -jar jobhunter.jar doctor              check your setup and which job sites are reachable
                  java -jar jobhunter.jar run                 search now, filter, score, draft applications
                  java -jar jobhunter.jar serve [--port N]    open the review dashboard (default port 8080)
                  java -jar jobhunter.jar daemon [--every-hours 3] [--port N]
                                                              dashboard plus automatic searching, keep this running
                  java -jar jobhunter.jar top [--n 15]        print the best jobs in the queue
                  java -jar jobhunter.jar gigs [--n 15]       search for simple online gigs now (uses about 55 search credits)

                Options: --home <folder> if you run it from somewhere other than the project folder.
                Optional keys (environment variables): BRAVE_API_KEY, SERPER_API_KEY, SERPAPI_KEY, ANTHROPIC_API_KEY
                """);
    }
}

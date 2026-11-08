package jobhunter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Who you are and what you want, loaded from config/profile.json. Edit that file to change behaviour. */
final class Profile {
    /** A family of related skills, e.g. "AI work" with its keywords and a proof line for drafts. */
    record Group(String name, String label, double weight, List<String> keywords, String opener, String proof) {}
    /** A job title you are qualified for: how to search for it, how to spot it, and the words its ads ask for. */
    record Target(String title, String group, String fit, String query, List<String> match, List<String> keywords) {}

    String name = "", email = "", phone = "", location = "", timezone = "", github = "", cvFile = "cv/CV.pdf";
    int hoursPerWeek = 30, scoreThreshold = 40, maxAgeDays = 45, maxSearchQueries = 12;
    Map<String, Double> minHourlyUsd = new LinkedHashMap<>();
    Map<String, Double> fx = new LinkedHashMap<>();
    List<Group> groups = new ArrayList<>();
    List<String> blockedEmployers = new ArrayList<>();
    List<String> searchQueries = new ArrayList<>();
    List<Target> targets = new ArrayList<>();
    int maxBoardQueries = 10;
    Cv cv;                                         // config/cv.json, if present (used for tailored CVs)
    boolean upfrontFees = true, fixedHours = true, mandatoryCalls = true, geoLocked = true, unpaidWork = true;

    static Profile load(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Profile p = fromMap(Json.obj(Json.parse(text)));
        // the CV sits beside the profile: cv.json (yours) or cv.example.json (for the example profile)
        String cvName = file.getFileName().toString().contains("example") ? "cv.example.json" : "cv.json";
        Path cvPath = file.resolveSibling(cvName);
        if (Files.exists(cvPath)) p.cv = Cv.load(cvPath);
        return p;
    }

    static Profile fromMap(Map<String, Object> m) {
        Profile p = new Profile();
        p.name = d(m, "name", ""); p.email = d(m, "email", ""); p.phone = d(m, "phone", "");
        p.location = d(m, "location", ""); p.timezone = d(m, "timezone", ""); p.github = d(m, "github", "");
        p.cvFile = d(m, "cvFile", "cv/CV.pdf");
        p.hoursPerWeek = i(m.get("hoursPerWeek"), 30);
        p.scoreThreshold = i(m.get("scoreThreshold"), 40);
        p.maxAgeDays = i(m.get("maxAgeDays"), 45);
        p.maxSearchQueries = i(m.get("maxSearchQueries"), 12);
        for (Map.Entry<String, Object> e : Json.obj(m.get("minHourlyUsd")).entrySet()) {
            Double v = Json.num(e.getValue());
            if (v != null) p.minHourlyUsd.put(e.getKey(), v);
        }
        p.fx.put("USD", 1.0);
        for (Map.Entry<String, Object> e : Json.obj(m.get("fx")).entrySet()) {
            Double v = Json.num(e.getValue());
            if (v != null) p.fx.put(e.getKey().toUpperCase(Locale.ROOT), v);
        }
        for (Object o : Json.arr(m.get("skillGroups"))) {
            Map<String, Object> g = Json.obj(o);
            String n = d(g, "name", "");
            if (n.isEmpty()) continue;
            Double w = Json.num(g.get("weight"));
            List<String> kws = new ArrayList<>();
            for (String k : Json.strings(g.get("keywords"))) kws.add(k.toLowerCase(Locale.ROOT).trim());
            p.groups.add(new Group(n, d(g, "label", n), w == null ? 20 : w, kws, d(g, "opener", ""), d(g, "proof", "")));
        }
        p.blockedEmployers = Json.strings(m.get("blockedEmployers"));
        p.searchQueries = Json.strings(m.get("searchQueries"));
        p.maxBoardQueries = i(m.get("maxBoardQueries"), 10);
        for (Object o : Json.arr(m.get("targetTitles"))) {
            Map<String, Object> t = Json.obj(o);
            String title = d(t, "title", "");
            if (title.isEmpty()) continue;
            List<String> match = new ArrayList<>();
            for (String x : Json.strings(t.get("match"))) match.add(Text.norm(x));
            p.targets.add(new Target(title, d(t, "group", ""), d(t, "fit", "B"), d(t, "query", title), match, Json.strings(t.get("keywords"))));
        }
        Map<String, Object> db = Json.obj(m.get("dealBreakers"));
        p.upfrontFees = Json.bool(db.get("upfrontFees"), true);
        p.fixedHours = Json.bool(db.get("fixedHours"), true);
        p.mandatoryCalls = Json.bool(db.get("mandatoryCalls"), true);
        p.geoLocked = Json.bool(db.get("geoLocked"), true);
        p.unpaidWork = Json.bool(db.get("unpaidWork"), true);
        return p;
    }

    double minFor(String workType) {
        return minHourlyUsd.getOrDefault(workType, minHourlyUsd.getOrDefault("JOB", 0.0));
    }

    Group group(String name) {
        for (Group g : groups) if (g.name().equals(name)) return g;
        return null;
    }

    private static String d(Map<String, Object> m, String k, String dflt) {
        String v = Json.str(m.get(k));
        return v == null ? dflt : v;
    }

    private static int i(Object o, int dflt) {
        Double v = Json.num(o);
        return v == null ? dflt : v.intValue();
    }
}

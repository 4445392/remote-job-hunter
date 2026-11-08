package jobhunter;

import java.util.*;

/** One job (or gig, or platform listing) moving through the pipeline. */
final class Job {
    static final String NEW = "NEW", APPROVED = "APPROVED", APPLIED = "APPLIED", REPLIED = "REPLIED", REJECTED = "REJECTED", BLOCKED = "BLOCKED";

    String id = "", source = "", sourceType = "BOARD"; // sourceType: BOARD or SEARCH
    String title = "", company = "", url = "", applyUrl = "", location = "", type = "";
    String description = "", postedAt = "", salaryText = "", email = "";
    List<String> tags = new ArrayList<>();
    Double minHourlyUsd, maxHourlyUsd;

    // filled in by the pipeline
    String workType = "JOB";                    // AI_TRAINING, TUTORING, MICROTASK, FREELANCE, JOB
    int score;
    List<String> why = new ArrayList<>();       // reasons for the score
    List<String> flags = new ArrayList<>();     // things to double-check before applying
    String status = NEW;
    String draft = "", foundAt = "", statusAt = "", note = "";
    String followedUpAt = "";                   // last time you sent a follow-up (restarts the 7-day clock)
    boolean noReminder;                         // "stop reminding me" for this job

    /** Cleans fields and computes the id. Call after setting the raw fields. */
    Job finish() {
        title = Text.unescape(nz(title)).trim();
        company = Text.unescape(nz(company)).trim();
        location = Text.unescape(nz(location)).trim();
        type = nz(type).trim();
        url = nz(url).trim();
        applyUrl = nz(applyUrl).isBlank() ? url : applyUrl.trim();
        description = Text.truncate(nz(description), 6000);
        postedAt = nz(postedAt);
        salaryText = nz(salaryText);
        if (nz(email).isBlank()) {
            String e = Text.firstEmail(description);
            email = e == null ? "" : e;
        }
        id = Text.sha(normalizeUrl(url.isBlank() ? title + "|" + company : url));
        return this;
    }

    /** Same job posted on two boards: same title and company. */
    String dupKey() {
        return "d" + Text.sha(Text.norm(title) + "|" + Text.norm(company));
    }

    static String normalizeUrl(String u) {
        String s = nz(u).trim();
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int q = s.indexOf('?');
        if (q >= 0) {
            StringBuilder kept = new StringBuilder();
            for (String part : s.substring(q + 1).split("&")) {
                String lp = part.toLowerCase(Locale.ROOT);
                if (lp.startsWith("utm_") || lp.startsWith("ref=") || lp.startsWith("source=") || lp.isEmpty()) continue;
                if (kept.length() > 0) kept.append('&');
                kept.append(part);
            }
            String path = s.substring(0, q);
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            s = path + (kept.length() > 0 ? "?" + kept : "");
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }

    String payLabel() {
        if (!salaryText.isBlank()) return salaryText;
        if (minHourlyUsd == null && maxHourlyUsd == null) return "";
        double lo = minHourlyUsd != null ? minHourlyUsd : maxHourlyUsd;
        double hi = maxHourlyUsd != null ? maxHourlyUsd : lo;
        if (Math.abs(hi - lo) < 0.5) return String.format(Locale.ROOT, "about $%.0f/hr", hi);
        return String.format(Locale.ROOT, "about $%.0f-%.0f/hr", lo, hi);
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("source", source); m.put("sourceType", sourceType);
        m.put("title", title); m.put("company", company); m.put("url", url); m.put("applyUrl", applyUrl);
        m.put("location", location); m.put("type", type); m.put("description", description);
        m.put("postedAt", postedAt); m.put("salaryText", salaryText); m.put("email", email);
        m.put("tags", tags); m.put("minHourlyUsd", minHourlyUsd); m.put("maxHourlyUsd", maxHourlyUsd);
        m.put("workType", workType); m.put("score", score); m.put("why", why); m.put("flags", flags);
        m.put("status", status); m.put("draft", draft); m.put("foundAt", foundAt);
        m.put("statusAt", statusAt); m.put("note", note);
        m.put("followedUpAt", followedUpAt); m.put("noReminder", noReminder);
        return m;
    }

    /** What the dashboard sees. */
    Map<String, Object> view() {
        Map<String, Object> m = toMap();
        m.put("description", Text.truncate(description, 1800));
        m.put("pay", payLabel());
        return m;
    }

    static Job fromMap(Map<String, Object> m) {
        Job j = new Job();
        j.id = s(m, "id"); j.source = s(m, "source"); j.sourceType = s(m, "sourceType");
        j.title = s(m, "title"); j.company = s(m, "company"); j.url = s(m, "url"); j.applyUrl = s(m, "applyUrl");
        j.location = s(m, "location"); j.type = s(m, "type"); j.description = s(m, "description");
        j.postedAt = s(m, "postedAt"); j.salaryText = s(m, "salaryText"); j.email = s(m, "email");
        j.tags = Json.strings(m.get("tags"));
        j.minHourlyUsd = Json.num(m.get("minHourlyUsd")); j.maxHourlyUsd = Json.num(m.get("maxHourlyUsd"));
        j.workType = s(m, "workType"); if (j.workType.isEmpty()) j.workType = "JOB";
        Double sc = Json.num(m.get("score")); j.score = sc == null ? 0 : sc.intValue();
        j.why = Json.strings(m.get("why")); j.flags = Json.strings(m.get("flags"));
        j.status = s(m, "status"); if (j.status.isEmpty()) j.status = NEW;
        if (j.sourceType.isEmpty()) j.sourceType = "BOARD";
        j.draft = s(m, "draft"); j.foundAt = s(m, "foundAt"); j.statusAt = s(m, "statusAt"); j.note = s(m, "note");
        j.followedUpAt = s(m, "followedUpAt"); j.noReminder = Json.bool(m.get("noReminder"), false);
        return j;
    }

    private static String s(Map<String, Object> m, String k) { return nz(Json.str(m.get(k))); }
    static String nz(String s) { return s == null ? "" : s; }
}

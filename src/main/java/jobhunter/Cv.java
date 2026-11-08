package jobhunter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your CV as data (config/cv.json), and a tailor that re-orders it for one job.
 *
 * The big idea: a recruiter spends about 7 seconds on a CV, and an ATS (applicant tracking system)
 * scans for the job's own words. So for each job we
 *   1. pick the summary and headline that match the job's main skill area,
 *   2. move the most relevant bullets, projects and skills to the top,
 *   3. bold the job's keywords where your CV already has them,
 *   4. list the job's keywords your CV does NOT have, so you can learn them, never fake them.
 * Nothing is invented: every line comes from cv.json, which only you write.
 */
final class Cv {
    record Bullet(String text, List<String> tags) {}
    record Role(String title, String org, String dates, List<Bullet> bullets) {}
    record Item(String name, String link, String text, List<String> tags) {}
    record SkillLine(String label, List<String> items, List<String> tags) {}
    record Edu(String heading, String dates, List<String> details) {}

    /** How well this CV covers the words a job asks for. */
    record Report(List<String> asked, List<String> matched, List<String> missing) {
        int pct() { return asked.isEmpty() ? 0 : (int) Math.round(100.0 * matched.size() / asked.size()); }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pct", pct());
            m.put("matched", matched);
            m.put("missing", missing);
            return m;
        }
    }

    String linkedin = "", availability = "", languages = "";
    Map<String, String> headlines = new LinkedHashMap<>(), summaries = new LinkedHashMap<>();
    List<Role> experience = new ArrayList<>();
    List<Item> projects = new ArrayList<>();
    List<SkillLine> skills = new ArrayList<>();
    List<Edu> education = new ArrayList<>();
    int maxProjects = 4;

    /** A few common ways job ads say the same thing as your CV. */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("unit testing", List.of("unit test", "automated testing", "regression test")),
            Map.entry("automated testing", List.of("unit test", "playwright", "vitest")),
            Map.entry("rest api", List.of("rest apis")),
            Map.entry("data visualisation", List.of("data visualization", "dashboards", "recharts")),
            Map.entry("data visualization", List.of("data visualisation", "dashboards")),
            Map.entry("ci/cd", List.of("github actions")),
            Map.entry("llm", List.of("claude", "chatgpt")),
            Map.entry("ai tools", List.of("claude", "chatgpt")),
            Map.entry("oop", List.of("object-oriented", "java")),
            Map.entry("troubleshooting", List.of("root cause", "debugging")),
            Map.entry("point of sale", List.of("point-of-sale", "pos")),
            Map.entry("reconciliations", List.of("reconcil")),
            Map.entry("evaluate ai-generated code", List.of("ai-generated code")),
            Map.entry("technical writing", List.of("write-up", "documentation")),
            Map.entry("documentation", List.of("write-up")),
            Map.entry("spreadsheets", List.of("excel")),
            Map.entry("online tutoring", List.of("tutor")));

    private static final Pattern ROLE_WORDS = Pattern.compile(
            "\\b(analyst|developer|engineer|trainer|training|tutor|teacher|assistant|writer|designer|specialist|intern|annotator|rater)\\b");

    // ------------------------------------------------------------------ loading

    static Cv load(Path file) throws IOException {
        return fromMap(Json.obj(Json.parse(Files.readString(file, StandardCharsets.UTF_8))));
    }

    static Cv fromMap(Map<String, Object> m) {
        Cv cv = new Cv();
        cv.linkedin = s(m.get("linkedin"));
        cv.availability = s(m.get("availability"));
        cv.languages = s(m.get("languages"));
        Double mp = Json.num(m.get("maxProjects"));
        if (mp != null) cv.maxProjects = mp.intValue();
        for (Map.Entry<String, Object> e : Json.obj(m.get("headlines")).entrySet()) cv.headlines.put(e.getKey(), s(e.getValue()));
        for (Map.Entry<String, Object> e : Json.obj(m.get("summaries")).entrySet()) cv.summaries.put(e.getKey(), s(e.getValue()));
        for (Object o : Json.arr(m.get("experience"))) {
            Map<String, Object> r = Json.obj(o);
            List<Bullet> bs = new ArrayList<>();
            for (Object b : Json.arr(r.get("bullets"))) {
                Map<String, Object> bm = Json.obj(b);
                bs.add(new Bullet(s(bm.get("text")), Json.strings(bm.get("tags"))));
            }
            cv.experience.add(new Role(s(r.get("title")), s(r.get("org")), s(r.get("dates")), bs));
        }
        for (Object o : Json.arr(m.get("projects"))) {
            Map<String, Object> p = Json.obj(o);
            cv.projects.add(new Item(s(p.get("name")), s(p.get("link")), s(p.get("text")), Json.strings(p.get("tags"))));
        }
        for (Object o : Json.arr(m.get("skills"))) {
            Map<String, Object> k = Json.obj(o);
            cv.skills.add(new SkillLine(s(k.get("label")), Json.strings(k.get("items")), Json.strings(k.get("tags"))));
        }
        for (Object o : Json.arr(m.get("education"))) {
            Map<String, Object> e = Json.obj(o);
            cv.education.add(new Edu(s(e.get("heading")), s(e.get("dates")), Json.strings(e.get("details"))));
        }
        return cv;
    }

    // ------------------------------------------------------------------ keywords

    /** All CV text in one lower-case string, used to check which job keywords you already cover. */
    String allText() {
        StringBuilder b = new StringBuilder();
        summaries.values().forEach(x -> b.append(x).append(' '));
        headlines.values().forEach(x -> b.append(x).append(' '));
        for (Role r : experience) { b.append(r.title).append(' '); r.bullets.forEach(x -> b.append(x.text).append(' ')); }
        for (Item p : projects) b.append(p.name).append(' ').append(p.text).append(' ');
        for (SkillLine k : skills) b.append(String.join(" ", k.items)).append(' ');
        for (Edu e : education) b.append(e.heading).append(' ').append(String.join(" ", e.details)).append(' ');
        return Text.norm(b.toString());
    }

    /** Every keyword the tool knows about: target-title keywords, skill-group keywords and your skill items. */
    static List<String> vocabulary(Profile p, Cv cv) {
        LinkedHashSet<String> v = new LinkedHashSet<>();
        for (Profile.Target t : p.targets) for (String k : t.keywords()) v.add(Text.norm(k));
        for (Profile.Group g : p.groups) v.addAll(g.keywords());
        if (cv != null) for (SkillLine k : cv.skills) for (String it : k.items) v.add(Text.norm(it));
        v.removeIf(x -> x.length() < 2 || ROLE_WORDS.matcher(x).find());   // "data analyst" is a job title, not a skill to list

        return new ArrayList<>(v);
    }

    static Pattern termPattern(String term) {
        // word boundaries that also work for terms like "c++" or "ci/cd", plus simple plurals
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(Text.norm(term)) + "(?:s|es)?(?![a-z0-9])");
    }

    static boolean has(String normText, String term) {
        if (termPattern(term).matcher(normText).find()) return true;
        for (String a : ALIASES.getOrDefault(Text.norm(term), List.of())) if (normText.contains(a)) return true;
        return false;
    }

    /** Which of the job's words your CV covers, and which it does not. */
    Report report(Job j, Profile p) {
        String job = Text.norm(j.title + " " + String.join(" ", j.tags) + " " + j.description);
        String mine = allText();
        List<String> asked = new ArrayList<>(), matched = new ArrayList<>(), missing = new ArrayList<>();
        for (String term : vocabulary(p, this)) {
            if (!termPattern(term).matcher(job).find()) continue;
            if (asked.stream().anyMatch(a -> a.contains(term) || term.contains(a))) continue;   // "sql" vs "mysql": count once
            asked.add(term);
            (has(mine, term) ? matched : missing).add(term);
        }
        return new Report(asked, matched, missing);
    }

    // ------------------------------------------------------------------ tailoring

    /** A complete, printable HTML CV for this job. Open it and press Ctrl+P to save as PDF. */
    String tailor(Job j, Profile p, List<String> topGroups) {
        Report rep = report(j, p);
        List<String> terms = new ArrayList<>(rep.matched());
        terms.sort((a, b) -> b.length() - a.length());          // bold "spring boot" before "boot"
        String top = topGroups.isEmpty() ? "default" : topGroups.get(0);

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>")
         .append(esc(p.name)).append(" - CV - ").append(esc(j.title)).append("</title><style>").append(CSS).append("</style></head><body>");

        // screen-only helper bar: never printed
        h.append("<div class=\"tool no-print\"><b>Tailored for:</b> ").append(esc(j.title))
         .append(j.company.isBlank() ? "" : " at " + esc(j.company))
         .append(" &middot; <b>Keyword match ").append(rep.pct()).append("%</b> (").append(rep.matched().size()).append(" of ").append(rep.asked().size()).append(")");
        if (!rep.missing().isEmpty()) {
            h.append("<br>Asked for but not on your CV: <span class=\"miss\">").append(esc(String.join(", ", rep.missing())))
             .append("</span>. Only add one if it is true for you (edit config/cv.json).");
        }
        if (allText().contains("__") || placeholders()) {
            h.append("<div class=\"warn\">Your CV still has blanks (\"__\"). Fill them in config/cv.json before sending.</div>");
        }
        h.append("<br><button onclick=\"print()\">Save as PDF (Ctrl+P)</button></div>");

        h.append("<main>");
        h.append("<h1>").append(esc(p.name)).append("</h1>");
        List<String> contact = new ArrayList<>();
        if (!p.location.isBlank()) contact.add(esc(p.location) + (p.timezone.isBlank() ? "" : " (" + esc(p.timezone) + ")"));
        if (!p.phone.isBlank()) contact.add(esc(p.phone));
        if (!p.email.isBlank()) contact.add(esc(p.email));
        if (!p.github.isBlank()) contact.add(link(p.github, p.github.replaceFirst("^https?://", "")));
        if (!linkedin.isBlank()) contact.add(link(linkedin, linkedin.replaceFirst("^https?://(www\\.)?", "")));
        h.append("<p class=\"contact\">").append(String.join(" &middot; ", contact)).append("</p>");
        if (!availability.isBlank()) h.append("<p class=\"contact\">").append(esc(availability)).append("</p>");
        String headline = headlines.getOrDefault(top, headlines.getOrDefault("default", ""));
        if (!headline.isBlank()) h.append("<p class=\"headline\">").append(esc(headline)).append("</p>");

        String summary = summaries.getOrDefault(top, summaries.getOrDefault("default", ""));
        if (!summary.isBlank()) h.append("<h2>Summary</h2><p>").append(bold(summary, terms)).append("</p>");

        // skills go high up for technical jobs: an ATS and a recruiter both look there first
        h.append("<h2>Skills</h2><ul>");
        List<SkillLine> lines = new ArrayList<>(skills);
        lines.sort(Comparator.comparingDouble((SkillLine k) -> -(tagScore(k.tags, topGroups) * 10 + countTerms(String.join(" ", k.items), terms))));
        for (SkillLine k : lines) {
            List<String> items = new ArrayList<>(k.items);
            items.sort(Comparator.comparingInt((String it) -> terms.stream().anyMatch(t -> has(Text.norm(it), t)) ? 0 : 1));
            List<String> shown = new ArrayList<>();
            for (String it : items) shown.add(terms.stream().anyMatch(t -> has(Text.norm(it), t)) ? "<b>" + esc(it) + "</b>" : esc(it));
            h.append("<li><b>").append(esc(k.label)).append(":</b> ").append(String.join(", ", shown)).append("</li>");
        }
        h.append("</ul>");

        h.append("<h2>Experience</h2>");
        for (Role r : experience) {
            h.append("<div class=\"role\"><span><b>").append(esc(r.title)).append("</b>")
             .append(r.org.isBlank() ? "" : ", " + esc(r.org)).append("</span><span class=\"dates\">").append(esc(r.dates)).append("</span></div><ul>");
            List<Bullet> bs = new ArrayList<>(r.bullets);
            // stable sort: equally relevant bullets keep your own order
            bs.sort(Comparator.comparingDouble((Bullet b) -> -(tagScore(b.tags, topGroups) * 3 + countTerms(b.text, terms))));
            for (Bullet b : bs) h.append("<li>").append(bold(b.text, terms)).append("</li>");
            h.append("</ul>");
        }

        h.append("<h2>Projects</h2><ul>");
        List<Item> ps = new ArrayList<>(projects);
        ps.sort(Comparator.comparingDouble((Item it) -> -(tagScore(it.tags, topGroups) * 3 + countTerms(it.name + " " + it.text, terms))));
        for (Item it : ps.subList(0, Math.min(maxProjects, ps.size()))) {
            h.append("<li><b>").append(esc(it.name)).append("</b>");
            if (!it.link.isBlank()) h.append(" (").append(link(it.link, it.link.replaceFirst("^https?://", ""))).append(")");
            h.append(": ").append(bold(it.text, terms)).append("</li>");
        }
        h.append("</ul>");

        h.append("<h2>Education</h2>");
        for (Edu e : education) {
            h.append("<div class=\"role\"><span><b>").append(esc(e.heading)).append("</b></span><span class=\"dates\">").append(esc(e.dates)).append("</span></div><ul>");
            for (String d : e.details) h.append("<li>").append(bold(d, terms)).append("</li>");
            h.append("</ul>");
        }
        if (!languages.isBlank()) h.append("<h2>Languages</h2><p>").append(esc(languages)).append("</p>");
        h.append("</main></body></html>");
        return h.toString();
    }

    /**
     * A plain-text cover letter for this job. Every sentence is copied from config/cv.json (summary, best-matching
     * bullets and project, availability); only the greeting and the framing words are new. Gaps are never mentioned.
     */
    String coverLetter(Job j, Profile p, List<String> topGroups) {
        Report rep = report(j, p);
        List<String> terms = new ArrayList<>(rep.matched());
        String top = topGroups.isEmpty() ? "default" : topGroups.get(0);
        String role = j.title.isBlank() ? "this opportunity" : j.title;
        String company = "SEARCH".equals(j.sourceType) || j.company.isBlank() ? null : j.company;

        StringBuilder sb = new StringBuilder();
        sb.append("Subject: Application for ").append(role).append(" - ").append(p.name).append("\n\n");
        sb.append(company == null ? "Hello," : "Hello " + company + " team,").append("\n\n");
        sb.append("I am applying for ").append(j.title.isBlank() ? "this opportunity" : "the " + j.title + " position");
        if (company != null) sb.append(" at ").append(company);
        sb.append(".\n\n");

        String summary = summaries.getOrDefault(top, summaries.getOrDefault("default", ""));
        if (!summary.isBlank()) sb.append(summary).append("\n\n");

        // the two bullets (across all roles) that best fit this job, then the best-fitting project
        List<Bullet> all = new ArrayList<>();
        for (Role r : experience) all.addAll(r.bullets);
        all.sort(Comparator.comparingDouble((Bullet b) -> -(tagScore(b.tags, topGroups) * 3 + countTerms(b.text, terms))));
        if (!all.isEmpty()) {
            sb.append("Most relevant to this role:\n");
            for (Bullet b : all.subList(0, Math.min(2, all.size()))) sb.append("- ").append(b.text).append("\n");
            sb.append("\n");
        }
        List<Item> ps = new ArrayList<>(projects);
        ps.sort(Comparator.comparingDouble((Item it) -> -(tagScore(it.tags, topGroups) * 3 + countTerms(it.name + " " + it.text, terms))));
        if (!ps.isEmpty()) {
            Item it = ps.get(0);
            sb.append(it.name).append(": ").append(it.text);
            if (!it.link.isBlank()) sb.append(" ").append(it.link);
            sb.append("\n\n");
        }

        if (!rep.matched().isEmpty()) {
            List<String> m = rep.matched().subList(0, Math.min(4, rep.matched().size()));
            sb.append("Your listing mentions ").append(m.size() == 1 ? m.get(0)
                    : String.join(", ", m.subList(0, m.size() - 1)) + " and " + m.get(m.size() - 1)).append(", which are on my CV.\n\n");
        }
        if (!availability.isBlank()) sb.append("Availability: ").append(availability).append(".\n\n");
        sb.append("My CV is attached. Thank you for your time.\n\n");
        sb.append(p.name).append("\n").append(p.email);
        if (!p.phone.isBlank()) sb.append(" | ").append(p.phone);
        sb.append("\n");
        return sb.toString();
    }

    private boolean placeholders() {
        for (Role r : experience) if (r.dates.contains("__")) return true;
        return false;
    }

    /** 1 point for your strongest matching area, less for the next ones. */
    private static double tagScore(List<String> tags, List<String> topGroups) {
        double s = 0;
        double[] w = {1.0, 0.6, 0.3};
        for (int i = 0; i < topGroups.size() && i < w.length; i++) if (tags.contains(topGroups.get(i))) s = Math.max(s, w[i]);
        return s;
    }

    private static int countTerms(String text, List<String> terms) {
        String n = Text.norm(text);
        int c = 0;
        for (String t : terms) if (termPattern(t).matcher(n).find()) c++;
        return c;
    }

    /** Escape, then bold the job's keywords where they appear. */
    static String bold(String text, List<String> terms) {
        String out = esc(text);
        for (String t : terms) {
            Pattern p = Pattern.compile("(?i)(?<![A-Za-z0-9>])(" + Pattern.quote(esc(t)) + "(?:s|es)?)(?![A-Za-z0-9<])");
            Matcher m = p.matcher(out);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (m.find()) {
                // skip matches inside an earlier <b>...</b>
                String before = out.substring(0, m.start());
                if (before.lastIndexOf("<b>") > before.lastIndexOf("</b>")) continue;
                sb.append(out, last, m.start()).append("<b>").append(m.group(1)).append("</b>");
                last = m.end();
            }
            sb.append(out.substring(last));
            out = sb.toString();
        }
        return out;
    }

    private static String link(String url, String label) {
        if (!url.matches("(?i)^https?://.*")) return esc(label);
        return "<a href=\"" + esc(url) + "\">" + esc(label) + "</a>";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String s(Object o) {
        String v = Json.str(o);
        return v == null ? "" : v;
    }

    private static final String CSS = """
            @page { size: A4; margin: 14mm 15mm; }
            * { box-sizing: border-box; }
            body { margin: 0; background: #eef0f3; color: #1b2430; font: 10.5pt/1.45 "Segoe UI", Calibri, Arial, sans-serif; }
            main { max-width: 210mm; margin: 16px auto; background: #fff; padding: 14mm 15mm; box-shadow: 0 1px 4px rgba(0,0,0,.12); }
            h1 { font-size: 21pt; margin: 0; letter-spacing: .2px; }
            h2 { font-size: 11pt; text-transform: uppercase; letter-spacing: 1px; color: #1f4e8c; border-bottom: 1.5px solid #1f4e8c; padding-bottom: 2px; margin: 14px 0 6px; }
            p { margin: 4px 0; }
            ul { margin: 3px 0 8px; padding-left: 18px; }
            li { margin: 2px 0; }
            .contact { color: #4a5565; font-size: 9.5pt; margin: 2px 0; }
            .headline { font-weight: 600; margin-top: 6px; }
            .role { display: flex; justify-content: space-between; gap: 12px; margin-top: 6px; }
            .dates { color: #4a5565; white-space: nowrap; }
            .role, h2 { break-after: avoid; page-break-after: avoid; } li { break-inside: avoid; }
            a { color: #1f4e8c; text-decoration: none; }
            .tool { max-width: 210mm; margin: 12px auto 0; background: #fff8e6; border: 1px solid #f0c36d; border-radius: 8px; padding: 10px 14px; font-size: 10pt; }
            .tool button { margin-top: 8px; font: inherit; padding: 5px 12px; cursor: pointer; }
            .miss { color: #9a3412; }
            .warn { color: #b42318; font-weight: 600; margin-top: 4px; }
            @media print { body { background: #fff; } main { margin: 0; padding: 0; box-shadow: none; max-width: none; } .no-print { display: none; } }
            """;
}

package jobhunter;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** All the job sources, plus the pure parsing functions that turn each format into Job objects. */
final class Sources {
    private Sources() {}

    static List<Source> all() {
        return List.of(new RemoteOk(), new Jobicy(), new Remotive(), new Himalayas(), new WeWorkRemotely(), new WorkingNomads(),
                new Brave(), new Serper(), new BoardSearch(), new GoogleJobs());
    }

    // =====================================================================
    //  Job boards with free public feeds
    // =====================================================================

    static final class RemoteOk implements Source {
        public String name() { return "RemoteOK"; }
        public int minIntervalMinutes() { return 240; }
        public List<Job> fetch(Http http, Settings s, Profile p) throws Exception {
            return parseRemoteOk(http.get("https://remoteok.com/api", Map.of()), p.fx);
        }
    }

    static final class Jobicy implements Source {
        public String name() { return "Jobicy"; }
        public int minIntervalMinutes() { return 120; }   // Jobicy asks for roughly one call per hour
        public List<Job> fetch(Http http, Settings s, Profile p) throws Exception {
            return parseJobicy(http.get("https://jobicy.com/api/v2/remote-jobs?count=100", Map.of()), p.fx);
        }
    }

    static final class Remotive implements Source {
        public String name() { return "Remotive"; }
        public int minIntervalMinutes() { return 360; }   // Remotive asks for at most about 4 calls a day
        public List<Job> fetch(Http http, Settings s, Profile p) throws Exception {
            return parseRemotive(http.get("https://remotive.com/api/remote-jobs", Map.of()), p.fx);
        }
    }

    static final class Himalayas implements Source {
        public String name() { return "Himalayas"; }
        public int minIntervalMinutes() { return 720; }
        public List<Job> fetch(Http http, Settings s, Profile p) throws Exception {
            List<Job> out = new ArrayList<>();
            for (int off = 0; off < 100; off += 20) {
                List<Job> page = parseHimalayas(http.get("https://himalayas.app/jobs/api?limit=20&offset=" + off, Map.of()), p.fx);
                if (page.isEmpty()) break;
                out.addAll(page);
                Thread.sleep(1200);
            }
            return out;
        }
    }

    static final class WeWorkRemotely implements Source {
        static final List<String> FEEDS = List.of(
                "https://weworkremotely.com/remote-jobs.rss",
                "https://weworkremotely.com/categories/remote-programming-jobs.rss",
                "https://weworkremotely.com/categories/remote-customer-support-jobs.rss",
                "https://weworkremotely.com/categories/remote-management-and-finance-jobs.rss",
                "https://weworkremotely.com/categories/remote-design-jobs.rss",
                "https://weworkremotely.com/categories/remote-all-other-remote-jobs.rss");

        public String name() { return "WeWorkRemotely"; }
        public int minIntervalMinutes() { return 240; }
        public List<Job> fetch(Http http, Settings s, Profile p) throws Exception {
            List<Job> out = new ArrayList<>();
            Exception last = null;
            for (String feed : FEEDS) {
                try {
                    out.addAll(parseWwr(http.get(feed, Map.of())));
                } catch (Exception e) {
                    last = e;                       // one broken category feed should not sink the others
                }
                Thread.sleep(800);
            }
            if (out.isEmpty() && last != null) throw last;
            return out;
        }
    }

    /**
     * Working Nomads' public jobs feed (linked from their home page; robots.txt allows it). Their terms say nothing
     * about it, so this is gentle: twice a day at most, with an honest User-Agent, personal use only. Each job links
     * back to workingnomads.com and shows "Working Nomads" as its source.
     */
    static final class WorkingNomads implements Source {
        public String name() { return "Working Nomads"; }
        public int minIntervalMinutes() { return 720; }
        public List<Job> fetch(Http http, Settings s, Profile p) throws Exception {
            return parseWorkingNomads(http.get("https://www.workingnomads.com/api/exposed_jobs/", Map.of()));
        }
    }

    // =====================================================================
    //  Web search (this is how the tool "searches Google for everything")
    //  Google's own Custom Search API is closed to new users, so we use
    //  Brave Search or Serper (which returns Google results).
    // =====================================================================

    static final class Brave implements Source {
        public String name() { return "Search:Brave"; }
        public int minIntervalMinutes() { return 720; }
        public boolean enabled(Settings s) { return s.braveKey() != null; }
        public List<Job> fetch(Http http, Settings s, Profile p) throws Exception {
            List<Job> out = new ArrayList<>();
            int n = 0;
            for (String q : p.searchQueries) {
                if (n++ >= p.maxSearchQueries) break;
                String url = "https://api.search.brave.com/res/v1/web/search?count=20&freshness=pm&q="
                        + URLEncoder.encode(q, StandardCharsets.UTF_8);
                out.addAll(parseBrave(http.get(url, Map.of("Accept", "application/json", "X-Subscription-Token", s.braveKey()))));
                Thread.sleep(1200);
            }
            return out;
        }
    }

    /** Serper answers HTTP 400 to num=20 on site: and OR queries, so ask for 10 results (verified live). */
    static String serperBody(String q, String tbs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", q); body.put("num", 10); body.put("tbs", tbs);
        return Json.write(body);
    }

    static final class Serper implements Source {
        public String name() { return "Search:Google(Serper)"; }
        public int minIntervalMinutes() { return 720; }
        public boolean enabled(Settings s) { return s.serperKey() != null; }
        public List<Job> fetch(Http http, Settings s, Profile p) throws Exception {
            List<Job> out = new ArrayList<>();
            int n = 0;
            for (String q : p.searchQueries) {
                if (n++ >= p.maxSearchQueries) break;
                out.addAll(parseSerper(http.postJson("https://google.serper.dev/search",
                        Map.of("X-API-KEY", s.serperKey()), serperBody(q, "qdr:m"))));
                Thread.sleep(800);
            }
            return out;
        }
    }

    /**
     * LinkedIn and Indeed block programs from reading their pages, and their terms forbid scraping.
     * So we ask a search engine (Brave or Serper) for their job pages from the past week, one query
     * per target title, and keep only real job pages. You still open and apply on LinkedIn or Indeed.
     */
    static final class BoardSearch implements Source {
        public String name() { return "LinkedIn+Indeed+ZipRecruiter (via web search)"; }
        public int minIntervalMinutes() { return 1440; }
        public boolean enabled(Settings s) { return s.braveKey() != null || s.serperKey() != null; }
        public List<Job> fetch(Http http, Settings s, Profile p) throws Exception {
            List<Job> out = new ArrayList<>();
            int n = 0;
            for (String q : boardQueries(p)) {
                if (n++ >= p.maxBoardQueries * 3) break;
                List<Job> found;
                if (s.serperKey() != null) {
                    found = parseSerper(http.postJson("https://google.serper.dev/search", Map.of("X-API-KEY", s.serperKey()), serperBody(q, "qdr:w")));  // past week
                    Thread.sleep(800);
                } else {
                    String url = "https://api.search.brave.com/res/v1/web/search?count=20&freshness=pw&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
                    found = parseBrave(http.get(url, Map.of("Accept", "application/json", "X-Subscription-Token", s.braveKey())));
                    Thread.sleep(1200);
                }
                for (Job j : found) { Job b = asBoardJob(j); if (b != null) out.add(b); }
            }
            return out;
        }
    }

    /**
     * Google Jobs collects listings from thousands of sites. Google has no free public feed for it,
     * so this uses SerpApi's Google Jobs API (set SERPAPI_KEY). One query per best-fit target title,
     * remote, located in South Africa; anything older than 7 days is dropped.
     */
    static final class GoogleJobs implements Source {
        public String name() { return "Google Jobs (SerpApi)"; }
        public int minIntervalMinutes() { return 1440; }
        public boolean enabled(Settings s) { return s.serpapiKey() != null; }
        public List<Job> fetch(Http http, Settings s, Profile p) throws Exception {
            List<Job> out = new ArrayList<>();
            List<Profile.Target> ts = new ArrayList<>(p.targets);
            ts.sort(Comparator.comparing(Profile.Target::fit));
            int n = 0;
            for (Profile.Target t : ts) {
                if (n++ >= p.maxBoardQueries) break;
                String url = "https://serpapi.com/search.json?engine=google_jobs&hl=en&gl=za&location=" + URLEncoder.encode("South Africa", StandardCharsets.UTF_8)
                        + "&q=" + URLEncoder.encode(t.query() + " remote", StandardCharsets.UTF_8)
                        + "&api_key=" + URLEncoder.encode(s.serpapiKey(), StandardCharsets.UTF_8);
                out.addAll(parseGoogleJobs(http.get(url, Map.of("Accept", "application/json")), Instant.now(), 7));
                Thread.sleep(1000);
            }
            return out;
        }
    }

    private static final Pattern AGO = Pattern.compile("(?i)(\\d+)\\+?\\s*(minute|hour|day|week|month)s?\\s+ago");

    /** Turns "3 days ago" into an instant, or null if it cannot tell. */
    static Instant ago(String text, Instant now) {
        if (text == null) return null;
        if (text.toLowerCase(Locale.ROOT).contains("just posted") || text.toLowerCase(Locale.ROOT).contains("today")) return now;
        Matcher m = AGO.matcher(text);
        if (!m.find()) return null;
        long v = Long.parseLong(m.group(1));
        long minutes = switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "minute" -> v;
            case "hour" -> v * 60;
            case "day" -> v * 1440;
            case "week" -> v * 10080;
            default -> v * 43200;
        };
        return now.minusSeconds(minutes * 60);
    }

    static List<Job> parseGoogleJobs(String body, Instant now, int maxDays) {
        List<Job> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(Json.parse(body)).get("jobs_results"))) {
            Map<String, Object> m = Json.obj(o);
            Job j = new Job();
            j.source = "Google Jobs";
            j.title = Json.str(m.get("title"));
            j.company = Json.str(m.get("company_name"));
            j.location = Json.str(m.get("location"));
            j.description = Json.str(m.get("description"));
            Map<String, Object> ext = Json.obj(m.get("detected_extensions"));
            String schedule = Json.str(ext.get("schedule_type"));
            if (schedule != null) j.type = schedule;
            if (Json.bool(ext.get("work_from_home"), false)) j.tags.add("remote");
            String via = Json.str(m.get("via"));
            if (via != null) j.tags.add(via.replaceFirst("(?i)^via ", ""));
            String share = Json.str(m.get("share_link"));
            List<Object> apply = Json.arr(m.get("apply_options"));
            String applyLink = apply.isEmpty() ? null : Json.str(Json.obj(apply.get(0)).get("link"));
            j.url = applyLink != null ? applyLink : share;
            if (applyLink != null) j.applyUrl = applyLink;
            Instant posted = ago(Json.str(ext.get("posted_at")), now);
            if (posted != null) {
                if (posted.isBefore(now.minusSeconds(maxDays * 86400L))) continue;   // older than a week
                j.postedAt = posted.toString();
            }
            if (j.title == null || j.title.isBlank() || j.url == null || j.url.isBlank()) continue;
            out.add(j.finish());
        }
        return out;
    }

    /** Three queries per target title (LinkedIn, Indeed, ZipRecruiter), best-fit titles first. */
    static List<String> boardQueries(Profile p) {
        List<Profile.Target> ts = new ArrayList<>(p.targets);
        ts.sort(Comparator.comparing(Profile.Target::fit));             // A before B before C
        List<String> qs = new ArrayList<>();
        for (Profile.Target t : ts) {
            qs.add("site:linkedin.com/jobs/view " + t.query() + " remote South Africa");
            qs.add("site:indeed.com " + t.query() + " remote");
            qs.add("site:ziprecruiter.com " + t.query() + " remote");
        }
        return qs;
    }

    private static final Pattern ZR_TITLE = Pattern.compile("^(.+?)(?: Job)?(?: in (.+?))?(?: at (.+?))?$");
    private static final Pattern LI_TITLE = Pattern.compile("^(.+?) hiring (.+?)(?: in (.+?))?(?: \\| LinkedIn)?$");

    /** Keeps only real LinkedIn or Indeed job pages and pulls company and location out of the page title. */
    static Job asBoardJob(Job j) {
        String u = j.url.toLowerCase(Locale.ROOT);
        if (u.contains("linkedin.com/jobs/view/")) {
            j.source = "LinkedIn";
            Matcher m = LI_TITLE.matcher(j.title.trim());
            if (m.matches()) {
                j.company = m.group(1).trim();
                j.title = m.group(2).trim();
                if (m.group(3) != null) j.location = m.group(3).trim();
            } else {
                j.title = j.title.replaceAll("\\s*\\|\\s*LinkedIn\\s*$", "").trim();
            }
        } else if (u.contains("indeed.com/viewjob") || u.contains("indeed.com/rc/clk") || u.contains("jk=")) {
            j.source = "Indeed";
            String t = j.title.replaceAll("\\s*[|-]\\s*Indeed(\\.com)?\\s*$", "").trim();
            String[] parts = t.split(" - ");
            if (parts.length >= 3) {
                j.title = parts[0].trim();
                j.company = parts[1].trim();
                j.location = String.join(" - ", Arrays.copyOfRange(parts, 2, parts.length)).trim();
            } else {
                j.title = t;
            }
        } else if (u.contains("ziprecruiter.") && (u.contains("/job/") || u.contains("jid="))) {
            j.source = "ZipRecruiter";
            // page titles look like "Junior Java Developer Job in Remote at Acme | ZipRecruiter" (format varies)
            String t = j.title.replaceAll("\\s*[|-]\\s*ZipRecruiter\\s*$", "").trim();
            Matcher m = ZR_TITLE.matcher(t);
            if (m.matches()) {
                j.title = m.group(1).trim();
                if (m.group(2) != null) j.location = m.group(2).trim();
                if (m.group(3) != null) j.company = m.group(3).trim();
            } else {
                j.title = t;
            }
        } else {
            return null;                                              // search pages, company pages, salary pages: skip
        }
        return j.finish();
    }

    /** One-click searches you can open yourself: past 7 days, remote. */
    static Map<String, String> boardLinks(Profile.Target t) {
        String k = URLEncoder.encode(t.query(), StandardCharsets.UTF_8);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("LinkedIn (South Africa, remote, 7 days)", "https://www.linkedin.com/jobs/search/?keywords=" + k + "&location=South%20Africa&f_WT=2&f_TPR=r604800&sortBy=DD");
        m.put("LinkedIn (worldwide remote, 7 days)", "https://www.linkedin.com/jobs/search/?keywords=" + k + "&location=Worldwide&f_WT=2&f_TPR=r604800&sortBy=DD");
        m.put("Indeed South Africa (remote, 7 days)", "https://za.indeed.com/jobs?q=" + k + "&l=Remote&fromage=7&sort=date");
        m.put("ZipRecruiter (remote, 5 days, mostly US)", "https://www.ziprecruiter.com/jobs-search?search=" + k + "&location=Remote&days=5");
        m.put("Google Jobs (remote)", "https://www.google.com/search?q=" + URLEncoder.encode(t.query() + " remote jobs", StandardCharsets.UTF_8) + "&ibp=htl;jobs");
        return m;
    }

    // =====================================================================
    //  Parsers (pure functions: text in, jobs out, so they can be tested offline)
    // =====================================================================

    static List<Job> parseRemoteOk(String body, Map<String, Double> fx) {
        List<Job> out = new ArrayList<>();
        for (Object o : Json.arr(Json.parse(body))) {
            Map<String, Object> m = Json.obj(o);
            String title = Json.str(m.get("position"));
            if (title == null || title.isBlank()) continue;          // first element is a legal notice
            Job j = new Job();
            j.source = "RemoteOK";
            j.title = title;
            j.company = Json.str(m.get("company"));
            j.url = Json.str(m.get("url"));
            j.applyUrl = Text.firstNonBlank(Json.str(m.get("apply_url")), j.url);
            j.location = Json.str(m.get("location"));
            j.tags = Json.strings(m.get("tags"));
            j.description = Text.stripHtml(Json.str(m.get("description")));
            j.postedAt = Json.str(m.get("date"));
            j.minHourlyUsd = Pay.annualToHourly(Json.num(m.get("salary_min")), "USD", fx);
            j.maxHourlyUsd = Pay.annualToHourly(Json.num(m.get("salary_max")), "USD", fx);
            out.add(j.finish());
        }
        return out;
    }

    static List<Job> parseJobicy(String body, Map<String, Double> fx) {
        List<Job> out = new ArrayList<>();
        Map<String, Object> root = Json.obj(Json.parse(body));
        for (Object o : Json.arr(root.get("jobs"))) {
            Map<String, Object> m = Json.obj(o);
            Job j = new Job();
            j.source = "Jobicy";
            j.title = Json.str(m.get("jobTitle"));
            j.company = Json.str(m.get("companyName"));
            j.url = Json.str(m.get("url"));
            j.location = Json.str(m.get("jobGeo"));
            j.type = String.join(", ", Json.strings(m.get("jobType")));
            j.tags = new ArrayList<>(Json.strings(m.get("jobIndustry")));
            j.tags.addAll(Json.strings(m.get("jobLevel")));
            j.description = Text.stripHtml(Text.firstNonBlank(Json.str(m.get("jobDescription")), Json.str(m.get("jobExcerpt"))));
            j.postedAt = iso(Json.str(m.get("pubDate")));
            // current API: salaryMin / salaryMax / salaryCurrency / salaryPeriod; older API: annualSalaryMin / annualSalaryMax
            String cur = Text.firstNonBlank(Json.str(m.get("salaryCurrency")), "USD");
            String period = Text.firstNonBlank(Json.str(m.get("salaryPeriod")), "yearly");
            Double lo = Json.num(m.get("salaryMin")), hi = Json.num(m.get("salaryMax"));
            if (lo == null && hi == null) { lo = Json.num(m.get("annualSalaryMin")); hi = Json.num(m.get("annualSalaryMax")); period = "yearly"; }
            j.minHourlyUsd = Pay.toHourly(lo, period, cur, fx);
            j.maxHourlyUsd = Pay.toHourly(hi, period, cur, fx);
            if (j.title == null || j.title.isBlank()) continue;
            out.add(j.finish());
        }
        return out;
    }

    /** Working Nomads: a plain JSON array; tags is one comma-separated string; pub_date has a UTC offset. No pay fields. */
    static List<Job> parseWorkingNomads(String body) {
        List<Job> out = new ArrayList<>();
        for (Object o : Json.arr(Json.parse(body))) {
            Map<String, Object> m = Json.obj(o);
            Job j = new Job();
            j.source = "Working Nomads";
            j.title = Json.str(m.get("title"));
            j.company = Json.str(m.get("company_name"));
            j.url = Json.str(m.get("url"));
            j.location = Json.str(m.get("location"));
            j.description = Text.stripHtml(Json.str(m.get("description")));
            j.tags = new ArrayList<>();
            for (String t : Text.norm(Json.str(m.get("tags"))).split(",")) if (!t.isBlank()) j.tags.add(t.trim());
            String cat = Json.str(m.get("category_name"));
            if (cat != null && !cat.isBlank()) j.tags.add(cat.trim());
            String raw = Json.str(m.get("pub_date"));
            try { j.postedAt = java.time.OffsetDateTime.parse(raw).toInstant().toString(); }
            catch (Exception e) { j.postedAt = iso(raw); }
            if (j.title == null || j.title.isBlank()) continue;
            out.add(j.finish());
        }
        return out;
    }

    static List<Job> parseRemotive(String body, Map<String, Double> fx) {
        List<Job> out = new ArrayList<>();
        Map<String, Object> root = Json.obj(Json.parse(body));
        for (Object o : Json.arr(root.get("jobs"))) {
            Map<String, Object> m = Json.obj(o);
            Job j = new Job();
            j.source = "Remotive";
            j.title = Json.str(m.get("title"));
            j.company = Json.str(m.get("company_name"));
            j.url = Json.str(m.get("url"));
            j.location = Json.str(m.get("candidate_required_location"));
            j.type = Json.str(m.get("job_type"));
            j.tags = new ArrayList<>(Json.strings(m.get("tags")));
            String cat = Json.str(m.get("category"));
            if (cat != null) j.tags.add(cat);
            j.description = Text.stripHtml(Json.str(m.get("description")));
            j.postedAt = iso(Json.str(m.get("publication_date")));
            j.salaryText = Json.str(m.get("salary"));
            double[] pay = Pay.hourlyUsd(j.salaryText, fx);
            if (pay != null) { j.minHourlyUsd = pay[0]; j.maxHourlyUsd = pay[1]; }
            if (j.title == null || j.title.isBlank()) continue;
            out.add(j.finish());
        }
        return out;
    }

    static List<Job> parseHimalayas(String body, Map<String, Double> fx) {
        List<Job> out = new ArrayList<>();
        Map<String, Object> root = Json.obj(Json.parse(body));
        for (Object o : Json.arr(root.get("jobs"))) {
            Map<String, Object> m = Json.obj(o);
            Job j = new Job();
            j.source = "Himalayas";
            j.title = Json.str(m.get("title"));
            j.company = Json.str(m.get("companyName"));
            String guid = Json.str(m.get("guid"));
            String apply = Json.str(m.get("applicationLink"));
            j.url = guid != null && guid.startsWith("http") ? guid : apply;
            j.applyUrl = Text.firstNonBlank(apply, j.url);
            List<String> restr = Json.strings(m.get("locationRestrictions"));
            List<Object> tzs = Json.arr(m.get("timezoneRestrictions"));
            boolean tzNear = false;                          // is any allowed timezone close to South Africa (UTC+2)?
            StringBuilder tzText = new StringBuilder();
            for (Object t : tzs) {
                Double v = Json.num(t);
                if (v == null) continue;
                if (v >= 0 && v <= 4) tzNear = true;
                if (tzText.length() > 0) tzText.append(", ");
                tzText.append("UTC").append(v >= 0 ? "+" : "").append(v.intValue());
            }
            if (!restr.isEmpty()) j.location = String.join(", ", restr);
            else if (tzs.isEmpty() || tzNear) j.location = "Worldwide";
            else j.location = "Only timezones " + tzText;
            j.type = Json.str(m.get("employmentType"));
            j.tags = new ArrayList<>(Json.strings(m.get("categories")));
            j.tags.addAll(Json.strings(m.get("seniority")));
            j.description = Text.stripHtml(Text.firstNonBlank(Json.str(m.get("description")), Json.str(m.get("excerpt"))));
            j.postedAt = iso(Json.str(m.get("pubDate")));
            String cur = Json.str(m.get("currency"));
            String period = Text.firstNonBlank(Json.str(m.get("salaryPeriod")), "yearly");
            if (cur != null && !cur.isBlank()) {            // no currency means no pay figure, do not guess
                j.minHourlyUsd = Pay.toHourly(Json.num(m.get("minSalary")), period, cur, fx);
                j.maxHourlyUsd = Pay.toHourly(Json.num(m.get("maxSalary")), period, cur, fx);
            }
            if (j.title == null || j.title.isBlank()) continue;
            out.add(j.finish());
        }
        return out;
    }

    static List<Job> parseWwr(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder builder = f.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());      // throw on fatal errors, stay quiet otherwise
        Document d = builder.parse(new InputSource(new StringReader(xml)));
        NodeList items = d.getElementsByTagName("item");
        List<Job> out = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element it = (Element) items.item(i);
            String raw = tag(it, "title");
            if (raw.isBlank()) continue;
            Job j = new Job();
            j.source = "WeWorkRemotely";
            int c = raw.indexOf(": ");
            if (c > 0) { j.company = raw.substring(0, c); j.title = raw.substring(c + 2); }
            else { j.company = ""; j.title = raw; }
            j.url = tag(it, "link");
            if (j.url.isBlank()) j.url = tag(it, "guid");
            j.location = tag(it, "region");
            if (j.location.isBlank()) j.location = tag(it, "country");
            j.type = tag(it, "type");
            String cat = tag(it, "category");
            if (!cat.isBlank()) j.tags.add(cat);
            for (String sk : tag(it, "skills").split(",")) if (!sk.isBlank()) j.tags.add(sk.trim());
            j.description = Text.stripHtml(tag(it, "description"));
            j.postedAt = iso(tag(it, "pubDate"));
            out.add(j.finish());
        }
        return out;
    }

    static List<Job> parseBrave(String body) {
        List<Job> out = new ArrayList<>();
        Map<String, Object> web = Json.obj(Json.obj(Json.parse(body)).get("web"));
        for (Object o : Json.arr(web.get("results"))) {
            Map<String, Object> m = Json.obj(o);
            Job j = searchJob("Search:Brave", Json.str(m.get("title")), Json.str(m.get("url")), Json.str(m.get("description")));
            if (j != null) out.add(j);
        }
        return out;
    }

    static List<Job> parseSerper(String body) {
        List<Job> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(Json.parse(body)).get("organic"))) {
            Map<String, Object> m = Json.obj(o);
            Job j = searchJob("Search:Google(Serper)", Json.str(m.get("title")), Json.str(m.get("link")), Json.str(m.get("snippet")));
            if (j != null) out.add(j);
        }
        return out;
    }

    // ---------------------------------------------------------------------

    private static final Set<String> SKIP_HOSTS = Set.of("reddit.com", "quora.com", "medium.com", "youtube.com",
            "pinterest.com", "facebook.com", "tiktok.com", "wikipedia.org", "twitter.com", "x.com");

    private static Job searchJob(String source, String title, String url, String snippet) {
        if (title == null || title.isBlank() || url == null || url.isBlank()) return null;
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
        if (host == null) return null;
        host = host.startsWith("www.") ? host.substring(4) : host;
        for (String bad : SKIP_HOSTS) if (host.equals(bad) || host.endsWith("." + bad)) return null;
        Job j = new Job();
        j.source = source;
        j.sourceType = "SEARCH";
        j.title = Text.stripHtml(title);
        j.company = host;
        j.url = url;
        j.description = Text.stripHtml(snippet);
        return j.finish();
    }

    private static String tag(Element e, String name) {
        NodeList l = e.getElementsByTagName(name);
        return l.getLength() == 0 || l.item(0).getTextContent() == null ? "" : l.item(0).getTextContent().trim();
    }

    /** Best-effort conversion of the many date formats feeds use into an ISO-8601 instant. */
    static String iso(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw.trim();
        try {
            if (s.matches("\\d{9,13}")) {
                long v = Long.parseLong(s);
                return (s.length() > 10 ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v)).toString();
            }
            if (s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) return s.replace(' ', 'T') + "Z";
            if (s.matches("[A-Za-z]{3}, .*")) {
                // some feeds print the wrong weekday; the weekday is optional in the formatter, so drop it
                return ZonedDateTime.parse(s.substring(5), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString();
            }
            return s;
        } catch (Exception e) {
            return s;
        }
    }
}

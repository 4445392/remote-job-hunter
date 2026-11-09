package jobhunter;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simple-gigs pipeline:
 * search (Google, Reddit, YouTube and TikTok via a search API)  ->  one lead per domain  ->  read the platform's own public page
 * (robots.txt respected)  ->  extract facts  ->  South Africa and payout check  ->  independent-review check  ->  risk and fit  ->  draft.
 * Nothing is ever joined or sent; you review each gig and click yourself.
 */
final class GigPipeline {
    static final String AGENT = "RemoteJobHunter";
    static final String SOURCE = "Gigs";
    private static final int MAX_PAGE_CHARS = 600_000;
    private static final Pattern HREF = Pattern.compile("href=[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern USEFUL_PATH = Pattern.compile("/(faq|how-it-works|how|join|creators?|apply|pricing|payouts?|get-paid|earn|about)(/|$|\\?|-)", Pattern.CASE_INSENSITIVE);

    /** Wording that says a page is about earning: get paid, earn money, pays creators, per view, retainer, commission, referral rewards. */
    static final Pattern EARNING = Pattern.compile(
            "get paid|earn(?:ing|s)? (?:money|cash|\\$|up to|per|a |extra)|pay(?:s)? (?:creators|you|per|out)|paid (?:per|to|for|weekly|monthly)|\\bpay ?outs?\\b|per (?:1,?000 )?views?|\\bretainer\\b|\\bcommissions?\\b|referral (?:bonus|reward|commission)|\\bbounty\\b",
            Pattern.CASE_INSENSITIVE);

    private final Settings settings;
    private final Profile profile;
    private final Store store;
    private final Http http;
    private int reviewsMade;                                          // search credits spent on review checks in the current run

    GigPipeline(Settings settings, Profile profile, Store store, Http http) {
        this.settings = settings;
        this.profile = profile;
        this.store = store;
        this.http = http;
    }

    boolean enabled() { return settings.serperKey() != null || settings.braveKey() != null; }

    /** Runs a full discovery only when the weekly timer says it is due. Returns null when it is not. */
    Map<String, Object> runIfDue() {
        if (!enabled() || !store.sourceDue(SOURCE, profile.gigIntervalMinutes)) return null;
        return run();
    }

    // ------------------------------------------------------------------ full discovery

    Map<String, Object> run() {
        Map<String, Object> sum = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        sum.put("at", Instant.now().toString());
        if (!enabled()) {
            errors.add("Simple gigs need a search key: set SERPER_API_KEY or BRAVE_API_KEY");
            sum.put("errors", errors);
            return sum;
        }
        int queriesRun = 0, pages = 0, added = 0, rejected = 0, excluded = 0, known = 0;
        reviewsMade = 0;
        List<GigDiscovery.Hit> hits = new ArrayList<>();
        for (GigDiscovery.Query q : GigDiscovery.queries(profile.gigMaxQueriesPerRun)) {
            try {
                hits.addAll(search(q.q()));
                queriesRun++;
                Thread.sleep(800);
            } catch (Exception e) {
                errors.add("search: " + e.getMessage());
                if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); break; }
            }
        }
        List<GigDiscovery.Lead> leads = GigDiscovery.leads(hits);
        for (GigDiscovery.Lead l : leads) {
            if (store.hasGigDomain(l.domain)) { known++; continue; }
            if (pages >= profile.gigMaxPagesPerRun) break;
            pages++;
            try {
                Gig g = process(l, reviewsMade < profile.gigMaxReviewChecks);
                if (g.status.equals(Gig.REJECTED)) { rejected++; if (g.eligibility.equals(Gig.EXCLUDED)) excluded++; }
                else added++;
                Thread.sleep(800);
            } catch (Exception e) {
                errors.add(l.domain + ": " + e.getMessage());
                if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); break; }
            }
        }
        store.touchSource(SOURCE);
        sum.put("queriesRun", queriesRun);
        sum.put("searchResults", hits.size());
        sum.put("leads", leads.size());
        sum.put("alreadyKnown", known);
        sum.put("pagesRead", pages);
        sum.put("reviewChecks", reviewsMade);
        sum.put("addedToList", added);
        sum.put("rejectedOrExcluded", rejected);
        sum.put("excludedForSouthAfrica", excluded);
        sum.put("errors", errors);
        try { store.save(); } catch (IOException e) { errors.add("Could not save: " + e.getMessage()); }
        return sum;
    }

    /** Looks at one platform again (fresh page, fresh review check), keeping your status and any draft edits. */
    Gig recheck(String id) throws Exception {
        Gig old = store.gig(id);
        if (old == null) return null;
        GigDiscovery.Lead l = new GigDiscovery.Lead();
        l.domain = old.domain; l.url = old.url; l.name = old.name; l.snippet = old.snippet; l.direct = true;
        l.via.addAll(old.foundVia);
        Gig fresh = process(l, true);
        return fresh;
    }

    // ------------------------------------------------------------------ one platform

    /** Reads the platform's own page, runs every check and stores the result. */
    Gig process(GigDiscovery.Lead l, boolean reviewCheck) throws Exception {
        Gig old = store.gig(Gig.idFor(l.domain));
        String html = fetchPages(l);
        Gig g = GigExtract.extract(l.url, l.name, html, l.snippet);
        g.domain = l.domain; g.id = Gig.idFor(l.domain);               // a redirect must not change which platform this is
        g.foundVia = new ArrayList<>(l.via);
        g.foundAt = old != null ? old.foundAt : Instant.now().toString();
        String text = (Text.stripHtml(html.replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ")) + " " + l.snippet).replaceAll("\\s+", " ");

        // not every page a search returns is a gig platform (a basketball team's "Clippers" video, a transit card, a job post):
        // it only counts if the page shows a way of earning, and anything else is dropped before it costs a review search
        // (a scam page with no creator-category words still gets through, so that it is kept and rejected with its reasons)
        if (!EARNING.matcher(text).find() || (g.categories.isEmpty() && GigRisk.hardRejects(text).isEmpty())) {
            g.status = Gig.REJECTED; g.statusAt = Instant.now().toString(); g.checkedAt = g.statusAt;
            g.risk = 0; g.fit = 0;
            g.whyFits = "Not a gig platform: no pay model, payout or earning wording found on the page.";
            g.riskReasons = new ArrayList<>(List.of("Not scored: this does not look like a gig platform"));
            g.draft = "";
            store.putGig(g);
            return g;
        }

        GigScore.eligibility(g, profile);
        boolean dead =!GigRisk.hardRejects(text).isEmpty() || g.eligibility.equals(Gig.EXCLUDED);
        GigRisk.Reviews rv = GigRisk.Reviews.NONE;
        if (!dead && reviewCheck) {
            reviewsMade++;
            try { rv = reviews(g); } catch (Exception e) { if (e instanceof InterruptedException) throw e; }
        }
        GigRisk.assess(g, text, rv);
        GigScore.fit(g, profile);
        g.checkedAt = Instant.now().toString();

        boolean rejected = !g.hardRejects.isEmpty() || g.eligibility.equals(Gig.EXCLUDED);
        if (old != null && !old.status.equals(Gig.REJECTED)) { g.status = rejected ? Gig.REJECTED : old.status; g.statusAt = old.statusAt; }
        else g.status = rejected ? Gig.REJECTED : Gig.NEW;
        boolean editedDraft = old != null && !old.draft.isBlank() && !old.draft.equals(GigScore.draft(old, profile));
        g.draft = editedDraft ? old.draft : GigScore.draft(g, profile);
        store.putGig(g);
        return g;
    }

    private GigRisk.Reviews reviews(Gig g) throws Exception {
        String label = g.name.length() >= 3 ? g.name : g.domain;
        List<GigDiscovery.Hit> hits = search("\"" + label + "\" reviews payout legit");
        Thread.sleep(800);
        return GigRisk.Reviews.fromHits(g.name, g.domain, hits);
    }

    // ------------------------------------------------------------------ the network

    private List<GigDiscovery.Hit> search(String q) throws Exception {
        if (settings.serperKey() != null) {
            return GigDiscovery.parseSerper(http.postJson("https://google.serper.dev/search",
                    Map.of("X-API-KEY", settings.serperKey()), Sources.serperBody(q, "qdr:y")), q);
        }
        String url = "https://api.search.brave.com/res/v1/web/search?count=20&freshness=py&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
        return GigDiscovery.parseBrave(http.get(url, Map.of("Accept", "application/json", "X-Subscription-Token", settings.braveKey())), q);
    }

    /**
     * The platform's own public pages: the start page plus at most two useful ones (FAQ, how it works, payouts, join).
     * robots.txt is read first and obeyed. A site that cannot be read is simply left to the search snippet; never forced.
     */
    String fetchPages(GigDiscovery.Lead l) throws InterruptedException {
        String base = "https://" + l.domain;
        String robots = null;
        try {
            robots = http.get(base + "/robots.txt", Map.of("Accept", "text/plain"));
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (!m.startsWith("HTTP 4")) return "";                    // network trouble or a server error: do not push
        }
        String startPath = "/";
        try {
            String p = URI.create(l.url).getPath();
            if (l.direct && p != null && !p.isBlank()) startPath = p;
        } catch (Exception ignored) { }
        if (!GigDiscovery.Robots.allows(robots, AGENT, startPath)) return "";
        StringBuilder all = new StringBuilder();
        String first;
        try {
            first = cap(http.get(base + startPath, Map.of()));
        } catch (IOException e) {
            return "";
        }
        all.append(first);
        int extra = 0;
        Set<String> done = new HashSet<>(List.of(startPath));
        Matcher m = HREF.matcher(first);
        while (m.find() && extra < 2) {
            String path;
            try {
                URI u = URI.create(base + "/").resolve(m.group(1).trim());
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
                if (!host.equals(l.domain)) continue;
                path = u.getPath() == null ? "/" : u.getPath();
            } catch (Exception e) { continue; }
            if (!USEFUL_PATH.matcher(path).find() || !done.add(path)) continue;
            if (!GigDiscovery.Robots.allows(robots, AGENT, path)) continue;
            Thread.sleep(800);
            try {
                all.append("\n").append(cap(http.get(base + path, Map.of())));
                extra++;
            } catch (IOException ignored) { }
        }
        return all.toString();
    }

    private static String cap(String s) { return s == null ? "" : s.length() > MAX_PAGE_CHARS ? s.substring(0, MAX_PAGE_CHARS) : s; }
}

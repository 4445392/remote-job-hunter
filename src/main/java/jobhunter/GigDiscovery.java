package jobhunter;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finding candidate gig platforms: the search queries, reading search results, and turning them into leads.
 * Pure functions (no network). Search results from Reddit, YouTube and TikTok are never opened; their titles and
 * snippets are only read for platform names and for what real people say about getting paid.
 */
final class GigDiscovery {
    private GigDiscovery() {}

    record Query(String q, String category) {}
    record Hit(String title, String link, String snippet, String query) {}

    /** A platform to look at: its domain, a page to start from, and how many independent results mentioned it. */
    static final class Lead {
        String domain = "", url = "", name = "", snippet = "";
        int mentions;
        boolean direct;                       // true when a search result pointed at the platform's own site
        final List<String> via = new ArrayList<>();
    }

    // ------------------------------------------------------------------ the query bank

    private static final String[][] BANK = {
            // UGC and short-form creator programs
            {Gig.UGC, "paid to post short form videos no followers"},
            {Gig.UGC, "UGC creator no experience"},
            {Gig.UGC, "UGC creator program no followers required free to join"},
            {Gig.UGC, "creator retainer program TikTok Reels view bonus"},
            {Gig.UGC, "brand campaigns creators retainer plus view bonus no followers"},
            {Gig.UGC, "get paid to post TikTok videos worldwide"},
            // clipping and themed pages
            {Gig.CLIPPING, "clipping jobs pay per view"},
            {Gig.CLIPPING, "content clipping program pay per 1000 views free to join"},
            {Gig.CLIPPING, "faceless page themed page creator program paid"},
            {Gig.CLIPPING, "clipper program sign up pay per view worldwide"},
            // posting, social media and VA
            {Gig.POSTING_VA, "social media posting jobs remote no experience"},
            {Gig.POSTING_VA, "paid to post on social media worldwide part-time"},
            {Gig.POSTING_VA, "virtual assistant no experience remote worldwide"},
            {Gig.POSTING_VA, "social media manager entry level remote flexible hours"},
            // AI micro-tasks
            {Gig.AI_MICRO, "data annotation no experience worldwide paypal"},
            {Gig.AI_MICRO, "AI training micro tasks pay per task worldwide"},
            {Gig.AI_MICRO, "microtask platforms that pay via paypal or payoneer"},
            // paid referral programs
            {Gig.REFERRAL, "paid referral program creators free to join"},
            {Gig.REFERRAL, "refer and earn program no followers needed"},
            {Gig.REFERRAL, "affiliate program for content creators no experience"},
            // Reddit: what people actually say
            {Gig.UGC, "site:reddit.com/r/UGCcreators no followers program paid legit"},
            {Gig.UGC, "site:reddit.com/r/UGCcreators retainer view bonus payout"},
            {Gig.OTHER, "site:reddit.com/r/WorkOnline paid to post videos no experience"},
            {Gig.OTHER, "site:reddit.com/r/WorkOnline social media posting gig legit"},
            {Gig.OTHER, "site:reddit.com/r/beermoney clipping OR UGC paid worldwide"},
            {Gig.OTHER, "site:reddit.com/r/beermoney micro tasks paypal worldwide"},
            {Gig.OTHER, "site:reddit.com/r/forhire social media posting short form video editor beginner"},
            {Gig.OTHER, "site:reddit.com/r/forhire virtual assistant no experience"},
            // creator payout experiences on video sites (titles and snippets only)
            {Gig.OTHER, "site:youtube.com creator program payout proof no followers"},
            {Gig.OTHER, "site:youtube.com clipping program paid per view real payout"},
            {Gig.OTHER, "site:tiktok.com clipping program payout proof"},
            {Gig.OTHER, "site:tiktok.com UGC no followers got paid"},
    };

    /** Up to {@code max} queries, taking one from each category in turn so a small budget still covers everything. */
    static List<Query> queries(int max) {
        Map<String, Deque<Query>> byCat = new LinkedHashMap<>();
        for (String[] b : BANK) byCat.computeIfAbsent(b[0], k -> new ArrayDeque<>()).add(new Query(b[1], b[0]));
        List<Query> out = new ArrayList<>();
        while (out.size() < max && byCat.values().stream().anyMatch(d -> !d.isEmpty())) {
            for (Deque<Query> d : byCat.values()) {
                if (out.size() >= max) break;
                if (!d.isEmpty()) out.add(d.poll());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ reading search results

    static List<Hit> parseSerper(String body, String query) {
        List<Hit> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(Json.parse(body)).get("organic"))) {
            Map<String, Object> m = Json.obj(o);
            String link = Json.str(m.get("link"));
            if (link != null && !link.isBlank()) out.add(new Hit(nz(Json.str(m.get("title"))), link, nz(Json.str(m.get("snippet"))), query));
        }
        return out;
    }

    static List<Hit> parseBrave(String body, String query) {
        List<Hit> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(Json.obj(Json.parse(body)).get("web")).get("results"))) {
            Map<String, Object> m = Json.obj(o);
            String link = Json.str(m.get("url"));
            if (link != null && !link.isBlank())
                out.add(new Hit(Text.unescape(nz(Json.str(m.get("title")))), link, Text.stripHtml(nz(Json.str(m.get("description")))), query));
        }
        return out;
    }

    // ------------------------------------------------------------------ domains and leads

    static String domainOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    /** Sites that are never a gig platform themselves: search engines, social networks, payment brands, marketplaces, big publishers. */
    private static final Set<String> IGNORED = Set.of("google.com", "bing.com", "duckduckgo.com", "youtube.com", "youtu.be", "tiktok.com",
            "instagram.com", "facebook.com", "fb.com", "x.com", "twitter.com", "linkedin.com", "reddit.com", "redd.it", "quora.com",
            "medium.com", "pinterest.com", "wikipedia.org", "github.com", "apple.com", "paypal.com", "wise.com", "payoneer.com",
            "stripe.com", "discord.com", "discord.gg", "telegram.org", "t.me", "whatsapp.com", "notion.so", "canva.com", "capcut.com",
            "trustpilot.com", "glassdoor.com", "indeed.com", "ziprecruiter.com", "upwork.com", "fiverr.com", "amazon.com", "shopify.com",
            "substack.com", "blogspot.com", "wordpress.com", "tumblr.com", "linktr.ee", "bit.ly", "forbes.com", "nerdwallet.com",
            "businessinsider.com", "cnbc.com", "wikihow.com", "bloomberg.com", "yahoo.com", "microsoft.com", "gmail.com", "outlook.com",
            // job boards (the job finder already covers these) and Q&A sites or publications: never a gig platform themselves
            "mediabistro.com", "workingnomads.com", "flexjobs.com", "dedyn.io", "jobleads.com", "remoteok.com", "weworkremotely.com",
            "himalayas.app", "remotive.com", "jobicy.com", "simplyhired.com", "careerjunction.co.za", "pnet.co.za", "careers24.com",
            "adzuna.com", "jooble.org", "talent.com", "jobrapido.com", "monster.com", "graduates24.com", "prosple.com",
            "careervillage.org", "stackexchange.com", "stackoverflow.com", "digiday.com", "techcrunch.com", "mashable.com",
            "freelancer.com", "peopleperhour.com", "toptal.com", "join.com", "guru.com", "hubspot.com", "entrepreneur.com", "investopedia.com", "bankrate.com", "thebalancemoney.com");

    static boolean ignored(String domain) {
        if (domain == null || domain.isBlank()) return true;
        for (String d : IGNORED) if (domain.equals(d) || domain.endsWith("." + d)) return true;
        return domain.endsWith(".gov") || domain.endsWith(".edu");
    }

    /** Titles that describe many platforms or give advice, rather than being one platform's own page. */
    private static final Pattern ARTICLE = Pattern.compile(
            "\\b(best|top)\\b|\\b\\d+\\s+(?:\\w+\\s+){0,2}(ways|sites|platforms|apps|companies|programs|jobs|websites|places|tools)\\b|how to|guide|review|\\bvs\\.?\\b|\\bis\\b.{0,40}\\b(legit|scam|worth)\\b|what is|explained",
            Pattern.CASE_INSENSITIVE);

    static boolean looksLikeArticle(String title) { return title != null && (ARTICLE.matcher(title).find() || title.trim().endsWith("?") || title.toLowerCase(Locale.ROOT).startsWith("does anyone")); }

    /** Blog posts, news, help-centre and wiki pages, forums and Q&A: pages about platforms, not a platform's own sign-up page. */
    private static final Pattern NON_PLATFORM_PATH = Pattern.compile(
            "/(blog|blogs|post|posts|article|articles|news|creator-news|guide|guides|learn|resources?|help|support|wiki|forum|forums|community|questions?|answers?|s/article|hc/[a-z-]+/articles|how-to[^/]*)(/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_PLATFORM_HOST = Pattern.compile("^(help|support|blog|wiki|forum|forums|community|docs|news|learn)\\.", Pattern.CASE_INSENSITIVE);

    static boolean nonPlatformUrl(String url) {
        try {
            URI u = URI.create(url.trim());
            String host = u.getHost() == null ? "" : u.getHost();
            return NON_PLATFORM_HOST.matcher(host).find() || (u.getPath() != null && NON_PLATFORM_PATH.matcher(u.getPath()).find());
        } catch (Exception e) {
            return false;
        }
    }

    private static final Pattern DOMAIN_IN_TEXT = Pattern.compile(
            "\\b((?:[a-z0-9][a-z0-9-]{1,40}\\.)+(?:com|co|io|social|app|net|org|ai|me|gg|so|tv|xyz|live|link|club|online|site|co\\.za|co\\.uk|us|ly|fm|pro))\\b",
            Pattern.CASE_INSENSITIVE);

    static List<String> domainsIn(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = DOMAIN_IN_TEXT.matcher(text == null ? "" : text);
        while (m.find()) {
            String d = m.group(1).toLowerCase(Locale.ROOT);
            if (d.startsWith("www.")) d = d.substring(4);
            if (!ignored(d)) out.add(d);
        }
        return new ArrayList<>(out);
    }

    /**
     * Turns search results into one lead per domain. A result from the platform's own site is a direct lead. A result from
     * Reddit, YouTube, TikTok or an article is only mined for the platform domains it names; each mention counts.
     */
    static List<Lead> leads(List<Hit> hits) {
        Map<String, Lead> byDomain = new LinkedHashMap<>();
        for (Hit h : hits) {
            String d = domainOf(h.link());
            if (d.isEmpty()) continue;
            if (ignored(d) || looksLikeArticle(h.title()) || nonPlatformUrl(h.link())) {
                for (String c : domainsIn(h.title() + " " + h.snippet())) {
                    if (c.equals(d)) continue;
                    Lead l = byDomain.computeIfAbsent(c, k -> { Lead n = new Lead(); n.domain = k; n.url = "https://" + k + "/"; return n; });
                    l.mentions++;
                    if (!l.via.contains(h.query())) l.via.add(h.query());
                }
            } else {
                Lead l = byDomain.computeIfAbsent(d, k -> { Lead n = new Lead(); n.domain = k; return n; });
                l.mentions++;
                if (!l.direct) { l.direct = true; l.url = h.link(); l.name = h.title(); l.snippet = h.snippet(); }
                if (!l.via.contains(h.query())) l.via.add(h.query());
            }
        }
        List<Lead> out = new ArrayList<>(byDomain.values());
        out.sort((a, b) -> b.mentions != a.mentions ? b.mentions - a.mentions : Boolean.compare(b.direct, a.direct));
        return out;
    }

    // ------------------------------------------------------------------ robots.txt

    /** Reads a robots.txt. We only ever ask for pages a normal visitor can open, and we do not open what a site disallows. */
    static final class Robots {
        private Robots() {}

        static boolean allows(String robotsTxt, String agent, String path) {
            if (robotsTxt == null || robotsTxt.isBlank()) return true;
            List<List<String>> agents = new ArrayList<>();
            List<List<String[]>> rules = new ArrayList<>();
            List<String> curAgents = null;
            List<String[]> curRules = null;
            boolean sawRule = true;
            for (String line : robotsTxt.split("\\r?\\n")) {
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).trim().toLowerCase(Locale.ROOT), v = line.substring(c + 1).trim();
                if (k.equals("user-agent")) {
                    if (sawRule || curAgents == null) { curAgents = new ArrayList<>(); curRules = new ArrayList<>(); agents.add(curAgents); rules.add(curRules); sawRule = false; }
                    curAgents.add(v.toLowerCase(Locale.ROOT));
                } else if ((k.equals("allow") || k.equals("disallow")) && curRules != null) {
                    sawRule = true;
                    if (!v.isEmpty()) curRules.add(new String[]{k, v});
                }
            }
            String me = agent.toLowerCase(Locale.ROOT);
            int pick = -1;
            for (int i = 0; i < agents.size(); i++) for (String a : agents.get(i)) if (!a.equals("*") && me.contains(a)) pick = i;
            if (pick < 0) for (int i = 0; i < agents.size(); i++) if (agents.get(i).contains("*")) pick = i;
            if (pick < 0) return true;
            int bestLen = -1;
            boolean allowed = true;
            for (String[] r : rules.get(pick)) {
                int len = matchLength(r[1], path);
                if (len < 0) continue;
                boolean allow = r[0].equals("allow");
                if (len > bestLen || (len == bestLen && allow)) { bestLen = len; allowed = allow; }
            }
            return allowed;
        }

        /** Length of the rule when it matches the path (supports * and a trailing $), else -1. */
        private static int matchLength(String rule, String path) {
            StringBuilder re = new StringBuilder("^");
            for (int i = 0; i < rule.length(); i++) {
                char ch = rule.charAt(i);
                if (ch == '*') re.append(".*");
                else if (ch == '$' && i == rule.length() - 1) re.append("$");
                else re.append(Pattern.quote(String.valueOf(ch)));
            }
            return Pattern.compile(re.toString()).matcher(path).find() ? rule.length() : -1;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}

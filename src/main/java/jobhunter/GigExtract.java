package jobhunter;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the facts you care about out of a platform's own public page (and the search snippet that led to it).
 * Pure function: nothing here touches the network. A fact the page does not state stays unknown; nothing is guessed,
 * and each fact that is found keeps the words it came from in {@link Gig#evidence}.
 */
final class GigExtract {
    private GigExtract() {}

    private static Pattern re(String r) { return Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.DOTALL); }

    // ---- what kind of gig ----
    private static final Map<String, Pattern> CATEGORY = new LinkedHashMap<>();
    static {
        CATEGORY.put(Gig.UGC, re("\\bugc\\b|user[- ]generated|brand (campaigns?|deals?|partnerships?)|creator (program|retainer|campaigns?)|paid to (post|create) (short|videos?|content)|post(ing)? (short[- ]form )?(videos?|content) (on|to) (tiktok|instagram|reels)"));
        CATEGORY.put(Gig.CLIPPING, re("\\bclipp(ing|er|ers)\\b|\\bclips?\\b.{0,30}\\b(per|pay|paid|views?)\\b|faceless|themed page|fan page|\\brepost"));
        CATEGORY.put(Gig.AI_MICRO, re("data annotation|\\bannotat(or|ors|ion)\\b|\\bai (trainer|training)\\b|data label+ing|micro-?tasks?|\\brater\\b|\\brlhf\\b|transcri(be|ption)"));
        CATEGORY.put(Gig.POSTING_VA, re("social media (posting|manager|management|assistant)|virtual assistant|\\bva\\b|schedul(e|ing) posts|community manag"));
        CATEGORY.put(Gig.REFERRAL, re("\\brefer(ral|rals|ring)?\\b.{0,20}\\b(earn|bonus|commission|reward|program)|affiliate|ambassador|invite (friends|others)|per referral"));
    }

    // ---- how it pays ----
    private static final Pattern RETAINER = re("\\bretainers?\\b|monthly (pay|payment|fee|base)|base (pay|salary|rate)|fixed (monthly )?(pay|rate|fee)");
    private static final Pattern PER_VIEW = re("per (1[, ]?000|thousand|1k|k) views?|per views?\\b|\\bcpm\\b|\\brpm\\b|view (bonus|bonuses|based)|pay(s|ment)? per view|bonus(es)? (for|per) views?");
    private static final Pattern PER_TASK = re("per (task|video|post|clip|hour|review|survey|assignment|project|annotation)\\b|\\bpaid per\\b|per approved");
    private static final Pattern REFERRAL_PAY = re("referral (bonus|commission|fee|reward)s?|per referral|commission on referrals|for (each|every) (friend|referral|person) (you )?(refer|invite)");

    private static final Pattern THRESHOLD = re(
            "(?:minimum|min\\.?|threshold)[^.]{0,40}?(?:payout|payment|withdraw(?:al)?|cash ?out)[^.]{0,40}?((?:\\$|usd|us\\$|€|£|zar)\\s?\\d[\\d,.]*)"
          + "|(?:payout|withdraw(?:al)?|cash ?out)[^.]{0,20}?(?:minimum|min\\.?|threshold)[^.]{0,30}?((?:\\$|usd|us\\$|€|£|zar)\\s?\\d[\\d,.]*)"
          + "|(?:withdraw|cash out|get paid)[^.]{0,30}?(?:from|at|once you (?:reach|hit)|when you (?:reach|hit)|after)\\s*((?:\\$|usd|us\\$|€|£|zar)\\s?\\d[\\d,.]*)");

    private static final String[][] METHODS = {
            {"PayPal", "paypal"}, {"Payoneer", "payoneer"}, {"Wise", "\\bwise\\b|transferwise"},
            {"Bank transfer", "bank transfer|direct deposit|\\bach\\b|\\bswift\\b|\\beft\\b|wire transfer|bank account"},
            {"Crypto", "bitcoin|\\bbtc\\b|\\busdt\\b|\\busdc\\b|ethereum|crypto(currency|currencies)?\\b"},
            {"Cash App", "cash ?app"}, {"Venmo", "venmo"}, {"Zelle", "zelle"}, {"Skrill", "skrill"}, {"Stripe", "stripe"},
            {"Gift cards", "gift cards?"}, {"Mobile money", "mobile money|m-?pesa"}, {"PayFast", "payfast"}};

    // ---- who can join ----
    private static final Pattern NO_FOLLOWERS = re("no (minimum )?followers?( are)?( required| needed| necessary)?|\\b0 followers\\b|zero followers|without (a )?(following|followers)|don'?t need (any )?followers|no audience");
    private static final Pattern NO_EXPERIENCE = re("no (prior |previous )?experience( is)?( required| needed| necessary)?|beginner[- ]friendly|no (skills|portfolio) (required|needed)|anyone can (join|apply|start)");
    private static final Pattern MIN_FOLLOWERS = re("(?:at least|minimum(?: of)?|must have|need(?: at least)?|require[sd]?|over)\\s+(\\d[\\d,.]*)\\s?(k|m)?\\+?\\s+(?:followers|subscribers)");
    private static final Pattern YEARS_EXP = re("(\\d{1,2})\\+?\\s*years?[^.]{0,20}experience");

    private static final Pattern SA = re("south africa|south african");
    private static final Pattern SA_EXCLUDED = re("(?:not|isn'?t|aren'?t|unavailable|excluded|restricted|except|excluding|outside)[^.]{0,80}south africa|south africa[^.]{0,60}(?:not (?:available|supported|eligible|accepted)|excluded|restricted|unavailable)");
    private static final Pattern WORLDWIDE = re("\\b(worldwide|world-wide|globally|global creators|anywhere in the world|all countries|any country|from anywhere|international creators)\\b");
    private static final Pattern ONLY_IN = re("(?:only|exclusively|solely)\\s+(?:available\\s+|open\\s+|accepting\\s+)?(?:to|for|in|from)\\s+([^.]{3,100})|([^.]{3,60}?)\\s+(?:residents|citizens|creators|users|applicants)\\s+only|must (?:be|live|reside) (?:in|located in|a resident of)\\s+([^.]{3,80})");
    private static final Pattern AVAILABLE_IN = re("(?:available|open|accepting|eligible|supported|operat\\w*|launch\\w*|currently)[^.]{0,30}\\b(?:in|to|for|across)\\s+([^.]{5,160})");
    private static final Pattern US_WORD = Pattern.compile("\\b(US|USA|U\\.S\\.A?\\.?)\\b");          // case-sensitive: "join us" is not a country
    private static final List<Pattern> COUNTRY_WORDS = new ArrayList<>();
    static {
        for (String c : new String[]{"united states", "united kingdom", "\\buk\\b", "canada", "australia", "new zealand", "ireland", "germany",
                "france", "spain", "italy", "netherlands", "india", "philippines", "nigeria", "kenya", "ghana", "mexico", "brazil",
                "\\buae\\b", "singapore", "europe", "\\beu\\b", "north america"})
            COUNTRY_WORDS.add(Pattern.compile(c.startsWith("\\") ? c : "\\b" + c + "\\b", Pattern.CASE_INSENSITIVE));
    }

    // ---- cost ----
    private static final Pattern FREE = re("free to (join|sign ?up|start|use|apply)|100% free|join for free|sign ?up for free|no (cost|fee|fees|charge)( to (join|start|sign ?up))?|completely free|costs? nothing|free (account|membership)");
    private static final Pattern PAID = re("(?:\\$|usd|€|£)\\s?\\d+[^.]{0,20}(?:to join|membership|registration|sign[- ]?up fee|enrol|enroll|access fee|one[- ]time fee|monthly fee)|(?:membership|registration|enrol(?:l)?ment|signup|starter|access) fee|starter kit|\\bpay (?:a|an|the)? ?(?:small )?(?:fee|deposit)");

    private static final Pattern HOURS_RANGE = re("(\\d{1,3})\\s?(?:-|–|to)\\s?(\\d{1,3})\\s?(?:hours?|hrs?)\\s?(?:a|per|/|each)\\s?week");
    private static final Pattern HOURS_ONE = re("(\\d{1,3})\\+?\\s?(?:hours?|hrs?)\\s?(?:a|per|/|each)\\s?week");
    private static final Pattern FLEXIBLE = re("flexible (hours|schedule)|(?:own|your own) (hours|schedule|time)|work (anytime|whenever|when you want)|at your own pace|set your own (hours|schedule)|no (fixed|set) (hours|schedule)|on your own schedule");
    private static final Pattern UNLIMITED = re("as many (videos|posts|clips|hours|tasks) as you (want|like|can)|unlimited (videos|posts|clips|hours|tasks)");

    private static final Pattern WHAT = re("(?:get paid to|earn (?:money )?(?:by|for|when)|you (?:will|'ll|can) (?:create|post|film|record|clip|share|edit|label|annotate|review|promote)|post(?:ing)? (?:short|videos|content)|create (?:short|videos|content))[^.!?]{0,160}");

    private static final Pattern TITLE = re("<title[^>]*>(.*?)</title>");
    private static final Pattern TERMS = re("href=[\"'][^\"']*(?:terms|tos|conditions)[^\"']*[\"']|terms (?:of (?:service|use)|(?:&amp;|&|and) conditions)");
    private static final Pattern PRIVACY = re("href=[\"'][^\"']*privacy[^\"']*[\"']|privacy (?:policy|notice)");
    private static final Pattern CONTACT = re("mailto:|href=[\"'][^\"']*contact[^\"']*[\"']|contact us|\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b|registered (?:in|office|company)|©\\s?\\d{4}\\s+[A-Za-z][^.<]{2,40}\\b(?:ltd|llc|inc|pty|gmbh|limited|corp)\\b");

    /**
     * @param hintName a name from the search result, used when the page gives none
     * @param html     the page (may be empty if it could not be read)
     * @param snippet  the search-result text that led here (may be empty)
     */
    static Gig extract(String url, String hintName, String html, String snippet) {
        Gig g = new Gig();
        g.url = url == null ? "" : url;
        g.domain = GigDiscovery.domainOf(g.url);
        g.id = Gig.idFor(g.domain);
        String raw = html == null ? "" : html.replaceAll("(?is)<script.*?</script>|<style.*?</style>|<!--.*?-->", " ");
        g.pageFetched = !raw.isBlank();
        String snip = snippet == null ? "" : snippet.trim();
        g.snippet = snip.length() > 300 ? snip.substring(0, 300) : snip;

        String title = group(TITLE, raw), desc = meta(raw, "description"), site = meta(raw, "og:site_name");
        String body = Text.stripHtml(raw);
        String text = (title + ". " + desc + ". " + body + " " + snip).replaceAll("\\s+", " ").trim();

        g.name = pickName(site, title, hintName, g.domain);

        // links and company details: only judged from the page itself
        if (g.pageFetched) {
            g.hasTerms = TERMS.matcher(raw).find();
            g.hasPrivacy = PRIVACY.matcher(raw).find();
            g.hasContact = CONTACT.matcher(raw).find();
        }

        categories(g, text);
        payModels(g, text);
        payout(g, text);
        requirements(g, text);
        countries(g, text);
        cost(g, text);
        hours(g, text);

        Matcher w = WHAT.matcher(text);
        if (w.find()) { g.whatYouDo = cap(w.group().trim(), 200); g.evidence.put("whatYouDo", around(text, w)); }
        else if (!desc.isBlank()) g.whatYouDo = cap(desc, 200);
        else g.whatYouDo = cap(snip, 200);

        if (re("discord").matcher(text).find()) g.support = "Discord community";
        else if (re("telegram").matcher(text).find()) g.support = "Telegram community";
        return g;
    }

    // ------------------------------------------------------------------ pieces

    private static void categories(Gig g, String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Pattern> e : CATEGORY.entrySet()) {
            Matcher m = e.getValue().matcher(text);
            int n = 0;
            while (m.find()) n++;
            if (n > 0) counts.put(e.getKey(), n);
        }
        List<String> order = new ArrayList<>(counts.keySet());
        order.sort((a, b) -> counts.get(b) - counts.get(a));            // stable: ties keep the order above
        g.categories = order;
        g.category = order.isEmpty() ? Gig.OTHER : order.get(0);
    }

    private static void payModels(Gig g, String text) {
        add(g, text, "RETAINER", RETAINER);
        add(g, text, "PER_VIEW", PER_VIEW);
        add(g, text, "PER_TASK", PER_TASK);
        add(g, text, "REFERRAL", REFERRAL_PAY);
    }

    private static void add(Gig g, String text, String model, Pattern p) {
        Matcher m = p.matcher(text);
        if (m.find()) { g.payModels.add(model); g.evidence.put("pay:" + model, around(text, m)); }
    }

    private static void payout(Gig g, String text) {
        Matcher t = THRESHOLD.matcher(text);
        if (t.find()) {
            for (int i = 1; i <= t.groupCount(); i++) if (t.group(i) != null) { g.payoutThreshold = t.group(i).trim().replaceAll("[.,]+$", ""); break; }
            g.evidence.put("payoutThreshold", around(text, t));
        }
        // a payment brand only counts as a payout method when the words around it are about paying YOU
        // (a site that merely takes card payments through Stripe does not pay out through Stripe)
        for (String[] m : METHODS) {
            Matcher mm = re(m[1]).matcher(text);
            while (mm.find()) {
                int a = Math.max(0, mm.start() - 90), b = Math.min(text.length(), mm.end() + 90);
                if (PAYING_YOU.matcher(text.substring(a, b)).find()) {
                    g.payoutMethods.add(m[0]);
                    g.evidence.putIfAbsent("payoutMethod:" + m[0], around(text, mm));
                    break;
                }
            }
        }
    }

    private static final Pattern PAYING_YOU = re("\\bpay ?outs?\\b|\\bpaid\\b|withdraw\\w*|cash ?out|\\bwe pay\\b|payments? (?:are )?(?:sent|made|issued|released)|you(?:'ll)? (?:get|receive) (?:paid|your)|\\bearnings\\b");

    private static void requirements(Gig g, String text) {
        List<String> parts = new ArrayList<>();
        Matcher nf = NO_FOLLOWERS.matcher(text);
        if (nf.find()) { g.noFollowersRequired = true; parts.add("No followers required"); g.evidence.put("noFollowers", around(text, nf)); }
        Matcher ne = NO_EXPERIENCE.matcher(text);
        if (ne.find()) { g.noExperienceRequired = true; parts.add("No experience needed"); g.evidence.put("noExperience", around(text, ne)); }
        Matcher mf = MIN_FOLLOWERS.matcher(text);
        if (mf.find()) {
            g.minFollowers = parseCount(mf.group(1), mf.group(2));
            if (g.minFollowers > 0) {
                parts.add("Needs at least " + mf.group(1) + (mf.group(2) == null ? "" : mf.group(2)) + " followers");
                g.evidence.put("minFollowers", around(text, mf));
                g.noFollowersRequired = false;                           // a stated minimum beats a vague "no followers"
            }
        }
        Matcher ye = YEARS_EXP.matcher(text);
        if (ye.find()) { parts.add("Asks for " + ye.group(1) + "+ years of experience"); g.noExperienceRequired = false; g.evidence.put("experience", around(text, ye)); }
        g.requirements = String.join("; ", parts);
    }

    static int parseCount(String num, String unit) {
        try {
            double v = Double.parseDouble(num.replace(",", ""));
            if (unit != null && unit.equalsIgnoreCase("k")) v *= 1_000;
            if (unit != null && unit.equalsIgnoreCase("m")) v *= 1_000_000;
            return (int) Math.min(v, Integer.MAX_VALUE);
        } catch (NumberFormatException e) { return 0; }
    }

    /** Whether South Africa can join, judged only from what the page says. */
    private static void countries(Gig g, String text) {
        Matcher ex = SA_EXCLUDED.matcher(text);
        if (ex.find()) {
            g.eligibility = Gig.EXCLUDED; g.eligibilityWhy = "The page says South Africa is not supported"; g.countries = around(text, ex);
            g.evidence.put("countries", g.countries);
            return;
        }
        Matcher sa = SA.matcher(text);
        if (sa.find()) {
            g.eligibility = Gig.OK; g.eligibilityWhy = "The page names South Africa"; g.countries = around(text, sa);
            g.evidence.put("countries", g.countries);
            return;
        }
        Matcher only = ONLY_IN.matcher(text);
        while (only.find()) {
            String grp = firstGroup(only);
            if (grp != null && countryCount(grp) >= 1 && !WORLDWIDE.matcher(grp).find()) {
                g.eligibility = Gig.EXCLUDED; g.eligibilityWhy = "Limited to other countries: " + cap(grp.trim(), 80);
                g.countries = around(text, only); g.evidence.put("countries", g.countries);
                return;
            }
        }
        Matcher av = AVAILABLE_IN.matcher(text);
        while (av.find()) {
            String grp = av.group(1);
            if (countryCount(grp) >= 2 && !WORLDWIDE.matcher(grp).find()) {
                g.eligibility = Gig.EXCLUDED; g.eligibilityWhy = "Lists countries that do not include South Africa: " + cap(grp.trim(), 80);
                g.countries = around(text, av); g.evidence.put("countries", g.countries);
                return;
            }
        }
        Matcher ww = WORLDWIDE.matcher(text);
        if (ww.find()) {
            g.eligibility = Gig.OK; g.eligibilityWhy = "Says it is open worldwide (confirm South Africa when you sign up)";
            g.countries = around(text, ww); g.evidence.put("countries", g.countries);
            return;
        }
        g.eligibility = Gig.UNKNOWN; g.eligibilityWhy = "The page does not say which countries can join";
    }

    private static String firstGroup(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) if (m.group(i) != null) return m.group(i);
        return null;
    }

    static int countryCount(String s) {
        int n = US_WORD.matcher(s).find() ? 1 : 0;
        for (Pattern p : COUNTRY_WORDS) if (p.matcher(s).find()) n++;
        return n;
    }

    private static void cost(Gig g, String text) {
        Matcher paid = PAID.matcher(text);
        if (paid.find()) { g.costToJoin = Gig.PAID; g.evidence.put("cost", around(text, paid)); return; }
        Matcher free = FREE.matcher(text);
        if (free.find()) { g.costToJoin = Gig.FREE; g.evidence.put("cost", around(text, free)); }
    }

    private static void hours(Gig g, String text) {
        Matcher r = HOURS_RANGE.matcher(text);
        if (r.find()) {
            g.hours = r.group(1) + "-" + r.group(2) + " hours a week"; g.hoursMax = Integer.parseInt(r.group(2));
            g.evidence.put("hours", around(text, r));
        } else {
            Matcher o = HOURS_ONE.matcher(text);
            if (o.find()) { g.hours = o.group(1) + " hours a week"; g.hoursMax = Integer.parseInt(o.group(1)); g.evidence.put("hours", around(text, o)); }
        }
        Matcher f = FLEXIBLE.matcher(text);
        if (f.find()) { g.flexibleHours = true; g.evidence.put("flexibleHours", around(text, f)); if (g.hours.isEmpty()) g.hours = "flexible"; }
        Matcher u = UNLIMITED.matcher(text);
        if (u.find()) { if (g.hoursMax < 40) g.hoursMax = 40; if (g.hours.isEmpty()) g.hours = "as many as you want"; g.evidence.put("hoursUnlimited", around(text, u)); }
    }

    // ------------------------------------------------------------------ helpers

    private static String pickName(String site, String title, String hint, String domain) {
        // a name is short: a whole headline ("Does anyone know of platforms that...") is not a name
        for (String c : new String[]{site, firstSegment(title), hint}) {
            if (c != null && !c.isBlank() && c.length() <= 40 && c.trim().split("\\s+").length <= 5 && !c.contains("?")) return c.trim();
        }
        String label = domain.contains(".") ? domain.substring(0, domain.indexOf('.')) : domain;
        return label.isEmpty() ? domain : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private static String firstSegment(String title) {
        if (title == null) return "";
        String[] parts = Text.unescape(title).trim().split("\\s*[|–—:]\\s*|\\s+-\\s+");
        return parts.length == 0 ? "" : parts[0].trim();
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? Text.unescape(m.group(1)).replaceAll("\\s+", " ").trim() : "";
    }

    private static String meta(String raw, String key) {
        Matcher m = re("<meta[^>]+(?:name|property)=[\"']" + Pattern.quote(key) + "[\"'][^>]*content=[\"']([^\"']*)[\"']").matcher(raw);
        if (m.find()) return Text.unescape(m.group(1)).trim();
        m = re("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:name|property)=[\"']" + Pattern.quote(key) + "[\"']").matcher(raw);
        return m.find() ? Text.unescape(m.group(1)).trim() : "";
    }

    /** The words around a match, so the dashboard can show where a fact came from. */
    static String around(String text, Matcher m) {
        int a = Math.max(0, m.start() - 70), b = Math.min(text.length(), m.end() + 90);
        return (a > 0 ? "…" : "") + text.substring(a, b).trim() + (b < text.length() ? "…" : "");
    }

    private static String cap(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n).trim() + "…"; }
}

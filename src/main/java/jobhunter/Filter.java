package jobhunter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The deal-breaker gate. A job that trips any "reject" rule never reaches your queue.
 * "Flags" are softer warnings shown on the card so you can check before applying.
 */
final class Filter {
    record Result(List<String> rejects, List<String> flags) {
        boolean ok() { return rejects.isEmpty(); }
    }

    private record Rule(String label, Pattern re) {}

    private static Pattern re(String r) { return Pattern.compile(r, Pattern.CASE_INSENSITIVE); }
    private static Rule rule(String label, String r) { return new Rule(label, re(r)); }

    // ---------- scams and non-payers ----------
    private static final List<Rule> SCAM = List.of(
            rule("asks you to pay a fee or deposit", "\\b(registration|application|training|onboarding|admin(istration)?|processing|starter|background[- ]check|security|refundable|equipment|certification)\\s+(fee|deposit)\\b"),
            rule("asks you to pay a fee or deposit", "\\bpay (a|an|the|your)?\\s*(small\\s+)?(fee|deposit)\\b|\\b(fee|deposit) (is )?(required|to start|upfront|up-front)\\b|\\bbuy (your own )?(a )?(starter )?kit\\b"),
            rule("mentions gift cards or cheques", "\\bgift cards?\\b|\\b(cheque|check) (will be )?(sent|mailed)\\b|\\bdeposit (the|this|a) (cheque|check)\\b"),
            rule("recruits only through WhatsApp or Telegram", "\\b(whatsapp|telegram)\\b.{0,40}\\b(interview|recruit|apply|hiring)\\b|\\b(interview|apply|contact)\\b.{0,40}\\b(whatsapp|telegram)\\b"),
            rule("too-good-to-be-true income claim", "\\bearn\\s+\\$?\\d{3,}\\s*(usd)?\\s*(per|a|/)\\s*(day|hour)\\b|\\bguaranteed (income|earnings|salary)\\b|\\bno experience.{0,40}(\\$|usd)\\s?\\d{3,}|\\bmake money (online )?fast\\b"),
            rule("looks like MLM or network marketing", "\\bmlm\\b|\\bnetwork marketing\\b|\\bmulti-level\\b|\\bpassive income\\b")
    );

    private static final List<Rule> UNPAID = List.of(
            rule("unpaid or commission-only", "\\bunpaid\\s+(internship|position|role|trial|work|test|task|project|volunteer)\\b|\\bvolunteer (position|role|basis|work)\\b|\\bcommission[- ]only\\b|\\bequity[- ]only\\b|\\brevenue[- ]share only\\b|\\bno (salary|pay|compensation)\\b|\\bfor exposure\\b|\\b(free|unpaid) (trial|sample) (task|work|project)\\b")
    );

    // ---------- set hours ----------
    private static final List<Rule> FIXED = List.of(
            rule("fixed shifts", "\\b(night|day|evening|weekend|rotating|split|morning|afternoon|overnight)\\s+shifts?\\b|\\bshift[- ]based\\b|\\bshift work\\b"),
            rule("9-to-5 style hours", "\\b9\\s?(am)?\\s?(-|to|–)\\s?5\\s?(pm)?\\b|\\bnine to five\\b"),
            rule("Monday-to-Friday schedule", "\\bmon(day)?\\.?\\s?(-|to|through|–)\\s?fri(day)?\\b"),
            rule("business or core hours", "\\b(business|core|office) hours\\b"),
            rule("fixed or scheduled hours", "\\b(fixed|set|scheduled|regular) (work )?(hours|schedule|shifts)\\b"),
            rule("must be available at set times", "\\bmust be available (during|between|from|on)\\b|\\bavailability (during|between)\\b|\\bmust work (from|between|during)\\b"),
            rule("time-zone overlap required", "\\boverlap(ping)? (with|of|for)?.{0,40}(hours|time ?zone|\\best\\b|\\bpst\\b|\\bcst\\b|\\bcet\\b|\\bgmt\\b|\\butc\\b)"),
            rule("hours pinned to a time zone", "\\b\\d{1,2}\\s?(am|pm)?\\s?(-|to|–)\\s?\\d{1,2}\\s?(am|pm)\\s?(est|edt|pst|pdt|cst|cdt|cet|cest|gmt|bst|utc|et|pt)\\b"),
            rule("24/7 coverage or on-call", "\\b24/7\\b|\\bon[- ]call\\b")
    );
    static final Pattern FLEXIBLE = re("\\bflexible (hours|schedule|working|work hours|timing)|set your own (hours|schedule)|choose your (own )?(hours|schedule)|\\bown (hours|schedule)\\b|work (whenever|when you want|anytime)|\\basynchronous(ly)?\\b|\\basync\\b|at your own pace|on your own time");

    // ---------- calls ----------
    private static final List<Rule> CALLS = List.of(
            rule("phone or call-centre work", "\\bcall[- ]cent(er|re)s?\\b|\\b(inbound|outbound|cold) calls?\\b|\\bcold[- ]calling\\b|\\btele(sales|marketing)\\b|\\bphone (support|agent|sales|calls)\\b|\\bhandle (customer )?(phone )?calls\\b"),
            rule("requires regular live calls", "\\bdaily (stand-?ups?|calls|meetings|video)\\b|\\bon[- ]camera\\b|\\bvideo calls? (daily|every)\\b|\\bcamera (on|required)\\b")
    );
    private static final Pattern MEETINGS = re("\\b(meetings?|stand-?ups?|video calls?|zoom)\\b");

    // ---------- geography ----------
    static final Pattern GEO_OK = re("worldwide|anywhere|global|international|africa|south africa|\\bza\\b|any location|all countries");
    private static final Pattern GEO_EMEA = re("\\bemea\\b");
    private static final Pattern GEO_BAD_LOC = re("\\b(us|usa|u\\.s\\.a?\\.?|united states|america|americas|north america|canada|canadian|uk|united kingdom|england|europe|european|eu|eea|germany|france|netherlands|spain|italy|ireland|poland|portugal|australia|new zealand|india|philippines|brazil|latam|latin america|mexico|singapore|japan|china|israel|uae|dubai|asia|apac)\\b");
    private static final List<Rule> GEO_BAD_DESC = List.of(
            rule("only for residents of certain countries", "\\b(must|need to|should|have to)\\s+(currently\\s+)?(be\\s+)?(reside|live|located|based|living|residing)\\s+(in|within)\\s+(the\\s+)?(us|u\\.s\\.|usa|united states|uk|united kingdom|eu|europe|canada|australia|germany)\\b"),
            rule("only for residents of certain countries", "\\b(us|usa|u\\.s\\.|uk|eu|canada|canadian)\\s+(residents?|citizens?|based|only|applicants only)\\b"),
            rule("requires work authorisation in another country", "\\b(authori[sz]ed|eligible|legally entitled|right) to work in (the )?(us|u\\.s\\.|usa|united states|uk|united kingdom|eu|canada|europe)\\b"),
            rule("requires work authorisation in another country", "(?<!no )(?<!not )(?<!without )\\bwork authori[sz]ation\\b"),
            rule("only for residents of certain countries", "\\b(europe|eu|eea|us|usa|north america|latam|uk) only\\b")
    );

    // ---------- search results that are articles, not listings ----------
    private static final Pattern ARTICLE = re("^\\s*(\\d+|top|best|how to|ways to|what is|the \\d+)\\b|\\b(best|top)\\s+\\d*\\s*(remote|work from home|online|legit)?\\s*(jobs|sites|websites|platforms|companies|apps)\\b|\\bhow to\\b|\\bguide\\b|\\b\\d+\\s+(best|top|legit|ways)\\b|\\breviews?\\b|\\bvs\\.?\\b");

    /** A page that lists many jobs ("Grade 12 jobs in Work From Home", Indeed's q- search pages), not one job to apply for. */
    private static final Set<String> TRUSTED_BOARDS = Set.of("LinkedIn", "Indeed", "ZipRecruiter");
    private static final Pattern LISTING_TITLE = re("\\bjobs\\b");
    private static final Pattern LISTING_URL = re("[a-z]\\.indeed\\.[a-z.]+/q-|/jobs-search");

    /** Not a line of work for this tool. Kept as a plain rule so it is easy to see and easy to remove. */
    private static final Rule ADULT = rule("adult-content role", "\\bonlyfans\\b|\\bfansly\\b|\\badult (content|entertainment|chat)\\b|\\bnsfw\\b|\\bescort\\b|\\bwebcam model\\b");

    private final Profile profile;

    Filter(Profile profile) { this.profile = profile; }

    Result screen(Job j, Store store) {
        Set<String> rej = new LinkedHashSet<>();
        Set<String> flags = new LinkedHashSet<>();
        String loc = Text.norm(j.location);
        String body = j.title + "\n" + j.description + "\n" + String.join(" ", j.tags);

        // employer blocklist: yours plus the one the dashboard builds up
        String comp = Text.norm(j.company);
        boolean blocked = store != null && store.isBlocked(j.company);
        for (String b : profile.blockedEmployers) if (!comp.isEmpty() && comp.equals(Text.norm(b))) blocked = true;
        if (blocked) rej.add("Employer is on your blocklist");

        // search results need a sanity check first
        if ("SEARCH".equals(j.sourceType)) {
            if (ARTICLE.matcher(j.title).find()) rej.add("Looks like an article, not a job listing");
            // LinkedIn, Indeed and ZipRecruiter results were already narrowed to single-job pages (their titles end in "- Jobs")
            else if (!TRUSTED_BOARDS.contains(j.source) && (LISTING_TITLE.matcher(j.title).find() || LISTING_URL.matcher(Job.nz(j.url)).find())) rej.add("Looks like a list of jobs, not one listing");
            flags.add("Found by web search: open it and confirm it is a real, current listing");
        }

        if (ADULT.re.matcher(body).find()) rej.add("Adult-content role");

        if (profile.upfrontFees) for (Rule r : SCAM) if (r.re.matcher(body).find()) rej.add("Possible scam: " + r.label);
        if (profile.unpaidWork) for (Rule r : UNPAID) if (r.re.matcher(body).find()) rej.add(r.label.substring(0, 1).toUpperCase(Locale.ROOT) + r.label.substring(1));

        if (profile.fixedHours) {
            boolean flexible = FLEXIBLE.matcher(body).find();
            for (Rule r : FIXED) {
                if (!r.re.matcher(body).find()) continue;
                if (flexible) flags.add("Mentions " + r.label + " but also flexible hours: check");
                else rej.add("Set hours: " + r.label);
            }
        }

        if (profile.mandatoryCalls) {
            for (Rule r : CALLS) if (r.re.matcher(body).find()) rej.add("Calls: " + r.label);
            if (MEETINGS.matcher(body).find()) flags.add("Mentions meetings or calls: check how many");
        }

        if (profile.geoLocked) geography(j, loc, body, rej, flags);

        // age
        long age = ageDays(j.postedAt);
        if (age > profile.maxAgeDays) rej.add("Posting is " + age + " days old");

        // pay
        Double top = j.maxHourlyUsd != null ? j.maxHourlyUsd : j.minHourlyUsd;
        if (top != null) {
            double min = profile.minFor(j.workType);
            if (top < min) rej.add(String.format(Locale.ROOT, "Pay about $%.1f/hr is below your $%.0f/hr minimum for %s", top, min, j.workType));
        } else if ("BOARD".equals(j.sourceType)) {
            flags.add("Pay not stated: ask before you start");
        }

        return new Result(new ArrayList<>(rej), new ArrayList<>(flags));
    }

    private void geography(Job j, String loc, String body, Set<String> rej, Set<String> flags) {
        boolean locOk = GEO_OK.matcher(loc).find();
        if (loc.isBlank() || loc.equals("remote")) {
            if (!"SEARCH".equals(j.sourceType)) flags.add("Location not stated: confirm South Africa is allowed");
        } else if (GEO_EMEA.matcher(loc).find() && !locOk) {
            flags.add("Region is EMEA: confirm South Africa is allowed");
        } else if (!locOk && GEO_BAD_LOC.matcher(loc).find()) {
            rej.add("Location-locked: " + j.location);
        } else if (!locOk) {
            flags.add("Location says \"" + j.location + "\": confirm South Africa is allowed");
        }
        for (Rule r : GEO_BAD_DESC) if (r.re.matcher(body).find()) { rej.add("Location-locked: " + r.label); break; }
    }

    /** Whole days since the posting date, or 0 when the date is unknown. */
    static long ageDays(String postedAt) {
        if (postedAt == null || postedAt.isBlank()) return 0;
        try {
            Instant t;
            if (postedAt.length() == 10) t = LocalDate.parse(postedAt).atStartOfDay().toInstant(ZoneOffset.UTC);
            else t = OffsetDateTime.parse(postedAt).toInstant();
            return Math.max(0, ChronoUnit.DAYS.between(t, Instant.now()));
        } catch (Exception e) {
            try {
                return Math.max(0, ChronoUnit.DAYS.between(Instant.parse(postedAt), Instant.now()));
            } catch (Exception e2) {
                return 0;
            }
        }
    }
}

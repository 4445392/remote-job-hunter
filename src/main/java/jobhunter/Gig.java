package jobhunter;

import java.util.*;

/** One "simple gig" platform: a place that pays creators, posters or micro-taskers. Facts are only what the page or search results said. */
final class Gig {
    static final String NEW = "NEW", APPROVED = "APPROVED", JOINED = "JOINED", SKIPPED = "SKIPPED", REJECTED = "REJECTED";
    static final String OK = "OK", UNKNOWN = "UNKNOWN", EXCLUDED = "EXCLUDED", NONE = "NONE";
    static final String FREE = "FREE", PAID = "PAID";
    static final String UGC = "UGC", CLIPPING = "CLIPPING", POSTING_VA = "POSTING_VA", AI_MICRO = "AI_MICRO", REFERRAL = "REFERRAL", OTHER = "OTHER";

    String id = "", name = "", domain = "", url = "", snippet = "";
    String category = OTHER;                                      // main category
    List<String> categories = new ArrayList<>();                  // every category the text matched, strongest first
    List<String> foundVia = new ArrayList<>();                    // which searches surfaced it

    // extracted facts ("" or UNKNOWN when the page did not say)
    String whatYouDo = "", payoutThreshold = "", hours = "", requirements = "", countries = "", support = "";
    String costToJoin = UNKNOWN;                                  // FREE, PAID or UNKNOWN
    List<String> payModels = new ArrayList<>();                   // RETAINER, PER_VIEW, PER_TASK, REFERRAL
    List<String> payoutMethods = new ArrayList<>();
    boolean noFollowersRequired, noExperienceRequired, flexibleHours;
    int minFollowers, hoursMax;
    Map<String, String> evidence = new LinkedHashMap<>();         // fact -> the words on the page that say so

    // page and independent-review facts
    boolean pageFetched, hasTerms, hasPrivacy, hasContact;
    int reviewMentions, reviewNegative, reviewPositive, reviewProof;
    List<String> reviewSources = new ArrayList<>();

    // South Africa
    String eligibility = UNKNOWN, eligibilityWhy = "";            // OK, UNKNOWN, EXCLUDED
    String payoutFit = UNKNOWN;                                   // OK, UNKNOWN, NONE

    // scores
    int risk, fit;                                                // risk 0-10 (10 worst), fit 0-100
    boolean unverified;
    List<String> riskReasons = new ArrayList<>(), goodSigns = new ArrayList<>(), hardRejects = new ArrayList<>();
    String whyFits = "";

    // your side
    String draft = "", status = NEW, foundAt = "", statusAt = "", checkedAt = "";

    /** Higher is better: fit, minus 6 per risk point, with a bonus when South Africa is confirmed and a big penalty when excluded. */
    int rank() {
        int elig = OK.equals(eligibility) ? 10 : EXCLUDED.equals(eligibility) ? -50 : 0;
        return fit - risk * 6 + elig;
    }

    static String idFor(String domain) { return "g" + Text.sha(domain); }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("domain", domain); m.put("url", url); m.put("snippet", snippet);
        m.put("category", category); m.put("categories", categories); m.put("foundVia", foundVia);
        m.put("whatYouDo", whatYouDo); m.put("payoutThreshold", payoutThreshold); m.put("hours", hours);
        m.put("requirements", requirements); m.put("countries", countries); m.put("support", support);
        m.put("costToJoin", costToJoin); m.put("payModels", payModels); m.put("payoutMethods", payoutMethods);
        m.put("noFollowersRequired", noFollowersRequired); m.put("noExperienceRequired", noExperienceRequired);
        m.put("flexibleHours", flexibleHours); m.put("minFollowers", minFollowers); m.put("hoursMax", hoursMax);
        m.put("evidence", evidence);
        m.put("pageFetched", pageFetched); m.put("hasTerms", hasTerms); m.put("hasPrivacy", hasPrivacy); m.put("hasContact", hasContact);
        m.put("reviewMentions", reviewMentions); m.put("reviewNegative", reviewNegative);
        m.put("reviewPositive", reviewPositive); m.put("reviewProof", reviewProof); m.put("reviewSources", reviewSources);
        m.put("eligibility", eligibility); m.put("eligibilityWhy", eligibilityWhy); m.put("payoutFit", payoutFit);
        m.put("risk", risk); m.put("fit", fit); m.put("unverified", unverified);
        m.put("riskReasons", riskReasons); m.put("goodSigns", goodSigns); m.put("hardRejects", hardRejects);
        m.put("whyFits", whyFits); m.put("draft", draft); m.put("status", status);
        m.put("foundAt", foundAt); m.put("statusAt", statusAt); m.put("checkedAt", checkedAt);
        m.put("rank", rank());
        return m;
    }

    static Gig fromMap(Map<String, Object> m) {
        Gig g = new Gig();
        g.id = s(m, "id"); g.name = s(m, "name"); g.domain = s(m, "domain"); g.url = s(m, "url"); g.snippet = s(m, "snippet");
        g.category = s(m, "category"); if (g.category.isEmpty()) g.category = OTHER;
        g.categories = Json.strings(m.get("categories")); g.foundVia = Json.strings(m.get("foundVia"));
        g.whatYouDo = s(m, "whatYouDo"); g.payoutThreshold = s(m, "payoutThreshold"); g.hours = s(m, "hours");
        g.requirements = s(m, "requirements"); g.countries = s(m, "countries"); g.support = s(m, "support");
        g.costToJoin = s(m, "costToJoin"); if (g.costToJoin.isEmpty()) g.costToJoin = UNKNOWN;
        g.payModels = Json.strings(m.get("payModels")); g.payoutMethods = Json.strings(m.get("payoutMethods"));
        g.noFollowersRequired = Json.bool(m.get("noFollowersRequired"), false);
        g.noExperienceRequired = Json.bool(m.get("noExperienceRequired"), false);
        g.flexibleHours = Json.bool(m.get("flexibleHours"), false);
        g.minFollowers = n(m, "minFollowers"); g.hoursMax = n(m, "hoursMax");
        for (Map.Entry<String, Object> e : Json.obj(m.get("evidence")).entrySet()) g.evidence.put(e.getKey(), String.valueOf(Json.str(e.getValue())));
        g.pageFetched = Json.bool(m.get("pageFetched"), false); g.hasTerms = Json.bool(m.get("hasTerms"), false);
        g.hasPrivacy = Json.bool(m.get("hasPrivacy"), false); g.hasContact = Json.bool(m.get("hasContact"), false);
        g.reviewMentions = n(m, "reviewMentions"); g.reviewNegative = n(m, "reviewNegative");
        g.reviewPositive = n(m, "reviewPositive"); g.reviewProof = n(m, "reviewProof");
        g.reviewSources = Json.strings(m.get("reviewSources"));
        g.eligibility = s(m, "eligibility"); if (g.eligibility.isEmpty()) g.eligibility = UNKNOWN;
        g.eligibilityWhy = s(m, "eligibilityWhy");
        g.payoutFit = s(m, "payoutFit"); if (g.payoutFit.isEmpty()) g.payoutFit = UNKNOWN;
        g.risk = n(m, "risk"); g.fit = n(m, "fit"); g.unverified = Json.bool(m.get("unverified"), false);
        g.riskReasons = Json.strings(m.get("riskReasons")); g.goodSigns = Json.strings(m.get("goodSigns"));
        g.hardRejects = Json.strings(m.get("hardRejects"));
        g.whyFits = s(m, "whyFits"); g.draft = s(m, "draft");
        g.status = s(m, "status"); if (g.status.isEmpty()) g.status = NEW;
        g.foundAt = s(m, "foundAt"); g.statusAt = s(m, "statusAt"); g.checkedAt = s(m, "checkedAt");
        return g;
    }

    private static String s(Map<String, Object> m, String k) { String v = Json.str(m.get(k)); return v == null ? "" : v; }
    private static int n(Map<String, Object> m, String k) { Double d = Json.num(m.get(k)); return d == null ? 0 : d.intValue(); }
}

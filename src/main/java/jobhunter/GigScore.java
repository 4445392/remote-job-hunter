package jobhunter;

import java.util.*;

/** South Africa and payout checks, the fit score, the one-line "why it fits you", and the draft message. Pure functions. */
final class GigScore {
    private GigScore() {}

    static String categoryLabel(String c) {
        return switch (c) {
            case Gig.UGC -> "UGC / short-form creator";
            case Gig.CLIPPING -> "content clipper";
            case Gig.POSTING_VA -> "social media posting / virtual assistant";
            case Gig.AI_MICRO -> "AI training / data annotation";
            case Gig.REFERRAL -> "referral partner";
            default -> "online gig";
        };
    }

    // ------------------------------------------------------------------ South Africa + payout

    /**
     * Combines what the page said about countries with whether any stated payout method works from South Africa
     * (the list lives in config/profile.json as gigPayoutMethods). A gig that only pays by methods you cannot use is excluded.
     */
    static void eligibility(Gig g, Profile p) {
        Set<String> usable = new HashSet<>();
        for (String m : p.gigPayoutMethods) usable.add(m.toLowerCase(Locale.ROOT).trim());
        if (g.payoutMethods.isEmpty()) { g.payoutFit = Gig.UNKNOWN; return; }
        List<String> works = new ArrayList<>();
        for (String m : g.payoutMethods) if (usable.contains(m.toLowerCase(Locale.ROOT))) works.add(m);
        if (!works.isEmpty()) { g.payoutFit = Gig.OK; return; }
        g.payoutFit = Gig.NONE;
        g.eligibility = Gig.EXCLUDED;
        String why = "Pays only by " + String.join(", ", g.payoutMethods) + ", which you cannot use from South Africa";
        g.eligibilityWhy = g.eligibilityWhy.isEmpty() ? why : g.eligibilityWhy + ". " + why;
    }

    static List<String> usableMethods(Gig g, Profile p) {
        Set<String> usable = new HashSet<>();
        for (String m : p.gigPayoutMethods) usable.add(m.toLowerCase(Locale.ROOT).trim());
        List<String> out = new ArrayList<>();
        for (String m : g.payoutMethods) if (usable.contains(m.toLowerCase(Locale.ROOT))) out.add(m);
        return out;
    }

    // ------------------------------------------------------------------ fit

    /** 0-100: how well this gig suits a South African student who wants free, no-experience, flexible online work. Sets fit and whyFits. */
    static void fit(Gig g, Profile p) {
        int s = 0;
        s += g.costToJoin.equals(Gig.FREE) ? 20 : g.costToJoin.equals(Gig.UNKNOWN) ? 8 : 0;
        if (g.minFollowers > 0) s += 0;
        else s += (g.noFollowersRequired ? 12 : 6) + (g.noExperienceRequired ? 8 : 0);
        s += g.eligibility.equals(Gig.OK) ? 15 : g.eligibility.equals(Gig.UNKNOWN) ? 5 : 0;
        s += g.payoutFit.equals(Gig.OK) ? 15 : g.payoutFit.equals(Gig.UNKNOWN) ? 5 : 0;
        if (g.flexibleHours) s += 6;
        s += g.hoursMax >= p.hoursPerWeek ? 6 : g.hoursMax >= 10 ? 4 : g.hoursMax > 0 ? 2 : 3;
        s += g.payModels.isEmpty() ? 0 : 8;
        s += (g.category.equals(Gig.AI_MICRO) || g.category.equals(Gig.POSTING_VA)) ? 6 : g.category.equals(Gig.OTHER) ? 0 : 4;
        g.fit = Math.max(0, Math.min(100, s));
        g.whyFits = why(g, p);
    }

    private static String why(Gig g, Profile p) {
        List<String> parts = new ArrayList<>();
        if (g.costToJoin.equals(Gig.FREE)) parts.add("free to join");
        if (g.noFollowersRequired) parts.add("no followers required");
        if (g.noExperienceRequired) parts.add("no experience needed");
        if (g.eligibility.equals(Gig.OK)) parts.add(g.eligibilityWhy.contains("names South Africa") ? "names South Africa" : "open worldwide");
        List<String> ok = usableMethods(g, p);
        if (!ok.isEmpty()) parts.add("pays via " + String.join("/", ok));
        if (!g.payModels.isEmpty()) parts.add(payLabel(g.payModels));
        if (g.flexibleHours) parts.add("your own hours");
        if (g.hoursMax >= p.hoursPerWeek) parts.add("room for " + p.hoursPerWeek + "+ hours a week");
        if (!g.support.isEmpty()) parts.add("training and help via " + g.support.replace(" community", ""));

        String head = categoryLabel(g.category);
        head = Character.toUpperCase(head.charAt(0)) + head.substring(1);
        String body = parts.isEmpty() ? "not enough published detail yet: read the page before deciding" : String.join(", ", parts);
        List<String> cautions = new ArrayList<>();
        if (g.eligibility.equals(Gig.UNKNOWN)) cautions.add("South Africa unconfirmed");
        if (g.payoutFit.equals(Gig.UNKNOWN)) cautions.add("payout method unconfirmed");
        if (g.minFollowers > 0) cautions.add("wants " + g.minFollowers + "+ followers");
        return head + ": " + body + (cautions.isEmpty() ? "." : " (" + String.join("; ", cautions) + ").");
    }

    static String payLabel(List<String> models) {
        List<String> out = new ArrayList<>();
        for (String m : models) out.add(switch (m) {
            case "RETAINER" -> "monthly retainer";
            case "PER_VIEW" -> "per-view bonus";
            case "PER_TASK" -> "pay per task";
            case "REFERRAL" -> "referral rewards";
            default -> m.toLowerCase(Locale.ROOT);
        });
        return "pays by " + String.join(" + ", out);
    }

    // ------------------------------------------------------------------ draft

    /** A short, honest sign-up message built from your profile and from what is still unknown about the platform. Never sent for you. */
    static String draft(Gig g, Profile p) {
        StringBuilder sb = new StringBuilder();
        sb.append("Subject: Joining ").append(g.name).append(" - ").append(p.name).append("\n\n");
        sb.append("Hello ").append(g.name).append(" team,\n\n");
        sb.append("I am ").append(p.name).append(", a BSc Computer Science student at the University of the Western Cape, based in South Africa");
        if (!p.timezone.isBlank()) sb.append(" (").append(p.timezone).append(")");
        sb.append(". I would like to join as a ").append(categoryLabel(g.category)).append(". I can give about ").append(p.hoursPerWeek)
          .append(" hours a week and I work independently on my own schedule.\n\n");

        List<String> qs = new ArrayList<>();
        if (!g.eligibility.equals(Gig.OK) || !g.eligibilityWhy.contains("names South Africa"))
            qs.add("Can creators in South Africa join and be paid?");
        if (g.payoutMethods.isEmpty()) qs.add("Which payout methods do you support (for example PayPal, Payoneer or Wise or bank transfer), and is there a minimum payout?");
        else if (g.payoutThreshold.isEmpty()) qs.add("What is the minimum payout, and how often are creators paid?");
        if (!g.costToJoin.equals(Gig.FREE)) qs.add("Please confirm there is no cost to join or to start.");
        if (g.pageFetched && (!g.hasTerms || !g.hasPrivacy)) qs.add("Where can I read your Terms and Privacy Policy?");
        if (g.payModels.isEmpty()) qs.add("How are creators paid (retainer, per view, per task)?");
        if (!qs.isEmpty()) {
            sb.append("Before I start, could you please confirm:\n");
            for (String q : qs) sb.append("- ").append(q).append("\n");
            sb.append("\n");
        }
        sb.append("Thank you for your time.\n\n").append(p.name).append("\n").append(p.email).append("\n");
        return sb.toString();
    }
}

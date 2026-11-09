package jobhunter;

import java.util.*;
import java.util.regex.Pattern;

/**
 * The scam and risk score for a gig platform: 0 (low) to 10 (high), always with the reasons.
 * Hard rejects (fees, paid courses or starter kits, crypto, bank logins or ID before a contract, guaranteed income) send
 * the score to 10. Everything else adds or removes points, and nothing is hidden: every point has a sentence.
 */
final class GigRisk {
    private GigRisk() {}

    private static Pattern re(String r) { return Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.DOTALL); }

    // ---- hard rejects ----
    private record Rule(String label, Pattern re) {}
    private static final List<Rule> HARD = List.of(
            new Rule("asks you to pay a fee or deposit", re("\\b(registration|application|training|onboarding|admin(istration)?|processing|starter|membership|enrol(l)?ment|access|activation|sign[- ]?up)\\s+(fee|deposit)\\b")),
            new Rule("asks you to pay a fee or deposit", re("\\bpay (a|an|the|your)?\\s*(small\\s+)?(fee|deposit)\\b|\\b(fee|deposit) (is )?(required|to start|upfront|up-front)\\b")),
            new Rule("sells a starter kit", re("\\b(buy|purchase|get) (your |the |our )?(starter )?kit\\b|starter (kit|pack)\\b")),
            new Rule("sells a paid course or coaching", re("\\b(course|masterclass|bootcamp|academy|mentorship|coaching( program)?|mastermind)\\b[^.]{0,60}(\\$|usd|€|£)\\s?\\d|(\\$|usd|€|£)\\s?\\d[^.]{0,40}\\b(course|masterclass|bootcamp|academy|mentorship|coaching)\\b")),
            new Rule("involves crypto", re("\\bcrypto(currency|currencies)?\\b|\\bbitcoin\\b|\\busdt\\b|\\busdc\\b|\\bethereum\\b|\\bnft\\b|\\bairdrop\\b|\\btoken (sale|presale)\\b|\\bweb3\\b")),
            new Rule("wants bank logins or ID before a contract", re("(bank|banking) (login|log-in|password|credentials|pin)\\b|share your (bank|online banking)|(send|upload|submit|provide)[^.]{0,30}\\b(id|passport|driver'?s licen[sc]e|identity document|selfie with)\\b[^.]{0,40}\\b(before|to start|upfront|first|prior to)|\\b(ssn|social security number)\\b")),
            // "guaranteed payment" or "guaranteed on-time payouts" are good signs; only promises of income itself count
            new Rule("promises guaranteed income", re("\\bguaranteed (income|earnings|salary|profits?|returns|\\$\\s?\\d)|(earn|make) \\$?\\d[\\d,]*k?\\+? (per|a|/) ?(day|week|month) guaranteed|risk[- ]free (income|earnings)|100% guaranteed (income|earnings|profit|results)|\\bget rich\\b"))
    );

    // ---- risk points ----
    private static final Pattern RECRUITING = re("\\brecruit(ing)? (others|people|members|friends)|\\bdownline\\b|\\byour team\\b.{0,30}\\b(commission|earn)|\\blevel \\d (commission|earnings)|network marketing|\\bmlm\\b|pyramid");
    private static final Pattern HYPE = re("limited (spots|slots|time)|act now|hurry|only \\d+ (spots|slots) left|once[- ]in[- ]a[- ]lifetime|life-?changing|quit your (9|job)|financial freedom|passive income|replace your (income|salary)");
    private static final Pattern EARN_CLAIM = re("earn(ing)? up to (\\$|usd|€|£)?\\s?\\d|make (\\$|usd|€|£)\\s?\\d[\\d,]*k?\\+? (per|a|/) ?(day|week|month)|\\$\\d[\\d,]*k? ?(/|per|a) ?(day|week|month)");

    /** Rules that would reject a platform outright. Empty list means none matched. */
    static List<String> hardRejects(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Rule r : HARD) if (r.re.matcher(text == null ? "" : text).find()) out.add(r.label);
        return new ArrayList<>(out);
    }

    /** Fills in risk, reasons, good signs and the "unverified" flag. {@code text} is everything read about the platform. */
    static void assess(Gig g, String text, Reviews rv) {
        g.reviewMentions = rv.mentions(); g.reviewNegative = rv.negative(); g.reviewPositive = rv.positive();
        g.reviewProof = rv.proof(); g.reviewSources = new ArrayList<>(rv.sources());
        g.riskReasons = new ArrayList<>(); g.goodSigns = new ArrayList<>();
        g.hardRejects = hardRejects(text);

        if (!g.hardRejects.isEmpty()) {
            g.risk = 10;
            for (String h : g.hardRejects) g.riskReasons.add("Rejected: " + h);
            g.unverified = true;
            return;
        }

        int risk = 3;
        g.riskReasons.add("Starts at 3: gig platforms are unverified until proven otherwise");

        if (g.pageFetched) {
            if (!g.hasTerms) { risk += 2; g.riskReasons.add("+2 no Terms page found on its site"); }
            if (!g.hasPrivacy) { risk += 1; g.riskReasons.add("+1 no Privacy page found on its site"); }
            if (!g.hasContact) { risk += 2; g.riskReasons.add("+2 no contact or company details found"); }
            if (g.hasTerms && g.hasPrivacy) { risk -= 1; g.goodSigns.add("-1 has Terms and Privacy pages"); }
        } else {
            risk += 2; g.riskReasons.add("+2 could not read the platform's own page, so Terms, Privacy and contact details are unchecked");
        }
        if (RECRUITING.matcher(text).find()) { risk += 2; g.riskReasons.add("+2 income seems to depend on recruiting other people"); }
        if (HYPE.matcher(text).find()) { risk += 1; g.riskReasons.add("+1 hype language (urgency, financial freedom, passive income)"); }
        boolean earnClaim = EARN_CLAIM.matcher(text).find();
        if (earnClaim) { risk += 1; g.riskReasons.add("+1 states earnings figures"); }

        if (rv.negative() >= 3) { risk += 5; g.riskReasons.add("+5 " + rv.negative() + " independent results report non-payment or a scam"); }
        else if (rv.negative() == 2) { risk += 4; g.riskReasons.add("+4 2 independent results report non-payment or a scam"); }
        else if (rv.negative() == 1) { risk += 1; g.riskReasons.add("+1 one independent result reports a payment problem"); }

        if (g.costToJoin.equals(Gig.FREE)) { risk -= 1; g.goodSigns.add("-1 says it is free to join"); }
        if (!g.payoutThreshold.isEmpty()) { risk -= 1; g.goodSigns.add("-1 states a payout threshold (" + g.payoutThreshold + ")"); }
        if (rv.positive() >= 2 && rv.negative() == 0) { risk -= 2; g.goodSigns.add("-2 independent results describe getting paid (" + String.join(", ", rv.sources()) + ")"); }
        if (rv.proof() >= 1 && rv.negative() == 0) { risk -= 1; g.goodSigns.add("-1 independent payout proof mentioned"); }

        if (rv.mentions() == 0) { risk += 1; g.riskReasons.add("+1 no independent reviews found"); }
        g.unverified = rv.mentions() < 2 || (earnClaim && rv.proof() == 0);
        if (g.unverified) g.riskReasons.add("Unverified: earnings and payouts are self-reported with no independent proof");

        // several independent reports of non-payment outweigh any good sign on the platform's own site
        if (rv.negative() >= 3 && rv.positive() == 0 && risk < 7) { risk = 7; g.riskReasons.add("Raised to 7: several independent reports of non-payment, none of getting paid"); }
        g.risk = Math.max(0, Math.min(10, risk));
    }

    // ------------------------------------------------------------------ independent reviews

    /** What independent search results (Reddit, Trustpilot, YouTube and so on) say about a platform. */
    record Reviews(int mentions, int negative, int positive, int proof, List<String> sources) {
        static final Reviews NONE = new Reviews(0, 0, 0, 0, List.of());

        private static final Pattern QUESTION = Pattern.compile("\\bis\\b[^?.]{0,60}\\b(a )?(scam|legit)\\b\\??|scam or legit|legit or (a )?scam|is it (a )?scam", Pattern.CASE_INSENSITIVE);
        private static final Pattern NEGATIVE = Pattern.compile("not paid|never (got )?paid|didn'?t (get )?pa(y|id)|did not pay|won'?t pay|refus(ed|es) to pay|(it'?s|is|was|total|complete) (a )?scam\\b|\\bstole\\b|rip-?off|banned me|no payout|payout (denied|issues?|problems?)|didn'?t receive|still waiting for (my )?(payment|payout)", Pattern.CASE_INSENSITIVE);
        private static final Pattern POSITIVE = Pattern.compile("got paid|i (was|am|have been) paid|paid out|received (my )?(payment|payout)|\\blegit\\b|withdrew|cashed out|payment (arrived|received)", Pattern.CASE_INSENSITIVE);
        private static final Pattern PROOF = Pattern.compile("payout proof|proof of payment|screenshot of (my )?(payment|payout)|received \\$\\d|paid me \\$?\\d|withdrew \\$\\d", Pattern.CASE_INSENSITIVE);

        /** Counts results from other sites that actually mention this platform. "Is X a scam?" questions are not evidence either way. */
        static Reviews fromHits(String name, String domain, List<GigDiscovery.Hit> hits) {
            String n = Text.norm(name), label = domain.contains(".") ? domain.substring(0, domain.indexOf('.')) : domain;
            int mentions = 0, neg = 0, pos = 0, proof = 0;
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            for (GigDiscovery.Hit h : hits) {
                String d = GigDiscovery.domainOf(h.link());
                if (d.isEmpty() || d.equals(domain) || d.endsWith("." + domain)) continue;       // the platform's own pages are not independent
                String t = Text.norm(h.title() + " " + h.snippet());
                boolean about = (n.length() >= 3 && t.contains(n)) || (label.length() >= 4 && t.contains(label));
                if (!about) continue;
                mentions++;
                sources.add(d);
                String cleaned = QUESTION.matcher(t).replaceAll(" ");
                if (NEGATIVE.matcher(cleaned).find()) neg++;
                if (POSITIVE.matcher(cleaned).find() && !NEGATIVE.matcher(cleaned).find()) pos++;
                if (PROOF.matcher(cleaned).find()) proof++;
            }
            return new Reviews(mentions, neg, pos, proof, new ArrayList<>(sources).subList(0, Math.min(5, sources.size())));
        }
    }
}

package jobhunter;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives each job a 0-100 fit score against your skill groups, and explains why.
 *
 * Idea: for each skill group, a title match counts most, extra keyword matches in the text add more.
 * The best group counts fully, the second half, the third a quarter. Then small bonuses
 * (flexible hours, contract, open to Africa, junior-friendly) and penalties (senior roles, many years required).
 */
final class Scorer {
    record Result(int score, List<String> why, List<String> matched, List<String> topGroups) {}

    private static Pattern re(String r) { return Pattern.compile(r, Pattern.CASE_INSENSITIVE); }

    private static final Pattern SENIOR = re("\\b(senior|sr\\.?|lead|principal|staff|head of|director|vp|chief|architect)\\b");
    private static final Pattern JUNIOR = re("\\b(junior|jr\\.?|entry[- ]level|graduate|trainee|intern(ship)?|no experience|student|beginner)\\b");
    private static final Pattern YEARS = re("(\\d{1,2})\\s?\\+?\\s?(?:-\\s?\\d{1,2}\\s?)?years?(?: of)?(?: relevant| professional| proven| hands-on)?\\s+(?:of\\s+)?(?:experience|exp)");
    private static final Pattern PART_TIME = re("\\b(part[- ]time|contract(or)?|freelance|project[- ]based|gig|per project)\\b");

    private final Profile profile;
    private final Map<String, List<Pattern>> kw = new HashMap<>();

    Scorer(Profile profile) {
        this.profile = profile;
        for (Profile.Group g : profile.groups) {
            List<Pattern> ps = new ArrayList<>();
            for (String k : g.keywords()) ps.add(Pattern.compile("(?<![a-z0-9+#])" + Pattern.quote(k) + "(?![a-z0-9+#])"));
            kw.put(g.name(), ps);
        }
    }

    /** What kind of work is this? Decides which minimum pay applies. */
    static String classify(Job j) {
        String t = Text.norm(j.title);
        String all = Text.norm(j.title + " " + j.description + " " + String.join(" ", j.tags));
        if (re("\\bai (trainer|training|tutor|evaluator|rater|data)|data annotat|annotator|\\brlhf\\b|\\bllm (trainer|evaluat)|model evaluation|data label|labell?er|\\brater\\b|prompt (writer|engineer)").matcher(t).find()
                || re("\\bai (trainer|training) |data annotation|rlhf").matcher(all).find() && re("\\b(ai|data)\\b").matcher(t).find()) {
            return "AI_TRAINING";
        }
        if (re("\\b(tutor|teacher|instructor|lecturer|coach)\\b").matcher(t).find()) return "TUTORING";
        if (re("micro-?tasks?|\\bsurvey|crowd|tasker|\\bper task\\b").matcher(t + " " + Text.norm(j.type)).find()) return "MICROTASK";
        if (re("\\bfreelance|\\bcontract(or)?\\b|project[- ]based|\\bgig\\b").matcher(t + " " + Text.norm(j.type)).find()
                || re("\\bfreelance\\b").matcher(all).find()) return "FREELANCE";
        return "JOB";
    }

    Result score(Job j) {
        String title = Text.norm(j.title);
        String text = Text.norm(j.title + " " + String.join(" ", j.tags) + " " + j.description);

        record GroupHit(Profile.Group g, double points, List<String> hits, boolean inTitle) {}
        List<GroupHit> hits = new ArrayList<>();
        for (Profile.Group g : profile.groups) {
            List<String> found = new ArrayList<>();
            boolean inTitle = false;
            for (int i = 0; i < g.keywords().size(); i++) {
                Pattern p = kw.get(g.name()).get(i);
                if (p.matcher(text).find()) {
                    found.add(g.keywords().get(i));
                    if (p.matcher(title).find()) inTitle = true;
                }
            }
            if (found.isEmpty()) continue;
            double pts = g.weight() * ((inTitle ? 0.55 : 0.0) + 0.45 * Math.min(1.0, found.size() / 3.0));
            hits.add(new GroupHit(g, pts, found, inTitle));
        }
        hits.sort((a, b) -> Double.compare(b.points, a.points));

        double total = 0;
        double[] mult = {1.0, 0.5, 0.25};
        List<String> why = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        List<String> top = new ArrayList<>();
        for (int i = 0; i < hits.size() && i < mult.length; i++) {
            GroupHit h = hits.get(i);
            total += h.points * mult[i];
            top.add(h.g.name());
            List<String> shown = h.hits.subList(0, Math.min(3, h.hits.size()));
            why.add(h.g.label() + (h.inTitle ? " (in the title" : " (in the text") + ": " + String.join(", ", shown) + ")");
            for (String s : shown) if (!matched.contains(s)) matched.add(s);
        }

        // bonuses only count when the job matches at least one of your skills (a nurse job open worldwide is still not for you)
        if (!hits.isEmpty()) {
            if (Filter.FLEXIBLE.matcher(text).find()) { total += 10; why.add("+10 flexible hours"); }
            if (PART_TIME.matcher(Text.norm(j.type + " " + j.title) + " " + text).find()) { total += 6; why.add("+6 contract, freelance or part-time"); }
            if (Filter.GEO_OK.matcher(Text.norm(j.location)).find()) { total += 6; why.add("+6 open to Africa or worldwide"); }
            if (JUNIOR.matcher(text).find()) { total += 6; why.add("+6 junior or student friendly"); }
            Double pay = j.maxHourlyUsd != null ? j.maxHourlyUsd : j.minHourlyUsd;
            if (pay != null && pay >= profile.minFor(j.workType) * 1.5) { total += 5; why.add("+5 pay well above your minimum"); }
            Profile.Target t = targetFor(j);
            if (t != null) { total += 8; why.add("+8 one of your 20 target titles (" + t.title() + ")"); }
        }

        // roles a school leaver can start, and roles that train you: a bonus when they also fit your skills, and a
        // place in the queue (at the threshold, so they sit at the bottom) when they do not, so you can judge them
        Entry.Result entry = Entry.detect(j);
        if (!hits.isEmpty()) {
            if (entry.training()) { total += 6; why.add("+6 training provided"); }
            if (entry.matric()) { total += 4; why.add("+4 matric is enough (no degree or experience asked)"); }
        } else if (entry.any()) {
            total = Math.max(total, profile.scoreThreshold);
            why.add("Entry-level or training role that does not match your skill list: shown so you can judge it");
        }

        // penalties
        if (SENIOR.matcher(title).find()) { total -= 25; why.add("-25 senior-level title"); }
        int years = maxYears(text);
        if (years >= 7) { total -= 25; why.add("-25 asks for " + years + "+ years"); }
        else if (years >= 5) { total -= 18; why.add("-18 asks for " + years + "+ years"); }
        else if (years >= 3) { total -= 8; why.add("-8 asks for " + years + "+ years"); }

        int score = (int) Math.max(0, Math.min(100, Math.round(total)));
        return new Result(score, why, matched, top);
    }

    /** The first of your target titles whose match phrases appear in this job's title, or null. */
    Profile.Target targetFor(Job j) {
        String t = Text.norm(j.title);
        for (Profile.Target tg : profile.targets)
            for (String m : tg.match()) if (!m.isBlank() && t.contains(m)) return tg;
        return null;
    }

    private static int maxYears(String text) {
        int max = 0;
        Matcher m = YEARS.matcher(text);
        while (m.find()) {
            try { max = Math.max(max, Integer.parseInt(m.group(1))); } catch (NumberFormatException ignored) { }
        }
        return max;
    }
}

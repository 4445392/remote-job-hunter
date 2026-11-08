package jobhunter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * The whole idea in one class:
 *   fetch from each source  ->  skip what we've seen  ->  deal-breaker filter  ->  score  ->  draft  ->  your queue.
 */
final class Pipeline {
    static final class Counts {
        int fetched, duplicates, rejected, lowScore, added;
        final Map<String, Integer> rejectReasons = new TreeMap<>();
    }

    private final Settings settings;
    private final Profile profile;
    private final Store store;
    private final Http http;
    private final Filter filter;
    private final Scorer scorer;
    private final Drafter drafter;

    Pipeline(Settings settings, Profile profile, Store store, Http http) {
        this.settings = settings;
        this.profile = profile;
        this.store = store;
        this.http = http;
        this.filter = new Filter(profile);
        this.scorer = new Scorer(profile);
        this.drafter = new Drafter(profile, settings, http);
    }

    /** Fetch every source that is due, process the results, save. Returns a summary map. */
    Map<String, Object> run() {
        Counts c = new Counts();
        List<String> used = new ArrayList<>(), skipped = new ArrayList<>(), errors = new ArrayList<>(), off = new ArrayList<>();
        for (Source src : Sources.all()) {
            if (!src.enabled(settings)) { off.add(src.name()); continue; }
            if (!store.sourceDue(src.name(), src.minIntervalMinutes())) { skipped.add(src.name()); continue; }
            try {
                List<Job> jobs = src.fetch(http, settings, profile);
                used.add(src.name() + " (" + jobs.size() + ")");
                process(jobs, c);
                store.touchSource(src.name());
            } catch (Exception e) {
                errors.add(src.name() + ": " + e.getMessage());
            }
        }
        Map<String, Object> sum = new LinkedHashMap<>();
        sum.put("at", Instant.now().toString());
        sum.put("fetched", c.fetched);
        sum.put("alreadySeen", c.duplicates);
        sum.put("rejectedByDealBreakers", c.rejected);
        sum.put("lowScore", c.lowScore);
        sum.put("addedToQueue", c.added);
        sum.put("sourcesUsed", used);
        sum.put("sourcesNotDueYet", skipped);
        sum.put("sourcesOff", off);
        sum.put("errors", errors);
        sum.put("topRejectReasons", topReasons(c.rejectReasons));
        store.recordRun(sum);
        try {
            store.save();
        } catch (IOException e) {
            errors.add("Could not save: " + e.getMessage());
        }
        return sum;
    }

    /** Runs a batch of jobs through filter, score and draft. Public to the package so tests can use it offline. */
    void process(List<Job> jobs, Counts c) {
        String now = Instant.now().toString();
        for (Job j : jobs) {
            c.fetched++;
            if (store.isKnown(j)) { c.duplicates++; continue; }
            j.workType = Scorer.classify(j);
            Filter.Result fr = filter.screen(j, store);
            if (!fr.ok()) {
                c.rejected++;
                String first = fr.rejects().get(0);
                c.rejectReasons.merge(generic(first), 1, Integer::sum);
                store.markSeen(j, "rejected: " + first);
                continue;
            }
            Scorer.Result sr = scorer.score(j);
            if (sr.score() < profile.scoreThreshold) {
                c.lowScore++;
                store.markSeen(j, "low score " + sr.score());
                continue;
            }
            j.score = sr.score();
            j.why = sr.why();
            j.flags = fr.flags();
            j.foundAt = now;
            j.status = Job.NEW;
            j.draft = drafter.draft(j, sr, sr.score() >= 75);
            store.add(j);
            c.added++;
        }
    }

    /**
     * A job you pasted in yourself (for example from LinkedIn or Indeed). It always goes into your queue:
     * deal-breakers become warnings instead of silently dropping it, because you chose it.
     */
    Job addManual(Job j) {
        j.finish();
        Job existing = store.get(j.id);
        if (existing != null) return existing;
        if (j.minHourlyUsd == null && j.maxHourlyUsd == null) {
            double[] pay = Pay.hourlyUsd(j.description, profile.fx);   // pasted ads keep the pay inside the text
            if (pay != null) { j.minHourlyUsd = pay[0]; j.maxHourlyUsd = pay[1]; }
        }
        j.workType = Scorer.classify(j);
        Filter.Result fr = filter.screen(j, store);
        Scorer.Result sr = scorer.score(j);
        j.score = sr.score();
        j.why = sr.why();
        List<String> flags = new ArrayList<>();
        for (String r : fr.rejects()) flags.add("Deal-breaker: " + r);
        flags.addAll(fr.flags());
        j.flags = flags;
        j.foundAt = Instant.now().toString();
        j.status = Job.NEW;
        j.draft = drafter.draft(j, sr, false);
        store.add(j);
        return j;
    }

    Scorer.Result scoreOf(Job j) { return scorer.score(j); }

    /** Re-write the draft for one job (optionally with Claude). */
    String redraft(Job j, boolean useClaude) {
        return drafter.draft(j, scorer.score(j), useClaude);
    }

    private static String generic(String reason) {
        int i = reason.indexOf(':');
        String head = i > 0 ? reason.substring(0, i) : reason;
        if (head.startsWith("Posting is")) return "Posting too old";
        if (head.startsWith("Pay about")) return "Pay below your minimum";
        return head;
    }

    private static Map<String, Integer> topReasons(Map<String, Integer> all) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(all.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(8, es.size()); i++) out.put(es.get(i).getKey(), es.get(i).getValue());
        return out;
    }
}

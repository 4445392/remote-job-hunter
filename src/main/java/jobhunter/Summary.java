package jobhunter;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** The "how is my search going" numbers. Pure function over the saved jobs; reads nothing else and sends nothing. */
final class Summary {
    private Summary() {}

    /**
     * @param missing which CV keywords a job asked for that your CV lacks (so the page can say what to learn next)
     */
    static Map<String, Object> weekly(Collection<Job> jobs, Instant now, int days, Function<Job, List<String>> missing) {
        Instant from = now.minus(Duration.ofDays(days));
        int found = 0, applied = 0, replies = 0, allApplied = 0, allReplied = 0, due = 0;
        Map<String, int[]> bySource = new LinkedHashMap<>();            // source -> {found, applied, replied}
        Map<String, Integer> gaps = new HashMap<>();

        for (Job j : jobs) {
            boolean isApplied = Job.APPLIED.equals(j.status) || Job.REPLIED.equals(j.status);
            boolean isReplied = Job.REPLIED.equals(j.status);
            int[] s = bySource.computeIfAbsent(j.source.isBlank() ? "Unknown" : j.source, k -> new int[3]);
            s[0]++;
            if (isApplied) { s[1]++; allApplied++; }
            if (isReplied) { s[2]++; allReplied++; }

            if (after(j.foundAt, from)) {
                found++;
                if (!Job.REJECTED.equals(j.status) && !Job.BLOCKED.equals(j.status))
                    for (String g : missing.apply(j)) gaps.merge(g, 1, Integer::sum);
            }
            if (isApplied && after(j.statusAt, from)) applied++;
            if (isReplied && after(j.statusAt, from)) replies++;
            if (FollowUp.isDue(j, now, FollowUp.DAYS)) due++;
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        bySource.entrySet().stream()
                .sorted((a, b) -> {
                    int[] x = a.getValue(), y = b.getValue();
                    if (y[2] != x[2]) return y[2] - x[2];                 // most replies
                    if (y[1] != x[1]) return y[1] - x[1];                 // then most applications
                    if (y[0] != x[0]) return y[0] - x[0];                 // then most jobs found
                    return a.getKey().compareTo(b.getKey());
                })
                .limit(8)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", e.getKey()); m.put("found", e.getValue()[0]);
                    m.put("applied", e.getValue()[1]); m.put("replied", e.getValue()[2]);
                    sources.add(m);
                });

        List<Map<String, Object>> topGaps = new ArrayList<>();
        gaps.entrySet().stream()
                .sorted((a, b) -> !b.getValue().equals(a.getValue()) ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()))
                .limit(10)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("keyword", e.getKey()); m.put("jobs", e.getValue());
                    topGaps.add(m);
                });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("found", found);
        out.put("applied", applied);
        out.put("replies", replies);
        out.put("followUpsDue", due);
        out.put("replyRatePct", allApplied == 0 ? 0 : (int) Math.round(100.0 * allReplied / allApplied));
        out.put("totalApplied", allApplied);
        out.put("sources", sources);
        out.put("gaps", topGaps);
        return out;
    }

    private static boolean after(String iso, Instant from) {
        try { return iso != null && !iso.isBlank() && Instant.parse(iso).isAfter(from); }
        catch (Exception e) { return false; }
    }
}

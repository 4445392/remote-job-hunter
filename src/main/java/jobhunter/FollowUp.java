package jobhunter;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Which applications need a nudge, and the short message to send. Pure functions: it never sends anything. */
final class FollowUp {
    private FollowUp() {}

    static final int DAYS = 7;

    /** When the clock for this job started: the last follow-up, else the day you applied. Null if unknown. */
    static Instant since(Job j) {
        String s = !Job.nz(j.followedUpAt).isBlank() ? j.followedUpAt : j.statusAt;
        try { return s == null || s.isBlank() ? null : Instant.parse(s); }
        catch (Exception e) { return null; }
    }

    static boolean isDue(Job j, Instant now, int days) {
        if (!Job.APPLIED.equals(j.status) || j.noReminder) return false;   // replied, hidden or stopped: never nag
        Instant t = since(j);
        return t != null && !t.plus(Duration.ofDays(days)).isAfter(now);
    }

    /** Days since the clock started, or -1 if unknown. */
    static int daysSince(Job j, Instant now) {
        Instant t = since(j);
        return t == null ? -1 : (int) Duration.between(t, now).toDays();
    }

    /** Applied jobs waiting 7+ days with no reply, longest-waiting first. */
    static List<Job> due(Collection<Job> jobs, Instant now, int days) {
        List<Job> out = new ArrayList<>();
        for (Job j : jobs) if (isDue(j, now, days)) out.add(j);
        out.sort(Comparator.comparing(FollowUp::since));
        return out;
    }

    /** Short, polite, and built only from the job title, the company, the day you applied and your own contact details. */
    static String draft(Job j, Profile p) {
        String role = j.title.isBlank() ? "this opportunity" : j.title;
        String company = "SEARCH".equals(j.sourceType) || j.company.isBlank() ? null : j.company;
        String applied = Csv.date(j);
        StringBuilder sb = new StringBuilder();
        sb.append("Subject: Following up: ").append(role).append(" - ").append(p.name).append("\n\n");
        sb.append(company == null ? "Hello," : "Hello " + company + " team,").append("\n\n");
        sb.append("I applied for ").append(j.title.isBlank() ? "this opportunity" : "the " + j.title + " position");
        if (!applied.isBlank()) sb.append(" on ").append(applied);
        sb.append(" and wanted to check in. I am still very interested and happy to answer any questions or share more about my work.\n\n");
        sb.append("Could you let me know where things stand, or whether you need anything else from me?\n\n");
        sb.append("Thank you for your time.\n\n");
        sb.append(p.name).append("\n").append(p.email);
        if (!p.phone.isBlank()) sb.append(" | ").append(p.phone);
        sb.append("\n");
        return sb.toString();
    }
}

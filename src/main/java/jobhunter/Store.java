package jobhunter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Everything the tool remembers, in one JSON file (data/state.json). All methods are thread-safe. */
final class Store {
    private final Path file;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, String> seen = new LinkedHashMap<>();        // id or dupKey -> why it was skipped / "kept"
    private final Map<String, String> sourceRuns = new LinkedHashMap<>();  // source name -> ISO time of last fetch
    private final Set<String> blocked = new LinkedHashSet<>();              // lower-case employer names
    private final Set<String> signedUp = new LinkedHashSet<>();             // platform ids you have joined
    private final List<Map<String, Object>> runs = new ArrayList<>();

    Store(Path file) { this.file = file; }

    static Store load(Path file) throws IOException {
        Store s = new Store(file);
        if (Files.exists(file)) {
            Map<String, Object> root = Json.obj(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            for (Object o : Json.arr(root.get("jobs"))) {
                Job j = Job.fromMap(Json.obj(o));
                if (!j.id.isEmpty()) s.jobs.put(j.id, j);
            }
            for (Map.Entry<String, Object> e : Json.obj(root.get("seen")).entrySet()) s.seen.put(e.getKey(), Json.str(e.getValue()));
            for (Map.Entry<String, Object> e : Json.obj(root.get("sourceRuns")).entrySet()) s.sourceRuns.put(e.getKey(), Json.str(e.getValue()));
            for (String b : Json.strings(root.get("blocked"))) s.blocked.add(b.toLowerCase(Locale.ROOT));
            s.signedUp.addAll(Json.strings(root.get("signedUp")));
            for (Object o : Json.arr(root.get("runs"))) s.runs.add(Json.obj(o));
        }
        return s;
    }

    synchronized void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>();
        for (Job j : jobs.values()) list.add(j.toMap());
        root.put("jobs", list);
        // keep the "seen" memory bounded
        Map<String, String> s = seen;
        if (s.size() > 20000) {
            Iterator<String> it = s.keySet().iterator();
            for (int n = s.size() - 20000; n > 0 && it.hasNext(); n--) { it.next(); it.remove(); }
        }
        root.put("seen", s);
        root.put("sourceRuns", sourceRuns);
        root.put("blocked", new ArrayList<>(blocked));
        root.put("signedUp", new ArrayList<>(signedUp));
        root.put("runs", runs.size() > 60 ? runs.subList(runs.size() - 60, runs.size()) : runs);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.pretty(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ----- jobs -----

    synchronized boolean isKnown(Job j) {
        return jobs.containsKey(j.id) || seen.containsKey(j.id) || seen.containsKey(j.dupKey());
    }

    synchronized void markSeen(Job j, String why) {
        seen.put(j.id, why);
        seen.put(j.dupKey(), why);
    }

    synchronized void add(Job j) {
        jobs.put(j.id, j);
        markSeen(j, "kept");
    }

    synchronized Job get(String id) { return jobs.get(id); }

    synchronized List<Job> all() { return new ArrayList<>(jobs.values()); }

    synchronized boolean setStatus(String id, String status) {
        Job j = jobs.get(id);
        if (j == null) return false;
        j.status = status;
        j.statusAt = Instant.now().toString();
        if (Job.APPLIED.equals(status)) { j.followedUpAt = ""; j.noReminder = false; }   // fresh 7-day clock
        return true;
    }

    /** You sent a follow-up: restart the 7-day clock. */
    synchronized boolean markFollowedUp(String id) {
        Job j = jobs.get(id);
        if (j == null) return false;
        j.followedUpAt = Instant.now().toString();
        return true;
    }

    /** "Stop reminding me" for this job. */
    synchronized boolean stopReminders(String id) {
        Job j = jobs.get(id);
        if (j == null) return false;
        j.noReminder = true;
        return true;
    }

    synchronized boolean setDraft(String id, String draft) {
        Job j = jobs.get(id);
        if (j == null) return false;
        j.draft = draft;
        return true;
    }

    // ----- employer blocklist -----

    synchronized int blockCompany(String company) {
        if (company == null || company.isBlank()) return 0;
        String c = company.toLowerCase(Locale.ROOT).trim();
        blocked.add(c);
        int n = 0;
        for (Job j : jobs.values()) {
            if (j.company.toLowerCase(Locale.ROOT).trim().equals(c) && !Job.APPLIED.equals(j.status)) {
                j.status = Job.BLOCKED;
                j.statusAt = Instant.now().toString();
                n++;
            }
        }
        return n;
    }

    synchronized boolean isBlocked(String company) {
        if (company == null) return false;
        String c = company.toLowerCase(Locale.ROOT).trim();
        if (c.isEmpty()) return false;
        for (String b : blocked) if (c.equals(b) || (b.length() > 4 && c.contains(b))) return true;
        return false;
    }

    synchronized List<String> blockedList() { return new ArrayList<>(blocked); }

    // ----- source schedule -----

    synchronized boolean sourceDue(String source, int minIntervalMinutes) {
        String last = sourceRuns.get(source);
        if (last == null) return true;
        try {
            return Instant.parse(last).plusSeconds(minIntervalMinutes * 60L).isBefore(Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

    synchronized void touchSource(String source) { sourceRuns.put(source, Instant.now().toString()); }

    // ----- platforms -----

    synchronized boolean toggleSignedUp(String id) {
        if (!signedUp.remove(id)) { signedUp.add(id); return true; }
        return false;
    }

    synchronized Set<String> signedUp() { return new LinkedHashSet<>(signedUp); }

    // ----- run history -----

    synchronized void recordRun(Map<String, Object> summary) { runs.add(summary); }

    synchronized Map<String, Object> lastRun() { return runs.isEmpty() ? null : runs.get(runs.size() - 1); }

    synchronized Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Integer> by = new LinkedHashMap<>();
        for (String s : List.of(Job.NEW, Job.APPROVED, Job.APPLIED, Job.REJECTED, Job.BLOCKED)) by.put(s, 0);
        for (Job j : jobs.values()) by.merge(j.status, 1, Integer::sum);
        m.put("byStatus", by);
        m.put("total", jobs.size());
        m.put("seen", seen.size() / 2);
        m.put("blockedEmployers", blocked.size());
        return m;
    }
}

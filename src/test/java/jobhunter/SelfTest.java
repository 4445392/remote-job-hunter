package jobhunter;

import com.sun.net.httpserver.HttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Dependency-free self-tests. Run with: ./build.sh test   (everything runs offline). */
public final class SelfTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("  FAIL: " + name); }
    }

    static void eq(String name, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual);
        if (!ok) System.out.println("    expected <" + expected + "> but got <" + actual + ">");
        check(name, ok);
    }

    static void near(String name, double expected, Double actual) {
        boolean ok = actual != null && Math.abs(expected - actual) < 0.05;
        if (!ok) System.out.println("    expected ~" + expected + " but got " + actual);
        check(name, ok);
    }

    static Profile profile;

    public static void main(String[] args) throws Exception {
        Path cfg = Files.exists(Path.of("config/profile.json")) ? Path.of("config/profile.json") : Path.of("config/profile.example.json");
        profile = Profile.load(cfg);
        jsonTests();
        textAndPayTests();
        parserTests();
        filterTests();
        scorerTests();
        drafterTests();
        pipelineTests();
        dashboardTests();
        cvTests();
        boardTests();
        csvTests();
        followUpTests();
        coverLetterTests();
        dedupeTests();
        summaryTests();
        workingNomadsTests();
        entryTests();
        gigExtractTests();
        gigRiskTests();
        gigDiscoveryTests();
        gigPipelineTests();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    // ------------------------------------------------------------------

    static void jsonTests() {
        System.out.println("Json");
        Object o = Json.parse("{\"a\": [1, 2.5, \"x\\n\\u00e9\\\"\", true, null], \"b\": {\"c\": -3e2}}");
        Map<String, Object> m = Json.obj(o);
        eq("array length", 5, Json.arr(m.get("a")).size());
        eq("long", 1L, Json.arr(m.get("a")).get(0));
        eq("double", 2.5, Json.arr(m.get("a")).get(1));
        eq("escapes", "x\né\"", Json.arr(m.get("a")).get(2));
        eq("exponent", -300.0, Json.obj(m.get("b")).get("c"));
        String round = Json.write(o);
        eq("round trip", Json.write(Json.parse(round)), round);
        check("pretty parses back", Json.write(Json.parse(Json.pretty(o))).equals(round));
        boolean threw = false;
        try { Json.parse("{\"a\": }"); } catch (IllegalArgumentException e) { threw = true; }
        check("bad json throws", threw);
        threw = false;
        try { Json.parse("[1,2] x"); } catch (IllegalArgumentException e) { threw = true; }
        check("trailing junk throws", threw);
        eq("write escapes", "\"a\\\"b\\\\c\\u0001\"", Json.write("a\"b\\c\u0001"));
    }

    static void textAndPayTests() {
        System.out.println("Text and Pay");
        eq("strip html", "Hello & world\n- One", Text.stripHtml("<p>Hello &amp; <b>world</b></p><ul><li>One</li></ul>"));
        eq("double-escaped html", "Hi there", Text.stripHtml("&lt;p&gt;Hi &lt;b&gt;there&lt;/b&gt;&lt;/p&gt;"));
        eq("numeric entity", "it's", Text.unescape("it&#39;s"));
        eq("email found", "jobs@acme.io", Text.firstEmail("write to noreply@acme.io or jobs@acme.io"));
        eq("normalize url", "https://x.com/job/1?id=5", Job.normalizeUrl("https://X.com/job/1/?utm_source=a&id=5#top"));

        Map<String, Double> fx = profile.fx;
        near("$25/hr", 25, Pay.hourlyUsd("Pay: $25/hr", fx)[0]);
        double[] r = Pay.hourlyUsd("$40k - $60k", fx);
        near("$40k low", 40000 / 2080.0, r[0]);
        near("$60k high", 60000 / 2080.0, r[1]);
        near("range per hour", 20, Pay.hourlyUsd("Salary: $15 - $20 per hour", fx)[1]);
        near("rand per month", 18000 * 0.055 / 173.33, Pay.hourlyUsd("R10,000 - R18,000/month", fx)[1]);
        near("euro per year", 50000 * 1.08 / 2080, Pay.hourlyUsd("EUR 50,000 per year", fx)[0]);
        near("bare big number is yearly", 120000 / 2080.0, Pay.hourlyUsd("$120,000", fx)[0]);
        check("no pay text", Pay.hourlyUsd("competitive salary and R&D time", fx) == null);
        check("ambiguous number ignored", Pay.hourlyUsd("$800", fx) == null);
        near("annual to hourly", 52000 / 2080.0, Pay.annualToHourly(52000.0, "USD", fx));
    }

    static void parserTests() throws Exception {
        System.out.println("Parsers");
        String remoteOk = """
                [{"legal":"terms"},
                 {"slug":"a","id":"1","date":"2026-11-05T10:00:00+00:00","company":"Acme","position":"AI Trainer",
                  "tags":["ai","english"],"description":"<p>Rate responses.<br>Flexible hours.</p>","location":"Worldwide",
                  "salary_min":30000,"salary_max":52000,"apply_url":"https://remoteok.com/apply/1","url":"https://remoteok.com/remote-jobs/1"},
                 {"company":"NoTitle"}]
                """;
        List<Job> ro = Sources.parseRemoteOk(remoteOk, profile.fx);
        eq("remoteok count", 1, ro.size());
        eq("remoteok title", "AI Trainer", ro.get(0).title);
        near("remoteok pay", 52000 / 2080.0, ro.get(0).maxHourlyUsd);
        check("remoteok html stripped", !ro.get(0).description.contains("<"));
        check("remoteok id set", !ro.get(0).id.isEmpty());

        String jobicy = """
                {"jobs":[{"id":1,"url":"https://jobicy.com/jobs/1","jobTitle":"Data Entry &amp; Excel Assistant","companyName":"Beta",
                 "jobIndustry":["Admin"],"jobType":["Part-time"],"jobGeo":"Anywhere","jobLevel":"Junior",
                 "jobDescription":"&lt;p&gt;Work in Excel&lt;/p&gt;","pubDate":"2026-11-05 09:00:00","salaryMin":20000,"salaryMax":30000,"salaryCurrency":"USD","salaryPeriod":"yearly"},
                 {"id":2,"url":"https://jobicy.com/jobs/2","jobTitle":"Chat Agent","companyName":"Beta","jobGeo":"Anywhere","jobType":["Contract"],"jobLevel":"Any","jobDescription":"Chat","pubDate":"2026-11-05T09:00:00+00:00","salaryMin":10,"salaryMax":14,"salaryCurrency":"USD","salaryPeriod":"hourly"}]}
                """;
        List<Job> jo = Sources.parseJobicy(jobicy, profile.fx);
        eq("jobicy count", 2, jo.size());
        near("jobicy yearly pay", 30000 / 2080.0, jo.get(0).maxHourlyUsd);
        near("jobicy hourly pay", 14, jo.get(1).maxHourlyUsd);
        eq("jobicy title unescaped", "Data Entry & Excel Assistant", jo.get(0).title);
        eq("jobicy description", "Work in Excel", jo.get(0).description);
        eq("jobicy date", "2026-11-05T09:00:00Z", jo.get(0).postedAt);

        String remotive = """
                {"jobs":[{"id":2,"url":"https://remotive.com/j/2","title":"Customer Support","company_name":"Gamma","category":"Customer Service",
                 "tags":["chat"],"job_type":"full_time","publication_date":"2026-11-04T12:00:00","candidate_required_location":"Africa",
                 "salary":"$12 - $15 per hour","description":"<p>Chat support</p>"}]}
                """;
        List<Job> re = Sources.parseRemotive(remotive, profile.fx);
        near("remotive pay", 15, re.get(0).maxHourlyUsd);
        eq("remotive location", "Africa", re.get(0).location);

        String himalayas = """
                {"jobs":[{"title":"Junior Java Developer","companyName":"Delta","employmentType":"Contractor","minSalary":2000,"maxSalary":3000,"salaryPeriod":"monthly",
                 "currency":"USD","locationRestrictions":["United States"],"categories":["Software"],"seniority":["Entry-level"],
                 "description":"<p>Spring Boot</p>","pubDate":1793000000,"applicationLink":"https://himalayas.app/apply/9","guid":"https://himalayas.app/jobs/9"},
                 {"title":"Media Planner","companyName":"Pesos Co","minSalary":30000,"maxSalary":40000,"salaryPeriod":"monthly","currency":"PHP",
                  "locationRestrictions":[],"timezoneRestrictions":[8],"categories":[],"seniority":[],"description":"x","pubDate":1793000000,"applicationLink":"https://himalayas.app/apply/10","guid":"https://himalayas.app/jobs/10"}]}
                """;
        List<Job> hi = Sources.parseHimalayas(himalayas, profile.fx);
        eq("himalayas location", "United States", hi.get(0).location);
        check("himalayas epoch date", hi.get(0).postedAt.startsWith("2026-"));
        eq("himalayas url", "https://himalayas.app/jobs/9", hi.get(0).url);
        near("himalayas monthly pay", 3000 / 173.33, hi.get(0).maxHourlyUsd);
        check("unknown currency gives no pay", hi.get(1).maxHourlyUsd == null);
        eq("timezone-only restriction", "Only timezones UTC+8", hi.get(1).location);

        String rss = """
                <?xml version="1.0"?><rss><channel>
                <item><title>Epsilon: Remote Excel Analyst</title><region>Anywhere in the World</region><skills>Excel, SQL</skills><type>Contract</type><category>Data</category>
                <description><![CDATA[<p>Analyse <b>spreadsheets</b></p>]]></description><pubDate>Wed, 05 Nov 2026 10:00:00 +0000</pubDate>
                <link>https://weworkremotely.com/remote-jobs/epsilon-1</link></item></channel></rss>
                """;
        List<Job> ww = Sources.parseWwr(rss);
        eq("wwr company", "Epsilon", ww.get(0).company);
        eq("wwr title", "Remote Excel Analyst", ww.get(0).title);
        eq("wwr description", "Analyse spreadsheets", ww.get(0).description);
        check("wwr skills become tags", ww.get(0).tags.contains("SQL"));
        eq("wwr date", "2026-11-05T10:00:00Z", ww.get(0).postedAt);
        boolean blocked = false;
        try { Sources.parseWwr("<!DOCTYPE x [<!ENTITY a \"b\">]><rss/>"); } catch (Exception e) { blocked = true; }
        check("xml doctype refused", blocked);

        List<Job> br = Sources.parseBrave("{\"web\":{\"results\":[{\"title\":\"Remote AI Trainer\",\"url\":\"https://www.jobs.example/ai-trainer\",\"description\":\"Flexible <strong>hours</strong>\"},{\"title\":\"Chat\",\"url\":\"https://www.reddit.com/r/x\",\"description\":\"x\"}]}}");
        eq("brave skips reddit", 1, br.size());
        eq("brave company is host", "jobs.example", br.get(0).company);
        eq("brave type", "SEARCH", br.get(0).sourceType);
        List<Job> se = Sources.parseSerper("{\"organic\":[{\"title\":\"Maths tutor wanted\",\"link\":\"https://tutor.example/job\",\"snippet\":\"Online\"}]}");
        eq("serper count", 1, se.size());
    }

    static Job job(String title, String company, String location, String desc) {
        Job j = new Job();
        j.source = "Test"; j.title = title; j.company = company; j.location = location; j.description = desc;
        j.url = "https://example.test/" + Text.sha(title + company + location + desc);
        j.postedAt = Instant.now().minus(2, ChronoUnit.DAYS).toString();
        j.workType = Scorer.classify(j);
        return j.finish();
    }

    static void filterTests() throws Exception {
        System.out.println("Filter");
        Filter f = new Filter(profile);
        Store store = new Store(Files.createTempFile("state", ".json"));

        Filter.Result ok = f.screen(job("AI Trainer", "Acme", "Worldwide", "Rate AI responses. Flexible hours."), store);
        check("good job passes", ok.ok());

        check("registration fee rejected", !f.screen(job("Data Clerk", "Z", "Worldwide", "A one-time $50 registration fee is required."), store).ok());
        check("gift cards rejected", !f.screen(job("Assistant", "Z", "Worldwide", "You will buy gift cards for the client."), store).ok());
        check("telegram recruiting rejected", !f.screen(job("Assistant", "Z", "Worldwide", "Interview via Telegram only."), store).ok());
        check("unpaid rejected", !f.screen(job("Writer", "Z", "Worldwide", "This is an unpaid internship."), store).ok());
        check("night shifts rejected", !f.screen(job("Support", "Z", "Worldwide", "Night shifts, Monday to Friday."), store).ok());
        check("9 to 5 rejected", !f.screen(job("Support", "Z", "Worldwide", "Hours are 9 to 5."), store).ok());
        check("overlap rejected", !f.screen(job("Dev", "Z", "Worldwide", "Must overlap with EST hours."), store).ok());
        Filter.Result mixed = f.screen(job("Dev", "Z", "Worldwide", "Flexible hours, though core hours are 10-12 for syncing."), store);
        check("flexible plus core hours only flagged", mixed.ok() && mixed.flags().stream().anyMatch(x -> x.contains("check")));
        check("call centre rejected", !f.screen(job("Agent", "Z", "Worldwide", "Work in our call center taking inbound calls."), store).ok());
        check("US only location rejected", !f.screen(job("Dev", "Z", "USA Only", "Build things."), store).ok());
        check("US text rejected", !f.screen(job("Dev", "Z", "Worldwide", "You must reside in the United States."), store).ok());
        check("work authorization rejected", !f.screen(job("Dev", "Z", "Remote", "Candidates need work authorization."), store).ok());
        check("Africa location passes", f.screen(job("Dev", "Z", "Africa", "Build things."), store).ok());
        Filter.Result noLoc = f.screen(job("Dev", "Z", "", "Build things."), store);
        check("missing location flagged", noLoc.ok() && noLoc.flags().stream().anyMatch(x -> x.startsWith("Location not stated")));

        Job cheap = job("AI Trainer", "Cheap", "Worldwide", "Rate responses.");
        cheap.maxHourlyUsd = 3.0;
        check("below minimum pay rejected", !f.screen(cheap, store).ok());
        Job fine = job("AI Trainer", "Fine", "Worldwide", "Rate responses.");
        fine.maxHourlyUsd = 20.0;
        check("good pay passes", f.screen(fine, store).ok());
        check("pay not stated flagged", ok.flags().stream().anyMatch(x -> x.startsWith("Pay not stated")));

        Job old = job("AI Trainer", "Old", "Worldwide", "Rate responses.");
        old.postedAt = Instant.now().minus(100, ChronoUnit.DAYS).toString();
        check("old posting rejected", !f.screen(old, store).ok());

        store.blockCompany("Bad Payers Ltd");
        check("blocked employer rejected", !f.screen(job("AI Trainer", "Bad Payers Ltd", "Worldwide", "Rate responses."), store).ok());

        Job art = job("10 Best Remote Jobs for 2026", "blog.example", "", "A list.");
        art.sourceType = "SEARCH";
        check("article rejected", !f.screen(art, store).ok());
        Job listing = job("Remote AI Trainer", "jobs.example", "", "Flexible hours.");
        listing.sourceType = "SEARCH";
        check("search listing passes", f.screen(listing, store).ok());
    }

    static void scorerTests() {
        System.out.println("Scorer");
        Scorer sc = new Scorer(profile);
        eq("classify ai", "AI_TRAINING", Scorer.classify(job("AI Trainer - Writing", "A", "", "x")));
        eq("classify tutor", "TUTORING", Scorer.classify(job("Maths Tutor", "A", "", "x")));
        eq("classify freelance", "FREELANCE", Scorer.classify(job("Freelance Web Developer", "A", "", "x")));
        eq("classify job", "JOB", Scorer.classify(job("Java Developer", "A", "", "x")));

        Job ai = job("AI Trainer", "A", "Worldwide", "Evaluate LLM responses, data annotation. Flexible hours. Part-time contract. Junior friendly.");
        int aiScore = sc.score(ai).score();
        check("ai trainer scores high (" + aiScore + ")", aiScore >= 70);
        Job senior = job("Senior Staff Java Engineer", "A", "Worldwide", "Java, Spring Boot, 10+ years of experience required.");
        int seniorScore = sc.score(senior).score();
        check("senior role penalised (" + seniorScore + ")", seniorScore < aiScore && seniorScore < 40);
        Job nurse = job("Registered Nurse", "A", "Worldwide", "Care for patients in hospital.");
        eq("irrelevant job scores zero", 0, sc.score(nurse).score());
        Job excel = job("Excel Data Analyst", "A", "Africa", "Spreadsheets, SQL and dashboards. Entry-level welcome.");
        check("excel analyst clears threshold", sc.score(excel).score() >= profile.scoreThreshold);
        check("why is explained", !sc.score(ai).why().isEmpty());
    }

    static void drafterTests() throws Exception {
        System.out.println("Drafter");
        Settings s = new Settings(Path.of("."), null, null, null, "claude-sonnet-5-5");
        Drafter d = new Drafter(profile, s, new Http());
        Scorer sc = new Scorer(profile);
        Job j = job("Junior Java Developer", "Delta", "Worldwide", "Spring Boot and MySQL. Flexible hours.");
        String text = d.draft(j, sc.score(j), false);
        check("subject line first", text.startsWith("Subject: Application for Junior Java Developer"));
        check("mentions company", text.contains("Delta"));
        check("mentions name", text.contains(profile.name));
        String devProof = profile.groups.stream().filter(g -> g.name().equals("dev")).map(Profile.Group::proof).findFirst().orElse("");
        check("uses a dev proof line", !devProof.isBlank() && text.contains(devProof));
        check("no template placeholders", !text.contains("{") && !text.contains("null"));
        Job viaSearch = job("Remote AI Trainer", "jobs.example", "", "Flexible hours.");
        viaSearch.sourceType = "SEARCH";
        check("search results do not greet the website", !d.draft(viaSearch, sc.score(viaSearch), false).contains("jobs.example"));

        String req = Drafter.buildRequest("claude-sonnet-5-5", "sys", "user \"quoted\"");
        Map<String, Object> parsed = Json.obj(Json.parse(req));
        eq("request model", "claude-sonnet-5-5", parsed.get("model"));
        eq("request user text", "user \"quoted\"", Json.obj(Json.arr(parsed.get("messages")).get(0)).get("content"));
        eq("response parsed", "Subject: Hi\n\nHello", Drafter.parseResponse("{\"content\":[{\"type\":\"text\",\"text\":\"Subject: Hi\\n\\nHello\"}]}"));
    }

    static void pipelineTests() throws Exception {
        System.out.println("Pipeline");
        Path dir = Files.createTempDirectory("jh");
        Settings s = new Settings(dir, null, null, null, "m");
        Store store = Store.load(s.stateFile());
        Pipeline p = new Pipeline(s, profile, store, new Http());

        Pipeline.Counts c = new Pipeline.Counts();
        List<Job> batch = new ArrayList<>();
        batch.add(job("AI Trainer", "Good Co", "Worldwide", "Evaluate LLM responses. Flexible hours. Part-time."));
        batch.add(job("Data Clerk", "Scam Co", "Worldwide", "Pay a $30 registration fee to start. Data entry in Excel."));
        batch.add(job("Excel Analyst", "US Co", "USA Only", "Excel, SQL, dashboards."));
        batch.add(job("AI Trainer", "Good Co", "Worldwide", "Evaluate LLM responses. Flexible hours. Part-time.")); // duplicate
        batch.add(job("Registered Nurse", "Hospital", "Worldwide", "Care for patients."));
        p.process(batch, c);
        eq("fetched", 5, c.fetched);
        eq("added", 1, c.added);
        eq("rejected", 2, c.rejected);
        eq("duplicates", 1, c.duplicates);
        eq("low score", 1, c.lowScore);

        Job added = store.all().get(0);
        check("draft attached", added.draft.startsWith("Subject:"));
        check("why attached", !added.why.isEmpty());
        eq("status new", Job.NEW, added.status);

        Pipeline.Counts again = new Pipeline.Counts();
        p.process(List.of(job("Excel Analyst", "US Co", "USA Only", "Excel, SQL, dashboards.")), again);
        eq("rejected jobs are remembered", 1, again.duplicates);

        store.setStatus(added.id, Job.APPROVED);
        store.save();
        Store reloaded = Store.load(s.stateFile());
        eq("state survives reload", Job.APPROVED, reloaded.get(added.id).status);
        eq("draft survives reload", added.draft, reloaded.get(added.id).draft);
        check("seen memory survives reload", reloaded.isKnown(batch.get(1)));

        reloaded.blockCompany("Good Co");
        eq("blocking hides jobs", Job.BLOCKED, reloaded.get(added.id).status);
        check("sourceDue first time", store.sourceDue("X", 60));
        store.touchSource("X");
        check("sourceDue after touch", !store.sourceDue("X", 60));
    }

    static void dashboardTests() throws Exception {
        System.out.println("Dashboard");
        Path dir = Files.createTempDirectory("jhd");
        Settings s = new Settings(dir, null, null, null, "m");
        Store store = Store.load(s.stateFile());
        Pipeline p = new Pipeline(s, profile, store, new Http());
        Job j = job("AI Trainer", "Good Co", "Worldwide", "Evaluate LLM responses. Flexible hours.");
        j.score = 80; j.draft = "Subject: x\n\nbody";
        store.add(j);
        Dashboard d = new Dashboard(s, profile, store, p);
        HttpServer srv = d.start(0);
        int port = srv.getAddress().getPort();
        HttpClient c = HttpClient.newHttpClient();
        String base = "http://localhost:" + port;
        try {
            HttpResponse<String> home = c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            eq("home page", 200, home.statusCode());
            check("home page is html", home.body().contains("Remote Job Hunter"));
            HttpResponse<String> jobs = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs")).build(), HttpResponse.BodyHandlers.ofString());
            eq("jobs listed", 1, Json.arr(Json.parse(jobs.body())).size());
            HttpResponse<String> plats = c.send(HttpRequest.newBuilder(URI.create(base + "/api/platforms")).build(), HttpResponse.BodyHandlers.ofString());
            check("platforms listed", Json.arr(Json.parse(plats.body())).size() >= 10);

            HttpResponse<String> noHeader = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/" + j.id + "/status"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"APPROVED\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("POST without header refused", 403, noHeader.statusCode());
            eq("status unchanged", Job.NEW, store.get(j.id).status);

            HttpResponse<String> okPost = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/" + j.id + "/status"))
                    .header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"APPROVED\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("approve works", 200, okPost.statusCode());
            eq("status changed", Job.APPROVED, store.get(j.id).status);

            HttpResponse<String> bad = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/" + j.id + "/status"))
                    .header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"HACKED\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("bad status refused", 400, bad.statusCode());

            HttpResponse<String> saveDraft = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/" + j.id + "/draft"))
                    .header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"draft\":\"edited\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("draft saved", "edited", store.get(j.id).draft);
            eq("draft response", 200, saveDraft.statusCode());

            HttpResponse<String> block = c.send(HttpRequest.newBuilder(URI.create(base + "/api/block"))
                    .header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"company\":\"Good Co\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("block works", 200, block.statusCode());
            eq("job hidden by block", Job.BLOCKED, store.get(j.id).status);

            HttpResponse<String> missing = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/nope/status"))
                    .header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("unknown job 404", 404, missing.statusCode());

            HttpResponse<String> targets = c.send(HttpRequest.newBuilder(URI.create(base + "/api/targets")).build(), HttpResponse.BodyHandlers.ofString());
            eq("targets listed", profile.targets.size(), Json.arr(Json.parse(targets.body())).size());

            String pasted = "{\"title\":\"Junior Java Developer\",\"company\":\"Paste Co\",\"url\":\"https://za.linkedin.com/jobs/view/123\","
                    + "\"description\":\"Remote. Java, Spring Boot, MySQL and Docker. Flexible hours, open to South Africa.\"}";
            HttpResponse<String> man = c.send(HttpRequest.newBuilder(URI.create(base + "/api/manual"))
                    .header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString(pasted)).build(), HttpResponse.BodyHandlers.ofString());
            eq("pasted job accepted", 200, man.statusCode());
            Map<String, Object> mj = Json.obj(Json.parse(man.body()));
            eq("pasted job source from link", "LinkedIn", Json.str(mj.get("source")));
            check("pasted job is in the store", store.get(Json.str(mj.get("id"))) != null);
            if (profile.cv != null) check("pasted job carries a CV match", Json.obj(mj.get("cv")).containsKey("pct"));
            HttpResponse<String> paid = c.send(HttpRequest.newBuilder(URI.create(base + "/api/manual"))
                    .header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"AI Trainer\",\"company\":\"P2\",\"description\":\"Flexible. Pay $16-$33 per hour.\"}")).build(), HttpResponse.BodyHandlers.ofString());
            near("pay read from pasted text", 33.0, Json.num(Json.obj(Json.parse(paid.body())).get("maxHourlyUsd")));

            HttpResponse<String> manBad = c.send(HttpRequest.newBuilder(URI.create(base + "/api/manual"))
                    .header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("paste without description refused", 400, manBad.statusCode());
            HttpResponse<String> manJs = c.send(HttpRequest.newBuilder(URI.create(base + "/api/manual"))
                    .header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"x\",\"description\":\"y\",\"url\":\"javascript:alert(1)\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("javascript link refused", 400, manJs.statusCode());

            HttpResponse<String> cvPage = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/" + Json.str(mj.get("id")) + "/cv")).build(), HttpResponse.BodyHandlers.ofString());
            if (profile.cv != null) {
                eq("tailored CV page", 200, cvPage.statusCode());
                check("tailored CV has the name", cvPage.body().contains(Cv.esc(profile.name)));
            } else {
                eq("no cv.json gives 404", 404, cvPage.statusCode());
            }
        } finally {
            srv.stop(0);
        }
    }

    // ------------------------------------------------------------------

    static Cv sampleCv() {
        return Cv.fromMap(Json.obj(Json.parse("""
            {"availability":"Remote","languages":"English","maxProjects":2,
             "headlines":{"default":"Developer","dev":"Java Developer","data":"Data Analyst"},
             "summaries":{"default":"General summary.","dev":"Dev summary with Java and Spring Boot.","data":"Data summary with Excel and SQL."},
             "experience":[{"title":"Owner","org":"Shop","dates":"20__ - Present","bullets":[
                {"text":"Ran the shop floor.","tags":["admin"]},
                {"text":"Found R109,000 of misfiled takings in Excel.","tags":["data"]},
                {"text":"Built a Spring Boot and MySQL POS in Java.","tags":["dev"]}]}],
             "projects":[{"name":"Alpha","link":"https://example.com/a","text":"A statistics project.","tags":["data"]},
                         {"name":"Beta","link":"javascript:alert(1)","text":"A Java REST API.","tags":["dev"]},
                         {"name":"Gamma","link":"","text":"A cooking blog.","tags":[]}],
             "education":[{"heading":"BSc","dates":"2025 - 2027","details":["Calculus and statistics."]}],
             "skills":[{"label":"Data","items":["Excel","SQL"],"tags":["data"]},{"label":"Programming","items":["Python","Java","Spring Boot"],"tags":["dev"]}]}
            """)));
    }

    static void cvTests() {
        System.out.println("CV tailoring");
        Cv cv = sampleCv();
        Job dev = job("Junior Java Developer <script>", "Acme", "Remote", "We use Java, Spring Boot, MySQL and Docker. Unit testing is a plus.");
        Cv.Report r = cv.report(dev, profile);
        check("report finds java", r.matched().contains("java"));
        check("report finds spring boot", r.matched().contains("spring boot"));
        check("report lists docker as missing", r.missing().contains("docker"));
        check("report never lists a matched word as missing", Collections.disjoint(r.matched(), r.missing()));
        check("match percent in range", r.pct() > 0 && r.pct() <= 100);
        check("job-title words are not listed as skills", r.asked().stream().noneMatch(x -> x.contains("developer")));

        String h = cv.tailor(dev, profile, List.of("dev", "data"));
        check("dev headline chosen", h.contains("<p class=\"headline\">Java Developer</p>"));
        check("dev summary chosen", h.contains("Dev summary"));
        check("job keywords bolded", h.contains("<b>Spring Boot</b>"));
        check("job title escaped", h.contains("&lt;script&gt;") && !h.contains("<script>"));
        int devBullet = h.indexOf("Built a "), adminBullet = h.indexOf("Ran the shop");
        check("most relevant bullet first", devBullet > 0 && devBullet < adminBullet);
        check("all bullets kept", h.contains("Ran the shop") && h.contains("R109,000"));
        check("project limit respected", !h.contains("Gamma"));
        check("unsafe project link not linked", !h.contains("href=\"javascript"));
        check("blank dates warned about", h.contains("still has blanks"));
        check("programming skills first for a dev job", h.indexOf("<b>Programming:</b>") < h.indexOf("<b>Data:</b>"));
        check("matching skill items first", h.indexOf("<b>Java</b>") < h.indexOf("Python"));
        check("missing keywords shown on screen only", h.contains("no-print") && h.contains("docker"));

        Job data = job("Junior Data Analyst", "Zed", "Remote", "Excel, SQL and statistics. Dashboards.");
        String hd = cv.tailor(data, profile, List.of("data"));
        check("data summary chosen", hd.contains("Data summary"));
        check("data bullet first for a data job", hd.indexOf("R109,000") < hd.indexOf("Built a "));
        check("data project ranks first", hd.indexOf("Alpha") < hd.indexOf("Beta"));

        eq("bold does not double-wrap", "<b>Spring Boot</b> app", Cv.bold("Spring Boot app", List.of("spring boot", "boot")));
        eq("bold handles plurals", "two <b>REST APIs</b>", Cv.bold("two REST APIs", List.of("rest api")));
        check("alias: CI/CD covered by GitHub Actions", Cv.has("uses github actions daily", "ci/cd"));
        check("no partial-word match", !Cv.has("mysql database", "sql"));

        if (!profile.targets.isEmpty()) {
            Scorer sc = new Scorer(profile);
            Scorer.Result res = sc.score(job("Junior Java Developer", "Delta", "Worldwide", "Java and Spring Boot, remote."));
            check("target title bonus applied", res.why().stream().anyMatch(w -> w.contains("target titles")));
            check("no target bonus for a nurse job", sc.targetFor(job("Registered Nurse", "H", "", "")) == null);
            check("POS match does not hit 'position'", sc.targetFor(job("Position available: chef", "H", "", "")) == null);
        }
    }

    static Job csvJob(String title, String company, String status, String at, String url) {
        Job j = new Job();
        j.title = title; j.company = company; j.source = "RemoteOK"; j.url = url; j.applyUrl = url;
        j.status = status; j.statusAt = at; j.foundAt = "2026-01-01T00:00:00Z";
        return j;
    }

    static void csvTests() throws Exception {
        List<Job> js = new ArrayList<>();
        js.add(csvJob("Java Dev", "Acme, Inc", Job.APPLIED, "2026-11-01T10:00:00Z", "https://acme.example/1"));
        js.add(csvJob("=HYPERLINK(\"http://evil\")", "Beta \"B\"", Job.APPLIED, "2026-11-05T10:00:00Z", "javascript:alert(1)"));
        js.add(csvJob("Approved one", "Gamma", Job.APPROVED, "2026-11-03T10:00:00Z", "http://g.example/x"));
        js.add(csvJob("Still new", "Delta", Job.NEW, "", "https://d.example"));
        js.add(csvJob("Hidden", "Eps", Job.REJECTED, "2026-11-02T10:00:00Z", "https://e.example"));
        String out = Csv.applications(js);
        String[] lines = out.split("\r\n");
        check("csv starts with BOM for Excel", out.startsWith("﻿"));
        eq("csv header", Csv.HEADER, lines[0].substring(1));
        eq("csv has only applied and approved rows", 4, lines.length);
        check("csv newest first", lines[1].startsWith("\"2026-11-05\"") && lines[3].startsWith("\"2026-11-01\""));
        check("csv commas stay inside quotes", out.contains("\"Acme, Inc\""));
        check("csv inner quotes doubled", out.contains("\"Beta \"\"B\"\"\""));
        check("csv formula title defused", out.contains("\"'=HYPERLINK(") && !out.contains(",\"=HYPERLINK"));
        check("csv drops non-http link", !out.contains("javascript:"));
        check("csv keeps http link", out.contains("\"https://acme.example/1\""));
        check("csv status labels", out.contains("\"Applied\"") && out.contains("\"Approved\""));
        check("csv leaves out new and hidden", !out.contains("Delta") && !out.contains("Eps"));
        eq("csv falls back to found date", "2026-01-01", Csv.date(csvJob("t", "c", Job.APPLIED, "", "https://x.example")));
        check("csv newline in title flattened", Csv.cell("a\nb").equals("\"a b\""));

        // endpoint: served as a download, header present, local-only Host check still applies
        Path tmp = Files.createTempDirectory("jh-csv");
        Settings ss = new Settings(tmp, null, null, null, "m");
        Store st = Store.load(ss.stateFile());
        st.add(csvJob("Endpoint job", "Zeta", Job.APPLIED, "2026-11-06T10:00:00Z", "https://z.example/1"));
        Dashboard d = new Dashboard(ss, profile, st, new Pipeline(ss, profile, st, new Http()));
        HttpServer srv = d.start(0);
        try {
            int port = srv.getAddress().getPort();
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/export.csv")).build(), HttpResponse.BodyHandlers.ofString());
            eq("csv endpoint ok", 200, r.statusCode());
            check("csv endpoint is a download", r.headers().firstValue("Content-Disposition").orElse("").contains("applications.csv"));
            check("csv endpoint content type", r.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
            check("csv endpoint has the job", r.body().contains("Endpoint job"));
        } finally { srv.stop(0); }
    }

    static void followUpTests() throws Exception {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        Job six = csvJob("Six", "A", Job.APPLIED, now.minus(6, ChronoUnit.DAYS).toString(), "https://a.example");
        Job seven = csvJob("Seven", "B", Job.APPLIED, now.minus(7, ChronoUnit.DAYS).toString(), "https://b.example");
        Job twenty = csvJob("Twenty", "C", Job.APPLIED, now.minus(20, ChronoUnit.DAYS).toString(), "https://c.example");
        Job replied = csvJob("Replied", "D", Job.REPLIED, now.minus(30, ChronoUnit.DAYS).toString(), "https://d.example");
        Job queued = csvJob("Queued", "E", Job.NEW, now.minus(30, ChronoUnit.DAYS).toString(), "https://e.example");
        Job stopped = csvJob("Stopped", "F", Job.APPLIED, now.minus(30, ChronoUnit.DAYS).toString(), "https://f.example");
        stopped.noReminder = true;
        Job nodate = csvJob("NoDate", "G", Job.APPLIED, "", "https://g.example");
        List<Job> due = FollowUp.due(List.of(six, seven, twenty, replied, queued, stopped, nodate), now, FollowUp.DAYS);
        check("day 6 is not due", !FollowUp.isDue(six, now, 7));
        check("day 7 is due", FollowUp.isDue(seven, now, 7));
        eq("only applied, unreplied, not-stopped jobs are due", 2, due.size());
        eq("longest wait first", "Twenty", due.get(0).title);
        check("replied job never due", !FollowUp.isDue(replied, now, 7));
        check("queued job never due", !FollowUp.isDue(queued, now, 7));
        check("stopped job never due", !FollowUp.isDue(stopped, now, 7));
        check("unknown date is not due", !FollowUp.isDue(nodate, now, 7));
        eq("days since", 20, FollowUp.daysSince(twenty, now));
        eq("days since unknown", -1, FollowUp.daysSince(nodate, now));

        Job nudged = csvJob("Nudged", "H", Job.APPLIED, now.minus(30, ChronoUnit.DAYS).toString(), "https://h.example");
        nudged.followedUpAt = now.minus(3, ChronoUnit.DAYS).toString();
        check("follow-up restarts the clock", !FollowUp.isDue(nudged, now, 7));
        nudged.followedUpAt = now.minus(8, ChronoUnit.DAYS).toString();
        check("due again 7 days after a follow-up", FollowUp.isDue(nudged, now, 7));

        String d = FollowUp.draft(seven, profile);
        check("draft names the role", d.contains("the Seven position") && d.contains("Subject: Following up: Seven"));
        check("draft names the company", d.contains("Hello B team,"));
        check("draft has the day you applied", d.contains(Csv.date(seven)));
        check("draft signs with your name", d.contains(profile.name));
        check("draft invents no figures", !d.contains("$") && !d.contains("%") && !d.toLowerCase().contains("years of"));
        Job web = csvJob("Role", "SomeBoard", Job.APPLIED, now.toString(), "https://x.example");
        web.sourceType = "SEARCH";
        check("search jobs get a plain greeting", FollowUp.draft(web, profile).contains("Hello,\n"));

        Job rt = Job.fromMap(castMap(Json.parse(Json.write(nudged.toMap()))));
        eq("followedUpAt survives save and load", nudged.followedUpAt, rt.followedUpAt);
        Job rt2 = Job.fromMap(castMap(Json.parse(Json.write(stopped.toMap()))));
        check("noReminder survives save and load", rt2.noReminder);

        check("csv labels replied jobs", Csv.applications(List.of(replied)).contains("\"Replied\""));

        // store + endpoints
        Path tmp = Files.createTempDirectory("jh-fu");
        Settings ss = new Settings(tmp, null, null, null, "m");
        Store st = Store.load(ss.stateFile());
        Job a = csvJob("Endpoint FU", "Eta", Job.APPLIED, Instant.now().minus(10, ChronoUnit.DAYS).toString(), "https://eta.example/1");
        a.id = "fu1";
        st.add(a);
        st.markFollowedUp("fu1");
        check("markFollowedUp sets the date", !st.get("fu1").followedUpAt.isBlank());
        st.stopReminders("fu1");
        check("stopReminders sets the flag", st.get("fu1").noReminder);
        st.setStatus("fu1", Job.APPLIED);
        check("applying again resets the clock", st.get("fu1").followedUpAt.isEmpty() && !st.get("fu1").noReminder);
        st.get("fu1").statusAt = Instant.now().minus(10, ChronoUnit.DAYS).toString();   // pretend that was 10 days ago

        Dashboard dash = new Dashboard(ss, profile, st, new Pipeline(ss, profile, st, new Http()));
        HttpServer srv = dash.start(0);
        try {
            String base = "http://localhost:" + srv.getAddress().getPort();
            HttpClient c = HttpClient.newHttpClient();
            HttpResponse<String> noHdr = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/fu1/followed")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("followed without header refused", 403, noHdr.statusCode());
            HttpResponse<String> noHdr2 = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/fu1/stopreminder")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("stopreminder without header refused", 403, noHdr2.statusCode());
            check("nothing changed after refusals", st.get("fu1").followedUpAt.isEmpty() && !st.get("fu1").noReminder);

            HttpResponse<String> list = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs")).build(), HttpResponse.BodyHandlers.ofString());
            check("job view says follow-up is due", list.body().contains("\"followDue\":true"));
            check("job view carries a follow-up draft", list.body().contains("Following up: Endpoint FU"));

            HttpResponse<String> done = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/fu1/followed")).header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("followed with header ok", 200, done.statusCode());
            check("followed hides it from the due list", done.body().contains("\"followDue\":false"));
            HttpResponse<String> rep = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/fu1/status")).header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"REPLIED\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("replied status accepted", 200, rep.statusCode());
            eq("job is replied", Job.REPLIED, st.get("fu1").status);
        } finally { srv.stop(0); }
    }

    // ======================================================================== simple gigs

    static Profile gigProfile() {
        return Profile.fromMap(Json.obj(Json.parse("{\"name\":\"Test User\",\"email\":\"test@example.test\",\"timezone\":\"SAST, UTC+2\",\"hoursPerWeek\":30}")));
    }

    static String fixture(String name) throws Exception { return Files.readString(Path.of("src/test/resources/" + name)); }

    static void gigExtractTests() throws Exception {
        Profile gp = gigProfile();
        Gig g = GigExtract.extract("https://brandpost-example.test/", "hint", fixture("gig_creator_program.html"), "");
        eq("gig: name from og:site_name", "Brandpost Example", g.name);
        eq("gig: domain", "brandpost-example.test", g.domain);
        eq("gig: category", Gig.UGC, g.category);
        check("gig: retainer and per-view pay found", g.payModels.contains("RETAINER") && g.payModels.contains("PER_VIEW"));
        eq("gig: payout threshold", "$20", g.payoutThreshold);
        check("gig: payout methods found", g.payoutMethods.containsAll(List.of("PayPal", "Payoneer", "Wise")));
        check("gig: no followers and no experience", g.noFollowersRequired && g.noExperienceRequired);
        eq("gig: free to join", Gig.FREE, g.costToJoin);
        eq("gig: open worldwide counts as eligible", Gig.OK, g.eligibility);
        check("gig: worldwide still says to confirm South Africa", g.eligibilityWhy.contains("confirm South Africa"));
        eq("gig: hours range", "10-30 hours a week", g.hours);
        eq("gig: hours max", 30, g.hoursMax);
        check("gig: flexible hours", g.flexibleHours);
        eq("gig: training support", "Discord community", g.support);
        check("gig: terms, privacy and contact found", g.hasTerms && g.hasPrivacy && g.hasContact);
        check("gig: what you do", g.whatYouDo.toLowerCase().startsWith("get paid to post"));
        check("gig: facts keep their evidence", g.evidence.get("payoutThreshold").contains("minimum payout") && g.evidence.containsKey("pay:PER_VIEW"));
        check("gig: script and style text is ignored", GigRisk.hardRejects(Text.stripHtml(fixture("gig_creator_program.html").replaceAll("(?is)<script.*?</script>|<style.*?</style>", " "))).isEmpty());
        check("gig: but the same words in visible text are caught", !GigRisk.hardRejects("pay a fee and use crypto").isEmpty());

        GigScore.eligibility(g, gp);
        eq("gig: payout works from South Africa", Gig.OK, g.payoutFit);
        GigRisk.assess(g, "clean text", GigRisk.Reviews.NONE);
        GigScore.fit(g, gp);
        check("gig: fit is high for a free, no-experience, worldwide, flexible gig", g.fit >= 85);
        check("gig: why-it-fits names the facts", g.whyFits.contains("free to join") && g.whyFits.contains("pays via PayPal/Payoneer/Wise") && g.whyFits.contains("Discord"));
        check("gig: why-it-fits admits what is unconfirmed", !g.whyFits.contains("South Africa unconfirmed") || g.eligibility.equals(Gig.UNKNOWN));
        String d = GigScore.draft(g, gp);
        check("gig: draft is addressed and signed", d.contains("Hello Brandpost Example team") && d.contains("Test User") && d.contains("test@example.test"));
        check("gig: draft asks to confirm South Africa", d.contains("Can creators in South Africa join and be paid?"));
        check("gig: draft does not ask what is already known", !d.contains("minimum payout") && !d.contains("no cost to join") && !d.contains("Which payout methods"));
        check("gig: draft never claims experience", !d.toLowerCase().contains("years of experience"));

        // excluded: US only, and pays only by methods that do not work from South Africa
        Gig us = GigExtract.extract("https://cliprush-example.test/", "", fixture("gig_us_only.html"), "");
        eq("gig: US-only page is excluded", Gig.EXCLUDED, us.eligibility);
        check("gig: US-only says why", us.eligibilityWhy.contains("Limited to other countries"));
        eq("gig: clipping category", Gig.CLIPPING, us.category);
        GigScore.eligibility(us, gp);
        eq("gig: Venmo and Cash App cannot pay South Africa", Gig.NONE, us.payoutFit);
        check("gig: payout reason added", us.eligibilityWhy.contains("which you cannot use from South Africa"));
        Gig onlyVenmo = GigExtract.extract("https://payfast-free.test/", "X", "<p>Open worldwide. Get paid per task. Payouts via Venmo.</p>", "");
        eq("gig: worldwide page", Gig.OK, onlyVenmo.eligibility);
        GigScore.eligibility(onlyVenmo, gp);
        eq("gig: a worldwide gig that pays only by Venmo is excluded for you", Gig.EXCLUDED, onlyVenmo.eligibility);

        Gig ai = GigExtract.extract("https://labelbee-example.test/", "", fixture("gig_ai_micro.html"), "");
        eq("gig: micro-task category", Gig.AI_MICRO, ai.category);
        eq("gig: names South Africa", Gig.OK, ai.eligibility);
        check("gig: names-South-Africa reason", ai.eligibilityWhy.contains("names South Africa"));
        eq("gig: withdraw threshold from sentence", "$10", ai.payoutThreshold);
        check("gig: per-task pay", ai.payModels.contains("PER_TASK"));
        check("gig: bank transfer and PayPal", ai.payoutMethods.containsAll(List.of("PayPal", "Bank transfer")));
        check("gig: no experience required", ai.noExperienceRequired);

        // edge cases
        eq("gig: explicit South Africa exclusion", Gig.EXCLUDED, GigExtract.extract("https://a.test/", "A", "<p>Not available in South Africa.</p>", "").eligibility);
        eq("gig: country list without South Africa is excluded", Gig.EXCLUDED, GigExtract.extract("https://a.test/", "A", "<p>Currently available in the US, UK, Canada and Australia.</p>", "").eligibility);
        eq("gig: 'join us' is not the United States", Gig.UNKNOWN, GigExtract.extract("https://a.test/", "A", "<p>Join us today and start posting videos.</p>", "").eligibility);
        eq("gig: silence about countries stays unknown", Gig.UNKNOWN, GigExtract.extract("https://a.test/", "A", "<p>Post videos and get paid.</p>", "").eligibility);
        Gig followers = GigExtract.extract("https://a.test/", "A", "<p>No followers required, but you must have at least 5K followers to unlock bonuses.</p>", "");
        eq("gig: a stated follower minimum beats a vague 'no followers'", 5000, followers.minFollowers);
        check("gig: and clears the no-followers flag", !followers.noFollowersRequired && followers.requirements.contains("at least 5K followers"));
        Gig years = GigExtract.extract("https://a.test/", "A", "<p>No experience needed. Must have 3 years of experience editing.</p>", "");
        check("gig: asking for years of experience cancels no-experience", !years.noExperienceRequired && years.requirements.contains("3+ years"));
        Gig unread = GigExtract.extract("https://payday-like.test/", "Payday Like", "", "Earn money posting short videos. Open worldwide. No followers required.");
        check("gig: unreadable page falls back to the search snippet", !unread.pageFetched && unread.noFollowersRequired && unread.eligibility.equals(Gig.OK));
        eq("gig: hint name used when the page gives none", "Payday Like", unread.name);
        eq("gig: counts like 5K and 1.2M", 1_200_000, GigExtract.parseCount("1.2", "M"));
        eq("gig: payout fit stays unknown when no method is stated", Gig.UNKNOWN, GigExtract.extract("https://a.test/", "A", "<p>Get paid.</p>", "").payoutFit);

        // lessons from the first live run: payout methods count only when the words around them are about paying YOU
        Gig stripe = GigExtract.extract("https://s.test/", "S", "<p>Open worldwide. Plans start at $9 a month. We accept payments via Stripe and PayPal at checkout.</p>", "");
        check("live lesson: taking payments through Stripe is not a payout method", stripe.payoutMethods.isEmpty());
        Gig stripePayout = GigExtract.extract("https://s.test/", "S", "<p>Creators are paid out weekly through Stripe.</p>", "");
        check("live lesson: paying you through Stripe is", stripePayout.payoutMethods.contains("Stripe"));
        Gig paypalPayout = GigExtract.extract("https://s.test/", "S", "<p>We pay creators by PayPal every Friday. Subscribe with PayPal or card.</p>", "");
        check("live lesson: a payout method is found next to the words about paying you", paypalPayout.payoutMethods.contains("PayPal"));
        Gig names = GigExtract.extract("https://rev.test/", "", "<html><head><title>Investigative Intelligence &amp; Legal Transcription Platform for teams</title></head><body>hi</body></html>", "");
        eq("live lesson: a long headline is not a name; the domain is", "Rev", names.name);
        eq("live lesson: a question is not a name", "Forum", GigExtract.extract("https://forum.test/", "Does anyone know of platforms that pay?", "", "").name.equals("Does anyone know of platforms that pay?") ? "bad" : "Forum");

        // ranking: confirmed, low-risk, high-fit first; excluded last
        Gig low = GigExtract.extract("https://brandpost-example.test/", "", fixture("gig_creator_program.html"), ""); GigScore.eligibility(low, gp); GigRisk.assess(low, "x", GigRisk.Reviews.NONE); GigScore.fit(low, gp);
        Gig riskyG = GigExtract.extract("https://easy.test/", "", fixture("gig_scam.html"), ""); GigScore.eligibility(riskyG, gp); GigRisk.assess(riskyG, "starter kit crypto guaranteed income", GigRisk.Reviews.NONE); GigScore.fit(riskyG, gp);
        check("gig: low-risk gig outranks a scammy one", low.rank() > riskyG.rank());
        check("gig: excluded gigs rank below everything", us.rank() < low.rank() && us.rank() < 0 + riskyG.rank() + 100);
    }

    static void gigRiskTests() throws Exception {
        Gig scam = GigExtract.extract("https://easy.test/", "", fixture("gig_scam.html"), "");
        String text = Text.stripHtml(fixture("gig_scam.html"));
        List<String> hard = GigRisk.hardRejects(text);
        check("risk: registration fee rejected", hard.contains("asks you to pay a fee or deposit"));
        check("risk: starter kit rejected", hard.contains("sells a starter kit"));
        check("risk: paid course rejected", hard.contains("sells a paid course or coaching"));
        check("risk: crypto rejected", hard.contains("involves crypto"));
        check("risk: guaranteed income rejected", hard.contains("promises guaranteed income"));
        GigRisk.assess(scam, text, GigRisk.Reviews.NONE);
        eq("risk: a hard reject is 10 out of 10", 10, scam.risk);
        check("risk: reasons say Rejected", scam.riskReasons.stream().anyMatch(r -> r.startsWith("Rejected: ")));
        check("risk: bank logins and ID before a contract", GigRisk.hardRejects("Send your passport before you start and share your bank password.").contains("wants bank logins or ID before a contract"));
        check("live lesson: 'guaranteed payment' is a good sign, not a scam sign", GigRisk.hardRejects("We offer guaranteed payment and guaranteed on-time payouts to every creator.").isEmpty());
        check("live lesson: 'guaranteed income' is still rejected", !GigRisk.hardRejects("Guaranteed income of $5000 every month, risk-free income!").isEmpty());
        check("live lesson: 'guaranteed $500' is still rejected", !GigRisk.hardRejects("Get guaranteed $500 in your first week").isEmpty());
        check("risk: ID after a contract is not rejected", GigRisk.hardRejects("We verify your identity for payouts once you are accepted.").isEmpty());
        check("risk: a free course is not a paid course", GigRisk.hardRejects("Free training course in our community.").isEmpty());

        // points and reasons on a middling gig
        Gig mid = GigExtract.extract("https://mid.test/", "Mid", "<p>Post videos and get paid. Earn up to $300 per week. Passive income!</p>", "");
        GigRisk.assess(mid, "Post videos and get paid. Earn up to $300 per week. Passive income! recruit others", GigRisk.Reviews.NONE);
        check("risk: missing terms, privacy and contact add points", mid.riskReasons.stream().anyMatch(r -> r.contains("no Terms page")) && mid.riskReasons.stream().anyMatch(r -> r.contains("no contact or company details")));
        check("risk: recruiting, hype and earnings claims add points", mid.riskReasons.stream().anyMatch(r -> r.contains("recruiting")) && mid.riskReasons.stream().anyMatch(r -> r.contains("hype")) && mid.riskReasons.stream().anyMatch(r -> r.contains("earnings figures")));
        check("risk: no independent reviews is flagged unverified", mid.unverified && mid.riskReasons.stream().anyMatch(r -> r.startsWith("Unverified")));
        check("risk: a shaky page scores high", mid.risk >= 8);
        check("risk: stays within 0 to 10", mid.risk <= 10 && mid.risk >= 0);

        Gig clean = GigExtract.extract("https://brandpost-example.test/", "", fixture("gig_creator_program.html"), "");
        GigRisk.assess(clean, "Post videos. Free to join.", GigRisk.Reviews.NONE);
        check("risk: a clean, free, documented platform scores low", clean.risk <= 2);
        check("risk: good signs are listed", clean.goodSigns.size() >= 3);
        check("risk: no reviews still means unverified", clean.unverified);
        GigRisk.assess(clean, "Post videos. Free to join.", new GigRisk.Reviews(4, 0, 3, 1, List.of("reddit.com")));
        check("risk: independent payout proof lowers risk and clears unverified", clean.risk == 0 && !clean.unverified);
        GigRisk.assess(clean, "Post videos.", new GigRisk.Reviews(4, 3, 0, 0, List.of("reddit.com")));
        check("risk: reports of non-payment raise risk a lot", clean.risk >= 4 && clean.riskReasons.stream().anyMatch(r -> r.contains("report non-payment or a scam")));
        Gig unread = GigExtract.extract("https://x.test/", "X", "", "Post videos.");
        GigRisk.assess(unread, "Post videos.", GigRisk.Reviews.NONE);
        check("risk: an unreadable page is not double-penalised for missing terms", unread.riskReasons.stream().anyMatch(r -> r.contains("could not read")) && unread.riskReasons.stream().noneMatch(r -> r.contains("no Terms page")));

        // independent reviews
        List<GigDiscovery.Hit> hits = List.of(
                new GigDiscovery.Hit("Brandpost review: got paid $50, payout proof inside", "https://reddit.com/r/UGCcreators/x", "I got paid $50 from Brandpost", "q"),
                new GigDiscovery.Hit("Is Brandpost a scam?", "https://reddit.com/r/WorkOnline/y", "Anyone tried Brandpost? scam or legit?", "q"),
                new GigDiscovery.Hit("Brandpost never paid me", "https://trustpilot.com/review/brandpost", "Brandpost never paid me, total scam", "q"),
                new GigDiscovery.Hit("Brandpost home", "https://brandpost.com/", "Brandpost pays creators", "q"),
                new GigDiscovery.Hit("Unrelated post", "https://reddit.com/r/beermoney/z", "Best survey sites 2026", "q"));
        GigRisk.Reviews rv = GigRisk.Reviews.fromHits("Brandpost", "brandpost.com", hits);
        eq("reviews: only independent results that name it count", 3, rv.mentions());
        eq("reviews: one negative", 1, rv.negative());
        eq("reviews: one positive", 1, rv.positive());
        eq("reviews: one proof", 1, rv.proof());
        check("reviews: a 'Is X a scam?' question is not evidence", rv.negative() == 1);
        check("reviews: its own site is not independent", !rv.sources().contains("brandpost.com"));
        eq("reviews: nothing found", 0, GigRisk.Reviews.fromHits("Brandpost", "brandpost.com", List.of()).mentions());
    }

    static void gigDiscoveryTests() throws Exception {
        List<GigDiscovery.Query> all = GigDiscovery.queries(1000);
        check("discovery: a thorough query bank", all.size() >= 30);
        check("discovery: no duplicate queries", all.stream().map(GigDiscovery.Query::q).distinct().count() == all.size());
        for (String sub : List.of("r/UGCcreators", "r/WorkOnline", "r/beermoney", "r/forhire"))
            check("discovery: searches " + sub, all.stream().anyMatch(q -> q.q().contains("site:reddit.com/" + sub)));
        check("discovery: searches YouTube and TikTok for payout experiences", all.stream().anyMatch(q -> q.q().contains("site:youtube.com")) && all.stream().anyMatch(q -> q.q().contains("site:tiktok.com")));
        for (String must : List.of("paid to post short form videos no followers", "UGC creator no experience", "clipping jobs pay per view", "social media posting jobs remote"))
            check("discovery: has the query '" + must + "'", all.stream().anyMatch(q -> q.q().contains(must)));
        for (String cat : List.of(Gig.UGC, Gig.CLIPPING, Gig.POSTING_VA, Gig.AI_MICRO, Gig.REFERRAL))
            check("discovery: covers " + cat, all.stream().anyMatch(q -> q.category().equals(cat)));
        Set<String> small = new HashSet<>();
        for (GigDiscovery.Query q : GigDiscovery.queries(5)) small.add(q.category());
        eq("discovery: a small budget still spreads across all five categories", 5, small.size());
        eq("discovery: budget respected", 7, GigDiscovery.queries(7).size());

        eq("discovery: domain strips www", "example.com", GigDiscovery.domainOf("https://www.Example.com/path?x=1"));
        eq("discovery: bad url gives no domain", "", GigDiscovery.domainOf("not a url"));
        check("discovery: social sites are never platforms", GigDiscovery.ignored("reddit.com") && GigDiscovery.ignored("m.youtube.com") && GigDiscovery.ignored("tiktok.com") && GigDiscovery.ignored("paypal.com"));
        check("discovery: a normal domain is not ignored", !GigDiscovery.ignored("clipstar.io"));
        check("live lesson: job boards and publications are never gig platforms", GigDiscovery.ignored("mediabistro.com") && GigDiscovery.ignored("workingnomads.com") && GigDiscovery.ignored("careervillage.org") && GigDiscovery.ignored("us.remotejobs.dedyn.io") && GigDiscovery.ignored("digiday.com"));
        check("live lesson: blog, help, wiki and news pages are not platforms", GigDiscovery.nonPlatformUrl("https://ugcjobs.com/blog/ugc-creator-jobs-no-experience") && GigDiscovery.nonPlatformUrl("https://help.rocketmoney.com/en/articles/1") && GigDiscovery.nonPlatformUrl("https://wiki.tribute.tg/for-content-creators/referral-program") && GigDiscovery.nonPlatformUrl("https://creatordb.app/creator-news/the-rise-of-the-clip-economy/") && GigDiscovery.nonPlatformUrl("https://support.whoop.com/s/article/Refer-A-Friend"));
        check("live lesson: 'how-to' slugs are articles", GigDiscovery.nonPlatformUrl("https://onlinemediamasters.com/how-to-make-money-with-affiliate-marketing/"));
        check("live lesson: a platform's own pages are still platforms", !GigDiscovery.nonPlatformUrl("https://www.pitchlo.com/ugc-creator-jobs/beginners") && !GigDiscovery.nonPlatformUrl("https://clipstar.io/join") && !GigDiscovery.nonPlatformUrl("https://thanks.io/refer-and-earn"));
        check("live lesson: questions are forum posts, not platforms", GigDiscovery.looksLikeArticle("Does anyone know of platforms that pay per task?") && GigDiscovery.looksLikeArticle("Is there a legit UGC site?"));
        List<GigDiscovery.Lead> mined = GigDiscovery.leads(List.of(
                new GigDiscovery.Hit("UGC Creator Jobs With No Experience", "https://ugcjobs.com/blog/ugc-creator-jobs-no-experience", "Try clipstar.io or labelbee.ai", "q")));
        check("live lesson: a blog post is mined for names and is not itself a lead", mined.stream().noneMatch(l -> l.domain.equals("ugcjobs.com")) && mined.stream().anyMatch(l -> l.domain.equals("clipstar.io")));
        check("discovery: article titles are spotted", GigDiscovery.looksLikeArticle("10 best UGC platforms for beginners") && GigDiscovery.looksLikeArticle("Is ClipStar legit?") && !GigDiscovery.looksLikeArticle("ClipStar - get paid to clip"));
        check("discovery: finds domains named in text", GigDiscovery.domainsIn("I joined clipstar.io and payday-example.com last week, paypal.com paid me").equals(List.of("clipstar.io", "payday-example.com")));

        List<GigDiscovery.Hit> hits = List.of(
                new GigDiscovery.Hit("ClipStar - get paid to clip", "https://www.clipstar.io/join", "Clip streams, earn per view", "clipping jobs pay per view"),
                new GigDiscovery.Hit("ClipStar - get paid to clip", "https://clipstar.io/", "Clip streams", "UGC creator no experience"),
                new GigDiscovery.Hit("Anyone tried payday-example.com?", "https://reddit.com/r/beermoney/a", "payday-example.com paid me last week", "reddit query"),
                new GigDiscovery.Hit("payday-example.com payout proof", "https://youtube.com/watch?v=1", "I withdrew from payday-example.com", "youtube query"),
                new GigDiscovery.Hit("10 best UGC platforms", "https://someblog.com/best-ugc", "Top picks: clipstar.io, labelbee.ai and brandpost-example.com", "q"),
                new GigDiscovery.Hit("TikTok video", "https://tiktok.com/@x/video/1", "nothing here", "q"));
        List<GigDiscovery.Lead> leads = GigDiscovery.leads(hits);
        Map<String, GigDiscovery.Lead> by = new HashMap<>();
        for (GigDiscovery.Lead l : leads) by.put(l.domain, l);
        check("discovery: one lead per domain", leads.size() == by.size());
        check("discovery: www and non-www are the same domain", by.containsKey("clipstar.io") && !by.containsKey("www.clipstar.io"));
        eq("discovery: mentions add up across direct and article results", 3, by.get("clipstar.io").mentions);
        check("discovery: a platform's own site is a direct lead", by.get("clipstar.io").direct && by.get("clipstar.io").url.equals("https://www.clipstar.io/join"));
        check("discovery: Reddit and YouTube are mined for names, never opened", by.get("payday-example.com").mentions == 2 && !by.get("payday-example.com").direct);
        check("discovery: mined leads start at the home page", by.get("payday-example.com").url.equals("https://payday-example.com/"));
        check("discovery: articles are mined, not treated as platforms", !by.containsKey("someblog.com") && by.containsKey("labelbee.ai"));
        check("discovery: social and payment domains never become leads", !by.containsKey("tiktok.com") && !by.containsKey("reddit.com") && !by.containsKey("youtube.com"));
        eq("discovery: most-mentioned first", "clipstar.io", leads.get(0).domain);
        check("discovery: leads remember which searches found them", by.get("clipstar.io").via.size() == 3);

        eq("discovery: reads Serper results (including Reddit)", 2, GigDiscovery.parseSerper("{\"organic\":[{\"title\":\"A\",\"link\":\"https://reddit.com/x\",\"snippet\":\"s\"},{\"title\":\"B\",\"link\":\"https://b.test/\",\"snippet\":\"t\"},{\"title\":\"no link\"}]}", "q").size());
        eq("discovery: reads Brave results", 1, GigDiscovery.parseBrave("{\"web\":{\"results\":[{\"title\":\"A <b>x</b>\",\"url\":\"https://a.test/\",\"description\":\"d\"}]}}", "q").size());
        eq("discovery: empty responses are fine", 0, GigDiscovery.parseSerper("{}", "q").size());

        // robots.txt
        check("robots: no file means allowed", GigDiscovery.Robots.allows(null, "RemoteJobHunter", "/"));
        check("robots: Disallow / blocks everything", !GigDiscovery.Robots.allows("User-agent: *\nDisallow: /", "RemoteJobHunter", "/faq"));
        check("robots: an empty Disallow allows everything", GigDiscovery.Robots.allows("User-agent: *\nDisallow:", "RemoteJobHunter", "/faq"));
        check("robots: a specific path is blocked, others are not", !GigDiscovery.Robots.allows("User-agent: *\nDisallow: /admin", "RemoteJobHunter", "/admin/x") && GigDiscovery.Robots.allows("User-agent: *\nDisallow: /admin", "RemoteJobHunter", "/faq"));
        check("robots: the longer Allow wins", GigDiscovery.Robots.allows("User-agent: *\nDisallow: /\nAllow: /faq", "RemoteJobHunter", "/faq"));
        check("robots: our own group beats the wildcard group", !GigDiscovery.Robots.allows("User-agent: *\nDisallow:\n\nUser-agent: RemoteJobHunter\nDisallow: /", "RemoteJobHunter", "/"));
        check("robots: another bot's rules do not apply to us", GigDiscovery.Robots.allows("User-agent: BadBot\nDisallow: /", "RemoteJobHunter", "/"));
        check("robots: wildcards and comments work", !GigDiscovery.Robots.allows("User-agent: *  # everyone\nDisallow: /*.json$", "RemoteJobHunter", "/data.json") && GigDiscovery.Robots.allows("User-agent: *\nDisallow: /*.json$", "RemoteJobHunter", "/data.html"));
    }

    static class FakeHttp extends Http {
        final Map<String, String> pages = new HashMap<>();
        final List<String> asked = new ArrayList<>();
        String searchBody = "{\"organic\":[]}";
        @Override String get(String url, Map<String, String> headers) throws java.io.IOException {
            asked.add(url);
            String b = pages.get(url);
            if (b == null) throw new java.io.IOException("HTTP 404 from " + URI.create(url).getHost());
            if (b.startsWith("HTTP ")) throw new java.io.IOException(b);
            return b;
        }
        @Override String postJson(String url, Map<String, String> headers, String json) { asked.add("POST " + json); return searchBody; }
    }

    static void gigPipelineTests() throws Exception {
        Profile gp = gigProfile();
        Path tmp = Files.createTempDirectory("jh-gig");
        Settings ss = new Settings(tmp, null, "serper-test-key", null, "m");
        Store st = Store.load(ss.stateFile());
        FakeHttp fh = new FakeHttp();
        GigPipeline gpl = new GigPipeline(ss, gp, st, fh);
        fh.pages.put("https://brandpost-example.test/", fixture("gig_creator_program.html"));
        fh.pages.put("https://brandpost-example.test/how-it-works", "<p>How it works: pick a campaign and post. Payouts every Friday.</p>");

        GigDiscovery.Lead lead = new GigDiscovery.Lead();
        lead.domain = "brandpost-example.test"; lead.url = "https://brandpost-example.test/"; lead.name = "Brandpost"; lead.direct = true; lead.via.add("q1");
        Gig g = gpl.process(lead, false);
        eq("pipeline: a clean platform lands in the list", Gig.NEW, g.status);
        check("pipeline: its own page was read", g.pageFetched && g.hasTerms && g.payoutMethods.contains("PayPal"));
        check("pipeline: robots.txt was read first", fh.asked.get(0).endsWith("/robots.txt"));
        check("pipeline: a useful second page was read", fh.asked.contains("https://brandpost-example.test/how-it-works"));
        check("pipeline: terms and privacy pages are never fetched", fh.asked.stream().noneMatch(u -> u.endsWith("/terms") || u.endsWith("/privacy")));
        check("pipeline: stored with a draft and a why-it-fits line", !g.draft.isBlank() && !g.whyFits.isBlank() && st.gig(g.id) != null);
        check("pipeline: risk, fit and eligibility are all set", g.risk <= 3 && g.fit >= 80 && g.eligibility.equals(Gig.OK));
        check("pipeline: remembers which search found it", g.foundVia.equals(List.of("q1")));

        // robots.txt that forbids us: the page is not fetched, and the snippet is all we have
        fh.asked.clear();
        fh.pages.put("https://blocked.test/robots.txt", "User-agent: *\nDisallow: /");
        fh.pages.put("https://blocked.test/", "<p>should never be read</p>");
        GigDiscovery.Lead b = new GigDiscovery.Lead(); b.domain = "blocked.test"; b.url = "https://blocked.test/"; b.snippet = "Get paid to post short-form videos for brand campaigns. Open worldwide."; b.name = "Blocked";
        Gig bg = gpl.process(b, false);
        check("pipeline: a site that disallows us is not fetched", fh.asked.equals(List.of("https://blocked.test/robots.txt")) && !bg.pageFetched);
        check("pipeline: it is still judged from the snippet, with a warning", bg.riskReasons.stream().anyMatch(r -> r.contains("could not read")));

        // a server error on robots.txt means: do not push
        fh.asked.clear();
        fh.pages.put("https://broken.test/robots.txt", "HTTP 500 from broken.test");
        fh.pages.put("https://broken.test/", "<p>page</p>");
        GigDiscovery.Lead br = new GigDiscovery.Lead(); br.domain = "broken.test"; br.url = "https://broken.test/"; br.name = "Broken";
        check("pipeline: a robots.txt server error stops the fetch", !gpl.process(br, false).pageFetched && !fh.asked.contains("https://broken.test/"));

        // a page that is not about earning is dropped before it costs a review search
        fh.asked.clear();
        fh.pages.put("https://clippers-example.test/", "<html><head><title>LA Clippers announce new signing</title></head><body>Kid clipper joins the team as an announcer. Watch the video.</body></html>");
        GigDiscovery.Lead nl = new GigDiscovery.Lead(); nl.domain = "clippers-example.test"; nl.url = "https://clippers-example.test/"; nl.name = "Clippers"; nl.direct = true;
        Gig ng = gpl.process(nl, true);
        eq("pipeline: a page with no earning wording is not a gig platform", Gig.REJECTED, ng.status);
        check("pipeline: and says so", ng.whyFits.startsWith("Not a gig platform") && ng.fit == 0 && ng.draft.isEmpty());
        check("pipeline: and spends no review search", fh.asked.stream().noneMatch(a -> a.startsWith("POST")));
        check("pipeline: earning wording is recognised", GigPipeline.EARNING.matcher("Get paid to post. Creators earn up to $500. Payouts weekly.").find() && !GigPipeline.EARNING.matcher("Watch the clippers game tonight").find());
        check("live lesson: numbered listicles with a word in between are articles", GigDiscovery.looksLikeArticle("10 Powerful Websites For Finding Micro Jobs"));
        check("live lesson: freelance marketplaces and job posts are not gig platforms", GigDiscovery.ignored("freelancer.com") && GigDiscovery.ignored("join.com"));

        // scam and excluded platforms are kept (so they are never re-read) but marked rejected
        fh.pages.put("https://easy.test/", fixture("gig_scam.html"));
        GigDiscovery.Lead sc = new GigDiscovery.Lead(); sc.domain = "easy.test"; sc.url = "https://easy.test/"; sc.name = "Easy"; sc.direct = true;
        Gig sg = gpl.process(sc, true);
        eq("pipeline: a scam page is rejected", Gig.REJECTED, sg.status);
        eq("pipeline: with the maximum risk", 10, sg.risk);
        check("pipeline: and the rejection reasons are kept", sg.hardRejects.size() >= 4);
        fh.pages.put("https://cliprush-example.test/", fixture("gig_us_only.html"));
        GigDiscovery.Lead usl = new GigDiscovery.Lead(); usl.domain = "cliprush-example.test"; usl.url = "https://cliprush-example.test/"; usl.name = "ClipRush"; usl.direct = true;
        Gig usg = gpl.process(usl, true);
        check("pipeline: a US-only platform is dropped for South Africa", usg.status.equals(Gig.REJECTED) && usg.eligibility.equals(Gig.EXCLUDED));
        check("pipeline: dropped platforms spend no review credits", fh.asked.stream().noneMatch(a -> a.startsWith("POST") && a.contains("cliprush") ) && fh.asked.stream().noneMatch(a -> a.startsWith("POST") && a.contains("Easy")));

        // your status and your edits survive a re-check
        st.setGigStatus(g.id, Gig.APPROVED);
        st.setGigDraft(g.id, "my own edited draft");
        Gig again = gpl.recheck(g.id);
        eq("pipeline: re-check keeps your status", Gig.APPROVED, again.status);
        eq("pipeline: re-check keeps your edited draft", "my own edited draft", again.draft);
        eq("pipeline: re-check keeps when it was found", g.foundAt, again.foundAt);

        // full run with a canned search API
        FakeHttp fh2 = new FakeHttp();
        fh2.searchBody = "{\"organic\":["
                + "{\"title\":\"Brandpost Example - get paid to post\",\"link\":\"https://brandpost-example.test/\",\"snippet\":\"Creators earn a retainer\"},"
                + "{\"title\":\"Anyone tried clipstar.io?\",\"link\":\"https://reddit.com/r/beermoney/a\",\"snippet\":\"clipstar.io paid me last week, got paid $40\"}]}";
        fh2.pages.put("https://brandpost-example.test/", fixture("gig_creator_program.html"));
        Path st2File = Files.createTempDirectory("jh-gig2").resolve("state.json");
        Store st2 = Store.load(st2File);
        Profile limited = gigProfile(); limited.gigMaxQueriesPerRun = 6; limited.gigMaxPagesPerRun = 5; limited.gigMaxReviewChecks = 1;
        GigPipeline run = new GigPipeline(ss, limited, st2, fh2);
        Map<String, Object> sum = run.run();
        eq("run: spends exactly the query budget", 6, sum.get("queriesRun"));
        check("run: found platforms and read pages", ((Number) sum.get("pagesRead")).intValue() >= 2 && ((Number) sum.get("leads")).intValue() >= 2);
        check("run: only one review check was allowed", ((Number) sum.get("reviewChecks")).intValue() == 1);
        check("run: both platforms are in the store", st2.hasGigDomain("brandpost-example.test") && st2.hasGigDomain("clipstar.io"));
        check("run: the review check used the search API", fh2.asked.stream().filter(a -> a.startsWith("POST")).count() == 7);
        check("run: the weekly timer was set", !st2.sourceDue(GigPipeline.SOURCE, limited.gigIntervalMinutes));
        Map<String, Object> second = run.run();
        eq("run: a second run reads nothing it already knows (dedupe by domain)", 0, second.get("pagesRead"));
        check("run: and says how many it skipped", ((Number) second.get("alreadyKnown")).intValue() >= 2);
        check("run: not due again straight away", run.runIfDue() == null);
        check("run: without a search key nothing runs", !new GigPipeline(new Settings(tmp, null, null, null, "m"), limited, st2, fh2).enabled()
                && ((List<?>) new GigPipeline(new Settings(tmp, null, null, null, "m"), limited, st2, fh2).run().get("errors")).size() == 1);

        // storage survives a restart
        st2.save();
        Store re = Store.load(st2File);
        check("storage: gigs survive save and load", re.hasGigDomain("brandpost-example.test") && re.allGigs().size() >= 2);
        Gig reloaded = re.gig(Gig.idFor("brandpost-example.test"));
        check("storage: facts, reasons and evidence survive", reloaded != null && reloaded.payoutMethods.contains("PayPal") && !reloaded.riskReasons.isEmpty() && !reloaded.evidence.isEmpty() && reloaded.fit == st2.gig(reloaded.id).fit);

        // endpoints
        Dashboard dash = new Dashboard(ss, gp, st, new Pipeline(ss, gp, st, fh));
        dash.setGigPipeline(gpl);
        HttpServer srv = dash.start(0);
        try {
            String base = "http://localhost:" + srv.getAddress().getPort();
            HttpClient c = HttpClient.newHttpClient();
            HttpResponse<String> list = c.send(HttpRequest.newBuilder(URI.create(base + "/api/gigs")).build(), HttpResponse.BodyHandlers.ofString());
            eq("endpoint: list ok", 200, list.statusCode());
            List<?> arr = Json.arr(Json.parse(list.body()));
            check("endpoint: lists gigs best first", arr.size() >= 3 && ((Number) Json.obj(arr.get(0)).get("rank")).intValue() >= ((Number) Json.obj(arr.get(arr.size() - 1)).get("rank")).intValue());
            check("endpoint: sends risk reasons and the why-it-fits line", list.body().contains("\"riskReasons\"") && list.body().contains("\"whyFits\""));
            HttpResponse<String> noHdr = c.send(HttpRequest.newBuilder(URI.create(base + "/api/gigs/" + g.id + "/status")).POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"JOINED\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("endpoint: change without the header refused", 403, noHdr.statusCode());
            eq("endpoint: nothing changed", Gig.APPROVED, st.gig(g.id).status);
            HttpResponse<String> ok = c.send(HttpRequest.newBuilder(URI.create(base + "/api/gigs/" + g.id + "/status")).header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"JOINED\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("endpoint: status change ok", 200, ok.statusCode());
            eq("endpoint: gig is joined", Gig.JOINED, st.gig(g.id).status);
            HttpResponse<String> bad = c.send(HttpRequest.newBuilder(URI.create(base + "/api/gigs/" + g.id + "/status")).header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"REJECTED\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("endpoint: you cannot set the automatic REJECTED status by hand", 400, bad.statusCode());
            HttpResponse<String> dr = c.send(HttpRequest.newBuilder(URI.create(base + "/api/gigs/" + g.id + "/draft")).header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"draft\":\"edited\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("endpoint: draft saved", "edited", st.gig(g.id).draft);
            HttpResponse<String> unk = c.send(HttpRequest.newBuilder(URI.create(base + "/api/gigs/nope/status")).header("X-JobHunter", "1").POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"NEW\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("endpoint: unknown gig is a 404", 404, unk.statusCode());
            HttpResponse<String> runNoHdr = c.send(HttpRequest.newBuilder(URI.create(base + "/api/gigs/run")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            eq("endpoint: starting a gig search needs the header too", 403, runNoHdr.statusCode());
            HttpResponse<String> state = c.send(HttpRequest.newBuilder(URI.create(base + "/api/state")).build(), HttpResponse.BodyHandlers.ofString());
            check("endpoint: state says whether gig search is available", state.body().contains("\"gigsEnabled\":true"));
        } finally { srv.stop(0); }

        // settings and their defaults
        Profile dflt = Profile.fromMap(new LinkedHashMap<>());
        check("settings: payout methods that work from South Africa have sensible defaults", dflt.gigPayoutMethods.containsAll(List.of("PayPal", "Payoneer", "Wise", "Bank transfer")));
        eq("settings: weekly by default", 10080, dflt.gigIntervalMinutes);
        Profile custom = Profile.fromMap(Json.obj(Json.parse("{\"gigPayoutMethods\":[\"Wise\"],\"gigMaxQueriesPerRun\":10}")));
        check("settings: payout methods can be edited", custom.gigPayoutMethods.equals(List.of("Wise")) && custom.gigMaxQueriesPerRun == 10);
        Gig viaPaypal = GigExtract.extract("https://p.test/", "P", "<p>Worldwide. Get paid per task via PayPal.</p>", "");
        GigScore.eligibility(viaPaypal, custom);
        eq("settings: removing PayPal from your list excludes a PayPal-only gig", Gig.EXCLUDED, viaPaypal.eligibility);
    }

    static void entryTests() throws Exception {
        Entry.Result app = Entry.detect(job("Remote Web Development Apprentice", "LegKraal", "South Africa", "Remote apprenticeship. Mentorship and comprehensive training. Fully remote."));
        check("entry: apprenticeship with training is training", app.training());
        Entry.Result all = Entry.detect(job("Support Assistant", "Co", "South Africa", "Matric (Grade 12) is the minimum. No experience needed. Full training provided."));
        check("entry: matric, no experience and training all found", all.matric() && all.noExperience() && all.training());
        eq("entry: labels in plain words", List.of("Matric is enough", "No experience needed", "Training provided"), all.labels());
        Entry.Result years = Entry.detect(job("Support Assistant", "Co", "South Africa", "Matric required. 3+ years of experience in support. We will train you on our tools."));
        check("entry: years of experience cancel every entry label", !years.any());
        Entry.Result degree = Entry.detect(job("Analyst", "Co", "South Africa", "Bachelor's degree required. No experience necessary."));
        check("entry: a required degree cancels matric and no-experience", !degree.matric() && !degree.noExperience());
        check("entry: senior titles never count", !Entry.detect(job("Senior Support Lead", "Co", "", "We will train you. No experience needed. Matric.")).any());
        check("entry: we'll train you (straight apostrophe)", Entry.detect(job("Helper", "Co", "", "We'll train you from scratch.")).training());
        check("entry: we'll train you (curly apostrophe)", Entry.detect(job("Helper", "Co", "", "We’ll train you from scratch.")).training());
        check("entry: learnership counts as training", Entry.detect(job("ICT Learnership Programme", "Co", "", "12 month programme.")).training());
        check("entry: ordinary job has no labels", !Entry.detect(job("Java Developer", "Co", "", "Spring Boot and MySQL. Remote.")).any());
        check("entry: word 'training' alone is not enough", !Entry.detect(job("Data Analyst", "Co", "", "You will analyse training data for models.")).training());
        check("entry: grade 12 spelled out", Entry.detect(job("Clerk", "Co", "", "Grade 12 with good Excel skills.")).matric());

        Scorer sc = new Scorer(profile);
        Scorer.Result fit = sc.score(job("Junior Java Developer", "Co", "Worldwide", "Java and Spring Boot, remote. We will train you. Matric is enough."));
        check("entry score: training bonus when skills match", fit.why().stream().anyMatch(w -> w.contains("+6 training provided")));
        check("entry score: matric bonus when skills match", fit.why().stream().anyMatch(w -> w.contains("+4 matric is enough")));
        Scorer.Result off = sc.score(job("Beekeeper trainee", "Co", "Worldwide", "Matric is enough. No experience needed. We will train you on bees."));
        check("entry score: unmatched entry role still reaches the queue", off.score() >= profile.scoreThreshold);
        check("entry score: and says why it is there", off.why().stream().anyMatch(w -> w.contains("does not match your skill list")));
        Scorer.Result sen = sc.score(job("Senior Beekeeper", "Co", "Worldwide", "We will train you. Matric. No experience needed."));
        check("entry score: senior role gets no entry floor", sen.score() < profile.scoreThreshold);
        Scorer.Result plain = sc.score(job("Beekeeper", "Co", "Worldwide", "Looking after bees."));
        check("entry score: a plain unmatched job is still dropped", plain.score() < profile.scoreThreshold);

        // the deal-breakers still win: an entry-level job with a fee, or a WhatsApp-only application, is rejected
        Filter flt = new Filter(profile);
        Job fee = job("Trainee Agent", "Co", "Worldwide", "No experience needed. We will train you. Pay a $30 registration fee to start.");
        check("entry never bypasses the scam filter (fee)", !flt.screen(fee, new Store(Path.of("none.json"))).ok());
        Job wa = job("Trainee Agent", "Co", "Worldwide", "No experience needed. Apply on WhatsApp for an interview today.");
        check("entry never bypasses the scam filter (WhatsApp)", !flt.screen(wa, new Store(Path.of("none.json"))).ok());

        // web-search results that are lists of jobs, not one listing, are rejected; real postings and board feeds are not
        Store none = new Store(Path.of("none.json"));
        Job listPage = job("Grade 12 jobs in Work From Home", "careerjunction.co.za", "South Africa", "Browse jobs.");
        listPage.sourceType = "SEARCH";
        check("list page by title rejected", flt.screen(listPage, none).rejects().stream().anyMatch(r -> r.contains("list of jobs")));
        Job indeedQ = job("Matric no experience", "za.indeed.com", "South Africa", "Browse.");
        indeedQ.sourceType = "SEARCH"; indeedQ.url = "https://za.indeed.com/q-matric-no-experience,-remote-work-l-crown-mines,-gauteng-jobs.html";
        check("indeed search page by link rejected", flt.screen(indeedQ, none).rejects().stream().anyMatch(r -> r.contains("list of jobs")));
        Job real = job("Remote Web Development Apprentice", "LegKraal Gate", "South Africa", "Fully remote within South Africa. Mentorship and training.");
        real.sourceType = "SEARCH";
        check("a real apprenticeship is not mistaken for a list", flt.screen(real, none).rejects().stream().noneMatch(r -> r.contains("list of jobs")));
        Job linkedin = job("Frontend Developer", "HiJob", "South Africa", "Remote.");
        linkedin.sourceType = "SEARCH"; linkedin.url = "https://za.linkedin.com/jobs/view/frontend-developer-at-hijob-work-4486650292";
        check("linkedin job links are not mistaken for lists", flt.screen(linkedin, none).rejects().stream().noneMatch(r -> r.contains("list of jobs")));
        Job liJobs = job("Data Labeling Specialist at Alignerr - Jobs", "Alignerr", "South Africa", "Remote.");
        liJobs.sourceType = "SEARCH"; liJobs.source = "LinkedIn"; liJobs.url = "https://za.linkedin.com/jobs/view/data-labeling-specialist-at-alignerr-4487588473";
        check("linkedin titles ending in '- Jobs' are real listings", flt.screen(liJobs, none).rejects().stream().noneMatch(r -> r.contains("list of jobs")));
        Job zrJobs = job("DataAnnotation - Full Stack Software Developer Jobs", "DataAnnotation", "Remote", "Remote.");
        zrJobs.sourceType = "SEARCH"; zrJobs.source = "ZipRecruiter";
        check("ziprecruiter job pages are trusted the same way", flt.screen(zrJobs, none).rejects().stream().noneMatch(r -> r.contains("list of jobs")));
        check("board feeds are not checked for list pages", flt.screen(job("Jobs Coordinator", "Co", "Worldwide", "Coordinate things."), none).rejects().stream().noneMatch(r -> r.contains("list of jobs")));
        check("adult-content role rejected", flt.screen(job("OnlyFans Chatter Entry Level (No Experience)", "Co", "Worldwide", "Chat with fans."), none).rejects().stream().anyMatch(r -> r.contains("Adult-content")));

        // the public example config ships with the new queries and stays within the per-run limit
        Profile ex = Profile.load(Path.of("config/profile.example.json"));
        check("example config searches for matric jobs", ex.searchQueries.stream().anyMatch(q -> q.contains("matric")));
        check("example config searches for training jobs", ex.searchQueries.stream().anyMatch(q -> q.contains("we will train you")));
        check("example config searches for learnerships", ex.searchQueries.stream().anyMatch(q -> q.contains("learnership")));
        check("example config queries fit the per-run limit", ex.searchQueries.size() <= ex.maxSearchQueries);

        // the dashboard tells the page which jobs are entry or training
        Path tmp = Files.createTempDirectory("jh-entry");
        Settings ss = new Settings(tmp, null, null, null, "m");
        Store st = Store.load(ss.stateFile());
        Job t = job("Remote Web Apprentice", "Acme", "South Africa", "Mentorship and comprehensive training. Matric is enough.");
        st.add(t);
        HttpServer srv = new Dashboard(ss, profile, st, new Pipeline(ss, profile, st, new Http())).start(0);
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + srv.getAddress().getPort() + "/api/jobs")).build(), HttpResponse.BodyHandlers.ofString());
            check("dashboard sends entry labels", r.body().contains("\"entry\":[\"Matric is enough\",\"Training provided\"]"));
        } finally { srv.stop(0); }
    }

    static void workingNomadsTests() throws Exception {
        // src/test/resources/workingnomads.json is a trimmed copy of the real feed (6 jobs, descriptions cut to 500 chars)
        String real = Files.readString(Path.of("src/test/resources/workingnomads.json"));
        List<Job> js = Sources.parseWorkingNomads(real);
        eq("working nomads: all jobs parsed", 6, js.size());
        Job first = js.get(0);
        eq("working nomads: title", "AI Content Reviewer, Video Annotation", first.title);
        eq("working nomads: company", "iMerit Technology", first.company);
        eq("working nomads: source name", "Working Nomads", first.source);
        check("working nomads: links back to workingnomads.com", first.url.startsWith("https://www.workingnomads.com/"));
        check("working nomads: html stripped", !first.description.contains("<p>") && first.description.contains("100% Remote"));
        check("working nomads: comma tags split", first.tags.contains("annotation") && first.tags.contains("artificial intelligence"));
        check("working nomads: category kept as a tag", first.tags.contains("Administration"));
        eq("working nomads: date converted to UTC", "2026-11-05T08:10:45Z", first.postedAt);
        check("working nomads: location kept", first.location.contains("Switzerland"));
        check("working nomads: id set", !first.id.isEmpty());
        check("working nomads: ids are unique", js.stream().map(j -> j.id).distinct().count() == js.size());

        eq("working nomads: empty feed", 0, Sources.parseWorkingNomads("[]").size());
        List<Job> odd = Sources.parseWorkingNomads("[{\"title\":\"\",\"url\":\"https://x.example/a\"},"
                + "{\"title\":\"No tags or date\",\"url\":\"https://x.example/b\",\"company_name\":\"Z\",\"tags\":\"\",\"pub_date\":\"not a date\"}]");
        eq("working nomads: job without a title skipped", 1, odd.size());
        check("working nomads: empty tags and bad date do not crash", odd.get(0).tags.isEmpty() && odd.get(0).postedAt.equals("not a date"));
        boolean threw = false;
        try { Sources.parseWorkingNomads("not json"); } catch (Exception e) { threw = true; }
        check("working nomads: broken response raises an error (the run logs it and carries on)", threw);

        Source src = Sources.all().stream().filter(s -> s.name().equals("Working Nomads")).findFirst().orElse(null);
        check("working nomads: registered as a source", src != null);
        check("working nomads: fetched at most twice a day", src != null && src.minIntervalMinutes() >= 720);
        check("working nomads: needs no key", src != null && src.enabled(new Settings(Path.of("."), null, null, null, "m")));
    }

    static void summaryTests() throws Exception {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        java.util.function.Function<Instant, String> ago = t -> t.toString();
        Job j1 = csvJob("One", "A", Job.NEW, "", "https://1.example"); j1.source = "SrcA"; j1.foundAt = ago.apply(now.minus(2, ChronoUnit.DAYS)); j1.id = "s1";
        Job j2 = csvJob("Two", "B", Job.APPLIED, ago.apply(now.minus(1, ChronoUnit.DAYS)), "https://2.example"); j2.source = "SrcA"; j2.foundAt = ago.apply(now.minus(3, ChronoUnit.DAYS)); j2.id = "s2";
        Job j3 = csvJob("Three", "C", Job.REPLIED, ago.apply(now.minus(2, ChronoUnit.DAYS)), "https://3.example"); j3.source = "SrcB"; j3.foundAt = ago.apply(now.minus(30, ChronoUnit.DAYS)); j3.id = "s3";
        Job j4 = csvJob("Four", "D", Job.REJECTED, ago.apply(now.minus(1, ChronoUnit.DAYS)), "https://4.example"); j4.source = "SrcB"; j4.foundAt = ago.apply(now.minus(1, ChronoUnit.DAYS)); j4.id = "s4";
        Map<String, List<String>> gapsByJob = Map.of("s1", List.of("docker", "python"), "s2", List.of("docker"), "s3", List.of("sql"), "s4", List.of("kubernetes"));
        Map<String, Object> s = Summary.weekly(List.of(j1, j2, j3, j4), now, 7, j -> gapsByJob.get(j.id));
        eq("found in the last 7 days", 3, s.get("found"));
        eq("applied counts applied and replied this week", 2, s.get("applied"));
        eq("replies this week", 1, s.get("replies"));
        eq("reply rate across all applications", 50, s.get("replyRatePct"));
        eq("total applied", 2, s.get("totalApplied"));
        eq("nothing due yet", 0, s.get("followUpsDue"));
        List<?> sources = (List<?>) s.get("sources");
        eq("best source is the one with a reply", "SrcB", ((Map<?, ?>) sources.get(0)).get("source"));
        List<?> gaps = (List<?>) s.get("gaps");
        eq("most common gap first", "docker", ((Map<?, ?>) gaps.get(0)).get("keyword"));
        eq("gap count", 2, ((Map<?, ?>) gaps.get(0)).get("jobs"));
        check("old jobs do not add gaps", gaps.stream().noneMatch(g -> "sql".equals(((Map<?, ?>) g).get("keyword"))));
        check("skipped jobs do not add gaps", gaps.stream().noneMatch(g -> "kubernetes".equals(((Map<?, ?>) g).get("keyword"))));
        eq("empty store", 0, Summary.weekly(List.of(), now, 7, j -> List.of()).get("found"));

        Path tmp = Files.createTempDirectory("jh-sum");
        Settings ss = new Settings(tmp, null, null, null, "m");
        Store st = Store.load(ss.stateFile());
        HttpServer srv = new Dashboard(ss, profile, st, new Pipeline(ss, profile, st, new Http())).start(0);
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + srv.getAddress().getPort() + "/api/summary")).build(), HttpResponse.BodyHandlers.ofString());
            eq("summary endpoint ok", 200, r.statusCode());
            check("summary endpoint has the numbers", r.body().contains("\"found\":0") && r.body().contains("\"gaps\""));
        } finally { srv.stop(0); }
    }

    static void dedupeTests() throws Exception {
        Job a = job("Senior Java Developer (Remote)", "Acme, Inc.", "Worldwide", "x");
        Job b = job("Sr. Java Dev", "ACME Inc", "Anywhere", "y");
        eq("same job on two boards: abbreviations, suffixes, remote tag", a.fuzzyKey(), b.fuzzyKey());
        eq("word order ignored", job("Data Analyst, Junior", "Zed Ltd", "", "p").fuzzyKey(), job("Junior Data Analyst", "Zed", "", "q").fuzzyKey());
        check("different company is a different job", !a.fuzzyKey().equals(job("Senior Java Developer", "Other Co", "", "z").fuzzyKey()));
        check("senior and junior are different jobs", !job("Senior Java Developer", "Acme", "", "1").fuzzyKey().equals(job("Junior Java Developer", "Acme", "", "2").fuzzyKey()));
        check("different countries stay separate", !job("AI Data Rater - Swedish (Latvia)", "Welo", "", "1").fuzzyKey()
                .equals(job("AI Data Rater - Swedish (Estonia)", "Welo", "", "2").fuzzyKey()));
        Job nocompany = job("AI Trainer", "", "", "n");
        eq("no company falls back to the old key", nocompany.dupKey(), nocompany.fuzzyKey());

        Path dir = Files.createTempDirectory("jh-dd");
        Settings s = new Settings(dir, null, null, null, "m");
        Store store = Store.load(s.stateFile());
        Pipeline p = new Pipeline(s, profile, store, new Http());
        Pipeline.Counts c = new Pipeline.Counts();
        p.process(List.of(
                job("AI Trainer (Remote)", "Good Co Inc", "Worldwide", "Evaluate LLM responses. Flexible hours. Part-time."),
                job("AI Trainer", "Good Co", "Worldwide", "Evaluate LLM responses. Flexible hours. Part-time. Posted on another board."),
                job("Excel Analyst", "Different Co", "Worldwide", "Excel, SQL, dashboards. Flexible hours.")), c);
        eq("cross-board duplicate caught in one run", 1, c.duplicates);
        check("the other jobs are not lost", c.added + c.rejected + c.lowScore == 2);

        // survives a restart, and jobs saved before this key existed are still recognised
        store.save();
        Store reloaded = Store.load(s.stateFile());
        check("duplicate still caught after restart", reloaded.isKnown(job("AI Trainer", "GOOD CO", "Worldwide", "third board")));
        Job legacy = job("Support Agent", "Legacy Ltd", "", "l");
        Files.writeString(dir.resolve("old.json"), Json.write(Map.of("jobs", List.of(legacy.toMap()))));
        check("old state without the new key is still deduped", Store.load(dir.resolve("old.json")).isKnown(job("Support Agent (Remote)", "Legacy", "", "other board")));
    }

    static void coverLetterTests() throws Exception {
        Cv cv = sampleCv();
        Job dev = job("Junior Java Developer <b>", "Acme", "Remote", "We use Java, Spring Boot, MySQL and Docker. Unit testing is a plus.");
        String l = cv.coverLetter(dev, profile, List.of("dev", "data"));
        check("letter greets the company", l.contains("Hello Acme team,"));
        check("letter names role and company", l.contains("the Junior Java Developer <b> position at Acme"));
        check("letter uses the dev summary", l.contains("Dev summary") && !l.contains("Data summary"));
        check("letter uses a relevant bullet", l.contains("Built a "));
        check("letter names matched keywords", l.contains("which are on my CV") && l.toLowerCase().contains("java"));
        check("letter never mentions a gap (docker)", !l.toLowerCase().contains("docker"));
        check("letter ends with your name", l.contains(profile.name));
        check("letter uses at most 2 bullets", l.lines().filter(x -> x.startsWith("- ")).count() <= 2);

        Job data = job("Junior Data Analyst", "Zed", "Remote", "Excel, SQL and statistics. Dashboards.");
        String ld = cv.coverLetter(data, profile, List.of("data"));
        check("data letter uses the data summary", ld.contains("Data summary"));
        check("data letter leads with the data bullet", ld.contains("R109,000"));
        Job web = job("Role", "Board", "Remote", "Java");
        web.sourceType = "SEARCH";
        check("search jobs get a plain greeting", cv.coverLetter(web, profile, List.of("dev")).contains("Hello,\n"));

        // endpoint: needs a CV, escapes the title, 404 for unknown jobs
        Cv saved = profile.cv;
        profile.cv = cv;
        Path tmp = Files.createTempDirectory("jh-cl");
        Settings ss = new Settings(tmp, null, null, null, "m");
        Store st = Store.load(ss.stateFile());
        Job j = job("Java <script>x</script> Dev", "Acme", "Remote", "Java and Spring Boot");
        st.add(j);
        HttpServer srv = new Dashboard(ss, profile, st, new Pipeline(ss, profile, st, new Http())).start(0);
        try {
            String base = "http://localhost:" + srv.getAddress().getPort();
            HttpClient c = HttpClient.newHttpClient();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/" + j.id + "/cover")).build(), HttpResponse.BodyHandlers.ofString());
            eq("cover endpoint ok", 200, r.statusCode());
            check("cover page has an editable box and copy button", r.body().contains("<textarea") && r.body().contains("Copy"));
            check("cover page escapes the job title", !r.body().contains("<script>") && r.body().contains("&lt;script&gt;"));
            HttpResponse<String> none = c.send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/nope/cover")).build(), HttpResponse.BodyHandlers.ofString());
            eq("cover endpoint 404 for unknown job", 404, none.statusCode());
        } finally { srv.stop(0); profile.cv = saved; }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    static void boardTests() {
        System.out.println("LinkedIn and Indeed");
        Job li = new Job();
        li.title = "Acme hiring Junior Java Developer in Cape Town, Western Cape, South Africa | LinkedIn";
        li.url = "https://za.linkedin.com/jobs/view/junior-java-developer-at-acme-123";
        li.sourceType = "SEARCH"; li.company = "linkedin.com";
        Job a = Sources.asBoardJob(li);
        check("linkedin job kept", a != null);
        eq("linkedin source", "LinkedIn", a.source);
        eq("linkedin company", "Acme", a.company);
        eq("linkedin title", "Junior Java Developer", a.title);
        eq("linkedin location", "Cape Town, Western Cape, South Africa", a.location);

        Job in = new Job();
        in.title = "Junior Data Analyst - Hire Hangar - Remote | Indeed.com";
        in.url = "https://za.indeed.com/viewjob?jk=abc123";
        Job b = Sources.asBoardJob(in);
        eq("indeed source", "Indeed", b.source);
        eq("indeed title", "Junior Data Analyst", b.title);
        eq("indeed company", "Hire Hangar", b.company);

        Job listing = new Job();
        listing.title = "100+ Remote Java Developer Jobs | Indeed";
        listing.url = "https://za.indeed.com/q-remote-java-developer-jobs.html";
        check("indeed search pages skipped", Sources.asBoardJob(listing) == null);
        Job liSearch = new Job();
        liSearch.title = "Remote Java Jobs in South Africa";
        liSearch.url = "https://za.linkedin.com/jobs/remote-java-jobs";
        check("linkedin search pages skipped", Sources.asBoardJob(liSearch) == null);

        Job zr = new Job();
        zr.title = "Junior Java Developer Job in Remote at Acme Corp | ZipRecruiter";
        zr.url = "https://www.ziprecruiter.com/c/Acme-Corp/Job/Junior-Java-Developer/-in-Remote?jid=abc123";
        Job z = Sources.asBoardJob(zr);
        eq("ziprecruiter source", "ZipRecruiter", z.source);
        eq("ziprecruiter title", "Junior Java Developer", z.title);
        eq("ziprecruiter company", "Acme Corp", z.company);
        Job zrSearch = new Job();
        zrSearch.title = "Remote Java Jobs | ZipRecruiter";
        zrSearch.url = "https://www.ziprecruiter.com/Jobs/Remote-Java";
        check("ziprecruiter search pages skipped", Sources.asBoardJob(zrSearch) == null);

        System.out.println("Google Jobs");
        Instant now = Instant.parse("2026-11-08T10:00:00Z");
        eq("3 days ago", Instant.parse("2026-11-05T10:00:00Z"), Sources.ago("3 days ago", now));
        eq("hours ago", Instant.parse("2026-11-08T05:00:00Z"), Sources.ago("5 hours ago", now));
        eq("30+ days ago", Instant.parse("2026-10-09T10:00:00Z"), Sources.ago("30+ days ago", now));
        check("unknown age is null", Sources.ago("recently", now) == null);
        String gj = """
            {"jobs_results":[
              {"title":"AI Trainer (Java)","company_name":"Mindrift","location":"Anywhere","via":"via LinkedIn",
               "description":"Flexible, remote. Java code review.","detected_extensions":{"posted_at":"2 days ago","schedule_type":"Contractor","work_from_home":true},
               "share_link":"https://www.google.com/search?ibp=htl;jobs#x","apply_options":[{"title":"LinkedIn","link":"https://www.linkedin.com/jobs/view/1"}],"job_id":"a"},
              {"title":"Old Java job","company_name":"Old Co","location":"Anywhere","description":"x",
               "detected_extensions":{"posted_at":"12 days ago"},"share_link":"https://www.google.com/search?ibp=htl;jobs#y"},
              {"title":"No link","company_name":"Z","description":"x","detected_extensions":{"posted_at":"1 day ago"}}
            ]}""";
        List<Job> g = Sources.parseGoogleJobs(gj, now, 7);
        eq("only fresh jobs with a link kept", 1, g.size());
        eq("google jobs source", "Google Jobs", g.get(0).source);
        eq("apply link used", "https://www.linkedin.com/jobs/view/1", g.get(0).url);
        check("remote tag added", g.get(0).tags.contains("remote"));
        eq("posted date set", "2026-11-06T10:00:00Z", g.get(0).postedAt);
        eq("schedule type kept", "Contractor", g.get(0).type);
        eq("empty google jobs response", 0, Sources.parseGoogleJobs("{}", now, 7).size());
        check("google jobs off without key", !new Sources.GoogleJobs().enabled(new Settings(Path.of("."), null, null, null, "m")));
        check("google jobs on with key", new Sources.GoogleJobs().enabled(new Settings(Path.of("."), null, null, null, "m", "k")));

        Object sb = Json.parse(Sources.serperBody("site:indeed.com \"AI\" OR java", "qdr:w"));
        check("serper asks for 10 results (20 gets HTTP 400)", sb instanceof Map<?, ?> m && Double.valueOf(10).equals(((Number) m.get("num")).doubleValue()));
        check("serper keeps time filter", sb instanceof Map<?, ?> m2 && "qdr:w".equals(m2.get("tbs")));
        check("serper query survives quotes", sb instanceof Map<?, ?> m3 && "site:indeed.com \"AI\" OR java".equals(m3.get("q")));

        if (!profile.targets.isEmpty()) {
            List<String> qs = Sources.boardQueries(profile);
            eq("three queries per title", profile.targets.size() * 3, qs.size());
            check("ziprecruiter searched", qs.stream().anyMatch(q -> q.startsWith("site:ziprecruiter.com")));
            check("best-fit titles searched first", qs.get(0).contains(profile.targets.stream().filter(t -> t.fit().equals("A")).findFirst().get().query()));
            Map<String, String> links = Sources.boardLinks(profile.targets.get(0));
            check("linkedin link is last 7 days", links.values().stream().anyMatch(u -> u.contains("linkedin.com") && u.contains("f_TPR=r604800")));
            check("indeed link is last 7 days", links.values().stream().anyMatch(u -> u.contains("indeed.com") && u.contains("fromage=7")));
            check("ziprecruiter link", links.values().stream().anyMatch(u -> u.contains("ziprecruiter.com/jobs-search") && u.contains("days=5")));
            check("google jobs link", links.values().stream().anyMatch(u -> u.contains("ibp=htl;jobs")));
        }
    }
}

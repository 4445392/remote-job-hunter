# Remote Job Hunter: notes for Claude Code

## What this is
A personal Java tool that finds remote, flexible-hours work for Sfundo (South Africa, SAST, UTC+2), filters out scams and deal-breakers, scores each job against his skills, drafts an application and builds a CV tailored to that job. He reviews and sends every application himself.

## The person
- 2nd-year BSc Computer Science student at UWC. Writes Java.
- Wants **short answers**, the **big idea first**, then detail. Diagrams help (Mermaid in Markdown).
- Uses Windows and PowerShell. Give PowerShell commands, not bash, when he has to type something.

## Hard rules (never break these)
1. **JDK 21 only, zero dependencies.** No Maven or Gradle dependencies, no new libraries. Use `java.net.http`, `com.sun.net.httpserver`, `javax.xml`.
2. **Never send an application automatically.** The tool prepares; he clicks. No form-filling bots.
3. **Never scrape sites that forbid it** (LinkedIn, Indeed, ZipRecruiter, Handshake, Glassdoor). Use official APIs, public feeds, search APIs (Brave, Serper, SerpApi), one-click links, or the paste box.
4. **Never invent CV content.** The tailor only reorders and highlights what is in `config/cv.json`. Missing keywords are shown as gaps, never added.
5. **Secrets come from environment variables only** (`BRAVE_API_KEY`, `SERPER_API_KEY`, `SERPAPI_KEY`, `ANTHROPIC_API_KEY`). Never write a key to a file, a log, `data/state.json` or an error message. Error messages show the host, never the full URL.
6. **Personal files stay out of git:** `config/profile.json`, `config/cv.json`, `cv/`, `data/`. Check `git status` before every commit.
7. **The dashboard stays local-only:** bound to loopback, Host-header check, `X-JobHunter: 1` header on every POST, all page text escaped, only `http(s)` links.
8. **Every change gets a test** in `src/test/java/jobhunter/SelfTest.java`, and `./build.sh test` (or `build.bat test`) must end with `0 failed` before you say it is done.

## Commands
```powershell
.\build.bat test                       # compile + all offline self-tests (must say 0 failed)
java -jar jobhunter.jar doctor         # setup check + which job sites are reachable
java -jar jobhunter.jar run            # one search now
.\start.bat                            # dashboard at http://localhost:8080 + search every 3 hours
```

## Code map
| File | Job |
|---|---|
| `Sources.java` | each job source (feeds, web search, LinkedIn/Indeed/ZipRecruiter via search, Google Jobs via SerpApi) and its parser |
| `Filter.java` | deal-breakers and scam signals |
| `Scorer.java` | skill-group matching, target-title bonus, seniority penalties |
| `Drafter.java` | application text (optional Claude polish) |
| `Cv.java` | CV data, keyword match report, tailored printable CV |
| `Pipeline.java` | fetch → dedupe → filter → score → draft → store; pasted jobs |
| `Store.java` | `data/state.json` |
| `Dashboard.java` + `resources/web/index.html` | local review page |
| `config/profile.json` | skills, deal-breakers, pay minimums, 20 target titles |
| `config/cv.json` | his CV as data |

## How to work
- Plan first and show the plan; wait for "go" before editing files.
- Parsers are pure functions: when a live feed breaks, save a real response as a fixture under `src/test/resources/` and test against it.
- After a phase: run tests, then `git add` only source files, commit with a clear message, and report in 3 lines: what works, what's next, what he must do.

# Paste this into Claude Code

Open the unzipped `remote-job-hunter` folder in Claude Code (Code tab → Local → Select folder). Then paste everything between the lines below as your first message.

---

Read CLAUDE.md and README.md first. They hold the rules for this project, and the rules are not optional.

**Goal:** get my Remote Job Hunter running flawlessly on my Windows PC, then make it better, one phase at a time. For each phase: show me a short plan, wait for me to say "go", build it, run `.\build.bat test` until it says `0 failed`, commit, and tell me in 3 lines what works, what's next and what I must do. Explain new ideas big-picture first, in short answers. Use Java for any code you show me.

**Phase 0: Set up and prove it works**
1. Check that Java 21 is installed (`java -version`). If not, tell me the exact winget command, then wait.
2. Run `.\build.bat test`. Every test must pass before anything else.
3. If `config\profile.json` or `config\cv.json` is missing, copy them from the `.example.json` files and stop. Tell me to fill them in.
4. `git init`. Confirm `.gitignore` keeps `config/profile.json`, `config/cv.json`, `cv/` and `data/` out. Make the first commit.
5. Help me create an empty public GitHub repo called `remote-job-hunter`, add it as the remote and push. Then show me `git status` proving no personal file was pushed.

**Phase 1: Test it against the real internet**
1. Run `java -jar jobhunter.jar doctor`, then `java -jar jobhunter.jar run`.
2. For any source that fails or returns 0 jobs, find out why from the real response. Save a trimmed copy of that response as a test fixture, fix the parser, and add a test that uses the fixture. Never weaken a deal-breaker filter just to get more jobs.
3. Start the dashboard (`.\start.bat`), open http://localhost:8080 and walk me through one job: read the score reasons, open the Tailored CV, save it as PDF.

**Phase 2: Finish my CV data**
Ask me, a few questions at a time, for everything still blank or unconfirmed in `config\cv.json`:
- the start years shown as `20__`;
- my LinkedIn URL and the Yengwayo Designs website link;
- confirmation of each number (7 tills, 40 incidents, R109,000, R800 and R582, about 34,000 lines);
- the GitHub links for `nsimbini-pos-design` and `remote-job-hunter` once they are published.

Only write what I confirm. Then open a tailored CV for one AI job and one data job, and show me they fit on 2 pages.

**Phase 3: Turn on automatic searching**
Walk me through getting free keys for Brave Search or Serper, and optionally SerpApi for Google Jobs. Set them with `setx` (PowerShell), never in a file. Run `doctor` to confirm. Then set up Windows Task Scheduler so `start.bat` runs when I log in, and show me how to check it is running.

**Phase 4: Improvements (one at a time, my pick each time)**
Show me this list, recommend the order, and let me choose:
1. A tailored cover letter per job, next to the tailored CV (same rule: never invent facts).
2. Follow-up reminders: jobs I applied to 7 days ago with no reply show in a "Follow up" tab with a short follow-up draft.
3. Export my applications to a CSV I can open in Excel (date, company, title, source, status, link).
4. A weekly summary page: jobs found, applied, replies, best sources, keyword gaps that show up most often (so I know what to learn next).
5. Better duplicate detection when the same job appears on several boards.
6. More free, legal job feeds that allow South Africa (check each site's terms first and tell me what you found).

**Definition of done for every phase:**
- `.\build.bat test` ends with `0 failed`.
- No API key appears in any file, log or `data\state.json`.
- No personal file is committed.
- Nothing ever submits an application without my click.

Start with Phase 0 now.

---

## If something goes wrong
Type: *"Undo everything since the last commit."*

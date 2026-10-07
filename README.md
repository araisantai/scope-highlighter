# Scope Highlighter

A Burp Suite extension (Montoya API, Java) that **auto-highlights requests in
Burp history** based on endpoint groups you define, each with its own title and
Burp highlight color — and works as a **per-endpoint request logger** for
Repeater, Intruder, Proxy history and Target.

Built for bug bounty / pentest workflows where you manage scope endpoints in
Excel and want a fast visual split in Burp — e.g. "Hiring Stage" in red,
"Candidate Detail" in blue.

## Features

1. **Auto-highlight, always up to date** — every request flowing through Burp
   (Proxy, Repeater, Scanner, ...) is matched against your groups and
   highlighted automatically. There is no manual button: adding, editing,
   removing or re-ordering a group immediately re-applies highlights to
   everything already in the Proxy history (removing a group also clears the
   colors and notes it had written).
2. **"Scope Highlighter" tab** — an Excel-like grid:

   ```
   | #  | Endpoint / Group                    | Color | Match  | Action                                            |
   | G1 | Hiring Stage  (2 endpoints)         | Red   | Prefix | [show all][repeater][intruder][history][target]    |
   | 1  |     https://example.com/hiring-...  | Red   | Prefix | [show all][repeater][intruder][history][target]    |
   | 2  |     /hiring-detail/admin            | Red   | Prefix | [show all][repeater][intruder][history][target]    |
   | G2 | Candidate Detail  (1 endpoint)      | Blue  | Regex  | [show all][repeater][intruder][history][target]    |
   | 1  |     https://example.com/candidate.. | Blue  | Regex  | [show all][repeater][intruder][history][target]    |
   ```

   Add groups with title, color (Burp's 9 highlight colors), match mode and
   the endpoint list pasted straight from Excel. Groups are stored in the Burp
   project file and survive restarts.
3. **Excel-friendly paste** — copy a column (or block) of endpoints straight
   from Excel into the group's endpoint box. Newlines, tabs (Excel columns) and
   commas are all treated as separators. Duplicates are removed, `#` comments
   ignored.
4. **Request logger + "show" reveal** — every request is recorded with its tool
   of origin. Clicking any Action button on a row opens the results window for
   that endpoint (or the whole group), where you can reveal:
   - **show all** — everything recorded for the endpoint
   - **repeater** — all Repeater requests of that endpoint
   - **intruder** — all Intruder requests of that endpoint
   - **history** — all Proxy history requests of that endpoint
   - **target** — all requests of that endpoint in the Target/site map

   Each row shows time, source, method, URL, status and length, with the raw
   **Request** and **Response** panes below — so you never need to hunt the
   request down again. The window has a live text filter and per-source counts.
   Rows are tinted with the group's color.
5. **Saved with the project** — the request log is persisted into the Burp
   project file (throttled while running, flushed on unload), so Repeater and
   Intruder requests survive project save/reload — even though Burp itself
   keeps no history API for those tools. The log holds the most recent 3000
   requests (oldest dropped first); oversized bodies are truncated at 200 KB.
6. Optional: write the group title into the **Notes** column (only when the
   note is empty).

## Install

Requires Burp Suite 2023.1+ (Montoya API). Build with any JDK 17+.

```bash
./build.sh          # produces dist/scope-highlighter.jar
```

Then in Burp: **Extensions -> Installed -> Add -> Extension type: Java ->
Select file: `dist/scope-highlighter.jar`**.

The **Scope Highlighter** tab appears in Burp's top tab bar.

## Usage

1. Open the **Scope Highlighter** tab and click **Add group…**
2. Fill in **Group title** (e.g. `Hiring Stage`), pick the **Highlight color**
   and **Match mode**, then paste your endpoints from Excel.
3. Click **Add**. Existing and new traffic matching the group is highlighted
   immediately — no confirmation needed.
4. Click any **action** button on a group or endpoint row to reveal its
   recorded requests.
5. **Delete** removes the selected group — or just the selected endpoint row,
   keeping the rest of the group.

Endpoint examples (all work in `Prefix` mode):

| Paste this                    | Matches                                          |
| ----------------------------- | ------------------------------------------------ |
| `https://admin.site.com/career` | that URL and anything under it (query ignored) |
| `/admin/verify-location`      | that path on **any** host                       |
| `admin.site.com`              | everything on that host                         |
| `admin.site.com/api/`         | everything under `admin.site.com/api/`          |

Prefix matching is case-insensitive and segment-aware: `/admin/career` matches
`/admin/career/jobs?id=3` but **not** `/admin/careerx`.

### Match modes

| Mode      | Behaviour                                                        |
| --------- | ---------------------------------------------------------------- |
| `Prefix`  | endpoint is a URL / host / path prefix (see table above)          |
| `Contains`| the request URL contains the endpoint string anywhere             |
| `Regex`   | Java regex (case-insensitive) searched anywhere in the URL        |

### Rules

- **First matching group wins** — use the ↑ / ↓ buttons to set priority
  (put more specific groups on top).
- Lines starting with `#` or `//` in the endpoint box are ignored (comments).
- Commas separate endpoints, but a line containing `{` is never comma-split —
  so regex quantifiers like `admin/.*{1,3}` stay intact.
- When a group no longer matches a request (edited/removed), its highlight and
  its group-name note are cleaned up automatically (a note you wrote yourself
  is never touched).

## Project layout

```
build.sh                                  build the jar (downloads Montoya API if needed)
test.sh                                   headless self-test (59 checks)
src/main/java/scopehighlighter/
  ScopeHighlighterExtension.java          entry point, collect/auto-highlight wiring
  http/HighlighterHandler.java            auto-highlight + request recording on live traffic
  log/MessageLog.java                     request logger (persisted in project file)
  log/MessageRecord.java                  one recorded exchange
  log/Source.java                         show all / repeater / intruder / history / target
  model/ScopeGroup.java                   group + URL matching
  model/MatchMode.java                    Prefix / Contains / Regex
  model/GroupStore.java                   groups + settings persistence, change listener
  ui/ScopeHighlighterTab.java             the Excel-like grid + Action column
  ui/ResultsFrame.java                    reveal window (recorded requests + raw req/resp)
  ui/GroupDialog.java                     add/edit dialog (Excel paste box)
  ui/Host.java                            UI <-> Burp bridge
  util/EndpointParser.java                Excel paste parsing
src/test/java/scopehighlighter/SelfTest.java
```

## Verify

```bash
./test.sh
```

Runs a headless self-test of the paste parser, URL matcher, group persistence
and change listener, the message logger (record/filter/persist/cap), the grid
model and the results table model. All checks must pass before building a
release jar.

## Roadmap

Planned adjustments on top of this base: TBD (import/export, per-tool filters,
scope diffs, etc.).

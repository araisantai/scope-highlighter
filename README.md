# Scope Highlighter

A Burp Suite extension (Montoya API, Java) that **auto-highlights requests in
Burp history** based on endpoint groups you define, each with its own title and
Burp highlight color — and works as a **per-endpoint request logger** for
Repeater, Intruder, Proxy history and Target, with automatic Organizer sync.

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
   | #  | Endpoint / Group            | Color | Match  | Sources                | Action                                        |
   | G1 | Hiring Stage  (2 endpoints) | Red   | Prefix | Repeater, History      | [copy endpoint][show all][notes to organizer]  |
   | 1  |   https://example.com/hi..  | Red   | Prefix | Repeater, Target       | [copy endpoint][show all][notes to organizer]  |
   | 2  |   /hiring-detail/admin      | Red   | Prefix | History                | [copy endpoint][show all][notes to organizer]  |
   ```

   The **Sources** column shows which Burp places hold data for each row
   (Repeater / Intruder / History / Target), refreshed automatically.
   Action buttons are colorized (copy = slate, show all = green, notes to
   organizer = orange) with hover and pressed feedback.

   Add groups with title, color (Burp's 9 highlight colors), match mode and
   the endpoint list pasted straight from Excel. Groups are stored in the Burp
   project file and survive restarts.
3. **Excel-friendly paste** — copy a column (or block) of endpoints straight
   from Excel into the group's endpoint box. Newlines, tabs (Excel columns) and
   commas are all treated as separators. Duplicates are removed, `#` comments
   ignored.
4. **Three row actions**
   - **copy endpoint** — copies the endpoint (or the whole endpoint list on a
     group row) to the clipboard.
   - **show all** — opens the reveal window (see below).
   - **notes to organizer** — sends every recorded request of that row to
     Burp's Organizer, noted with the group title and highlighted in the group
     color (duplicates are never sent twice).
5. **Left-click behaviour (Burp-style)**
   - left-click endpoint row: the standard options menu (Send to Repeater,
     Send to Intruder, Send to Comparer, Send to Organizer, Copy endpoint,
     Show all) — right-click works too
   - double-click endpoint row: **Show All** (never opens the group editor)
   - (double-)click group row: opens the scope-group editor
6. **"show all" reveal window** — every related request of the endpoint,
   combining ALL available sources:
   - source chooser: **show all / repeater / intruder / target / proxy history**
     (proxy history and target are read live from Burp, so nothing is missed
     even for requests made before the extension loaded)
   - messages rendered in **Burp's own message editors** (Pretty / Raw / Hex),
     like Burp's search history — with the search term highlighted
   - **Inspector-style details pane** (method, URL, status, query parameters,
     request headers) and the **Notes** of the message
   - **live refresh**: every upcoming request of that endpoint appears
     automatically while the window is open (Repeater + Intruder included)
   - rows tinted with the group color, per-source counts, live text filter,
     and a diagnostic hint when a source has requests that don't match the
     endpoint pattern (shows sample URLs so the pattern can be fixed)
7. **Request logger, saved with the project** — every request is recorded with
   its tool of origin and persisted into the Burp project file, so Repeater and
   Intruder requests survive save/reload — Burp itself keeps no history API for
   those tools. The log holds the most recent 3000 requests (oldest dropped
   first); oversized bodies are truncated at 200 KB. Existing Proxy history is
   imported on first load.
8. **Auto-add to Organizer** (toolbar checkbox, on by default) — every request
   matching a group is sent to the Organizer automatically, noted with the
   group title and highlighted in the group color.

   *Limitation:* the Montoya API (even in 2026.x) does not expose Organizer
   **collections** — the group title is written into each item's *Notes*,
   which is the closest grouping mechanism available to extensions.
9. Optional: write the group title into the history **Notes** column (only
   when the note is empty).

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
4. Click **show all** on a row to reveal its recorded requests; **copy
   endpoint** and **notes to organizer** are next to it. Left-click an
   endpoint row for the Burp-style context menu.
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
- Recording starts when the extension loads: past Repeater/Intruder requests
  from before the extension was loaded cannot be recovered (Burp keeps no
  history for them) — load the extension at the start of a session and
  everything is recorded.

## Project layout

```
build.sh                                  build the jar (downloads Montoya API if needed)
test.sh                                   headless self-test (82 checks)
src/main/java/scopehighlighter/
  ScopeHighlighterExtension.java          entry point, host bridge, organizer sync
  http/HighlighterHandler.java            auto-highlight + request recording on live traffic
  log/MessageLog.java                     request logger (persisted in project file)
  log/MessageRecord.java                  one recorded exchange (with notes)
  log/Source.java                         show all / repeater / intruder / target / proxy history
  organizer/OrganizerSync.java            organizer dedupe state (persisted)
  model/ScopeGroup.java                   group + URL matching
  model/MatchMode.java                    Prefix / Contains / Regex
  model/GroupStore.java                   groups + settings persistence, change listener
  ui/ScopeHighlighterTab.java             the Excel-like grid + Action column + context menu
  ui/ResultsFrame.java                    show all window (sources, editors, inspector, notes)
  ui/BurpMessageViewer.java               Burp message editors (Pretty/Raw/Hex) with fallback
  ui/MessageViewer.java                   viewer abstraction
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
and change listener, the message logger (record/filter/persist/cap/notes), the
action-button click routing, the organizer dedupe state, the inspector model,
the grid model and the results table model. All checks must pass before
building a release jar.

## Roadmap

Planned adjustments on top of this base: TBD (import/export, per-tool filters,
scope diffs, etc.).

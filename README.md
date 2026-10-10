# Scope Manager

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
2. **"Scope Manager" tab** — an Excel-like grid:

   ```
   | #  | Endpoint / Group            | Color | Match  | Sources           | Total request | With Notes |
   | G1 | Hiring Stage  (2 endpoints) | Red   | Prefix | Repeater, History | 8             | 3          |
   ```

   The **Sources** column shows which Burp places hold data for each row
   (Repeater / Intruder / History / Target). **Refresh Notes** updates the
   Sources, Total request, and With Notes columns; **Push Organizer** sits beside it in the toolbar.

   Add groups with title, color (Burp's 9 highlight colors), match mode and
   the endpoint list pasted straight from Excel. Groups are stored in the Burp
   project file and survive restarts.
3. **Excel-friendly paste** — copy a column (or block) of endpoints straight
   from Excel into the group's endpoint box. Newlines, tabs (Excel columns) and
   commas are all treated as separators. Duplicates are removed, `#` comments
   ignored.
4. **Toolbar Push Organizer** — catches up all matching requests that have a
   non-empty user note and sends them to Burp's Organizer Inbox. New noted
   traffic and notes changed in Show All are also queued automatically.
5. **Endpoint mouse controls**
   - left-click endpoint row: select it
   - right-click endpoint row: the options menu (Send to Repeater,
     Send to Intruder, Send to Comparer, Send to Organizer, Copy endpoint,
     Show all)
   - double-click endpoint row: **Show All** (never opens the group editor)
   - double-click group row: opens the scope-group editor
6. **"show all" reveal window** — every related request of the endpoint,
   combining ALL available sources:
   - source chooser: **show all / repeater / intruder / target / proxy history**
     (proxy history and target are read live from Burp, so nothing is missed
     even for requests made before the extension loaded)
   - messages rendered in **Burp's own message editors** (Pretty / Raw / Hex),
     like Burp's search history — with the search term highlighted
   - **Inspector-style details pane** (method, URL, status, query parameters,
     request headers) and an editable **Notes** field. Notes are blank by
     default; edits are persisted and synchronized with mutable History and
     Target annotations.
   - the window opens immediately while matching requests load in the background;
     use **Refresh** to include requests made after opening it
   - rows tinted with the group color, per-source counts, and a text filter
7. **Request logger, saved with the project** — every request is recorded with
   its tool of origin and persisted into the Burp project file, so Repeater and
   Intruder requests survive save/reload — Burp itself keeps no history API for
   those tools. The log holds the most recent 1500 requests (oldest dropped
   first); oversized bodies are truncated at 50 KB. Existing Proxy history is
   imported on first load.
8. **Organizer note sync** — only requests matching a scope group with a
   non-empty note are sent. Blank notes remain blank and are skipped. Changing
   a note in Show All queues an updated copy. Notes changed directly in live
   History or Target are picked up when those sources are next read (for
   example, **Refresh Notes**, **Push Organizer**, or opening Show All). There is
   no continuous history scan, to keep Burp responsive. The Montoya API cannot
   edit an existing Organizer item or create/move native collections, so an
   updated note adds a new Inbox item and the old one remains. Clearing a note
   does not remove an item already in Organizer; use Burp's Organizer UI for
   that. A group title selects matching requests but is not written into a
   user's note.
9. The extension never fills a request note automatically. Notes stay empty
   until you add one in Repeater, History, Target, or Show All.

## Install

Requires Burp Suite 2023.1+ (Montoya API). Build with any JDK 17+.

```bash
./build.sh          # produces dist/scope-manager.jar
```

Then in Burp: **Extensions -> Installed -> Add -> Extension type: Java ->
Select file: `dist/scope-manager.jar`**.

The **Scope Manager** tab appears in Burp's top tab bar.

## Usage

1. Open the **Scope Manager** tab and click **Add group…**
2. Fill in **Group title** (e.g. `Hiring Stage`), pick the **Highlight color**
   and **Match mode**, then paste your endpoints from Excel.
3. Click **Add**. Existing and new traffic matching the group is highlighted
   immediately — no confirmation needed.
4. Double-click an endpoint row to reveal its recorded requests. Right-click
   an endpoint row for the context menu, or use toolbar **Push Organizer** to catch
   up noted requests across all groups.
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

Variable URL parts can use `{name}` or `{{name}}` in Prefix or Contains mode.
For example, `https://example.com/pending-orders/{order_id}?transaction_id={randomtrxid}&channel=b0`
matches `https://example.com/pending-orders/12345?transaction_id=abc987&channel=b0`.
The placeholder names are labels; each path placeholder matches one non-empty
path segment, and each query placeholder matches one non-empty value. If a
request has no query string, the URL template still matches its path (for
example, `/pending-orders/123455`). If a query string is present, fixed
query parts such as `channel=b0` must still match. Regex mode keeps its normal
Java regex syntax.

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
test.sh                                   headless self-test
src/main/java/scopemanager/
  ScopeManagerExtension.java          entry point, host bridge, organizer sync
  http/HighlighterHandler.java            auto-highlight + request recording on live traffic
  log/MessageLog.java                     request logger (persisted in project file)
  log/MessageRecord.java                  one recorded exchange (with notes)
  log/Source.java                         show all / repeater / intruder / target / proxy history
  organizer/OrganizerSync.java            organizer dedupe state (persisted)
  model/ScopeGroup.java                   group + URL matching
  model/MatchMode.java                    Prefix / Contains / Regex
  model/GroupStore.java                   groups + settings persistence, change listener
  ui/ScopeManagerTab.java             the Excel-like grid + toolbar + context menu
  ui/ResultsFrame.java                    show all window (sources, editors, inspector, notes)
  ui/BurpMessageViewer.java               Burp message editors (Pretty/Raw/Hex) with fallback
  ui/MessageViewer.java                   viewer abstraction
  ui/GroupDialog.java                     add/edit dialog (Excel paste box)
  ui/Host.java                            UI <-> Burp bridge
  util/EndpointParser.java                Excel paste parsing
src/test/java/scopemanager/SelfTest.java
```

## Verify

```bash
./test.sh
```

Runs a headless self-test of the paste parser, URL matcher, group persistence
and change listener, the message logger (record/filter/persist/cap/notes), the
mouse click routing, the organizer dedupe state, the inspector model,
the grid model and the results table model. All checks must pass before
building a release jar.

## Roadmap

Planned adjustments on top of this base: TBD (import/export, per-tool filters,
scope diffs, etc.).

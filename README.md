# Scope Highlighter

A Burp Suite extension (Montoya API, Java) that **auto-highlights requests in
Burp history** based on endpoint groups you define, each with its own title and
Burp highlight color.

Built for bug bounty / pentest workflows where you manage scope endpoints in
Excel and want a fast visual split in Burp — e.g. "admin career page" in red,
"admin verification location" in blue.

## Features

1. **Auto-highlight** — every request flowing through Burp (Proxy, Repeater,
   Scanner, ...) is matched against your groups and highlighted automatically.
2. **"Scope Highlighter" tab** — manage groups: title, color, match mode, and
   the endpoint list. Groups are persisted in the Burp project file, so they
   survive restarts.
3. **Excel-friendly paste** — copy a column (or block) of endpoints straight
   from Excel into the group's endpoint box. Newlines, tabs (Excel columns) and
   commas are all treated as separators. Duplicate lines are removed.
4. **Highlight existing history** — one click applies your colors to requests
   already sitting in the Proxy history.
5. Optional: write the group title into the **Notes** column (only when the
   note is empty), so the group name is visible next to the highlight color.

## Install

Requires Burp Suite 2023.1+ (Montoya API). Build with any JDK 17+.

```bash
./build.sh          # produces dist/scope-highlighter.jar
```

Then in Burp: **Extensions -> Installed -> Add -> Extension type: Java ->
Select file: `dist/scope-highlighter.jar`**.

The **Scope Highlighter** tab appears in Burp's top tab bar.

## Usage

1. Open the **Scope Highlighter** tab.
2. Click **Add group…**
3. Fill in:
   - **Group title** — e.g. `admin career page` (also written to Notes).
   - **Highlight color** — Red, Orange, Yellow, Green, Cyan, Blue, Pink,
     Magenta or Gray (the standard Burp history highlight colors).
   - **Match mode** — `Prefix` (default), `Contains` or `Regex`.
   - **Endpoints** — paste from Excel. The dialog live-counts the parsed
     endpoints.
4. Click **Add**. New traffic matching the group is highlighted immediately.
5. Click **Highlight existing history** to color traffic captured before the
   group existed.

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
- A group's on/off checkbox in the table disables it without deleting it.
- Lines starting with `#` or `//` in the endpoint box are ignored (comments).
- Commas separate endpoints, but a line containing `{` is never comma-split —
  so regex quantifiers like `admin/.*{1,3}` stay intact.

## Project layout

```
build.sh                                  build the jar (downloads Montoya API if needed)
test.sh                                   headless self-test (33 checks)
src/main/java/scopehighlighter/
  ScopeHighlighterExtension.java          entry point (BurpExtension)
  http/HighlighterHandler.java            auto-highlight on live traffic
  model/ScopeGroup.java                   group + URL matching
  model/MatchMode.java                    Prefix / Contains / Regex
  model/GroupStore.java                   persistence + match precedence
  ui/ScopeHighlighterTab.java             the "Scope Highlighter" tab
  ui/GroupDialog.java                     add/edit dialog (Excel paste box)
  util/EndpointParser.java                Excel paste parsing
src/test/java/scopehighlighter/SelfTest.java
```

## Verify

```bash
./test.sh
```

Runs a headless self-test of the paste parser, URL matcher, group persistence
round-trip, match precedence and the tab model. All checks must pass before
building a release jar.

## Roadmap

Planned adjustments on top of this base: TBD (the extension is intentionally
small so features can be layered on: import/export, per-tool filters, scope
diffs, etc.).

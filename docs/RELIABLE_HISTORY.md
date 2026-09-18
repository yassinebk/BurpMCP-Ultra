# Reliable history for large Burp projects

Burp's Montoya API returns Proxy history as a complete `List`. It does not expose database-level
pagination or deletion. Calling `proxy.history(filter)` therefore evaluates a filter across the
entire project before an MCP tool can apply `count`, `offset`, or `max_results`.

This fork provides two complementary paths.

## Existing project history

- `proxy_history_summary` returns compact metadata and cursor-pages by stable Burp id.
- `proxy_history_entry` retrieves one selected request and response.
- `proxy_history_search_bounded` examines only a caller-selected id window and stops at a scan
  limit, result limit, regex-per-input limit, or wall-clock budget. Continue with `next_before_id`.

Montoya still creates the initial history list, so these tools bound expensive message inspection
and output but cannot make the initial list retrieval independent of project size.

## Size-independent live index

The extension records traffic observed after it loads in a fixed-memory ring:

- at most 20,000 entries;
- at most 128 MiB of retained request/response text;
- at most 32,000 characters from each request or response;
- oldest entries are evicted automatically.

`proxy_index_summary`, `proxy_index_entry`, and `proxy_index_search` use only this index. They never
read Burp's project database, so their runtime and memory usage do not grow with a multi-gigabyte
`.burp` file. `proxy_index_stats` shows current bounds and `proxy_index_clear` releases the retained
data immediately.

`proxy_index_policy_set` can restrict capture to in-scope traffic, include or exclude host globs,
and ignore selected file extensions. The policy is stored in Burp preferences.

## Optional persistent sidecar

Set `persistence_enabled` with `proxy_index_policy_set` to retain the bounded index across extension
reloads and Burp restarts. Persistence is deliberately opt-in. Each Burp project name maps to a
separate JSONL sidecar below `~/.burpmcp-ultra/history/`; the file is capped at 256 MiB and compacted
to the current bounded snapshot. Writes use a bounded background queue so proxy handling does not
wait for disk I/O.

Before a message is written, the sidecar redacts Authorization, Proxy-Authorization, Cookie,
Set-Cookie, X-API-Key, common password fields, and common token fields. The current in-memory index
retains the original captured text. `proxy_index_persistence_clear` deletes only the sidecar for the
current project.

For callers that cannot wait on a search response, `proxy_index_search_start` returns immediately.
Use `proxy_index_search_job` to retrieve its status or result and `proxy_index_search_cancel` to stop it.

## Repeater organization

Every tab created through `repeater_send` or `repeater_send_batch` is recorded in a bounded session
manifest. Callers can attach a purpose and source Proxy-history ID, then query logical groups with
`repeater_manifest_list` and `repeater_manifest_groups`. Clearing manifest metadata never closes or
modifies native Repeater tabs.

## What cannot be trimmed

Montoya 2026.2/2026.7 does not expose deletion or clearing of Burp Proxy history. Consequently,
`proxy_index_clear` deliberately does not modify Proxy history or the `.burp` project database.
Actual project-size control requires Burp logging filters, excluding noisy traffic, or rotating to
a new project. UI automation or direct database editing is intentionally not used because either
can corrupt evidence or project state.

## Recommended agent workflow

1. Enable redacted sidecar persistence if the index must survive extension reloads.
2. Configure scope, host, and extension filters before capturing noisy traffic.
3. Use `proxy_index_search` for normal traffic and background search jobs for longer queries.
4. Use `proxy_history_summary` to locate an id in older project data.
5. Use `proxy_history_search_bounded` only when older bodies must be searched.
6. Fetch full content for selected hits with `proxy_history_entry`.
7. Continue cursor pages instead of increasing limits.

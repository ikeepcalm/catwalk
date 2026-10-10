# CatWalk audit events

Producer `catwalk`, through the shaded audit client (relocated to `dev.ua.uaproject.catwalk.libs.audit`).
Rows are spooled to `plugins/mysterria-audit-spool`; the envelope `server`/`environment` come from
`mysterria.audit.server-id` / `mysterria.audit.environment` (or `MYSTERRIA_AUDIT_*`). `audit.enabled`
(default `true`) and `audit.per-peer-row-limit` (default `20`, `0` = off) are read at startup only.
Never recorded: the API key, `Authorization`, the `x-catwalk-key` cookie, bodies, query strings, the
keystore password, path lists. A key appears only as `key_fingerprint` (first 12 hex of SHA-256;
`none` / `invalid-format`).

| Event type | Outcome(s) | Key facts |
| --- | --- | --- |
| `api.request` | `COMMITTED` 2xx/3xx, `DENIED` 401/403, `FAILED` 5xx, `OBSERVED` other 4xx | Non-GET/HEAD/OPTIONS calls, 4xx/5xx, wrong key, or no key on a protected route. Correlation = `X-Request-Id` (caller UUID or generated, echoed on the response). `method`, `path`, `route`, `status`, `duration_ms`, `remote_ip` (TCP peer), `forwarded_for` (claim only), `forwarded` (boolean), `auth_result` (`ok`/`missing`/`invalid`/`whitelisted`/`disabled`), `handler_auth`, `key_fingerprint`, `user_agent`, `request_bytes`, `response_bytes`, `owner_plugin` (handler routes only), `request_id_source`. 4xx and missing/wrong-key rows are capped per peer per minute. `STAFF_RESTRICTED`; `HIGH` for `DENIED`. |
| `api.request_suppressed` | `OBSERVED` | One per (peer, status class, auth result) per minute over the cap: `remote_ip` or `overflow`, `suppressed_count`, `emitted_count`, `limit`, `key_fingerprints`, `distinct_key_fingerprints`, `key_fingerprints_truncated` (only when cut), `first_path`/`last_path`, `first_seen`/`last_seen`, window fields. |
| `api.request_summary` | `OBSERVED` | Hourly per (route, status class) for allowed reads without an `api.request` row: `route` (`unmatched`, `other` on overflow), `status_class`, `count`, `p50_ms`, `max_ms`, window fields. Max 2 000 keys per hour. |
| `api.route_registered` | `COMMITTED`, `CANCELLED` (blocked path), `FAILED` (replay threw) | Each route registered through `CatWalkWebserverService`, again on replay after reload: `owner_plugin` (handler routes only: the addon name CatWalk already uses for them), `method`, `path`, `kind` (`route`/`websocket`/`handler`), `replay`, `blocked` (only when true), `addon_name`, `error_class`. |
| `catwalk.reload` | `COMMITTED`, `FAILED` | `/catwalk reload`. Actor = player UUID; `actor_type`, `actor_name`, `_before`/`_after` snapshots (`auth_enabled`, `tls`, path counts, `key_fingerprint`, `mode`, `port`), `key_changed` (the keys are hashed off the server thread); unsuffixed `blocked_paths_count` / `whitelisted_paths_count` repeat the after counts. Shares its correlation id with the stop/start/replay rows it causes. `HIGH`. |
| `catwalk.webserver_started` | `COMMITTED`, `FAILED` | On enable and reload: `port`, `tls`, `tls_configured`, `auth`, path counts, `mode`, `server_id`, `catwalk_server_id`, `trigger`, `error_class`. `HIGH` for `FAILED`. |
| `catwalk.webserver_stopped` | `COMMITTED`, `FAILED` | On reload and disable; same fields as `webserver_started`. |

Window fields: `window_start`, `window_end` (ISO-8601 UTC), `partial` (cut short by disable),
`clock_step` (window closed after the clock was corrected backwards). Text is capped at 256 characters
(`path` 200, `user_agent` 120), at most 48 metadata keys per row.

Not audited: individual successful reads, websocket traffic, hub-mode proxy routes, routes added directly
on `getWebserver()`, `/catwalk info|status|endpoints|network`.

Main thread: no lookup runs only to fill a row. `owner_plugin` is no longer looked up from the
registering class's plugin (`JavaPlugin.getProvidingPlugin`), so routes added with `get`/`post`/`put`/
`delete`, the `*WithResponse` variants and `websocket` have no `owner_plugin`.

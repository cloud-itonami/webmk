# webmk — the web-marketing proposal actor

`webmk` is short for **web marketing**. The name does not say what it does, so:
this repository is a **governed actor that drafts a web-marketing proposal for a
client and hands it back over XRPC**. Give it a client name, an industry, and
optionally a website URL and a budget; it researches, drafts a strategy and copy,
scores the draft, stores it, and can mail it out.

- DID: `did:web:webmk.etzhayyim.com`
- nanoid: `wbmk0001`
- Five endpoints: `health`, `createProposal`, `deliverProposal`, `getProposal`,
  `listProposals` — all under `com.etzhayyim.apps.webmk.*`

**Start here: [`docs/operator-quickstart.md`](docs/operator-quickstart.md).** It is a
walked path — every command in it was run against this tree, and the responses
quoted there are the responses it produced.

## What is actually in this repository

Three planes. Only the first one runs today from a clean checkout.

| Plane | Path | State |
|---|---|---|
| **Agent loop** (canonical) | `lg/` | **Runs.** cljc on `langgraph-clj`, `bb` host. 11 tests / 36 assertions green |
| Edge worker | `appview/webmk-wbmk0001/` | Svelte + Cloudflare Worker config. Not exercised here |
| kotoba TS package | `kotoba/` | Needs two `git+https` deps from the `etzhayyim` org; `node_modules` is absent and the install is not part of the quickstart |

The agent loop is the canonical implementation. The Python LangGraph app it was
ported from was **deleted** under ADR-2606280030 — if you find instructions
referring to `lg/lg_webmk/*.py`, `pyproject.toml`, `langgraph.json`, or
`python -m kotodama.webmk_worker_main`, they describe code that is no longer here.

### The five graphs

| NSID | assistant_id | topology |
|---|---|---|
| `…webmk.health` | `health` | `check_store → summarize → audit` |
| `…webmk.createProposal` | `create_proposal` | `init → research_company → analyze_competitors → generate_strategy → generate_copy → quality_gate ⇄ store_proposal → audit` |
| `…webmk.deliverProposal` | `deliver_proposal` | draft → Resend → mark delivered |
| `…webmk.getProposal` | `get_proposal` | store read |
| `…webmk.listProposals` | `list_proposals` | store scan, newest-first, limit/offset |

## Two things that will surprise you

**1. Nothing reaches the outside world unless you hand it the capability.**
`lg-webmk.server/-main` throws on purpose:

```
WebMK portable runtime requires an explicit host adapter
```

That is not a bug. `src/` is portable `.cljc` and refuses ambient authority — no
socket, no HTTP client, no store, unless passed in. The two files that hand those
capabilities over are `lg/serve.clj` (the server) and `lg/run_tests.clj` (the
suite). They are the only places where `org.httpkit.server` and
`babashka.http-client` are reached. Adding an effect means going through one of
them, on purpose, where a reviewer will see it.

**2. `LG_API_KEY` is not a lock on the actor.** It gates `/runs` and
`/runs/stream`. It does **not** gate `/xrpc/*`, which reaches all five graphs.
Measured — see the quickstart's auth section. Put a real boundary in front of this
service; do not treat `LG_API_KEY` as one.

## Persistence

Off by default. `WEBMK_STORE_ENABLED=1` turns on an in-process append-only store
(`lg-webmk.store`), which is a swap seam for a kotoba Datom-log backend. It is
**in-process**: it does not survive a restart, and two replicas do not share it.
RisingWave/Postgres is out of bounds by charter (ADR-2605262130 / 2605312345).

With the store off, `health` truthfully answers `{"ok":false,"error":"store not
enabled"}`. That is the probe working, not the server failing.

## Layout

```
lg/                        the agent loop — start here
  bb.edn                   tasks: test, serve
  serve.clj                host adapter: sockets + HTTP client live here
  run_tests.clj            host adapter for the suite
  src/lg_webmk/            portable .cljc — no ambient authority
    server.cljc            /runs /runs/stream /xrpc/{nsid} /health
    store.cljc             swap seam (in-process append-only)
    llm.cljc               LiteLLM loopback, read-only, fail-open
    audit.cljc             fire-and-forget audit emit
    graphs/                the five graphs
  tests/lg_webmk/          smoke suite
appview/webmk-wbmk0001/    Svelte + Cloudflare Worker
kotoba/                    TS package (deps not vendored)
docs/operator-quickstart.md
CLAUDE.md                  design notes; paths predate the repo extraction
```

## Licence

Apache-2.0 — see `NOTICE`.

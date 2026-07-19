# lg-webmk — LangGraph Server actor for webmk.etzhayyim.com

Web Marketing Proposal Agent. OSS LangGraph FastAPI pattern (mirrors lg-yukkuri).

## Clojure port (ADR-2606280030 — langgraph-python → langgraph-clj)

A faithful `langgraph-clj` (babashka) twin of the Python LangGraph app lives under
`lg/src/lg_webmk/` (+ `lg/tests/`, `lg/bb.edn`, `lg/run_tests.clj`). Same 5 graphs,
same node topology, same NSID surface. **The clj twin is now the CANONICAL code:
the DEV-stage Python (`lg/lg_webmk/*.py`) and its python-only scaffolding
(`pyproject.toml`, `langgraph.json`, `Dockerfile`, `lg/tests/test_smoke.py`) were
DELETED per ADR-2606280030 (twin の py を削除)** — the twin covered every module (no
hard native dep to keep) and no code outside this app imported `lg_webmk`. The app
has no cron (`50-infra/vultr/lg-webmk-pool/` is Deployment-only, no CronJob), so the
deletion stops no scheduled work. The out-of-app helm chart + the
`kotodama.webmk_worker_main` worker are a separate deploy-cutover concern (not
touched here).

| Python | clj twin | notes |
|---|---|---|
| `langgraph.graph.StateGraph` | `langgraph.graph` (`io.github.com-junkawasaki/langgraph-clj`) | `:nodes`/`:edges`/`add-conditional-edges` |
| `httpx` | `babashka.http-client` | research fetch + Resend REST |
| `langchain_anthropic.ChatAnthropic` | `lg-webmk.llm` → Murakumo LiteLLM loopback (`/v1/chat/completions`, gemma-4-e4b-it) | read-only, fail-open template fallback (ADR-2605215000 / 2606172359) |
| JSON | `cheshire` | — |
| RisingWave / `psycopg` | `lg-webmk.store` swap seam (in-process append-only; kotoba-Datom-log target) | substrate boundary forbids RisingWave (ADR-2605262130 / 2605312345) |
| FastAPI | `org.httpkit.server` (`lg-webmk.server`) | same `/runs` `/runs/stream` `/xrpc/{nsid}` `/health` surface |

**Deviation (noted):** the Python quality-retry router can loop indefinitely on the
no-LLM fallback path (the gate stops incrementing `retry_count` after the first retry
while the router keeps routing back). The clj port preserves the *intent* ("retry
once") with a terminating gate (always increments; regenerates only while
`retry_count < 2`).

Run: `bb --config 60-apps/etzhayyim-project-webmk/lg/bb.edn test` (9 tests / 29 assertions green).

## Layout

```
lg/
├── bb.edn                                   # app-scoped babashka project (langgraph-clj pin)
├── run_tests.clj                            # bb-native test runner
├── src/lg_webmk/
│   ├── audit.cljc                           # fire-and-forget BPMN generic.audit.emit
│   ├── llm.cljc                             # Murakumo LiteLLM loopback (read-only, fail-open)
│   ├── server.cljc                          # http-kit /runs /runs/stream /xrpc/{nsid} /health
│   ├── store.cljc                           # store swap seam (replaces RW/psycopg + checkpointer)
│   └── graphs/
│       ├── health.cljc                      # com.etzhayyim.apps.webmk.health
│       ├── create_proposal.cljc             # com.etzhayyim.apps.webmk.createProposal
│       ├── deliver_proposal.cljc            # com.etzhayyim.apps.webmk.deliverProposal
│       ├── get_proposal.cljc                # com.etzhayyim.apps.webmk.getProposal
│       └── list_proposals.cljc             # com.etzhayyim.apps.webmk.listProposals
└── tests/lg_webmk/
    └── test_smoke.cljc                      # smoke tests (9 tests / 29 assertions)
```

## NSID Coverage (5 of 5)

| NSID | assistant_id | graph file | status |
|---|---|---|---|
| `com.etzhayyim.apps.webmk.health` | `health` | health.py | ✅ |
| `com.etzhayyim.apps.webmk.createProposal` | `create_proposal` | create_proposal.py | ✅ |
| `com.etzhayyim.apps.webmk.deliverProposal` | `deliver_proposal` | deliver_proposal.py | ✅ |
| `com.etzhayyim.apps.webmk.getProposal` | `get_proposal` | get_proposal.py | ✅ |
| `com.etzhayyim.apps.webmk.listProposals` | `list_proposals` | list_proposals.py | ✅ |

## Proposal Lifecycle

DAG: `validate_input → generate_content → store_proposal → notify_delivery`

1. **validate_input** — Validates proposal request fields (company, budget, goals).
2. **generate_content** — LLM generates proposal sections (executive summary, tactics, KPIs, budget breakdown).
3. **store_proposal** — Persists to `vertex_webmk_proposal` with `actor_did`/`org_did` RLS columns (ADR-0095).
4. **notify_delivery** — Emits audit event; delivery triggers via `notifyCompany` pattern.

## LLM Models

| Role | Model | Trigger |
|---|---|---|
| Proposal generation | `gemma-4-e4b-it` | `createProposal` with content generation enabled |

Routes through `llm.etzhayyim.com` (LiteLLM gateway → murakumo-serve fleet).

## Env Vars

| Var | Default | Purpose |
|---|---|---|
| `RW_URL` / `LG_CHECKPOINTER_URL` | (required) | RisingWave PG :4566 |
| `WEBMK_APP_DID` | `did:web:webmk.etzhayyim.com` | Actor DID for audit |
| `WEBMK_LLM_URL` | `http://llm.etzhayyim.com` | LiteLLM gateway |
| `WEBMK_LLM_API_KEY` | `""` | Bearer token |
| `WEBMK_LLM_MODEL` | `gemma-4-e4b-it` | Proposal generation model |
| `WEBMK_LLM_TIMEOUT` | `30` | LLM request timeout (sec) |
| `LG_API_KEY` | `""` | Optional /runs auth key |

## Helm Chart

`50-infra/vultr/lg-webmk-pool/` — Deployment only (no CronJob; webmk has no resident cron loops).

## DB Schema

Graph table: `vertex_webmk_proposal` (migration `20260516650000_vertex_webmk_proposal_rls_columns.up.sql`).

Columns include `actor_did VARCHAR NOT NULL DEFAULT 'anon'`, `org_did VARCHAR NOT NULL DEFAULT 'anon'` per ADR-0095.

## Phases

| Phase | Scope | Status |
|---|---|---|
| **P1** LangGraph scaffold | 5 graphs + server + Dockerfile + langgraph.json | ✅ 2026-05-16 |
| **P1** DB schema | `vertex_webmk_proposal` | ✅ migration `20260516650000` |
| **P1** Helm chart | `50-infra/vultr/lg-webmk-pool/` | ✅ deployment.yaml |
| **P1** CF tunnel route | cloudflared ConfigMap webmk NSID routes | ✅ 2026-05-16 added to bpmn-dispatcher-tunnel.yaml |
| **P1** Lexicon | `00-contracts/lexicons/com/etzhayyim/apps/webmk/` | ✅ 5 lexicons (health + 4 XRPC) |
| **P2** LLM proposal content | Full gemma-4-e4b-it proposal generation | ✅ implemented in create_proposal.py |
| **P3** Delivery integration | Email/notification deliver pathway | ⏳ deliver_proposal.py stub wired, delivery backend pending |

# Operator quickstart — lg-webmk

Every command below was run against this tree, in this order, on 2026-08-19. The
responses are the responses it gave. If a step stops working, that is a
regression, not a typo in this file.

Two things this quickstart does **not** need: no database, no API keys, no
network. The LLM call fails open to a deterministic template, so the whole path
below completes offline.

## 0. Prerequisites

```bash
bb --version    # babashka v1.12.218 when this was walked
```

Nothing else. `langgraph-clj` is pinned in `lg/bb.edn` and babashka fetches it on
first run — expect the first command to take ~20s longer than later ones.

> `kotoba/` (TypeScript) is **not** part of this path. It depends on two
> `git+https` packages in the `etzhayyim` org and has no `node_modules` here.

## 1. Run the suite

Both tasks resolve their entry file relative to the working directory, so `cd`
first — `bb --config lg/bb.edn test` from the repo root will not find the file.

```bash
cd lg
LG_AUDIT_DISABLED=1 bb --config bb.edn test
```

```
Ran 11 tests containing 36 assertions.
0 failures, 0 errors.
── lg-webmk: ALL suites green ──
```

`LG_AUDIT_DISABLED=1` only silences a reminder; the suite already stubs the audit
emit, so no background HTTP fires either way.

## 2. Start the server

```bash
cd lg
LG_PORT=8791 WEBMK_STORE_ENABLED=1 WEBMK_LLM_TIMEOUT=3 bb --config bb.edn serve
```

```
lg-webmk listening on port 8791 (all interfaces)  (store enabled, api-key not required)
```

Startup takes ~20s (babashka loads the graphs). `WEBMK_LLM_TIMEOUT=3` shortens
the wait for the LLM gateway that is not reachable from here; the default is 30s
and the fallback is the same either way.

### Always set `LG_PORT`. The default silently collides.

The default is `8080`, and httpkit binds the **wildcard** address, not the
loopback. If another process already holds IPv4 `127.0.0.1:8080` — an IPFS
gateway does, and one was running on the machine where this was walked — then
this process still binds the IPv6 wildcard and **starts without an error**:

```
$ bb --config bb.edn serve
lg-webmk listening on port 8080 (all interfaces)

$ curl -s http://127.0.0.1:8080/health
404 page not found            # ← the IPFS gateway answering, not lg-webmk

$ curl -s 'http://[::1]:8080/health'
{"ok":true,"ts":"..."}        # ← lg-webmk, on the v6 wildcard
```

There is no `BindException` because the two sockets are in different address
families. A health check on the v4 address would report this actor as broken
while it runs perfectly, and — worse — a *different* service answers on the
address you think is yours. Pick a port you know is free.

### If it throws instead

```
WebMK portable runtime requires an explicit host adapter
```

You called `lg-webmk.server/-main` directly. It throws by design — see the README.
Use the `serve` task, which loads `serve.clj`, which supplies the capabilities.

## 3. Liveness vs. the store probe — two different questions

```bash
curl -s http://127.0.0.1:8791/health
```
```json
{"ok":true,"ts":"2026-08-19T01:39:24Z"}
```

That is the process answering. Whether the actor can actually *work* is a
different question, and the `health` **graph** answers it:

```bash
curl -s -X POST http://127.0.0.1:8791/xrpc/com.etzhayyim.apps.webmk.health \
  -H 'Content-Type: application/json' -d '{}'
```
```json
{"store-ok":true,"store-latency-ms":0,"ok":true,"server-now":"2026-08-19T01:41:10Z"}
```

Start the server **without** `WEBMK_STORE_ENABLED=1` and the same call returns:

```json
{"store-ok":false,"error":"store not enabled","ok":false,"server-now":"..."}
```

`GET /health` is still `{"ok":true}` in that state. **Monitor the graph, not the
socket** — `GET /health` cannot distinguish a working actor from one that will
drop every proposal.

## 4. Create a proposal

```bash
curl -s -X POST http://127.0.0.1:8791/runs -H 'Content-Type: application/json' \
  -d '{"assistant_id":"create_proposal",
       "input":{"clientName":"ACME Corp","industry":"retail",
                "budgetJpy":500000,"deliveryEmail":"ops@acme.example"}}'
```
```json
{"ok":true,"output":{"proposal-id":"prop-8ccf3311d0c9","stored":true,
  "quality-score":0.0315,"retry-count":2,
  "strategy-json":"{\"goals\":[\"brand awareness\"],\"channels\":[\"SEO\",\"SNS\"],...}",
  "copy-markdown":"# Marketing Proposal for ACME Corp\n\nGenerated proposal content.",
  "company-context":"Company: ACME Corp. No website provided.", ...}}
```

Read the numbers before you trust the output:

- **`quality-score` 0.03, threshold 0.7.** The LLM gateway was unreachable, so
  `generate_strategy`/`generate_copy` fell back to templates, which score near
  zero. The proposal text above is boilerplate, not a draft for a client.
- **`retry-count` 2, and it stored anyway.** The gate retries exactly once and
  then proceeds *regardless of score* — it is a retry, not a veto. A low-quality
  proposal reaches the store and is `deliverProposal`-eligible. If you need a
  score floor before delivery, it is not here yet; enforce it upstream.
- Input keys are accepted as `camelCase`, `snake_case`, or kebab.
- Omitting `websiteUrl` skips `research_company`'s outbound fetch. Supplying one
  makes this call reach that URL.

## 5. Read it back

```bash
curl -s -X POST http://127.0.0.1:8791/xrpc/com.etzhayyim.apps.webmk.getProposal \
  -H 'Content-Type: application/json' -d '{"proposalId":"prop-8ccf3311d0c9"}'
```
```json
{"proposal-id":"prop-8ccf3311d0c9","ok":true,
 "proposal":{"clientName":"ACME Corp","industry":"retail","status":"draft",
             "qualityScore":0.0315,"createdAt":"2026-08-19T01:41:10Z",...}}
```

```bash
curl -s -X POST http://127.0.0.1:8791/xrpc/com.etzhayyim.apps.webmk.listProposals \
  -H 'Content-Type: application/json' -d '{"limit":5}'
```
```json
{"ok":true,"total":2,"limit":5,"offset":0,
 "items":[{"proposalId":"prop-7882985993d0","clientName":"Beta KK",...},
          {"proposalId":"prop-8ccf3311d0c9","clientName":"ACME Corp",...}]}
```

Newest first. **The store is in-process**: restart the server and both rows are
gone, and a second replica would not see them.

## 6. Delivery

```bash
curl -s -X POST http://127.0.0.1:8791/xrpc/com.etzhayyim.apps.webmk.deliverProposal \
  -H 'Content-Type: application/json' \
  -d '{"proposalId":"prop-8ccf3311d0c9","deliveryEmail":"ops@acme.example"}'
```
```json
{"proposal-id":"prop-8ccf3311d0c9","delivery-email":"ops@acme.example",
 "copy-markdown":"# Marketing Proposal for ACME Corp\n\n...","ok":true,"delivered":false}
```

**`"ok":true` with `"delivered":false`.** No `RESEND_API_KEY`, so no mail was
sent. The graph ran fine — that is what `ok` reports. **Alert on `delivered`,
not on `ok`.**

## 7. Watch a run step by step

```bash
curl -s -X POST http://127.0.0.1:8791/runs/stream -H 'Content-Type: application/json' \
  -d '{"assistant_id":"health","input":{}}'
```
```
data: {"node":"check-store","updates":{"store-ok":true,...},"step":0}
data: {"node":"summarize","updates":{"ok":true,...},"step":1}
data: {"node":"audit","updates":{},"step":2}
data: [DONE]
```

One event per superstep — the fastest way to see which node changed what. Note it
is buffered: the whole graph runs, then the events are written.

## 8. Authentication — read this before exposing the port

```bash
cd lg
LG_PORT=8792 LG_API_KEY=secret123 bb --config bb.edn serve
```

| Request | Status |
|---|---|
| `POST /runs`, no key | `401` |
| `POST /runs`, `x-api-key: wrong` | `401` |
| `POST /runs`, `x-api-key: secret123` | `200` |
| `POST /xrpc/com.etzhayyim.apps.webmk.health`, **no key** | **`200`** |

Measured, not inferred. **`LG_API_KEY` does not protect `/xrpc/*`**, and `/xrpc/*`
reaches all five graphs — including `createProposal`, which can be made to fetch
an arbitrary `websiteUrl`, and `deliverProposal`, which sends mail when Resend is
configured. Setting `LG_API_KEY` and exposing the port leaves the actor open. Put
a real boundary in front of it.

## 9. Errors

```bash
curl -s -X POST http://127.0.0.1:8791/runs -H 'Content-Type: application/json' \
  -d '{"assistant_id":"nope","input":{}}'          # 404 {"detail":"graph 'nope' not found"}
curl -s -X POST http://127.0.0.1:8791/xrpc/com.example.bogus \
  -H 'Content-Type: application/json' -d '{}'      # 404 {"detail":"NSID 'com.example.bogus' not mapped"}
curl -s http://127.0.0.1:8791/nope                 # 404 {"detail":"not found"}
curl -s http://127.0.0.1:8791/threads/t1/state     # 404 thread state not retained
```

Thread state is deliberately not retained — there is no checkpointer, by charter.

## Environment reference

| Var | Default | Effect |
|---|---|---|
| `LG_PORT` | `8080` | listen port (wildcard bind — **set it explicitly, see §2**) |
| `LG_API_KEY` | *(empty)* | gates `/runs` and `/runs/stream` only — **see §8** |
| `WEBMK_STORE_ENABLED` | `0` | `1` enables the in-process store; `RW_URL`/`LG_CHECKPOINTER_URL` also enable it |
| `WEBMK_LLM_URL` | `http://llm.etzhayyim.com` | LiteLLM gateway; unreachable ⇒ template fallback |
| `WEBMK_LLM_MODEL` | `gemma-4-e4b-it` | model name sent to the gateway |
| `WEBMK_LLM_TIMEOUT` | `30` | seconds; lower it when the gateway is unreachable |
| `WEBMK_QUALITY_THRESHOLD` | `0.7` | retry threshold — **not** a delivery gate, see §4 |
| `WEBMK_APP_DID` | `did:web:webmk.etzhayyim.com` | actor DID on audit events |
| `RESEND_API_KEY` / `RESEND_FROM` | *(empty)* / `webmk@etzhayyim.com` | unset ⇒ `delivered:false` |
| `BPMN_DISPATCHER_INTERNAL_URL` / `_SECRET` | see `audit.cljc` | audit sink |
| `LG_AUDIT_TIMEOUT_SEC` | `3.0` | audit post timeout |

## What this quickstart does not cover

- `appview/webmk-wbmk0001/` — the Cloudflare Worker. Deploying it is a separate
  concern with its own credentials, and it is not exercised here.
- `kotoba/` — TypeScript. `npm test` needs two `git+https` deps from the
  `etzhayyim` org; the install was not run, so this file makes no claim about it.
- A persistent store. The kotoba Datom-log backend behind `lg-webmk.store`'s swap
  seam is not implemented; the in-process atom is what exists today.

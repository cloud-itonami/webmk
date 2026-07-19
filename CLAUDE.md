# etzhayyim-project-webmk — Web Marketing Proposal Agent

**URL**: `https://webmk.etzhayyim.com` / `https://wbmk0001.etzhayyim.com`
**DID**: `did:web:webmk.etzhayyim.com`
**nanoid**: `wbmk0001`
**ADR**: `90-docs/adr/2605072000-langgraph-agent-loop-pattern.md`

## Architecture

- **Server**: TS thin-edge CF Worker (proxies XRPC to dispatcher)
- **Agent Loop**: LangGraph (Python, L8 pod) via LangServer
- **Delivery**: Resend (email)
- **Ad Integration**: ads.etzhayyim.com `createCampaign` (optional, per request)
- **Data**: RisingWave via Hyperdrive

## Flow

```
createProposal XRPC
  → CF Worker → Dispatcher → LangServer message
    → BPMN: webmk_create_proposal
      1. webmk.run_proposal_agent  (LangGraph: research→competitors→strategy→copy→quality_gate→store)
      2. webmk.deliver_via_resend  (Resend transactional email)
      3. webmk.create_ad_campaign  (ads.etzhayyim.com createCampaign, if createAdCampaign=true)
```

## LangGraph Nodes

| Node | Tool | Output |
|---|---|---|
| `research_company` | Playwright scrape + Claude extract | `company_context` |
| `analyze_competitors` | common-crawl XRPC + Claude diff | `competitor_summary` |
| `generate_strategy` | Claude claude-sonnet-4-6 | `strategy_json` |
| `generate_copy` | Claude claude-sonnet-4-6 | `copy_markdown` |
| `quality_gate` | score ≥ 0.7 → proceed, else retry once | `quality_score` |
| `store_proposal` | RisingWave INSERT vertex_webmk_proposal | `proposalId` |

## XRPC Endpoints

### Procedure (2)
`com.etzhayyim.apps.webmk.{createProposal, deliverProposal}`

### Query (2)
`com.etzhayyim.apps.webmk.{getProposal, listProposals}`

## RisingWave Tables

| Table | Purpose |
|---|---|
| `vertex_webmk_proposal` | Proposal records (status, strategyJson, copyMarkdown, qualityScore) |
| `vertex_webmk_client` | Client records (clientName, websiteUrl, industry) |
| `edge_webmk_campaign_link` | Proposal → ads.etzhayyim.com campaignId linkage |

## Python Worker

`40-engine/kotoba/crates/kotoba-kotodama/py/src/kotodama/webmk_worker_main.py`

Job types handled:
- `webmk.run_proposal_agent` — LangGraph loop (main agent)
- `webmk.deliver_via_resend` — Resend email delivery
- `webmk.create_ad_campaign` — XRPC to ads.etzhayyim.com

## Env Requirements

```
AGENTGATEWAY_MCP_URL   — LangServer gRPC (default 127.0.0.1:26500)
RW_URL          — RisingWave postgres URL
ANTHROPIC_API_KEY
RESEND_API_KEY
RESEND_FROM     — e.g. "webmk@etzhayyim.com"
ADS_XRPC_URL    — ads.etzhayyim.com base URL (default https://adsm4d5c.etzhayyim.com)
```

## Deploy

```bash
cd 60-apps/etzhayyim-project-webmk/appview/webmk-wbmk0001
etzhayyim deploy

# Start Python worker
python -m kotodama.webmk_worker_main

# Smoke test
curl https://wbmk0001.etzhayyim.com/health
curl -X POST https://wbmk0001.etzhayyim.com/xrpc/com.etzhayyim.apps.webmk.createProposal \
  -H "Content-Type: application/json" \
  -d '{"clientName":"ACME Corp","websiteUrl":"https://acme.example.com","industry":"retail","deliveryEmail":"test@example.com"}'
```

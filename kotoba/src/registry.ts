/**
 * webmk kotoba — registry.
 *
 * Plaintext path (campaignLink): sdk.write / sdk.read — public proposal→campaign
 * operational linkage.
 * E2E path (clientRecord, proposalRecord): sdk.encryptedWrite / sdk.encryptedRead
 * — PII (deliveryEmail) + commercial terms (budgetJpy) + the confidential
 * deliverable (strategyJson/copyMarkdown) sealed in the kotoba envelope
 * (ADR-2605181100), read-cap = owner DID + explicit recipients.
 *
 * STAYS etzhayyim (consent-capability, not here): Claude strategy/copy INFERENCE and
 * the Resend email-delivery EXECUTION (credential custody + send action).
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  CAMPAIGN_LINK_COLLECTION,
  CLIENT_INNER_TYPE,
  PROPOSAL_INNER_TYPE,
  campaignLinkDidFor,
  campaignLinkRkey,
  clientRkey,
  proposalRkey,
  isPct,
  isStatus,
  isUint,
  type CampaignLinkRecord,
  type CampaignLinkView,
  type ClientBody,
  type ClientView,
  type CoverageInput,
  type CoverageOutput,
  type GetClientInput,
  type GetClientOutput,
  type GetProposalInput,
  type GetProposalOutput,
  type ListCampaignLinksInput,
  type ListCampaignLinksOutput,
  type ListClientsInput,
  type ListClientsOutput,
  type ListProposalsInput,
  type ListProposalsOutput,
  type ProposalBody,
  type ProposalView,
  type RecordCampaignLinkInput,
  type RecordCampaignLinkOutput,
  type RecordProposalInput,
  type RecordProposalOutput,
  type RegisterClientInput,
  type RegisterClientOutput,
} from "./types.js";

const PAGE_LIMIT = 100;
const DEFAULT_MAX_SCAN = 10_000;

// ─── Campaign link (PLAINTEXT) ──────────────────────────────────────

export async function recordCampaignLink(e: Etzhayyim, input: RecordCampaignLinkInput): Promise<RecordCampaignLinkOutput> {
  if (!input.proposalId || !input.adsCampaignId || !input.adsCampaignDid) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  const rkey = campaignLinkRkey(input.proposalId);
  const existing = await e.read<CampaignLinkRecord>({ collection: CAMPAIGN_LINK_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", linkUri: existing.records[0].uri, did: existing.records[0].value.did, proposalId: input.proposalId };
  }
  const now = new Date().toISOString();
  const did = campaignLinkDidFor(input.proposalId);
  const record: CampaignLinkRecord = {
    did,
    proposalId: input.proposalId,
    adsCampaignId: input.adsCampaignId,
    adsCampaignDid: input.adsCampaignDid,
    linkedAt: input.linkedAt ?? now,
    createdAt: now,
  };
  const receipt = await e.write({ collection: CAMPAIGN_LINK_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "recorded", linkUri: receipt.uri, did, proposalId: input.proposalId };
}

export async function listCampaignLinks(e: Etzhayyim, input: ListCampaignLinksInput = {}): Promise<ListCampaignLinksOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<CampaignLinkRecord>({ collection: CAMPAIGN_LINK_COLLECTION, cursor: input.cursor, limit });
  const items: CampaignLinkView[] = resp.records
    .filter((r) => !input.proposalId || r.value.proposalId === input.proposalId)
    .map((r) => ({ ...r.value, linkUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Client (E2E-ENCRYPTED, PII) ────────────────────────────────────

export async function registerClient(e: Etzhayyim, input: RegisterClientInput): Promise<RegisterClientOutput> {
  if (!input.clientId || !input.clientName || !input.websiteUrl || !input.industry || !input.deliveryEmail) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  const body: ClientBody = {
    clientId: input.clientId,
    clientName: input.clientName,
    websiteUrl: input.websiteUrl,
    industry: input.industry,
    deliveryEmail: input.deliveryEmail,
    registeredAt: input.registeredAt ?? new Date().toISOString(),
  };
  const receipt = await e.encryptedWrite<Record<string, unknown>>({
    innerType: CLIENT_INNER_TYPE,
    record: body as unknown as Record<string, unknown>,
    recipients: input.recipients ?? [],
    rkey: clientRkey(input.clientId),
  });
  return { status: "registered", uri: receipt.uri, keyId: receipt.keyId, clientId: input.clientId };
}

async function scanClients(e: Etzhayyim, maxScan: number): Promise<ClientView[]> {
  const out: ClientView[] = [];
  let cursor: string | undefined;
  while (out.length < maxScan) {
    const page = await e.encryptedRead<ClientBody>({ innerType: CLIENT_INNER_TYPE, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      out.push({ ...r.value, uri: r.uri, sender: r.sender, createdAt: r.createdAt });
    }
    if (!page.cursor || page.records.length === 0) break;
    cursor = page.cursor;
  }
  return out;
}

export async function listClients(e: Etzhayyim, input: ListClientsInput = {}): Promise<ListClientsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const all = await scanClients(e, DEFAULT_MAX_SCAN);
  const filtered = all.filter((c) => !input.industry || c.industry === input.industry);
  return { items: filtered.slice(0, limit), total: filtered.length };
}

export async function getClient(e: Etzhayyim, input: GetClientInput): Promise<GetClientOutput> {
  if (!input.clientId) return { error: "invalidClientId" };
  const all = await scanClients(e, DEFAULT_MAX_SCAN);
  const found = all.find((c) => c.clientId === input.clientId);
  if (!found) return { error: "notFound" };
  return { client: found };
}

// ─── Proposal (E2E-ENCRYPTED, commercial terms + deliverable) ───────

export async function recordProposal(e: Etzhayyim, input: RecordProposalInput): Promise<RecordProposalOutput> {
  if (!input.proposalId || !input.clientId) return { status: "rejected", error: "missingRequiredFields" };
  if (!isUint(input.budgetJpy)) return { status: "rejected", error: "invalidBudgetJpy" };
  if (!isStatus(input.status)) return { status: "rejected", error: "invalidStatus" };
  if (!isPct(input.qualityScore)) return { status: "rejected", error: "invalidQualityScore" };
  const body: ProposalBody = {
    proposalId: input.proposalId,
    clientId: input.clientId,
    budgetJpy: input.budgetJpy,
    status: input.status,
    strategyJson: input.strategyJson ?? "",
    copyMarkdown: input.copyMarkdown ?? "",
    qualityScore: input.qualityScore,
    deliveredAt: input.deliveredAt,
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.encryptedWrite<Record<string, unknown>>({
    innerType: PROPOSAL_INNER_TYPE,
    record: body as unknown as Record<string, unknown>,
    recipients: input.recipients ?? [],
    rkey: proposalRkey(input.proposalId),
  });
  return { status: "recorded", uri: receipt.uri, keyId: receipt.keyId, proposalId: input.proposalId };
}

async function scanProposals(e: Etzhayyim, maxScan: number): Promise<ProposalView[]> {
  const out: ProposalView[] = [];
  let cursor: string | undefined;
  while (out.length < maxScan) {
    const page = await e.encryptedRead<ProposalBody>({ innerType: PROPOSAL_INNER_TYPE, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      out.push({ ...r.value, uri: r.uri, sender: r.sender, envCreatedAt: r.createdAt });
    }
    if (!page.cursor || page.records.length === 0) break;
    cursor = page.cursor;
  }
  return out;
}

export async function listProposals(e: Etzhayyim, input: ListProposalsInput = {}): Promise<ListProposalsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const all = await scanProposals(e, DEFAULT_MAX_SCAN);
  const filtered = all.filter(
    (p) => (!input.status || p.status === input.status) && (!input.clientId || p.clientId === input.clientId),
  );
  return { items: filtered.slice(0, limit), total: filtered.length };
}

export async function getProposal(e: Etzhayyim, input: GetProposalInput): Promise<GetProposalOutput> {
  if (!input.proposalId) return { error: "invalidProposalId" };
  const all = await scanProposals(e, DEFAULT_MAX_SCAN);
  const found = all.find((p) => p.proposalId === input.proposalId);
  if (!found) return { error: "notFound" };
  return { proposal: found };
}

// ─── Coverage rollup ────────────────────────────────────────────────

export async function coverage(e: Etzhayyim, input: CoverageInput = {}): Promise<CoverageOutput> {
  const maxScan = Math.min(input.maxScan ?? DEFAULT_MAX_SCAN, DEFAULT_MAX_SCAN);
  let campaignLinkCount = 0;
  let cursor: string | undefined;
  while (campaignLinkCount < maxScan) {
    const page = await e.read<CampaignLinkRecord>({ collection: CAMPAIGN_LINK_COLLECTION, cursor, limit: PAGE_LIMIT });
    campaignLinkCount += page.records.length;
    if (!page.cursor || page.records.length < PAGE_LIMIT) break;
    cursor = page.cursor;
  }
  const clientCount = (await scanClients(e, maxScan)).length;
  const proposals = await scanProposals(e, maxScan);
  const proposalsByStatus: Record<string, number> = {};
  for (const p of proposals) {
    proposalsByStatus[p.status] = (proposalsByStatus[p.status] ?? 0) + 1;
  }
  return {
    campaignLinkCount,
    clientCount,
    proposalCount: proposals.length,
    proposalsByStatus,
    truncated: campaignLinkCount >= maxScan || clientCount >= maxScan || proposals.length >= maxScan,
  };
}

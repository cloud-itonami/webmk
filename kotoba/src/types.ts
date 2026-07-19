/**
 * webmk kotoba — Web Marketing Proposal Agent, kotoba-E2E split.
 *
 * Per ADR-2606011400 (Consensys) + ADR-2605172400 (3-axis) + ADR-2605181100
 * (kotoba E2E encrypted-record envelope). Founder directive 2026-06-03: MAXIMAL
 * migration — front everything that can move; only irreducible regulated
 * EXECUTION stays etzhayyim.
 *
 * SPLIT (from the actual webmk data model: vertex_webmk_client /
 * vertex_webmk_proposal / edge_webmk_campaign_link):
 *   PLAINTEXT (public operational metadata) — campaignLink: the opaque
 *   proposalId → ads campaignId linkage. No PII, no commercial terms; a
 *   read-view of which proposals spawned which ad campaigns. sdk.write/read.
 *
 *   E2E (kotoba, com.etzhayyim.encrypted.record, read-cap = owner DID +
 *   explicit recipients):
 *     - clientRecord: client identity + deliveryEmail (per-person PII) + the
 *       sales pipeline. Confidential prospect data.
 *     - proposalRecord: budgetJpy (commercial terms) + strategyJson +
 *       copyMarkdown (the confidential deliverable) + qualityScore + status.
 *   Both sealed via sdk.encryptedWrite; substrate never sees them plaintext.
 *
 *   STAYS etzhayyim (consumed via consent-capability, NOT a collection) — the LLM
 *   INFERENCE that generates strategy/copy (Claude) and the Resend email
 *   delivery EXECUTION (credential custody + the actual send action). Those are
 *   regulated/secret-bearing acts; their RESULTS (the proposal text, the
 *   delivery timestamp) migrate here as E2E records.
 *
 * AT-Lexicon: no float. budgetJpy = integer yen; qualityScore = integer 0-100
 * (percent; RW DOUBLE_PRECISION 0..1 rescaled). Money never as float.
 */

// Plaintext public collection.
export const CAMPAIGN_LINK_COLLECTION = "com.etzhayyim.apps.webmk.campaignLink";
// E2E inner-type NSIDs (body shape inside the encrypted envelope).
export const CLIENT_INNER_TYPE = "com.etzhayyim.apps.webmk.clientRecord";
export const PROPOSAL_INNER_TYPE = "com.etzhayyim.apps.webmk.proposalRecord";

export const WEBMK_DID_PREFIX = "did:web:webmk.etzhayyim.com:" as const;

export type ProposalStatus = "queued" | "running" | "delivered" | "failed";

// ─── Campaign link (PLAINTEXT, public operational metadata) ──────────

export interface CampaignLinkRecord {
  did: string;
  proposalId: string;
  adsCampaignId: string;
  adsCampaignDid: string;
  linkedAt: string;
  createdAt: string;
}
export interface CampaignLinkView extends CampaignLinkRecord {
  linkUri: string;
}
export interface RecordCampaignLinkInput {
  proposalId: string;
  adsCampaignId: string;
  adsCampaignDid: string;
  linkedAt?: string;
}
export interface RecordCampaignLinkOutput {
  status: "recorded" | "alreadyExists" | "rejected";
  linkUri?: string;
  did?: string;
  proposalId?: string;
  error?: string;
}
export interface ListCampaignLinksInput {
  proposalId?: string;
  limit?: number;
  cursor?: string;
}
export interface ListCampaignLinksOutput {
  items: CampaignLinkView[];
  cursor?: string;
  total: number;
}

// ─── Client (E2E-ENCRYPTED, PII + sales pipeline) ───────────────────

export interface ClientBody {
  clientId: string;
  clientName: string;
  websiteUrl: string;
  industry: string;
  /** Per-person delivery contact — PII. */
  deliveryEmail: string;
  registeredAt: string;
}
export interface ClientView extends ClientBody {
  uri: string;
  sender: string;
  createdAt: string;
}
export interface RegisterClientInput {
  clientId: string;
  clientName: string;
  websiteUrl: string;
  industry: string;
  deliveryEmail: string;
  registeredAt?: string;
  /** Extra DIDs to grant read-cap (owner always included). */
  recipients?: string[];
}
export interface RegisterClientOutput {
  status: "registered" | "rejected";
  uri?: string;
  keyId?: string;
  clientId?: string;
  error?: string;
}
export interface ListClientsInput {
  industry?: string;
  limit?: number;
  cursor?: string;
}
export interface ListClientsOutput {
  items: ClientView[];
  cursor?: string;
  total: number;
}
export interface GetClientInput {
  clientId: string;
}
export interface GetClientOutput {
  client?: ClientView;
  error?: string;
}

// ─── Proposal (E2E-ENCRYPTED, commercial terms + deliverable) ───────

export interface ProposalBody {
  proposalId: string;
  clientId: string;
  /** Integer yen. */
  budgetJpy: number;
  status: ProposalStatus;
  strategyJson: string;
  copyMarkdown: string;
  /** integer 0-100 (percent). */
  qualityScore: number;
  deliveredAt?: string;
  createdAt: string;
}
export interface ProposalView extends ProposalBody {
  uri: string;
  sender: string;
  envCreatedAt: string;
}
export interface RecordProposalInput {
  proposalId: string;
  clientId: string;
  budgetJpy: number;
  status: ProposalStatus;
  strategyJson?: string;
  copyMarkdown?: string;
  qualityScore: number;
  deliveredAt?: string;
  recipients?: string[];
}
export interface RecordProposalOutput {
  status: "recorded" | "rejected";
  uri?: string;
  keyId?: string;
  proposalId?: string;
  error?: string;
}
export interface ListProposalsInput {
  status?: ProposalStatus;
  clientId?: string;
  limit?: number;
  cursor?: string;
}
export interface ListProposalsOutput {
  items: ProposalView[];
  cursor?: string;
  total: number;
}
export interface GetProposalInput {
  proposalId: string;
}
export interface GetProposalOutput {
  proposal?: ProposalView;
  error?: string;
}

// ─── Coverage rollup ────────────────────────────────────────────────

export interface CoverageInput {
  maxScan?: number;
}
export interface CoverageOutput {
  campaignLinkCount?: number;
  clientCount?: number;
  proposalCount?: number;
  proposalsByStatus?: Record<string, number>;
  truncated?: boolean;
  error?: string;
}

// ─── Validation + helpers ───────────────────────────────────────────

const STATUSES: ReadonlySet<string> = new Set(["queued", "running", "delivered", "failed"]);

export function isUint(n: unknown): n is number {
  return typeof n === "number" && Number.isInteger(n) && n >= 0;
}
export function isPct(n: unknown): n is number {
  return typeof n === "number" && Number.isInteger(n) && n >= 0 && n <= 100;
}
export function isStatus(s: unknown): s is ProposalStatus {
  return typeof s === "string" && STATUSES.has(s);
}
export function campaignLinkDidFor(id: string): string {
  return `${WEBMK_DID_PREFIX}link:${id.toLowerCase()}`;
}
function slug(id: string): string {
  return id.toLowerCase().replace(/[^a-z0-9]+/g, "-");
}
export function campaignLinkRkey(id: string): string {
  return `link-${slug(id)}`;
}
export function clientRkey(id: string): string {
  return `client-${slug(id)}`;
}
export function proposalRkey(id: string): string {
  return `proposal-${slug(id)}`;
}

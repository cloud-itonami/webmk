/**
 * webmk kotoba — barrel. kotoba-E2E split (plaintext campaignLink +
 * E2E clientRecord/proposalRecord, ADR-2605181100). Claude strategy/copy
 * inference + Resend delivery execution stay etzhayyim via consent-capability.
 */
export * from "./types.js";
export {
  recordCampaignLink,
  listCampaignLinks,
  registerClient,
  listClients,
  getClient,
  recordProposal,
  listProposals,
  getProposal,
  coverage,
} from "./registry.js";

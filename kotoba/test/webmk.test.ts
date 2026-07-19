import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
  recordCampaignLink,
  listCampaignLinks,
  registerClient,
  listClients,
  getClient,
  recordProposal,
  listProposals,
  getProposal,
  coverage,
} from "../src/index.js";

const OWNER = "did:web:webmk.etzhayyim.com";

describe("webmk kotoba (kotoba-E2E split)", () => {
  let e: any;
  beforeEach(() => {
    e = new MockEtzhayyim({ did: OWNER });
  });

  describe("campaignLink (PLAINTEXT public operational metadata)", () => {
    it("records, dedups, validates, lists/filters", async () => {
      expect((await recordCampaignLink(e, { proposalId: "p1", adsCampaignId: "cmp1", adsCampaignDid: "did:web:ads.etzhayyim.com:cmp1" })).status).toBe("recorded");
      expect((await recordCampaignLink(e, { proposalId: "p1", adsCampaignId: "cmp1", adsCampaignDid: "did:web:ads.etzhayyim.com:cmp1" })).status).toBe("alreadyExists");
      expect((await recordCampaignLink(e, { proposalId: "", adsCampaignId: "cmpX", adsCampaignDid: "d" })).status).toBe("rejected");
      await recordCampaignLink(e, { proposalId: "p2", adsCampaignId: "cmp2", adsCampaignDid: "did:web:ads.etzhayyim.com:cmp2" });
      expect((await listCampaignLinks(e)).total).toBe(2);
      expect((await listCampaignLinks(e, { proposalId: "p1" })).total).toBe(1);
    });
  });

  describe("clientRecord (E2E-ENCRYPTED PII + sales pipeline)", () => {
    it("seals via encryptedWrite, round-trips via encryptedRead, validates", async () => {
      const ok = await registerClient(e, { clientId: "c1", clientName: "ACME Corp", websiteUrl: "https://acme.example", industry: "retail", deliveryEmail: "buyer@acme.example" });
      expect(ok.status).toBe("registered");
      expect(ok.keyId).toBeTruthy();
      expect((await registerClient(e, { clientId: "cX", clientName: "", websiteUrl: "u", industry: "i", deliveryEmail: "e" })).status).toBe("rejected");
      const got = await getClient(e, { clientId: "c1" });
      expect(got.client?.deliveryEmail).toBe("buyer@acme.example");
      expect(got.client?.clientName).toBe("ACME Corp");
      await registerClient(e, { clientId: "c2", clientName: "Globex", websiteUrl: "https://globex.example", industry: "energy", deliveryEmail: "ops@globex.example" });
      expect((await listClients(e)).total).toBe(2);
      expect((await listClients(e, { industry: "retail" })).total).toBe(1);
    });

    it("enforces read-cap: a non-recipient DID cannot decrypt clients", async () => {
      await registerClient(e, { clientId: "c1", clientName: "ACME", websiteUrl: "u", industry: "retail", deliveryEmail: "buyer@acme.example" });
      const outsider: any = new MockEtzhayyim({ did: "did:web:outsider.example" });
      expect((await listClients(outsider)).total).toBe(0);
      expect((await getClient(outsider, { clientId: "c1" })).error).toBe("notFound");
    });

    it("grants read-cap to an explicit recipient", async () => {
      const partner = "did:web:partner.example";
      const r = await registerClient(e, { clientId: "c1", clientName: "ACME", websiteUrl: "u", industry: "retail", deliveryEmail: "buyer@acme.example", recipients: [partner] });
      expect(r.status).toBe("registered");
      expect((await listClients(e)).total).toBe(1);
    });
  });

  describe("proposalRecord (E2E-ENCRYPTED commercial terms + deliverable)", () => {
    it("seals, round-trips, validates budget/status/qualityScore", async () => {
      const ok = await recordProposal(e, { proposalId: "p1", clientId: "c1", budgetJpy: 500000, status: "delivered", strategyJson: "{\"plan\":1}", copyMarkdown: "# Ad", qualityScore: 82, deliveredAt: "2026-06-03T00:00:00Z" });
      expect(ok.status).toBe("recorded");
      expect(ok.keyId).toBeTruthy();
      expect((await recordProposal(e, { proposalId: "pX", clientId: "c1", budgetJpy: -1, status: "queued", qualityScore: 50 })).status).toBe("rejected"); // negative budget
      expect((await recordProposal(e, { proposalId: "pY", clientId: "c1", budgetJpy: 100, status: "bogus" as any, qualityScore: 50 })).status).toBe("rejected"); // bad status
      expect((await recordProposal(e, { proposalId: "pZ", clientId: "c1", budgetJpy: 100, status: "queued", qualityScore: 200 })).status).toBe("rejected"); // qualityScore>100
      const got = await getProposal(e, { proposalId: "p1" });
      expect(got.proposal?.budgetJpy).toBe(500000);
      expect(got.proposal?.qualityScore).toBe(82);
      expect(got.proposal?.copyMarkdown).toBe("# Ad");
      await recordProposal(e, { proposalId: "p2", clientId: "c2", budgetJpy: 200000, status: "queued", qualityScore: 40 });
      expect((await listProposals(e)).total).toBe(2);
      expect((await listProposals(e, { status: "delivered" })).total).toBe(1);
      expect((await listProposals(e, { clientId: "c2" })).total).toBe(1);
    });

    it("enforces read-cap: outsider cannot decrypt proposals", async () => {
      await recordProposal(e, { proposalId: "p1", clientId: "c1", budgetJpy: 500000, status: "delivered", qualityScore: 82 });
      const outsider: any = new MockEtzhayyim({ did: "did:web:outsider.example" });
      expect((await listProposals(outsider)).total).toBe(0);
    });
  });

  describe("coverage rollup", () => {
    it("counts plaintext links + E2E clients + E2E proposals by status", async () => {
      await recordCampaignLink(e, { proposalId: "p1", adsCampaignId: "cmp1", adsCampaignDid: "did:web:ads.etzhayyim.com:cmp1" });
      await registerClient(e, { clientId: "c1", clientName: "ACME", websiteUrl: "u", industry: "retail", deliveryEmail: "buyer@acme.example" });
      await recordProposal(e, { proposalId: "p1", clientId: "c1", budgetJpy: 500000, status: "delivered", qualityScore: 82 });
      await recordProposal(e, { proposalId: "p2", clientId: "c1", budgetJpy: 100000, status: "delivered", qualityScore: 70 });
      await recordProposal(e, { proposalId: "p3", clientId: "c1", budgetJpy: 100000, status: "queued", qualityScore: 60 });
      const cov = await coverage(e);
      expect(cov.campaignLinkCount).toBe(1);
      expect(cov.clientCount).toBe(1);
      expect(cov.proposalCount).toBe(3);
      expect(cov.proposalsByStatus?.delivered).toBe(2);
      expect(cov.proposalsByStatus?.queued).toBe(1);
    });
  });
});

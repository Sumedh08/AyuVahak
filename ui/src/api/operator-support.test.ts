import { describe, expect, it, vi } from "vitest";
import { createOperatorSupportApiClient } from "./operator-support";

const response = {
  status: "ADVISORY",
  answer: "Use the documented freshness threshold and confirm owner state.",
  sources: [
    {
      sourceId: "capacity-stale-guide",
      path: "docs/operations/capacity.md",
      section: "Staleness",
      contentHash: "a".repeat(64),
    },
  ],
  capabilityVersion: "operator-support-v1",
  corpusVersion: "operator-support-corpus-v1",
  reasonCode: "NONE",
};

describe("operator support API client", () => {
  it("sends only the selected finite question key to the same-origin endpoint", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify(response), { status: 200 }));
    const client = createOperatorSupportApiClient(async () => "synthetic-token", fetcher);

    await expect(client.answer("CAPACITY_STALENESS")).resolves.toEqual(response);

    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/operator-support/answers",
      expect.objectContaining({
        method: "POST",
        credentials: "omit",
        cache: "no-store",
        body: JSON.stringify({ questionKey: "CAPACITY_STALENESS" }),
      }),
    );
  });

  it("rejects malformed or unsafe citation paths", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(
        JSON.stringify({
          ...response,
          sources: [{ ...response.sources[0], path: "../../.env" }],
        }),
        { status: 200 },
      ),
    );
    const client = createOperatorSupportApiClient(async () => "synthetic-token", fetcher);

    await expect(client.answer("CAPACITY_STALENESS")).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
    });
  });

  it("enforces abstention shape and preserves disabled semantics", async () => {
    const abstained = {
      status: "ABSTAINED",
      answer: "",
      sources: [],
      capabilityVersion: "operator-support-v1",
      corpusVersion: "operator-support-corpus-v1",
      reasonCode: "DISABLED",
    };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify(abstained), { status: 200 }));
    const client = createOperatorSupportApiClient(async () => "synthetic-token", fetcher);

    await expect(client.answer("DATABASE_RESTORE")).resolves.toEqual(abstained);
  });
});

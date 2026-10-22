import { describe, expect, it, vi } from "vitest";
import {
  createOperatorSupportApiClient,
  OPERATOR_SUPPORT_TOPICS,
  parseOperatorSupportQuestionKey,
} from "./operator-support";

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
  it("parses only the configured finite question topics", () => {
    for (const topic of OPERATOR_SUPPORT_TOPICS) {
      expect(parseOperatorSupportQuestionKey(topic.key)).toBe(topic.key);
    }

    expect(parseOperatorSupportQuestionKey("")).toBeNull();
    expect(parseOperatorSupportQuestionKey("CAPACITY_STALENESS ")).toBeNull();
    expect(parseOperatorSupportQuestionKey("UNSUPPORTED_TOPIC")).toBeNull();
  });

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

  it("reads only safe provider status and never requests secret readback", async () => {
    const profile = {
      enabled: true,
      provider: "OPENAI_COMPATIBLE",
      configurationValid: true,
      secretReferenceConfigured: true,
      evaluationRequired: true,
      evaluationCurrent: true,
      requiredEvaluationFingerprint: `sha256:${"a".repeat(64)}`,
    } as const;
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify(profile), { status: 200 }));
    const client = createOperatorSupportApiClient(async () => "synthetic-token", fetcher);

    await expect(client.profileStatus()).resolves.toEqual(profile);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/operator-support/profile",
      expect.objectContaining({
        credentials: "omit",
        cache: "no-store",
        headers: expect.objectContaining({ Authorization: "Bearer synthetic-token" }),
      }),
    );
    expect(profile).not.toHaveProperty("endpoint");
    expect(profile).not.toHaveProperty("apiKey");
    expect(profile).not.toHaveProperty("secretReference");
  });

  it("rejects a profile response containing secret or endpoint fields", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(
        JSON.stringify({
          enabled: true,
          provider: "OPENAI_COMPATIBLE",
          configurationValid: true,
          secretReferenceConfigured: true,
          evaluationRequired: true,
          evaluationCurrent: true,
          requiredEvaluationFingerprint: `sha256:${"a".repeat(64)}`,
          endpoint: "https://provider.example/v1/chat/completions",
          apiKey: "SYN-SECRET-MUST-NOT-RETURN",
        }),
        { status: 200 },
      ),
    );
    const client = createOperatorSupportApiClient(async () => "synthetic-token", fetcher);

    await expect(client.profileStatus()).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
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

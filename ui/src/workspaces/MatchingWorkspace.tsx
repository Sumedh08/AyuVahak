import { useEffect, useMemo, useRef, useState } from "react";
import { type OwnerScope, originScopesForAuthority } from "../api/owner";
import {
  createReferralMatchingApiClient,
  type RecommendationHistory,
  type RecommendationSnapshot,
  type ReferralDetail,
  type ReferralMatchingApiClient,
  ReferralMatchingApiError,
  type ReferralPage,
} from "../api/referral-matching";
import type { SessionProjection } from "../api/session";

interface MatchingWorkspaceProps {
  readonly session: SessionProjection;
  readonly getAccessToken: () => Promise<string | null>;
  readonly apiClient?: ReferralMatchingApiClient;
}

type LoadState =
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | { readonly status: "loaded"; readonly page: ReferralPage };

type DetailState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | { readonly status: "loaded"; readonly detail: ReferralDetail };

type HistoryState =
  | { readonly status: "unavailable" }
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | { readonly status: "loaded"; readonly history: RecommendationHistory };

function errorCode(error: unknown): string {
  return error instanceof ReferralMatchingApiError ? error.code : "UNAVAILABLE";
}

function scopeLabel(scope: Extract<OwnerScope, { kind: "ORGANIZATION" | "FACILITY" }>): string {
  return scope.kind === "ORGANIZATION"
    ? `Organization ${scope.organizationId}`
    : `Facility ${scope.facilityId}`;
}

function covers(scope: OwnerScope, detail: ReferralDetail): boolean {
  return (
    scope.kind !== "PLATFORM" &&
    scope.organizationId === detail.origin.organizationId &&
    (scope.kind !== "FACILITY" || scope.facilityId === detail.origin.facilityId)
  );
}

function authorizedScopeFor(
  scopes: readonly OwnerScope[],
  detail: ReferralDetail,
): OwnerScope | undefined {
  return scopes.find((scope) => covers(scope, detail));
}

export function MatchingWorkspace({ session, getAccessToken, apiClient }: MatchingWorkspaceProps) {
  const referralScopes = useMemo(
    () => originScopesForAuthority(session, "ReferralRead"),
    [session],
  );
  const requestScopes = useMemo(
    () => originScopesForAuthority(session, "RecommendationRequest"),
    [session],
  );
  const recommendationReadScopes = useMemo(
    () => originScopesForAuthority(session, "RecommendationRead"),
    [session],
  );
  const api = useMemo(
    () => apiClient ?? createReferralMatchingApiClient(getAccessToken),
    [apiClient, getAccessToken],
  );
  const [scopeKey, setScopeKey] = useState(referralScopes[0]?.key ?? "");
  const [list, setList] = useState<LoadState>({ status: "loading" });
  const [refresh, setRefresh] = useState(0);
  const [selectedId, setSelectedId] = useState("");
  const [detail, setDetail] = useState<DetailState>({ status: "idle" });
  const [history, setHistory] = useState<HistoryState>({ status: "unavailable" });
  const [generation, setGeneration] = useState<"idle" | "loading" | "error">("idle");
  const [generated, setGenerated] = useState<RecommendationSnapshot | null>(null);
  const [generationError, setGenerationError] = useState("");
  const selectionVersion = useRef(0);
  const selectionController = useRef<AbortController | null>(null);
  const selectedScope = referralScopes.find((scope) => scope.key === scopeKey);

  useEffect(() => {
    // This counter is an explicit revalidation trigger for the owner read below.
    void refresh;
    if (!selectedScope) {
      setList({ status: "loaded", page: { items: [], page: 0, size: 25, hasMore: false } });
      return;
    }
    const controller = new AbortController();
    let active = true;
    setList({ status: "loading" });
    void api
      .listReferrals(selectedScope, 0, "SUBMITTED", controller.signal)
      .then((page) => {
        if (active) setList({ status: "loaded", page });
      })
      .catch((error: unknown) => {
        if (active && !controller.signal.aborted)
          setList({ status: "error", code: errorCode(error) });
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [api, refresh, selectedScope]);

  async function selectReferral(id: string): Promise<void> {
    if (!selectedScope) return;
    selectionVersion.current += 1;
    const requestVersion = selectionVersion.current;
    selectionController.current?.abort();
    const controller = new AbortController();
    selectionController.current = controller;
    setSelectedId(id);
    setDetail({ status: "loading" });
    setHistory({ status: "unavailable" });
    setGenerated(null);
    setGeneration("idle");
    setGenerationError("");
    try {
      const referral = await api.getReferral(selectedScope, id, controller.signal);
      if (controller.signal.aborted || requestVersion !== selectionVersion.current) return;
      setDetail({ status: "loaded", detail: referral });
      const historyScope = authorizedScopeFor(recommendationReadScopes, referral);
      if (historyScope) {
        setHistory({ status: "loading" });
        try {
          const result = await api.getRecommendationHistory(historyScope, id, controller.signal);
          if (controller.signal.aborted || requestVersion !== selectionVersion.current) return;
          setHistory({ status: "loaded", history: result });
        } catch (error: unknown) {
          if (!controller.signal.aborted && requestVersion === selectionVersion.current) {
            setHistory({ status: "error", code: errorCode(error) });
          }
        }
      }
    } catch (error: unknown) {
      if (!controller.signal.aborted && requestVersion === selectionVersion.current) {
        setDetail({ status: "error", code: errorCode(error) });
      }
    }
  }

  async function requestRecommendation(): Promise<void> {
    if (detail.status !== "loaded" || detail.detail.submittedRevisionNumber === null) return;
    const requestScope = authorizedScopeFor(requestScopes, detail.detail);
    if (!requestScope) return;
    const requestVersion = selectionVersion.current;
    setGeneration("loading");
    setGenerated(null);
    setGenerationError("");
    try {
      const result = await api.generateRecommendation(
        requestScope,
        detail.detail.id,
        detail.detail.submittedRevisionNumber,
      );
      if (requestVersion !== selectionVersion.current) return;
      setGenerated(result);
      setGeneration("idle");
      const historyScope = authorizedScopeFor(recommendationReadScopes, detail.detail);
      if (historyScope) {
        setHistory({ status: "loading" });
        try {
          const currentHistory = await api.getRecommendationHistory(
            historyScope,
            result.referralId,
          );
          if (requestVersion === selectionVersion.current) {
            setHistory({ status: "loaded", history: currentHistory });
          }
        } catch (error: unknown) {
          if (requestVersion === selectionVersion.current) {
            setHistory({ status: "error", code: errorCode(error) });
          }
        }
      }
    } catch (error: unknown) {
      if (requestVersion !== selectionVersion.current) return;
      setGeneration("error");
      setGenerationError(errorCode(error));
    }
  }

  return (
    <section className="workspace-panel" aria-labelledby="matching-title">
      <div className="workspace-intro">
        <p className="eyebrow">DETERMINISTIC MATCHING</p>
        <h2 id="matching-title">Matching recommendations</h2>
        <p>
          Matching evaluates submitted Referral revisions using the authoritative deterministic
          policy. A recommendation is not a clinical decision, acceptance, or reservation.
        </p>
      </div>
      {referralScopes.length === 0 ? (
        <div className="workspace-state" role="status">
          <h3>Referral read scope is not available</h3>
          <p>Matching worklists require an authorized ReferralRead scope. No cases are assumed.</p>
        </div>
      ) : (
        <>
          {referralScopes.length > 1 && (
            <label className="field-label" htmlFor="matching-scope">
              Authorized origin scope
              <select
                id="matching-scope"
                value={scopeKey}
                onChange={(event) => {
                  selectionVersion.current += 1;
                  selectionController.current?.abort();
                  setScopeKey(event.currentTarget.value);
                  setSelectedId("");
                  setDetail({ status: "idle" });
                  setHistory({ status: "unavailable" });
                  setGenerated(null);
                }}
              >
                {referralScopes.map((scope) => (
                  <option key={scope.key} value={scope.key}>
                    {scopeLabel(scope)}
                  </option>
                ))}
              </select>
            </label>
          )}
          <section className="workspace-subsection" aria-labelledby="matching-referrals-title">
            <h3 id="matching-referrals-title">Submitted Referrals</h3>
            {list.status === "loading" ? (
              <div className="workspace-state" role="status" aria-busy="true">
                Loading submitted Referrals…
              </div>
            ) : list.status === "error" ? (
              <div className="workspace-state workspace-error" role="alert">
                <h4>
                  {list.code === "FORBIDDEN"
                    ? "Referral worklist access was denied"
                    : list.code === "UNAUTHENTICATED"
                      ? "Sign in again to continue"
                      : "Submitted Referrals are unavailable"}
                </h4>
                <p>No worklist items are assumed.</p>
                <button
                  className="button button-quiet"
                  type="button"
                  onClick={() => setRefresh((value) => value + 1)}
                >
                  Retry
                </button>
              </div>
            ) : list.page.items.length === 0 ? (
              <div className="workspace-state" role="status">
                <h4>No submitted Referrals were returned</h4>
                <p>The owner returned an empty page for this scope.</p>
              </div>
            ) : (
              <div className="table-scroll">
                <table>
                  <caption>Submitted Referrals available in the authorized origin scope</caption>
                  <thead>
                    <tr>
                      <th scope="col">Referral</th>
                      <th scope="col">Origin facility</th>
                      <th scope="col">Version</th>
                      <th scope="col">Submitted</th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.page.items.map((referral) => (
                      <tr key={referral.id}>
                        <th scope="row">
                          <button
                            className="text-button"
                            type="button"
                            aria-pressed={selectedId === referral.id}
                            onClick={() => void selectReferral(referral.id)}
                          >
                            Review submitted revision
                          </button>
                          <small className="facility-id-label">{referral.id}</small>
                        </th>
                        <td>{referral.origin.facilityId ?? "Organization scope"}</td>
                        <td>{referral.version}</td>
                        <td>
                          {referral.submittedAt ? (
                            <time dateTime={referral.submittedAt}>
                              {new Date(referral.submittedAt).toLocaleString()}
                            </time>
                          ) : (
                            "Timestamp unavailable"
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
          {detail.status === "loading" && (
            <div className="workspace-state" role="status" aria-busy="true">
              Loading submitted Referral revision…
            </div>
          )}
          {detail.status === "error" && (
            <div className="workspace-state workspace-error" role="alert">
              <h3>
                {detail.code === "FORBIDDEN"
                  ? "Referral detail access was denied"
                  : "Referral detail is unavailable"}
              </h3>
              <p>No recommendation can be requested without an authorized submitted revision.</p>
            </div>
          )}
          {detail.status === "loaded" && (
            <>
              <article className="capacity-result" aria-labelledby="matching-referral-detail-title">
                <p className="eyebrow">SUBMITTED REFERRAL</p>
                <h3 id="matching-referral-detail-title">
                  Revision {detail.detail.submittedRevisionNumber ?? "unavailable"}
                </h3>
                <dl className="result-facts">
                  <div>
                    <dt>Referral</dt>
                    <dd>{detail.detail.id}</dd>
                  </div>
                  <div>
                    <dt>Status</dt>
                    <dd>{detail.detail.status}</dd>
                  </div>
                  <div>
                    <dt>Origin organization</dt>
                    <dd>{detail.detail.origin.organizationId}</dd>
                  </div>
                  <div>
                    <dt>Required capabilities</dt>
                    <dd>{detail.detail.careRequirement.requiredCareCapabilityCodes.join(", ")}</dd>
                  </div>
                </dl>
                <p className="field-help">
                  PatientReference and clinical context are not used by the Matching presentation.
                </p>
              </article>
              {detail.detail.submittedRevisionNumber !== null &&
              authorizedScopeFor(requestScopes, detail.detail) ? (
                <button
                  className="button button-primary"
                  type="button"
                  disabled={generation === "loading"}
                  onClick={() => void requestRecommendation()}
                >
                  {generation === "loading"
                    ? "Evaluating deterministic Matching…"
                    : "Request deterministic recommendation"}
                </button>
              ) : (
                <p className="field-help">
                  A submitted revision and RecommendationRequest grant for this origin scope are
                  required to request an evaluation.
                </p>
              )}
              {generation === "error" && (
                <div className="workspace-state workspace-error" role="alert">
                  <h3>
                    {generationError === "FORBIDDEN"
                      ? "Recommendation request was denied"
                      : generationError === "UNAUTHENTICATED"
                        ? "Sign in again to continue"
                        : "Matching evaluation is unavailable"}
                  </h3>
                  <p>No recommendation is assumed. Existing Referral state is unchanged.</p>
                </div>
              )}
              {history.status === "loading" && (
                <div className="workspace-state" role="status" aria-busy="true">
                  Loading recommendation history…
                </div>
              )}
              {history.status === "error" && (
                <div className="workspace-state workspace-error" role="alert">
                  <h3>
                    {history.code === "FORBIDDEN"
                      ? "Recommendation history access was denied"
                      : "Recommendation history is unavailable"}
                  </h3>
                  <p>Matching history is not assumed.</p>
                </div>
              )}
              {history.status === "unavailable" && (
                <p className="field-help">
                  Recommendation history is not listed in this session's access projection.
                </p>
              )}
              {history.status === "loaded" && history.history.items.length === 0 && (
                <div className="workspace-state" role="status">
                  <h3>No recommendation history</h3>
                  <p>No prior Matching result was returned for this Referral.</p>
                </div>
              )}
              {history.status === "loaded" &&
                history.history.items.map((item) => (
                  <RecommendationResult key={item.id} snapshot={item} />
                ))}
              {generated &&
                (history.status !== "loaded" ||
                  !history.history.items.some((item) => item.id === generated.id)) && (
                  <RecommendationResult snapshot={generated} />
                )}
            </>
          )}
        </>
      )}
    </section>
  );
}

function RecommendationResult({ snapshot }: { readonly snapshot: RecommendationSnapshot }) {
  const candidates = [...snapshot.candidates].sort((left, right) => {
    if (left.rank !== null && right.rank !== null) return left.rank - right.rank;
    if (left.rank !== null) return -1;
    if (right.rank !== null) return 1;
    return 0;
  });
  return (
    <article
      className="capacity-result"
      aria-label={`Deterministic recommendation revision ${snapshot.referralRevision}`}
    >
      <p className="eyebrow">DETERMINISTIC RECOMMENDATION · {snapshot.outcome}</p>
      <h3>Structured Matching result</h3>
      <p>
        Generated{" "}
        <time dateTime={snapshot.generatedAt}>
          {new Date(snapshot.generatedAt).toLocaleString()}
        </time>
        ; applicable until{" "}
        <time dateTime={snapshot.applicableUntil}>
          {new Date(snapshot.applicableUntil).toLocaleString()}
        </time>
        .
      </p>
      {snapshot.reasonCodes.length > 0 && (
        <p className="reason-codes">Result reasons: {snapshot.reasonCodes.join(", ")}</p>
      )}
      {candidates.length === 0 ? (
        <p>No candidate Facilities were returned.</p>
      ) : (
        <ol className="recommendation-list">
          {candidates.map((candidate) => (
            <li key={candidate.facility}>
              <strong>
                {candidate.rank === null ? candidate.status : `Rank ${candidate.rank}`}
              </strong>
              <span>
                Facility {candidate.facility} · Organization {candidate.organization}
              </span>
              {candidate.measure && (
                <span>
                  {candidate.measure.kind}: {candidate.measure.value} {candidate.measure.unit}
                </span>
              )}
              {candidate.reasons.length > 0 && (
                <span>
                  Reasons:{" "}
                  {candidate.reasons
                    .map((reason) =>
                      reason.subjectCode ? `${reason.code} (${reason.subjectCode})` : reason.code,
                    )
                    .join(", ")}
                </span>
              )}
            </li>
          ))}
        </ol>
      )}
      <p className="field-help">
        This recommendation is deterministic structured output. It does not accept a Referral or
        reserve Capacity.
      </p>
    </article>
  );
}

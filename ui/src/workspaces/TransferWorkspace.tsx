import { useMemo, useState } from "react";
import { OwnerApiError } from "../api/owner";
import type { SessionProjection } from "../api/session";
import {
  createTransferApiClient,
  type IncomingTransferRequest,
  receivingScopes,
  type TransferApiClient,
  type TransferPage,
  type TransferSummary,
  transferScopes,
} from "../api/transfer";

interface TransferWorkspaceProps {
  readonly session: SessionProjection;
  readonly getAccessToken: () => Promise<string | null>;
  readonly apiClient?: TransferApiClient;
  readonly view: "transfers" | "receiving";
}

type QueryState<T> =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | { readonly status: "loaded"; readonly result: TransferPage<T> };

function errorCode(error: unknown): string {
  return error instanceof OwnerApiError ? error.code : "UNAVAILABLE";
}

function errorTitle(code: string): string {
  switch (code) {
    case "FORBIDDEN":
      return "Transfer access was denied";
    case "UNAUTHENTICATED":
      return "Sign in again to continue";
    case "INVALID_RESPONSE":
      return "The Transfer owner returned an unusable response";
    default:
      return "Transfer information is unavailable";
  }
}

export function TransferWorkspace({
  session,
  getAccessToken,
  apiClient,
  view,
}: TransferWorkspaceProps) {
  const isReceiving = view === "receiving";
  const scopes = useMemo(
    () => (isReceiving ? receivingScopes(session) : transferScopes(session)),
    [isReceiving, session],
  );
  const api = useMemo(
    () => apiClient ?? createTransferApiClient(getAccessToken),
    [apiClient, getAccessToken],
  );
  const [scopeKey, setScopeKey] = useState(scopes[0]?.key ?? "");
  const [transferQuery, setTransferQuery] = useState<QueryState<TransferSummary>>({
    status: "idle",
  });
  const [incomingQuery, setIncomingQuery] = useState<QueryState<IncomingTransferRequest>>({
    status: "idle",
  });
  const scope = scopes.find((item) => item.key === scopeKey);
  const activeStatus = isReceiving ? incomingQuery.status : transferQuery.status;

  async function load(): Promise<void> {
    if (!scope) {
      if (isReceiving) setIncomingQuery({ status: "error", code: "INVALID_REQUEST" });
      else setTransferQuery({ status: "error", code: "INVALID_REQUEST" });
      return;
    }
    if (isReceiving) setIncomingQuery({ status: "loading" });
    else setTransferQuery({ status: "loading" });
    try {
      if (isReceiving) {
        if (scope.kind !== "FACILITY") throw new OwnerApiError(null, "INVALID_REQUEST");
        const result = await api.loadIncoming(scope, 0, 50);
        setIncomingQuery({ status: "loaded", result });
      } else {
        const result = await api.loadTransfers(scope, 0, 50);
        setTransferQuery({ status: "loaded", result });
      }
    } catch (error: unknown) {
      if (isReceiving) setIncomingQuery({ status: "error", code: errorCode(error) });
      else setTransferQuery({ status: "error", code: errorCode(error) });
    }
  }

  return (
    <section className="workspace-panel" aria-labelledby="transfer-workspace-title">
      <div className="workspace-intro">
        <p className="eyebrow">TRANSFER OWNER DATA</p>
        <h2 id="transfer-workspace-title">
          {isReceiving ? "Incoming receiving work" : "Transfer coordination"}
        </h2>
        <p>
          {isReceiving
            ? "This queue shows pending requests for an authorized receiving facility. Acceptance and reservation are separate states."
            : "Transfer state is shown as recorded by the Transfer owner. A Transfer, a Referral and a Transport request remain distinct records."}
        </p>
      </div>
      {scopes.length === 0 ? (
        <div className="workspace-state" role="status">
          <h3>
            {isReceiving
              ? "Receiving facility scope is not available"
              : "Transfer scope is not available"}
          </h3>
          <p>
            {isReceiving
              ? "This session has no recognized facility scope for TransferViewAuthority."
              : "This session has no recognized organization or facility scope for TransferViewAuthority."}
          </p>
        </div>
      ) : (
        <div className="query-form">
          <label className="field-label" htmlFor="transfer-scope">
            Authorized {scope?.kind === "FACILITY" || isReceiving ? "facility" : "organization"}
            <select
              id="transfer-scope"
              value={scopeKey}
              onChange={(event) => {
                setScopeKey(event.currentTarget.value);
                setTransferQuery({ status: "idle" });
                setIncomingQuery({ status: "idle" });
              }}
            >
              {scopes.map((item) => (
                <option key={item.key} value={item.key}>
                  {item.kind === "FACILITY"
                    ? `${item.organizationId} / ${item.facilityId}`
                    : item.organizationId}
                </option>
              ))}
            </select>
          </label>
          <button
            className="button button-primary"
            type="button"
            onClick={() => void load()}
            disabled={activeStatus === "loading" || !scope}
          >
            {isReceiving ? "Load pending requests" : "Load Transfers"}
          </button>
        </div>
      )}
      {activeStatus === "loading" && (
        <div className="workspace-state" role="status" aria-busy="true">
          Loading Transfer owner information…
        </div>
      )}
      {activeStatus === "error" && (
        <div className="workspace-state workspace-error" role="alert">
          <h3>
            {errorTitle(
              isReceiving && incomingQuery.status === "error"
                ? incomingQuery.code
                : !isReceiving && transferQuery.status === "error"
                  ? transferQuery.code
                  : "UNAVAILABLE",
            )}
          </h3>
          <p>
            AyuVahak did not show an inferred state. Verify the selected scope or contact the
            deployment administrator.
          </p>
        </div>
      )}
      {isReceiving && incomingQuery.status === "loaded" && (
        <IncomingRequests result={incomingQuery.result} />
      )}
      {!isReceiving && transferQuery.status === "loaded" && (
        <TransferList result={transferQuery.result} />
      )}
    </section>
  );
}

function TransferList({ result }: { readonly result: TransferPage<TransferSummary> }) {
  if (result.items.length === 0) {
    return (
      <div className="workspace-state" role="status">
        <h3>No Transfers are visible in this scope</h3>
        <p>The owner returned an empty page. No Referral or Transport state is inferred.</p>
      </div>
    );
  }
  return (
    <div className="table-scroll">
      <table>
        <caption>Transfer coordination records, page {result.page + 1}</caption>
        <thead>
          <tr>
            <th scope="col">Transfer</th>
            <th scope="col">Origin facility</th>
            <th scope="col">Receiving facility</th>
            <th scope="col">Transfer state</th>
            <th scope="col">Reservation outcome</th>
            <th scope="col">Created</th>
          </tr>
        </thead>
        <tbody>
          {result.items.map((item) => (
            <tr key={item.transferId}>
              <th scope="row">{item.transferId}</th>
              <td>{item.originFacilityId ?? "Not attributed to a facility"}</td>
              <td>{item.receivingFacilityId}</td>
              <td>
                <span className="state-tag">{item.status}</span>
              </td>
              <td>{item.reservationOutcome ?? "Not reported"}</td>
              <td>
                <time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleString()}</time>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {result.hasMore && <p className="field-help">More records are available on the next page.</p>}
    </div>
  );
}

function IncomingRequests({ result }: { readonly result: TransferPage<IncomingTransferRequest> }) {
  if (result.items.length === 0) {
    return (
      <div className="workspace-state" role="status">
        <h3>No pending receiving requests</h3>
        <p>The selected facility has no pending requests in the owner response.</p>
      </div>
    );
  }
  return (
    <div className="table-scroll">
      <table>
        <caption>Pending receiving requests, page {result.page + 1}</caption>
        <thead>
          <tr>
            <th scope="col">Request attempt</th>
            <th scope="col">Journey</th>
            <th scope="col">Origin organization</th>
            <th scope="col">Receiving facility</th>
            <th scope="col">Requested</th>
            <th scope="col">Expires</th>
          </tr>
        </thead>
        <tbody>
          {result.items.map((item) => (
            <tr key={item.requestAttemptId}>
              <th scope="row">{item.requestAttemptId}</th>
              <td>{item.journeyId}</td>
              <td>{item.originOrganizationId}</td>
              <td>{item.receivingFacilityId}</td>
              <td>
                <time dateTime={item.requestedAt}>
                  {new Date(item.requestedAt).toLocaleString()}
                </time>
              </td>
              <td>
                <time dateTime={item.expiresAt}>{new Date(item.expiresAt).toLocaleString()}</time>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="field-help">
        This view does not accept or reserve Capacity. Use the separately authorized owner workflow
        for assessments and decisions.
      </p>
    </div>
  );
}

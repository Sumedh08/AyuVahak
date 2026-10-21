import { useMemo, useState } from "react";
import { OwnerApiError } from "../api/owner";
import type { SessionProjection } from "../api/session";
import {
  createTransferApiClient,
  type TransferApiClient,
  type TransferPage,
  type TransferSummary,
  type TransportRequestSummary,
  transferScopes,
  transportScopes,
} from "../api/transfer";

interface TransportWorkspaceProps {
  readonly session: SessionProjection;
  readonly getAccessToken: () => Promise<string | null>;
  readonly apiClient?: TransferApiClient;
}

type Query<T> =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | { readonly status: "loaded"; readonly result: T };

function code(error: unknown): string {
  return error instanceof OwnerApiError ? error.code : "UNAVAILABLE";
}

export function TransportWorkspace({
  session,
  getAccessToken,
  apiClient,
}: TransportWorkspaceProps) {
  const scopes = useMemo(() => transportScopes(session), [session]);
  const transferReadScopes = useMemo(() => transferScopes(session), [session]);
  const api = useMemo(
    () => apiClient ?? createTransferApiClient(getAccessToken),
    [apiClient, getAccessToken],
  );
  const [facilityId, setFacilityId] = useState(scopes[0]?.facilityId ?? "");
  const [transfers, setTransfers] = useState<Query<TransferPage<TransferSummary>>>({
    status: "idle",
  });
  const [selectedTransferId, setSelectedTransferId] = useState("");
  const [requests, setRequests] = useState<Query<TransferPage<TransportRequestSummary>>>({
    status: "idle",
  });
  const selectedFacility = scopes.find((scope) => scope.facilityId === facilityId);
  const matchingTransferScope = transferReadScopes.find(
    (scope) => scope.kind === "FACILITY" && scope.facilityId === facilityId,
  );
  const transportTransferScope =
    transfers.status === "loaded"
      ? transfers.result.items.find((item) => item.transferId === selectedTransferId)
      : undefined;

  async function loadTransfers(): Promise<void> {
    if (matchingTransferScope?.kind !== "FACILITY") {
      setTransfers({ status: "error", code: "FORBIDDEN" });
      return;
    }
    setTransfers({ status: "loading" });
    setRequests({ status: "idle" });
    setSelectedTransferId("");
    try {
      const result = await api.loadTransfers(matchingTransferScope, 0, 50);
      setTransfers({ status: "loaded", result });
      setSelectedTransferId(result.items[0]?.transferId ?? "");
    } catch (error: unknown) {
      setTransfers({ status: "error", code: code(error) });
    }
  }

  async function loadRequests(): Promise<void> {
    if (!selectedFacility || !transportTransferScope) {
      setRequests({ status: "error", code: "INVALID_REQUEST" });
      return;
    }
    setRequests({ status: "loading" });
    try {
      const result = await api.loadTransportRequests(selectedFacility, selectedTransferId, 0, 50);
      setRequests({ status: "loaded", result });
    } catch (error: unknown) {
      setRequests({ status: "error", code: code(error) });
    }
  }

  return (
    <section className="workspace-panel" aria-labelledby="transport-title">
      <div className="workspace-intro">
        <p className="eyebrow">TRANSPORT OWNER DATA</p>
        <h2 id="transport-title">Transport coordination</h2>
        <p>
          Transport requests are scoped to a visible Transfer. A Transport request does not change
          the Transfer state, and an arrival observation does not mean handover is complete.
        </p>
      </div>
      {scopes.length === 0 ? (
        <div className="workspace-state" role="status">
          <h3>Transport facility scope is not available</h3>
          <p>This session has no recognized facility scope for Transport status information.</p>
        </div>
      ) : (
        <div className="query-form">
          <label className="field-label" htmlFor="transport-facility">
            Authorized facility
            <select
              id="transport-facility"
              value={facilityId}
              onChange={(event) => {
                setFacilityId(event.currentTarget.value);
                setTransfers({ status: "idle" });
                setRequests({ status: "idle" });
                setSelectedTransferId("");
              }}
            >
              {scopes.map((scope) => (
                <option key={scope.key} value={scope.facilityId}>
                  {scope.organizationId} / {scope.facilityId}
                </option>
              ))}
            </select>
          </label>
          <button
            className="button button-primary"
            type="button"
            onClick={() => void loadTransfers()}
            disabled={transfers.status === "loading" || !matchingTransferScope}
          >
            Load visible Transfers
          </button>
        </div>
      )}
      {transfers.status === "loading" && (
        <p className="workspace-state" role="status" aria-busy="true">
          Loading Transfers…
        </p>
      )}
      {transfers.status === "error" && (
        <p className="workspace-state workspace-error" role="alert">
          The Transfer owner did not return a usable list. No Transport state is inferred.
        </p>
      )}
      {transfers.status === "loaded" && transfers.result.items.length === 0 && (
        <div className="workspace-state" role="status">
          <h3>No visible Transfers</h3>
          <p>Transport requests are shown only within a Transfer authorized by the owner.</p>
        </div>
      )}
      {transfers.status === "loaded" && transfers.result.items.length > 0 && (
        <div className="query-form">
          <label className="field-label" htmlFor="transport-transfer">
            Visible Transfer
            <select
              id="transport-transfer"
              value={selectedTransferId}
              onChange={(event) => {
                setSelectedTransferId(event.currentTarget.value);
                setRequests({ status: "idle" });
              }}
            >
              {transfers.result.items.map((item) => (
                <option key={item.transferId} value={item.transferId}>
                  {item.transferId} · {item.status}
                </option>
              ))}
            </select>
          </label>
          <button
            className="button button-secondary"
            type="button"
            onClick={() => void loadRequests()}
            disabled={!transportTransferScope || requests.status === "loading"}
          >
            Load Transport requests
          </button>
        </div>
      )}
      {requests.status === "loading" && (
        <p className="workspace-state" role="status" aria-busy="true">
          Loading Transport requests…
        </p>
      )}
      {requests.status === "error" && (
        <div className="workspace-state workspace-error" role="alert">
          <h3>
            {requests.code === "FORBIDDEN"
              ? "Transport access was denied"
              : "Transport information is unavailable"}
          </h3>
          <p>The owning service did not return a usable response for the selected scope.</p>
        </div>
      )}
      {requests.status === "loaded" && <TransportRequestTable result={requests.result} />}
    </section>
  );
}

function TransportRequestTable({
  result,
}: {
  readonly result: TransferPage<TransportRequestSummary>;
}) {
  if (result.items.length === 0) {
    return (
      <div className="workspace-state" role="status">
        <h3>No Transport requests for this Transfer</h3>
        <p>The owner response contained no request records.</p>
      </div>
    );
  }
  return (
    <div className="table-scroll">
      <table>
        <caption>Transport requests, page {result.page + 1}</caption>
        <thead>
          <tr>
            <th scope="col">Transport request</th>
            <th scope="col">Status</th>
            <th scope="col">Origin facility</th>
            <th scope="col">Destination facility</th>
            <th scope="col">Requested</th>
            <th scope="col">Version</th>
          </tr>
        </thead>
        <tbody>
          {result.items.map((request) => (
            <tr key={request.transportRequestId}>
              <th scope="row">{request.transportRequestId}</th>
              <td>
                <span className="state-tag">{request.status}</span>
              </td>
              <td>{request.originFacilityId}</td>
              <td>{request.destinationFacilityId}</td>
              <td>
                <time dateTime={request.requestedAt}>
                  {new Date(request.requestedAt).toLocaleString()}
                </time>
              </td>
              <td>{request.version}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {result.hasMore && <p className="field-help">More records are available on the next page.</p>}
    </div>
  );
}

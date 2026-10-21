import { useMemo, useState } from "react";
import {
  createOperatorSupportApiClient,
  OPERATOR_SUPPORT_TOPICS,
  type OperatorSupportApiClient,
  type OperatorSupportQuestionKey,
  type OperatorSupportResponse,
  parseOperatorSupportQuestionKey,
} from "../api/operator-support";
import { OwnerApiError } from "../api/owner";

interface OperatorSupportWorkspaceProps {
  readonly getAccessToken: () => Promise<string | null>;
  readonly apiClient?: OperatorSupportApiClient;
}

type AnswerState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly code: string }
  | { readonly status: "answered"; readonly response: OperatorSupportResponse };

function errorCode(error: unknown): string {
  return error instanceof OwnerApiError ? error.code : "UNAVAILABLE";
}

function abstentionMessage(reasonCode: OperatorSupportResponse["reasonCode"]): string {
  switch (reasonCode) {
    case "DISABLED":
      return "Operator support is disabled by this deployment. Core AyuVahak workflows remain available.";
    case "NO_APPROVED_EVIDENCE":
      return "The approved documentation does not cover this topic. Use the linked runbooks or contact your operator.";
    case "INVALID_PROVIDER_OUTPUT":
      return "The support response did not meet validation requirements, so no answer is shown.";
    case "PROVIDER_UNAVAILABLE":
      return "The configured support provider is unavailable. Continue with the ordinary documentation and runbooks.";
    case "NONE":
      return "No advisory response is available.";
  }
}

export function OperatorSupportWorkspace({
  getAccessToken,
  apiClient,
}: OperatorSupportWorkspaceProps) {
  const api = useMemo(
    () => apiClient ?? createOperatorSupportApiClient(getAccessToken),
    [apiClient, getAccessToken],
  );
  const [questionKey, setQuestionKey] = useState<OperatorSupportQuestionKey | "">("");
  const [answer, setAnswer] = useState<AnswerState>({ status: "idle" });

  async function requestAnswer(): Promise<void> {
    if (!questionKey) return;
    setAnswer({ status: "loading" });
    try {
      setAnswer({ status: "answered", response: await api.answer(questionKey) });
    } catch (error: unknown) {
      setAnswer({ status: "error", code: errorCode(error) });
    }
  }

  return (
    <section className="workspace-panel" aria-labelledby="operator-support-title">
      <div className="workspace-intro">
        <p className="eyebrow">OPTIONAL DOCUMENTATION ASSISTANCE</p>
        <h2 id="operator-support-title">Operator support</h2>
        <p>
          Choose a documented topic. This optional capability uses approved AyuVahak guidance only;
          it does not accept case details and cannot change AyuVahak state.
        </p>
      </div>
      <div className="support-advisory-note" role="note">
        <strong>ADVISORY ONLY</strong>
        <span>
          This response does not change AyuVahak state. Verify operational decisions against
          authoritative records.
        </span>
      </div>
      <label className="field-label" htmlFor="operator-support-topic">
        Documentation topic
        <select
          id="operator-support-topic"
          value={questionKey}
          onChange={(event) => {
            setQuestionKey(parseOperatorSupportQuestionKey(event.currentTarget.value) ?? "");
            setAnswer({ status: "idle" });
          }}
        >
          <option value="">Choose a topic</option>
          {OPERATOR_SUPPORT_TOPICS.map((topic) => (
            <option key={topic.key} value={topic.key}>
              {topic.label}
            </option>
          ))}
        </select>
      </label>
      <button
        className="button button-primary"
        type="button"
        disabled={!questionKey || answer.status === "loading"}
        onClick={() => void requestAnswer()}
      >
        {answer.status === "loading" ? "Checking approved guidance…" : "Show advisory guidance"}
      </button>
      {answer.status === "loading" && (
        <p className="workspace-state" role="status" aria-busy="true">
          Looking up the selected documentation topic…
        </p>
      )}
      {answer.status === "error" && (
        <section className="workspace-state workspace-error" role="alert">
          <h3>
            {answer.code === "FORBIDDEN"
              ? "Operator support access was denied"
              : "Guidance is unavailable"}
          </h3>
          <p>No advisory answer was returned. Core workflows are unaffected.</p>
        </section>
      )}
      {answer.status === "answered" &&
        (answer.response.status === "ADVISORY" ? (
          <section className="support-answer" aria-labelledby="support-answer-title">
            <div className="support-answer-heading">
              <h3 id="support-answer-title">Advisory guidance</h3>
              <span className="state-tag">ADVISORY</span>
            </div>
            <p className="support-answer-copy">{answer.response.answer}</p>
            <div>
              <h4>Approved sources</h4>
              <ul className="support-sources">
                {answer.response.sources.map((source) => (
                  <li key={source.sourceId}>
                    <strong>{source.section}</strong>
                    <code>{source.path}</code>
                    <small>
                      {source.sourceId} · corpus {answer.response.corpusVersion} · content{" "}
                      {source.contentHash.slice(0, 12)}
                    </small>
                  </li>
                ))}
              </ul>
            </div>
            <p className="support-provenance">
              Capability {answer.response.capabilityVersion} · corpus{" "}
              {answer.response.corpusVersion}
            </p>
            <button
              className="button button-quiet"
              type="button"
              onClick={() => setAnswer({ status: "idle" })}
            >
              Close advisory
            </button>
          </section>
        ) : (
          <section className="workspace-state" role="status">
            <h3>No advisory answer</h3>
            <p>{abstentionMessage(answer.response.reasonCode)}</p>
            <button
              className="button button-quiet"
              type="button"
              onClick={() => setAnswer({ status: "idle" })}
            >
              Close
            </button>
          </section>
        ))}
      <p className="field-help">
        Do not enter patient, case, clinical, credential, or secret information. Topic selection is
        finite; free-form prompts are not accepted.
      </p>
    </section>
  );
}

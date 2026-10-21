# Phase 14 Operator Support Evaluation

**Status:** Local deterministic-contract evaluation only. This is not an evaluation of an LLM or external provider, and it is not a Phase 14 acceptance or freeze record.

## Evaluation target

The versioned synthetic case set is [operator-support-evaluation-v1.json](../../src/test/resources/evaluation/phase14/operator-support-evaluation-v1.json). It exercises the current `OperatorSupportService.answer` boundary and the finite-topic request parser. No model, network provider, external service, or operational data is used.

The cases cover a source-backed advisory, disabled capability, unsupported topic, provider failure, unapproved citation, malformed output, oversized output, and rejection of synthetic patient-like, secret-like, and injection-like request fields. Existing workspace tests separately verify that an operator can close an advisory and that disabled assistance leaves the application available.

## Local result

On Temurin 25.0.4.1+1, the focused Maven run passed:

```text
OperatorSupportControllerTest                 3 passed
OperatorSupportInputBoundaryEvaluationTest     1 passed
OperatorSupportEvaluationCorpusTest            1 passed
OperatorSupportServiceTest                    11 passed
Total                                         16 passed
Failures                                       0
Errors                                         0
Skipped                                        0
```

The evaluation uses the deterministic local provider only. It confirms bounded source/citation and failure behavior at the current application boundary; it does not establish factuality, omission, fairness, latency, cost, or safety properties for any model. There is no configured external provider profile or inference adapter in this acceptance slice, so real-provider evaluation remains future work under the existing Phase 14 governance and deployment gates.

## Limits and follow-up

- The static documentation corpus has a version identifier and source locators; this test does not detect changed documentation sections or automatically force re-evaluation after a corpus edit.
- Injection-like content is rejected when submitted as an extra request field. There is no model that consumes document text, so this does not claim prompt-injection resistance for future inference.
- The UI close action discards the displayed advisory locally; it does not persist feedback or alter an owner workflow.
- Provider timeout, invalid citation, malformed response, and output bounds are exercised by deterministic test doubles, not a network service.

Before any model provider is implemented or enabled, add a separately reviewed versioned source manifest and model-specific evaluation for support, omissions, abstention, stale/conflicting sources, injection, privacy, latency, and failure behavior. Keep the capability disabled unless that evaluation and the applicable Phase 13 controls pass.

# Phase 14 Operator Support Evaluation

**Status:** Local deterministic-contract evaluation only. This is not an evaluation of an LLM or external provider, and it is not a Phase 14 acceptance or freeze record.

## Evaluation target

The versioned synthetic case set is [operator-support-evaluation-v1.json](../../src/test/resources/evaluation/phase14/operator-support-evaluation-v1.json), and the current capability is described by [operator-support-capability-v1.json](../../src/test/resources/evaluation/phase14/operator-support-capability-v1.json). The cases exercise the current `OperatorSupportService.answer` boundary and the finite-topic request parser. No model, network provider, external service, or operational data is used. A test verifies that the manifest keeps the capability disabled by default, records no external inference or action authority, excludes raw prompt/answer retention, and lists the changes that require re-evaluation.

The machine-readable evaluation contract names its owner, no-model baseline, measurement method, and acceptance rule. The cases cover exact source/citation provenance, disabled capability, unsupported topic, provider failure and timeout, unapproved citation, malformed output, oversized output, and rejection of synthetic patient-like, secret-like, and injection-like request fields. The JUnit runner maps every scenario to an executable assertion rather than treating descriptive evidence strings as acceptance. Each corpus case has a separate test method whose Surefire XML name includes its stable case ID; the XML was inspected to confirm the IDs appear in the report without recording prompts or answers. Existing workspace tests separately verify that an operator can close an advisory and that disabled assistance leaves the application available.

## Local result

On Temurin 25.0.4.1+1, the focused Maven run passed:

```text
OperatorSupportControllerTest                 3 passed
OperatorSupportInputBoundaryEvaluationTest     1 passed
OperatorSupportEvaluationCorpusTest           10 passed (8 case-ID methods + corpus contract + manifest)
OperatorSupportServiceTest                    11 passed
Total                                         25 passed
Failures                                       0
Errors                                         0
Skipped                                        0
```

The evaluation uses the deterministic local provider and synthetic throwing doubles only. The timeout case exercises exception redaction at the application boundary; it does not measure or prove an HTTP deadline. It confirms bounded source/citation and failure behavior at the current application boundary; it does not establish factuality, omission, fairness, latency, cost, or safety properties for any model. There is no configured external provider profile or inference adapter in this acceptance slice, so real-provider evaluation remains future work under the existing Phase 14 governance and deployment gates.

## Limits and follow-up

- The static documentation corpus has a version identifier and source locators; this test does not detect changed documentation sections or automatically force re-evaluation after a corpus edit.
- Injection-like content is rejected when submitted as an extra request field. There is no model that consumes document text, so this does not claim prompt-injection resistance for future inference.
- The UI close action discards the displayed advisory locally; it does not persist feedback or alter an owner workflow.
- Provider timeout, invalid citation, malformed response, and output bounds are exercised by deterministic test doubles, not a network service.

Before any model provider is implemented or enabled, add a separately reviewed versioned source manifest and model-specific evaluation for support, omissions, abstention, stale/conflicting sources, injection, privacy, latency, and failure behavior. Keep the capability disabled unless that evaluation and the applicable Phase 13 controls pass.

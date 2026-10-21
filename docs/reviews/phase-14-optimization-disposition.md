# Phase 14 deterministic optimization disposition

**Disposition:** No production optimization is justified by the current evidence. No production
behavior or persistence code was changed for this assessment.

## Evidence reviewed

The versioned synthetic corpus is
[`deterministic-owner-corpus-v1.json`](../../src/test/resources/golden/phase14/deterministic-owner-corpus-v1.json).
Its expected outcomes are exercised at the following seams:

| Contract | Test evidence |
|---|---|
| Directory facts and Capacity freshness/readiness consumed by Matching, Referral submission, candidate eligibility, exclusions, reasons, rank, and deterministic tie break | `DirectoryCapacityReferralScenarioIT#volumeTwoHappyPathRunsThroughDirectoryCapacityReferralMatchingAndTransfer`; `RecommendationRankerTest` |
| Transfer acceptance and Capacity reservation outcome | Same integrated owner API scenario |
| Transport dispatch request and local synthetic provider acknowledgement | Same integrated owner API scenario |
| Analytics period boundary, completeness, aggregate value, source inputs and idempotent replay | `AnalyticsAggregateComputationPostgresIT` |
| Federated aggregate provenance, duplicate replay, and changed-content conflict | `FederatedAggregateFederationPostgresIT` |

At source revision `27862c47eb976f0c3e4dc9615cfd9ebcd9d157c4`, targeted verification passed:

- `RecommendationRankerTest`: 12 tests, 0 failures/errors/skips.
- `AnalyticsAggregateComputationPostgresIT`: 12 tests, 0 failures/errors/skips.
- `FederatedAggregateFederationPostgresIT`: 7 tests, 0 failures/errors/skips.
- The integrated Directory/Capacity/Referral/Matching/Transfer/Transport scenario: 1 test, 0 failures/errors/skips.
- Combined Failsafe result: 20 tests, 0 failures/errors/skips; build exit 0.

The integration tests used synthetic fixtures and disposable PostgreSQL 18.6/PostGIS. Any
transport-provider interaction used a local synthetic mock; no public or remote exchange endpoint
was used.

Commands were:

```powershell
.\mvnw.cmd -B -ntp '-Dtest=RecommendationRankerTest' test
.\mvnw.cmd -B -ntp '-Dit.test=DirectoryCapacityReferralScenarioIT#volumeTwoHappyPathRunsThroughDirectoryCapacityReferralMatchingAndTransfer,AnalyticsAggregateComputationPostgresIT,FederatedAggregateFederationPostgresIT' test-compile failsafe:integration-test failsafe:verify
```

The Phase 13 mixed-workload characterization was rerun at the same source revision with Temurin
25.0.4.1+1, Windows 11, Docker Desktop 29.3.1, PostgreSQL 18.6, eight reported processors, and a
Docker runtime reporting 5,930 MiB. It used the same 20-operation weighted cycle, four workers,
two warm-up cycles and five measured cycles (100 operations per profile). The Profile B acceptance
used two actual packaged application JVMs sharing the disposable database. Analytics computation
and local mock export ran through their application service boundary in the separately measured
benchmark JVM, as specified in the Phase 13 record.

| Measure | Profile A — one application JVM | Profile B — two application JVMs |
|---|---:|---:|
| Mixed operations / failures / timeouts | 100 / 0 / 0 | 100 / 0 / 0 |
| Duration | 5.271 s | 10.229 s |
| Overall p50 / p95 / p99 | 76.32 / 824.25 / 1,137.55 ms | 250.01 / 1,044.81 / 1,526.29 ms |
| Throughput | 18.97 ops/s | 9.78 ops/s |
| Packaged application CPU / core equivalents | 6.219 s / 1.180 | 11.813 s / 1.155 (sum across both JVMs) |
| Packaged application peak heap | 558,300,416 bytes | 863,718,160 bytes (sum across both JVMs) |
| Application active DB connections / pool capacity | 7 / 10 | 15 / 20 |
| Combined active connections / combined pool capacity | 8 / 20 | 15 / 30 |
| PostgreSQL statement calls | 3,100 | 3,615 |
| Oldest pending outbox age at interval end | 73,947 ms | 209,001 ms |
| Latest notification projection age at interval end | 74,288 ms | 209,337 ms |
| Pending/failed Analytics computations | 0 / 0 | 0 / 0 |
| Unresolved exports | 0 | 0 |
| Minimum active authorized SSE streams | 2 | 2 (one per replica) |

Operation-class p95 was highest for Capacity readiness (1,266.58 ms) and Referral reads (976.11 ms)
in Profile A, and Matching evaluation (1,899.41 ms), local mock export (1,047.71 ms), and SSE replay
(1,044.81 ms) in Profile B. These values are finite characterization samples, not SLOs. The
benchmark's query-plan hashes are simplified relation-level probes, not plans captured from each
complete application SQL statement. The database pool did not reach configured capacity, and the
measured work completed without request errors, timeouts, pending Analytics computations, or
unresolved exports.

## Decision and limits

The slower Profile B sample and outbox/projection ages are observable signals, but this run does not
identify a stable, isolated production bottleneck: it is a 100-operation local characterization,
there is no deployment SLO to compare against, pool capacity was not exhausted, and the plans are
relation-level probes. Changing query, index, worker, or concurrency behavior from this evidence
alone would be speculative. No production optimization, cache, denormalization, broker, or new
infrastructure was introduced.

This is a no-change optimization disposition, not a claim of adequate production performance. Any
future optimization must first identify a repeatable bottleneck under a deployment-relevant target,
then compare the exact owner outcomes against the golden corpus and record before/after resource
and latency evidence.

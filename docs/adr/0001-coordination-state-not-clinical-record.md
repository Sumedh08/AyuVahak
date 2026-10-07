---
status: accepted
---

# AyuVahak owns coordination, not the clinical record

AyuVahak's domain is cross-organization care coordination. It owns referral/transfer coordination state and its audit history, while EHR/HIS, registries, dispatch systems, LMIS, and DHIS2 remain authoritative for the information and workflows they own. AyuVahak stores only scoped references, caches, or time-bounded context needed for an active case. This boundary preserves the product's purpose and avoids creating a competing patient-record warehouse. Where a deployment already uses another platform for the same referral workflow, the deployment must map one owner explicitly instead of duplicating state.

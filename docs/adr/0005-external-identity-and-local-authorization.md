---
status: accepted
---

# Delegate authentication and keep application authorization local

Use an external identity provider for authentication; do not create home-grown username/password credentials. AyuVahak enforces application-specific role-based access initially, scoped by organization/facility and action, with a path to attribute-based rules when justified. SMART on FHIR can authorize compatible FHIR access but does not replace the app's full workflow policy. The distinction keeps identity proofing with an established authority while preserving local control over who may see or act on a coordination case.

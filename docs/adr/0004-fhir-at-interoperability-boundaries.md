---
status: accepted
---

# Keep FHIR at interoperability boundaries

Use FHIR R4 (4.0.1) as the initial exchange baseline and preserve separately versioned adapters for R4B/R5. Use mCSD for directory exchange, IPS for a minimal point-in-time summary when needed, and SMART on FHIR for compatible FHIR authorization/launch flows. Internal domain concepts remain AyuVahak's own. FHIR contains useful generic referral, task, and schedule/slot resources, but they do not define the complete emergency transfer lifecycle or AyuVahak's operational capacity/readiness semantics. This avoids both a bespoke-only exchange surface and a standards-shaped core that inherits external version and workflow assumptions.

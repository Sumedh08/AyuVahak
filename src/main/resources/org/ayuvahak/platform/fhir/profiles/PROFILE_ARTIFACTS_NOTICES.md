# Curated FHIR Profile Artifact Notices

These offline NPM-format packages contain selected FHIR `StructureDefinition`
resources used by Phase 8 profile validation. They are not full copies of the
Implementation Guides and do not include complete terminology expansions.
They are loaded from the application classpath; runtime package, terminology,
reference, and profile resolution over the network is not configured.

## Provenance and contents

| Local artifact | Source package and source | Source archive SHA-256 | Curated archive SHA-256 | Included resources |
| --- | --- | --- | --- | --- |
| `mcsd-4.0.0-curated.tgz` | `ihe.iti.mcsd#4.0.0`; [IHE mCSD download page](https://profiles.ihe.net/ITI/mCSD/download.html), [source repository](https://github.com/IHE/ITI.mCSD) | `933a143d7bb14c66731a32f52a084c6cb92476aca1b917db77a4640f8a5290ad` | `f10075f5ef9129bb11895f57aef64eced832532446b9e8752a48c5d29f7e1b0a` | 7 StructureDefinitions, selected from 24; 41 of 48 source package index entries are excluded. |
| `ips-2.0.1-curated.tgz` | `hl7.fhir.uv.ips#2.0.1`; [IPS 2.0.1 QA report](https://hl7.org/fhir/uv/ips/2.0.1/qa.html), [copyright and license](https://hl7.org/fhir/uv/ips/2.0.1/en/copyrights.html) | `5ebcb6d4bfbb32cd0b36b7d2165cb43c1aafc34e2849db3e37ecee07e7d781d2` | `7fa62afa911656d80f9dbcf374d841e8bdf0c65aa28373a183f739dfa87edabc` | 32 StructureDefinitions selected from 74 indexed entries; 42 entries are excluded. |
| `ips-extensions-r4-5.3.0-curated.tgz` | `hl7.fhir.uv.extensions.r4#5.3.0`; [HL7 FHIR extensions package](https://hl7.org/fhir/extensions/) | `dfbc3ac95df91ed845cc6b60920d2875f679361fa0e16f227cee93c4a9ab2104` | `fa8df3f11cb3941637a1a9b2a718eefd6a65351e8235f71ca5385352e6c595b4` | 6 directly referenced extension StructureDefinitions selected from 885 indexed entries; 879 are excluded. |

The curated mCSD set contains the Organization, FacilityOrganization,
Location, FacilityLocation, HealthcareService, Endpoint, and PurposeOfUse
StructureDefinitions. The IPS extension subset contains
`allergyintolerance-abatement`, `flag-priority`, `individual-genderIdentity`,
`individual-pronouns`, `note`, and `translation`. Package indexes and
canonical URLs are retained in each archive. The source archive hashes
identify the reviewed input archives; curated archive hashes identify the
exact files shipped here.

The official IPS QA metadata lists `hl7.terminology.r4#7.2.0`,
`hl7.fhir.uv.extensions.r4#5.3.0`, `hl7.fhir.uv.ipa#1.1.0`, and
`hl7.fhir.uv.tools.r4#1.1.2` as dependencies. The curated runtime set includes
only the IPS StructureDefinitions and the six directly referenced R4 extension
definitions. It does not include the full terminology, IPA, or tools packages,
or their full resource collections. The included package metadata declares
only the bundled R4 core dependency; it does not claim to reproduce the full
upstream dependency graph.

## License and attribution

- The IHE mCSD guide materials identify **CC BY 4.0**. Attribution is retained
  here and in the package metadata. The curated resource selection and package
  metadata are AyuVahak's derivative packaging; upstream canonical URLs and
  versions are preserved. See the [Creative Commons Attribution 4.0
  license](https://creativecommons.org/licenses/by/4.0/).
- The IPS 2.0.1 guide package and the selected HL7 R4 extensions are identified
  as **CC0** in their official publication/package metadata. See the [IPS
  copyright page](https://hl7.org/fhir/uv/ips/2.0.1/en/copyrights.html) and
  [FHIR R4 license](https://hl7.org/fhir/R4/license.html).
- IPS profile constraints refer to LOINC codes. No LOINC terminology package
  or expansion is redistributed here. The following attribution applies to
  LOINC references in synthetic profile fixtures and documentation:

  > This material contains content from LOINC (http://loinc.org). LOINC is
  > copyright © Regenstrief Institute, Inc. and the Logical Observation
  > Identifiers Names and Codes (LOINC) Committee and is available at no cost
  > under the license at http://loinc.org/license. LOINC® is a registered
  > United States trademark of Regenstrief Institute, Inc.

  See the [LOINC license](https://loinc.org/kb/license). Synthetic fixture
  codes are `60591-5`, `11450-4`, `48765-2`, and `10160-0`.

## Validation scope and limitations

Local tests exercise positive and negative examples against the pinned mCSD
Organization profile, IPS Patient profile, and IPS document Bundle profile.
An IPS Bundle must have document semantics; a non-document collection Bundle
is rejected. The validator verifies that required local profiles are available
at startup and fails initialization if a curated package or required profile
is missing or has the wrong version. It performs bounded local
structural/profile validation only. This is not a claim of complete mCSD or
IPS Implementation Guide conformance, full terminology validation, clinical
truth, identity matching, freshness, disclosure authorization, or trust in an
external fact.

Terminology coverage remains explicitly `INDETERMINATE` unless the relevant
code system/value set is available and verified under the applicable license.
Unknown safety-relevant terminology must not be treated as valid by display
text or by this profile check. No runtime package registry, terminology
service, remote reference resolution, or public healthcare endpoint is used.

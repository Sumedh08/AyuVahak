package org.ayuvahak.platform.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.BaseValidationSupport;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.junit.jupiter.api.Test;

class R4CoreVersionedCanonicalCompatibilitySupportTest {

  private static final String CORE_CONDITION = "http://hl7.org/fhir/StructureDefinition/Condition";
  private static final String IPS_CONDITION =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Condition-uv-ips|2.0.1";
  private static final String MCSD_ORGANIZATION =
      "https://profiles.ihe.net/ITI/mCSD/StructureDefinition/IHE.mCSD.Organization|4.0.0";
  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4Cached();

  @Test
  void resolvesOnlyExactVersionedR4CoreCanonicalAgainstLocalDefinition() {
    IValidationSupport coreSupport = new DefaultProfileValidationSupport(FHIR_CONTEXT);
    R4CoreVersionedCanonicalCompatibilitySupport compatibilitySupport =
        new R4CoreVersionedCanonicalCompatibilitySupport(FHIR_CONTEXT, coreSupport);

    IBaseResource result = compatibilitySupport.fetchStructureDefinition(CORE_CONDITION + "|4.0.1");

    assertThat(result).isInstanceOf(StructureDefinition.class);
    assertThat(((StructureDefinition) result).getVersion()).isEqualTo("4.0.1");
  }

  @Test
  void resolvesVersionedCoreCanonicalThroughTheGenericResourceLookupUsedByValidation() {
    IValidationSupport coreSupport = new DefaultProfileValidationSupport(FHIR_CONTEXT);
    R4CoreVersionedCanonicalCompatibilitySupport compatibilitySupport =
        new R4CoreVersionedCanonicalCompatibilitySupport(FHIR_CONTEXT, coreSupport);

    StructureDefinition result =
        compatibilitySupport.fetchResource(StructureDefinition.class, CORE_CONDITION + "|4.0.1");

    assertThat(result).isNotNull();
    assertThat(result.getVersion()).isEqualTo("4.0.1");
  }

  @Test
  void rejectsOtherVersionedCoreCanonicalsWithoutFallingBackToTheUnversionedDefinition() {
    RecordingSupport delegate = new RecordingSupport();
    R4CoreVersionedCanonicalCompatibilitySupport compatibilitySupport =
        new R4CoreVersionedCanonicalCompatibilitySupport(FHIR_CONTEXT, delegate);

    IBaseResource result = compatibilitySupport.fetchStructureDefinition(CORE_CONDITION + "|4.0.2");

    assertThat(result).isNull();
    assertThat(delegate.requestedCanonical).isNull();
  }

  @Test
  void doesNotRewriteVersionedIpsOrMcsdCanonicals() {
    RecordingSupport delegate = new RecordingSupport();
    R4CoreVersionedCanonicalCompatibilitySupport compatibilitySupport =
        new R4CoreVersionedCanonicalCompatibilitySupport(FHIR_CONTEXT, delegate);

    assertThat(compatibilitySupport.fetchStructureDefinition(IPS_CONDITION)).isNull();
    assertThat(delegate.requestedCanonical).isEqualTo(IPS_CONDITION);

    assertThat(compatibilitySupport.fetchStructureDefinition(MCSD_ORGANIZATION)).isNull();
    assertThat(delegate.requestedCanonical).isEqualTo(MCSD_ORGANIZATION);
  }

  private static final class RecordingSupport extends BaseValidationSupport {

    private String requestedCanonical;

    private RecordingSupport() {
      super(FHIR_CONTEXT);
    }

    @Override
    public IBaseResource fetchStructureDefinition(String canonical) {
      requestedCanonical = canonical;
      return null;
    }
  }
}

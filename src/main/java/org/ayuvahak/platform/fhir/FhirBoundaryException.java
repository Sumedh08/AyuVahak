package org.ayuvahak.platform.fhir;

public final class FhirBoundaryException extends RuntimeException {

  private final FhirBoundaryFailure failure;

  public FhirBoundaryException(FhirBoundaryFailure failure) {
    super(safeMessage(failure));
    this.failure = failure;
  }

  public FhirBoundaryFailure failure() {
    return failure;
  }

  private static String safeMessage(FhirBoundaryFailure failure) {
    return switch (failure) {
      case UNSUPPORTED_MEDIA_TYPE -> "FHIR content type is not supported";
      case UNSUPPORTED_FHIR_RELEASE -> "FHIR release is not supported";
      case MALFORMED_JSON -> "FHIR JSON payload is malformed";
      case LIMIT_EXCEEDED -> "FHIR payload exceeds a configured processing limit";
      case VALIDATION_OVERLOADED -> "FHIR validation capacity is temporarily unavailable";
      case UNSUPPORTED_PROFILE -> "FHIR profile is not available in local validation support";
      case PROFILE_VALIDATION_FAILED -> "FHIR payload does not conform to the configured profile";
    };
  }
}

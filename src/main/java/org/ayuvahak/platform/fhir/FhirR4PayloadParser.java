package org.ayuvahak.platform.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.StructureDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;

/** Parses and validates bounded R4 JSON using only locally configured validation artifacts. */
public final class FhirR4PayloadParser {

  private static final String SUPPORTED_RELEASE = "4.0.1";
  private static final String SUPPORTED_MEDIA_TYPE = "application/fhir+json";
  private static final String MCSD_PROFILE_PACKAGE =
      "/org/ayuvahak/platform/fhir/profiles/mcsd-4.0.0-curated.tgz";
  private static final String IPS_PROFILE_PACKAGE =
      "/org/ayuvahak/platform/fhir/profiles/ips-2.0.1-curated.tgz";
  private static final String IPS_EXTENSION_PACKAGE =
      "/org/ayuvahak/platform/fhir/profiles/ips-extensions-r4-5.3.0-curated.tgz";
  private static final String MCSD_ORGANIZATION_PROFILE =
      "https://profiles.ihe.net/ITI/mCSD/StructureDefinition/IHE.mCSD.Organization|4.0.0";
  private static final String IPS_PATIENT_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Patient-uv-ips|2.0.1";
  private static final String IPS_COMPOSITION_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips|2.0.1";
  private static final String IPS_BUNDLE_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips|2.0.1";
  private static final Duration VALIDATION_STARTUP_WARMUP_LIMIT = Duration.ofMinutes(2);
  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4Cached();
  private static final Object VALIDATOR_LOCK = new Object();
  private static final IValidationSupport VALIDATION_SUPPORT = createValidationSupport();
  private static final FhirValidator VALIDATOR = createValidator();
  private static final BoundedFhirValidationExecutor VALIDATION_EXECUTOR =
      new BoundedFhirValidationExecutor(16);

  static {
    warmValidationProfilesAtStartup();
  }

  private final FhirPayloadLimits limits;
  private final JsonFactory jsonFactory;
  private final BoundedFhirValidationExecutor validationExecutor;

  private FhirR4PayloadParser(FhirPayloadLimits limits) {
    this(limits, VALIDATION_EXECUTOR);
  }

  FhirR4PayloadParser(FhirPayloadLimits limits, BoundedFhirValidationExecutor validationExecutor) {
    this.limits = limits;
    this.validationExecutor = validationExecutor;
    this.jsonFactory =
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(limits.maxNestingDepth()).build())
            .build();
  }

  private static IValidationSupport createValidationSupport() {
    NpmPackageValidationSupport curatedProfileSupport =
        new NpmPackageValidationSupport(FHIR_CONTEXT);
    try {
      curatedProfileSupport.loadPackageFromClasspath(MCSD_PROFILE_PACKAGE);
      curatedProfileSupport.loadPackageFromClasspath(IPS_EXTENSION_PACKAGE);
      curatedProfileSupport.loadPackageFromClasspath(IPS_PROFILE_PACKAGE);
    } catch (IOException | RuntimeException unavailablePackage) {
      throw new IllegalStateException(
          "Pinned local FHIR profile packages could not be loaded", unavailablePackage);
    }
    ValidationSupportChain support =
        new ValidationSupportChain(
            curatedProfileSupport,
            new DefaultProfileValidationSupport(FHIR_CONTEXT),
            new InMemoryTerminologyServerValidationSupport(FHIR_CONTEXT));
    verifyPinnedProfile(
        support,
        "https://profiles.ihe.net/ITI/mCSD/StructureDefinition/IHE.mCSD.Organization",
        "4.0.0");
    verifyPinnedProfile(
        support, "http://hl7.org/fhir/uv/ips/StructureDefinition/Patient-uv-ips", "2.0.1");
    verifyPinnedProfile(
        support, "http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips", "2.0.1");
    return new R4CoreVersionedCanonicalCompatibilitySupport(FHIR_CONTEXT, support);
  }

  private static void verifyPinnedProfile(
      IValidationSupport support, String canonical, String version) {
    IBaseResource resource = support.fetchStructureDefinition(canonical);
    if (!(resource instanceof StructureDefinition structureDefinition)
        || !version.equals(structureDefinition.getVersion())) {
      throw new IllegalStateException(
          "A pinned local FHIR profile is unavailable or has the wrong version: " + canonical);
    }
  }

  private static FhirValidator createValidator() {
    FhirValidator validator = FHIR_CONTEXT.newValidator();
    validator.registerValidatorModule(new FhirInstanceValidator(VALIDATION_SUPPORT));
    return validator;
  }

  private static void warmValidationProfilesAtStartup() {
    try {
      VALIDATION_EXECUTOR.execute(
          new FhirProfileStartupWarmup(
              FHIR_CONTEXT,
              VALIDATION_SUPPORT,
              VALIDATOR,
              VALIDATOR_LOCK,
              MCSD_ORGANIZATION_PROFILE,
              IPS_PATIENT_PROFILE,
              IPS_COMPOSITION_PROFILE,
              IPS_BUNDLE_PROFILE),
          VALIDATION_STARTUP_WARMUP_LIMIT);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw startupWarmupFailure(exception);
    } catch (ExecutionException | TimeoutException | RejectedExecutionException exception) {
      throw startupWarmupFailure(exception);
    }
  }

  private static ExceptionInInitializerError startupWarmupFailure(Exception cause) {
    return new ExceptionInInitializerError(
        new IllegalStateException(
            "Pinned local FHIR R4 profile validation did not initialize within 2 minutes", cause));
  }

  public static FhirR4PayloadParser withCoreProfiles(FhirPayloadLimits limits) {
    if (limits == null) {
      throw new IllegalArgumentException("FHIR payload limits are required");
    }
    return new FhirR4PayloadParser(limits);
  }

  public FhirR4ParsedResource parse(
      byte[] payload, String contentType, FhirProfileContext profileContext) {
    long startedAt = System.nanoTime();
    requireSupportedMediaType(contentType);
    if (profileContext == null || !SUPPORTED_RELEASE.equals(profileContext.release())) {
      throw new FhirBoundaryException(FhirBoundaryFailure.UNSUPPORTED_FHIR_RELEASE);
    }
    if (payload == null || payload.length == 0) {
      throw new FhirBoundaryException(FhirBoundaryFailure.MALFORMED_JSON);
    }
    if (payload.length > limits.maxPayloadBytes()) {
      throw new FhirBoundaryException(FhirBoundaryFailure.LIMIT_EXCEEDED);
    }

    int resourceCount = countResources(payload, startedAt);
    if (resourceCount < 1) {
      throw new FhirBoundaryException(FhirBoundaryFailure.MALFORMED_JSON);
    }
    if (resourceCount > limits.maxResources()) {
      throw new FhirBoundaryException(FhirBoundaryFailure.LIMIT_EXCEEDED);
    }
    enforceProcessingBudget(startedAt);

    String json = decodeUtf8(payload);
    enforceProcessingBudget(startedAt);
    FhirR4ParsedResource parsed;
    try {
      parsed =
          validationExecutor.execute(
              () -> parseAndValidate(json, profileContext), remainingProcessingTime(startedAt));
    } catch (TimeoutException exception) {
      throw new FhirBoundaryException(FhirBoundaryFailure.VALIDATION_TIMED_OUT);
    } catch (RejectedExecutionException exception) {
      throw new FhirBoundaryException(FhirBoundaryFailure.VALIDATION_OVERLOADED);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new FhirBoundaryException(FhirBoundaryFailure.VALIDATION_TIMED_OUT);
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof FhirBoundaryException boundaryException) {
        throw boundaryException;
      }
      throw new FhirBoundaryException(FhirBoundaryFailure.PROFILE_VALIDATION_FAILED);
    }
    enforceProcessingBudget(startedAt);
    return parsed;
  }

  private static FhirR4ParsedResource parseAndValidate(
      String json, FhirProfileContext profileContext) {
    IBaseResource resource;
    try {
      IParser parser = FHIR_CONTEXT.newJsonParser();
      parser.setParserErrorHandler(new StrictErrorHandler());
      resource = parser.parseResource(json);
    } catch (RuntimeException exception) {
      throw new FhirBoundaryException(FhirBoundaryFailure.MALFORMED_JSON);
    }

    String profileUri = profileContext.requiredProfileUri();
    Set<String> profilesToValidate = new LinkedHashSet<>();
    profilesToValidate.add(profileUri);
    try {
      resource
          .getMeta()
          .getProfile()
          .forEach(
              declaredProfile -> {
                String declaredUri = declaredProfile.getValue();
                if (declaredUri == null
                    || !profileContext.allowedProfileUris().contains(declaredUri)) {
                  throw new FhirBoundaryException(FhirBoundaryFailure.UNSUPPORTED_PROFILE);
                }
                profilesToValidate.add(declaredUri);
              });
    } catch (FhirBoundaryException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new FhirBoundaryException(FhirBoundaryFailure.UNSUPPORTED_PROFILE);
    }

    ValidationResult validationResult =
        validateAgainstConfiguredProfiles(resource, profilesToValidate);
    if (!validationResult.isSuccessful()) {
      throw new FhirBoundaryException(FhirBoundaryFailure.PROFILE_VALIDATION_FAILED);
    }
    return new FhirR4ParsedResource(
        resource,
        SUPPORTED_RELEASE,
        profileUri,
        FhirTerminologyAssessment.LOCAL_TERMINOLOGY_COVERAGE_INDETERMINATE);
  }

  private static ValidationResult validateAgainstConfiguredProfiles(
      IBaseResource resource, Set<String> profilesToValidate) {
    synchronized (VALIDATOR_LOCK) {
      for (String profile : profilesToValidate) {
        if (!isConfiguredProfileAvailable(profile)) {
          throw new FhirBoundaryException(FhirBoundaryFailure.UNSUPPORTED_PROFILE);
        }
      }
      ValidationOptions validationOptions = new ValidationOptions();
      profilesToValidate.forEach(validationOptions::addProfile);
      try {
        return VALIDATOR.validateWithResult(resource, validationOptions);
      } catch (RuntimeException exception) {
        throw new FhirBoundaryException(FhirBoundaryFailure.PROFILE_VALIDATION_FAILED);
      }
    }
  }

  private static boolean isConfiguredProfileAvailable(String canonical) {
    int versionSeparator = canonical.lastIndexOf('|');
    String profileUrl = versionSeparator < 0 ? canonical : canonical.substring(0, versionSeparator);
    String requestedVersion =
        versionSeparator < 0 ? null : canonical.substring(versionSeparator + 1);
    IBaseResource resource = VALIDATION_SUPPORT.fetchStructureDefinition(profileUrl);
    return resource instanceof StructureDefinition structureDefinition
        && (requestedVersion == null || requestedVersion.equals(structureDefinition.getVersion()));
  }

  private int countResources(byte[] payload, long startedAt) {
    int resourceCount = 0;
    int collectionItemCount = 0;
    int tokenCount = 0;
    boolean rootClosed = false;
    boolean rootResourceTypeSeen = false;
    boolean pendingResourceType = false;
    boolean pendingRootResourceType = false;
    Deque<JsonContainer> containers = new ArrayDeque<>();
    try (JsonParser parser = jsonFactory.createParser(payload)) {
      JsonToken token = parser.nextToken();
      if (token != JsonToken.START_OBJECT) {
        throw new FhirBoundaryException(FhirBoundaryFailure.MALFORMED_JSON);
      }
      containers.push(JsonContainer.object());

      while ((token = parser.nextToken()) != null) {
        if (rootClosed) {
          throw new FhirBoundaryException(FhirBoundaryFailure.MALFORMED_JSON);
        }

        if (pendingResourceType) {
          if (token != JsonToken.VALUE_STRING) {
            throw new FhirBoundaryException(FhirBoundaryFailure.MALFORMED_JSON);
          }
          if (pendingRootResourceType) {
            rootResourceTypeSeen = true;
          }
          pendingResourceType = false;
          pendingRootResourceType = false;
        }

        if (token == JsonToken.PROPERTY_NAME) {
          JsonContainer currentContainer = containers.peek();
          if (currentContainer == null
              || currentContainer.array()
              || !currentContainer.propertyNames().add(parser.currentName())) {
            throw new FhirBoundaryException(FhirBoundaryFailure.MALFORMED_JSON);
          }
          if ("resourceType".equals(parser.currentName())) {
            resourceCount++;
            if (resourceCount > limits.maxResources()) {
              throw new FhirBoundaryException(FhirBoundaryFailure.LIMIT_EXCEEDED);
            }
            pendingResourceType = true;
            pendingRootResourceType = containers.size() == 1;
          }
        } else if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
          if (!containers.isEmpty() && containers.peek().array()) {
            collectionItemCount++;
            enforceCollectionLimit(collectionItemCount);
          }
          containers.push(
              token == JsonToken.START_ARRAY
                  ? JsonContainer.arrayContainer()
                  : JsonContainer.object());
        } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
          if (containers.isEmpty()) {
            throw new FhirBoundaryException(FhirBoundaryFailure.MALFORMED_JSON);
          }
          containers.pop();
          if (containers.isEmpty()) {
            rootClosed = true;
          }
        } else if (!containers.isEmpty() && containers.peek().array()) {
          collectionItemCount++;
          enforceCollectionLimit(collectionItemCount);
        }

        tokenCount++;
        if ((tokenCount & 0x3f) == 0) {
          enforceProcessingBudget(startedAt);
        }
      }

      if (!rootClosed || !rootResourceTypeSeen || pendingResourceType) {
        throw new FhirBoundaryException(FhirBoundaryFailure.MALFORMED_JSON);
      }
    } catch (FhirBoundaryException exception) {
      throw exception;
    } catch (StreamConstraintsException exception) {
      throw new FhirBoundaryException(FhirBoundaryFailure.LIMIT_EXCEEDED);
    } catch (JacksonException exception) {
      throw new FhirBoundaryException(FhirBoundaryFailure.MALFORMED_JSON);
    }
    return resourceCount;
  }

  private void enforceCollectionLimit(int collectionItemCount) {
    if (collectionItemCount > limits.maxCollectionItems()) {
      throw new FhirBoundaryException(FhirBoundaryFailure.LIMIT_EXCEEDED);
    }
  }

  private static final class JsonContainer {
    private final boolean array;
    private final Set<String> propertyNames;

    private JsonContainer(boolean array) {
      this.array = array;
      this.propertyNames = array ? Set.of() : new HashSet<>();
    }

    private static JsonContainer object() {
      return new JsonContainer(false);
    }

    private static JsonContainer arrayContainer() {
      return new JsonContainer(true);
    }

    private boolean array() {
      return array;
    }

    private Set<String> propertyNames() {
      return propertyNames;
    }
  }

  private static String decodeUtf8(byte[] payload) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(payload))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new FhirBoundaryException(FhirBoundaryFailure.MALFORMED_JSON);
    }
  }

  private static void requireSupportedMediaType(String contentType) {
    if (contentType == null) {
      throw new FhirBoundaryException(FhirBoundaryFailure.UNSUPPORTED_MEDIA_TYPE);
    }
    String[] parts = contentType.split(";", -1);
    if (!SUPPORTED_MEDIA_TYPE.equalsIgnoreCase(parts[0].trim())) {
      throw new FhirBoundaryException(FhirBoundaryFailure.UNSUPPORTED_MEDIA_TYPE);
    }
    boolean charsetSeen = false;
    boolean versionSeen = false;
    for (int index = 1; index < parts.length; index++) {
      String[] parameter = parts[index].trim().split("=", 2);
      if (parameter.length != 2) {
        throw new FhirBoundaryException(FhirBoundaryFailure.UNSUPPORTED_MEDIA_TYPE);
      }
      String name = parameter[0].trim().toLowerCase(Locale.ROOT);
      String value = parameter[1].trim().replace("\"", "").toLowerCase(Locale.ROOT);
      if ("charset".equals(name) && !charsetSeen && "utf-8".equals(value)) {
        charsetSeen = true;
      } else if ("fhirversion".equals(name) && !versionSeen && "4.0".equals(value)) {
        versionSeen = true;
      } else {
        throw new FhirBoundaryException(FhirBoundaryFailure.UNSUPPORTED_MEDIA_TYPE);
      }
    }
  }

  private void enforceProcessingBudget(long startedAt) {
    if (System.nanoTime() - startedAt > limits.maxProcessingTime().toNanos()) {
      throw new FhirBoundaryException(FhirBoundaryFailure.VALIDATION_TIMED_OUT);
    }
  }

  private Duration remainingProcessingTime(long startedAt) {
    long remainingNanos = limits.maxProcessingTime().toNanos() - (System.nanoTime() - startedAt);
    if (remainingNanos <= 0) {
      throw new FhirBoundaryException(FhirBoundaryFailure.VALIDATION_TIMED_OUT);
    }
    return Duration.ofNanos(remainingNanos);
  }
}

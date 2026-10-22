package org.ayuvahak.platform.operatorsupport.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.ayuvahak.platform.configuration.OperatorSupportProperties;

/**
 * Stable non-secret identity for the exact external operator-support profile that was evaluated.
 */
public final class OperatorSupportEvaluationProfile {

  public static final String PURPOSE_VERSION = "operator-support-purpose-v1";
  public static final String PROMPT_TEMPLATE_VERSION = "operator-support-prompt-v1";
  public static final String INPUT_POLICY_VERSION = "operator-support-input-policy-v1";

  private OperatorSupportEvaluationProfile() {}

  public static String fingerprint(
      OperatorSupportProperties properties, OperatorSupportCorpus corpus) {
    Objects.requireNonNull(properties, "operator support properties");
    Objects.requireNonNull(corpus, "approved corpus");
    String canonical =
        String.join(
            "\n",
            OperatorSupportService.EVALUATION_CONTRACT_VERSION,
            OperatorSupportService.CAPABILITY_VERSION,
            PURPOSE_VERSION,
            PROMPT_TEMPLATE_VERSION,
            INPUT_POLICY_VERSION,
            OpenAiCompatibleOperatorSupportProvider.evaluationTemplate(),
            properties.getProvider().name(),
            Objects.toString(properties.getEndpoint(), ""),
            properties.getModel(),
            properties.getProviderProfileRevision(),
            properties.getSecretReference(),
            properties.getAllowedHosts().stream()
                .map(host -> host.toLowerCase(java.util.Locale.ROOT))
                .sorted()
                .toList()
                .toString(),
            properties.getTimeout().toString(),
            Integer.toString(properties.getMaxResponseBytes()),
            corpus.version(),
            corpus.sourcesForAllTopics().stream()
                .map(source -> source.sourceId() + ":" + source.contentHash())
                .sorted()
                .reduce("", (left, right) -> left + "\n" + right));
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }
}

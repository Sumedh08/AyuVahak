package org.ayuvahak.capacity.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** One explicitly ranked source and the conditions under which a lower tier may be used. */
public record CapacitySourcePriority(
    String sourceCode,
    int priority,
    boolean fallbackWhenHigherPriorityAbsent,
    boolean fallbackWhenHigherPriorityStale) {

  private static final Pattern SOURCE_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9._:-]{0,127}");

  public CapacitySourcePriority {
    Objects.requireNonNull(sourceCode, "source code");
    if (!SOURCE_CODE.matcher(sourceCode).matches()) {
      throw new IllegalArgumentException("source code has an invalid format");
    }
    if (priority < 0) {
      throw new IllegalArgumentException("source priority cannot be negative");
    }
  }
}

package org.ayuvahak.platform.event;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A producer-owned internal event name with an explicit major contract version. */
public record EventType(String context, String fact, int majorVersion) {

  private static final String SEGMENT = "[a-z][a-z0-9]*(?:-[a-z0-9]+)*";
  private static final Pattern EVENT_NAME =
      Pattern.compile("(" + SEGMENT + ")\\.(" + SEGMENT + ")\\.v([1-9][0-9]*)");

  public EventType {
    requireSegment("context", context);
    requireSegment("fact", fact);
    if (majorVersion < 1) {
      throw new IllegalArgumentException("event major version must be positive");
    }
  }

  public static EventType parse(String name) {
    Matcher matcher = EVENT_NAME.matcher(Objects.requireNonNull(name, "name"));
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "event name must follow <context>.<fact>.v<positive-major>");
    }
    try {
      return new EventType(matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("event major version is outside the supported range");
    }
  }

  private static void requireSegment(String name, String value) {
    if (value == null || !value.matches(SEGMENT)) {
      throw new IllegalArgumentException(name + " must be a lower-kebab identifier");
    }
  }

  @Override
  public String toString() {
    return context + "." + fact + ".v" + majorVersion;
  }
}

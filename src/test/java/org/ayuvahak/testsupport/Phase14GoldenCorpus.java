package org.ayuvahak.testsupport;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Loads the versioned, synthetic Phase 14 deterministic-owner golden expectations. */
public final class Phase14GoldenCorpus {

  private static final String RESOURCE = "/golden/phase14/deterministic-owner-corpus-v1.json";
  private static final String JSON = load();

  private Phase14GoldenCorpus() {}

  public static String string(String path) {
    Object value = JsonPath.read(JSON, path);
    if (value instanceof String text) {
      return text;
    }
    throw new IllegalArgumentException("Golden corpus path is not a string: " + path);
  }

  public static String stringOrNull(String path) {
    Object value = JsonPath.read(JSON, path);
    if (value == null || value instanceof String) {
      return (String) value;
    }
    throw new IllegalArgumentException("Golden corpus path is not a string or null: " + path);
  }

  public static int integer(String path) {
    Object value = JsonPath.read(JSON, path);
    if (value instanceof Number number) {
      return number.intValue();
    }
    throw new IllegalArgumentException("Golden corpus path is not an integer: " + path);
  }

  public static Integer nullableInteger(String path) {
    Object value = JsonPath.read(JSON, path);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    throw new IllegalArgumentException("Golden corpus path is not an integer or null: " + path);
  }

  public static boolean bool(String path) {
    Object value = JsonPath.read(JSON, path);
    if (value instanceof Boolean result) {
      return result;
    }
    throw new IllegalArgumentException("Golden corpus path is not a boolean: " + path);
  }

  public static List<String> strings(String path) {
    Object value = JsonPath.read(JSON, path);
    if (!(value instanceof List<?> items)) {
      throw new IllegalArgumentException("Golden corpus path is not an array: " + path);
    }
    return items.stream()
        .map(
            item -> {
              if (item instanceof String text) {
                return text;
              }
              throw new IllegalArgumentException("Golden corpus array contains a non-string: " + path);
            })
        .toList();
  }

  public static List<Integer> integers(String path) {
    Object value = JsonPath.read(JSON, path);
    if (!(value instanceof List<?> items)) {
      throw new IllegalArgumentException("Golden corpus path is not an array: " + path);
    }
    return items.stream()
        .map(
            item -> {
              if (item instanceof Number number) {
                return number.intValue();
              }
              throw new IllegalArgumentException("Golden corpus array contains a non-integer: " + path);
            })
        .toList();
  }

  private static String load() {
    try (InputStream stream = Phase14GoldenCorpus.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Missing versioned golden corpus resource " + RESOURCE);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }
}

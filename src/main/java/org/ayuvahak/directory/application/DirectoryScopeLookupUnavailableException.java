package org.ayuvahak.directory.application;

/** The Directory owner could not validate referenced organization or facility scope facts. */
public final class DirectoryScopeLookupUnavailableException extends RuntimeException {

  public DirectoryScopeLookupUnavailableException(Throwable cause) {
    super("Directory scope references could not be validated safely.", cause);
  }
}

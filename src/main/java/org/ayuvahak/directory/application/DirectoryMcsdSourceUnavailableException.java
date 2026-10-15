package org.ayuvahak.directory.application;

/** Indicates a source read could not complete; callers must preserve the last committed cursor. */
public final class DirectoryMcsdSourceUnavailableException extends RuntimeException {

  public DirectoryMcsdSourceUnavailableException(String message) {
    super(message);
  }
}

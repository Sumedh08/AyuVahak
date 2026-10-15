package org.ayuvahak.directory.adapter.mcsd;

/** A source resource cannot be projected under the configured Directory profile. */
public final class DirectoryMcsdMappingException extends RuntimeException {

  public DirectoryMcsdMappingException(String safeMessage) {
    super(safeMessage);
  }
}

package org.ayuvahak.capacity.application;

/** Signals an optimistic-version mismatch or a conflicting initial definition. */
public final class CapacityVersionConflictException extends RuntimeException {

  public CapacityVersionConflictException() {
    super("The requested Capacity version is no longer current.");
  }
}

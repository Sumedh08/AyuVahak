package org.ayuvahak.platform.core;

/** Origin category for work; it is separate from the identity or authority of its actor. */
public enum SourceKind {
  INTERACTIVE_REQUEST,
  INTEGRATION,
  SCHEDULED_WORK,
  BACKGROUND_WORK,
  OTHER_GOVERNED
}

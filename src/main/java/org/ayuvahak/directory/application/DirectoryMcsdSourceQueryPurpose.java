package org.ayuvahak.directory.application;

/** Owner-declared reason for a bounded read from a configured mCSD source. */
public enum DirectoryMcsdSourceQueryPurpose {
  INITIAL_DISCOVERY("DIRECTORY_MCSD_DISCOVERY_READ"),
  INCREMENTAL_HISTORY("DIRECTORY_MCSD_HISTORY_READ");

  private final String actionCode;

  DirectoryMcsdSourceQueryPurpose(String actionCode) {
    this.actionCode = actionCode;
  }

  String actionCode() {
    return actionCode;
  }
}

package org.ayuvahak.platform.notification.application;

import java.io.IOException;

/** Narrow output seam for one bounded notification connection. */
public interface NotificationFeedSink {

  void sendHint(NotificationHintMessage hint) throws IOException;

  void sendRefreshRequired(String cursor) throws IOException;

  void sendHeartbeat() throws IOException;

  void complete();

  void onClose(Runnable callback);
}

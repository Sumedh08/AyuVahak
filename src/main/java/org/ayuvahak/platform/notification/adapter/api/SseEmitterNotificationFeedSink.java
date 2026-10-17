package org.ayuvahak.platform.notification.adapter.api;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ayuvahak.platform.notification.application.NotificationFeedSink;
import org.ayuvahak.platform.notification.application.NotificationHintMessage;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Spring MVC transport mapping for the application-owned hint contract. */
final class SseEmitterNotificationFeedSink implements NotificationFeedSink {

  private final SseEmitter emitter;
  private final AtomicBoolean closed = new AtomicBoolean();

  SseEmitterNotificationFeedSink(SseEmitter emitter) {
    this.emitter = Objects.requireNonNull(emitter, "emitter");
  }

  @Override
  public void sendHint(NotificationHintMessage hint) throws IOException {
    emitter.send(
        SseEmitter.event()
            .id(hint.cursor())
            .name(hint.notificationType())
            .data(hint, MediaType.APPLICATION_JSON));
  }

  @Override
  public void sendRefreshRequired(String cursor) throws IOException {
    emitter.send(
        SseEmitter.event()
            .id(cursor)
            .name("refresh-required")
            .data(new RefreshRequiredMessage(cursor), MediaType.APPLICATION_JSON));
  }

  @Override
  public void sendHeartbeat() throws IOException {
    emitter.send(SseEmitter.event().comment("keep-alive"));
  }

  @Override
  public void complete() {
    if (closed.compareAndSet(false, true)) {
      emitter.complete();
    }
  }

  @Override
  public void onClose(Runnable callback) {
    Objects.requireNonNull(callback, "callback");
    emitter.onCompletion(callback);
    emitter.onError(ignored -> callback.run());
    emitter.onTimeout(
        () -> {
          callback.run();
          complete();
        });
  }

  record RefreshRequiredMessage(String cursor) {}
}

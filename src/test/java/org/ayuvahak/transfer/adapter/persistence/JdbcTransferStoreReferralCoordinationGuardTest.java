package org.ayuvahak.transfer.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.ayuvahak.transfer.application.TransferStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTransferStoreReferralCoordinationGuardTest {

  @Test
  void failedUnlockAndAbortCloseTheEvictedPooledSession() throws Exception {
    assertPooledSessionReleased(true);
  }

  @Test
  void successfulAbortClosesTheEvictedPooledHandle() throws Exception {
    assertPooledSessionReleased(false);
  }

  @Test
  void lostLockAcquisitionResponseEvictsSessionThatMayAlreadyHoldTheLock() throws Exception {
    AtomicBoolean physicalConnectionClosed = new AtomicBoolean();
    AtomicBoolean sessionLockHeld = new AtomicBoolean();
    AtomicInteger physicalCloseCalls = new AtomicInteger();
    AtomicInteger abortCalls = new AtomicInteger();
    Connection physicalConnection =
        connectionProxy(
            physicalConnectionClosed, sessionLockHeld, physicalCloseCalls, abortCalls, false, true);
    HikariDataSource dataSource = pooledDataSource(physicalConnection);
    try (dataSource) {
      JdbcTransferStore store =
          new JdbcTransferStore(
              new JdbcTemplate(dataSource), dataSource, new CommandIdempotencyProperties());

      org.junit.jupiter.api.Assertions.assertThrows(
          DataAccessResourceFailureException.class,
          () -> store.acquireReferralCoordinationGuard(InternalId.generate()));

      assertTrue(sessionLockHeld.get());
      assertEquals(1, abortCalls.get());
      assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
      assertEquals(0, dataSource.getHikariPoolMXBean().getIdleConnections());
      assertTrue(physicalConnectionClosed.get());
      assertTrue(physicalCloseCalls.get() >= 1);
    }
  }

  private static void assertPooledSessionReleased(boolean abortFails) throws Exception {
    AtomicBoolean physicalConnectionClosed = new AtomicBoolean();
    AtomicBoolean sessionLockHeld = new AtomicBoolean();
    AtomicInteger physicalCloseCalls = new AtomicInteger();
    AtomicInteger abortCalls = new AtomicInteger();
    Connection physicalConnection =
        connectionProxy(
            physicalConnectionClosed,
            sessionLockHeld,
            physicalCloseCalls,
            abortCalls,
            abortFails,
            false);
    HikariDataSource dataSource = pooledDataSource(physicalConnection);
    try (dataSource) {
      JdbcTransferStore store =
          new JdbcTransferStore(
              new JdbcTemplate(dataSource), dataSource, new CommandIdempotencyProperties());

      TransferStore.ReferralCoordinationGuard guard =
          store.acquireReferralCoordinationGuard(InternalId.generate());
      guard.close();

      assertEquals(1, abortCalls.get());
      assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
      assertEquals(0, dataSource.getHikariPoolMXBean().getIdleConnections());
      assertTrue(physicalConnectionClosed.get());
      assertTrue(physicalCloseCalls.get() >= 1);
    }
  }

  private static HikariDataSource pooledDataSource(Connection connection) {
    HikariDataSource dataSource = new HikariDataSource();
    dataSource.setDataSource(
        proxy(
            DataSource.class,
            (methodName, arguments) ->
                methodName.equals("getConnection") ? connection : defaultValue(methodName)));
    dataSource.setMaximumPoolSize(1);
    dataSource.setMinimumIdle(0);
    dataSource.setConnectionTimeout(5_000);
    return dataSource;
  }

  private static Connection connectionProxy(
      AtomicBoolean physicalConnectionClosed,
      AtomicBoolean sessionLockHeld,
      AtomicInteger physicalCloseCalls,
      AtomicInteger abortCalls,
      boolean abortFails,
      boolean lockResponseLost) {
    return proxy(
        Connection.class,
        (methodName, arguments) -> {
          if (methodName.equals("createStatement")) {
            return proxy(
                Statement.class,
                (statementMethod, statementArguments) ->
                    statementMethod.equals("execute") ? false : null);
          }
          if (methodName.equals("prepareStatement")) {
            String sql = (String) arguments[0];
            if (sql.contains("pg_advisory_unlock")) {
              return proxy(
                  PreparedStatement.class,
                  (statementMethod, statementArguments) -> {
                    if (statementMethod.equals("executeQuery")) {
                      throw new SQLException("synthetic unlock failure");
                    }
                    return null;
                  });
            }
            if (sql.contains("pg_advisory_lock")) {
              return proxy(
                  PreparedStatement.class,
                  (statementMethod, statementArguments) -> {
                    if (statementMethod.equals("executeQuery")) {
                      if (lockResponseLost) {
                        sessionLockHeld.set(true);
                        throw new SQLException("synthetic lost lock response");
                      }
                      AtomicInteger calls = new AtomicInteger();
                      return proxy(
                          ResultSet.class,
                          (resultMethod, resultArguments) ->
                              resultMethod.equals("next") && calls.getAndIncrement() == 0);
                    }
                    return null;
                  });
            }
            return proxy(
                PreparedStatement.class,
                (statementMethod, statementArguments) -> {
                  if (statementMethod.equals("executeQuery")) {
                    AtomicInteger calls = new AtomicInteger();
                    return proxy(
                        ResultSet.class,
                        (resultMethod, resultArguments) ->
                            resultMethod.equals("next") && calls.getAndIncrement() == 0);
                  }
                  return null;
                });
          }
          if (methodName.equals("abort")) {
            abortCalls.incrementAndGet();
            if (abortFails) {
              throw new SQLException("synthetic abort failure");
            }
            physicalConnectionClosed.set(true);
            return null;
          }
          if (methodName.equals("close")) {
            physicalCloseCalls.incrementAndGet();
            physicalConnectionClosed.set(true);
            return null;
          }
          if (methodName.equals("isClosed")) {
            return physicalConnectionClosed.get();
          }
          if (methodName.equals("isValid")) {
            return !physicalConnectionClosed.get();
          }
          if (methodName.equals("getAutoCommit")) {
            return true;
          }
          if (methodName.equals("getTransactionIsolation")) {
            return Connection.TRANSACTION_READ_COMMITTED;
          }
          if (methodName.equals("isReadOnly")) {
            return false;
          }
          if (methodName.equals("getSchema")) {
            return "public";
          }
          if (methodName.equals("getNetworkTimeout")) {
            return 0;
          }
          return null;
        });
  }

  private static Object defaultValue(String methodName) {
    return switch (methodName) {
      case "getLoginTimeout" -> 0;
      case "isWrapperFor" -> false;
      default -> null;
    };
  }

  private static <T> T proxy(Class<T> type, Invocation invocation) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, arguments) ->
                invocation.invoke(
                    method.getName(), arguments == null ? new Object[0] : arguments)));
  }

  @FunctionalInterface
  private interface Invocation {
    Object invoke(String methodName, Object[] arguments) throws SQLException;
  }
}

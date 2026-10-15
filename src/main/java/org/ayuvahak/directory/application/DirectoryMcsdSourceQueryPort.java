package org.ayuvahak.directory.application;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/**
 * Directory-owned read boundary for mCSD ITI-90 discovery and optional ITI-91 history reads.
 * Implementations return already mapped Directory facts; secure network transport is supplied by
 * Phase 9 and must not be implemented by this contract. A source namespace is a logical selector,
 * never a URL or proof that a remote destination is trusted. A future adapter resolves it only
 * through administrator-controlled partner configuration.
 */
public interface DirectoryMcsdSourceQueryPort {

  DirectoryMcsdSourcePage search(SearchRequest request);

  DirectoryMcsdSourcePage history(HistoryRequest request);

  record SearchRequest(
      ExecutionContext executionContext,
      DirectoryMcsdSourceQueryPurpose purpose,
      IssuerId sourceNamespace,
      Set<DirectoryMcsdResourceType> resourceTypes,
      Optional<String> pageToken,
      int pageSize) {

    public SearchRequest {
      Objects.requireNonNull(executionContext, "execution context");
      Objects.requireNonNull(purpose, "purpose");
      Objects.requireNonNull(sourceNamespace, "sourceNamespace");
      if (purpose != DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY) {
        throw new IllegalArgumentException("search requires initial discovery purpose");
      }
      resourceTypes = Set.copyOf(Objects.requireNonNull(resourceTypes, "resourceTypes"));
      if (resourceTypes.isEmpty()) {
        throw new IllegalArgumentException("mCSD discovery must request a bounded resource type");
      }
      pageToken = Objects.requireNonNull(pageToken, "pageToken");
      pageToken.ifPresent(DirectoryMcsdSourceQueryPort::requireOpaqueToken);
      requirePageSize(pageSize);
    }
  }

  record HistoryRequest(
      ExecutionContext executionContext,
      DirectoryMcsdSourceQueryPurpose purpose,
      IssuerId sourceNamespace,
      String sinceCursor,
      Optional<String> pageToken,
      int pageSize) {

    public HistoryRequest {
      Objects.requireNonNull(executionContext, "execution context");
      Objects.requireNonNull(purpose, "purpose");
      if (purpose != DirectoryMcsdSourceQueryPurpose.INCREMENTAL_HISTORY) {
        throw new IllegalArgumentException("history requires incremental history purpose");
      }
      Objects.requireNonNull(sourceNamespace, "sourceNamespace");
      requireOpaqueToken(sinceCursor);
      pageToken = Objects.requireNonNull(pageToken, "pageToken");
      pageToken.ifPresent(DirectoryMcsdSourceQueryPort::requireOpaqueToken);
      requirePageSize(pageSize);
    }
  }

  private static void requirePageSize(int value) {
    if (value < 1 || value > 200) {
      throw new IllegalArgumentException("mCSD page size must be from 1 to 200");
    }
  }

  private static void requireOpaqueToken(String value) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > 2048
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("mCSD cursor or page token is invalid");
    }
  }
}

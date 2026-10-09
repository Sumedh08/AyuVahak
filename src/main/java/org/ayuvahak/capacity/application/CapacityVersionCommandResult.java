package org.ayuvahak.capacity.application;

import org.ayuvahak.platform.core.InternalId;

/** HTTP-neutral result of creating or replaying a versioned Capacity configuration command. */
public record CapacityVersionCommandResult(
    InternalId id, String code, long version, int responseStatus, boolean replayed) {}

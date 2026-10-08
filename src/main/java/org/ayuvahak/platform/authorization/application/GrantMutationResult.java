package org.ayuvahak.platform.authorization.application;

import org.ayuvahak.platform.core.InternalId;

public record GrantMutationResult(int status, InternalId grantId, boolean replayed) {}

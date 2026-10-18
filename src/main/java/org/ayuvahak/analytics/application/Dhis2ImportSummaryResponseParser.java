package org.ayuvahak.analytics.application;

import java.util.Optional;

/** Parses the configured one-value, synchronous DHIS2 import response profile. */
public interface Dhis2ImportSummaryResponseParser {

  Dhis2ImportSummary parse(int status, Optional<String> contentType, byte[] body);
}

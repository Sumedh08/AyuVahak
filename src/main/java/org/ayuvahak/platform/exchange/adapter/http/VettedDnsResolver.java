package org.ayuvahak.platform.exchange.adapter.http;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.hc.client5.http.DnsResolver;
import org.ayuvahak.platform.exchange.domain.PartnerKey;

/** Resolves one configured partner host and returns only the address set vetted for its port. */
final class VettedDnsResolver implements DnsResolver {

  private final String configuredHost;
  private final PartnerKey partnerKey;
  private final int configuredPort;
  private final Duration dnsTimeout;
  private final OutboundAddressPolicy addressPolicy;
  private final AddressLookup addressLookup;
  private volatile List<InetAddress> vettedAddresses;

  VettedDnsResolver(
      String configuredHost,
      PartnerKey partnerKey,
      int configuredPort,
      Duration dnsTimeout,
      OutboundAddressPolicy addressPolicy,
      AddressLookup addressLookup) {
    this.configuredHost = normalizeHost(configuredHost);
    this.partnerKey = Objects.requireNonNull(partnerKey, "partnerKey");
    this.configuredPort = configuredPort;
    this.dnsTimeout = Objects.requireNonNull(dnsTimeout, "dnsTimeout");
    if (dnsTimeout.isZero() || dnsTimeout.isNegative()) {
      throw new IllegalArgumentException("DNS timeout must be positive");
    }
    this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
    this.addressLookup = Objects.requireNonNull(addressLookup, "addressLookup");
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    if (!configuredHost.equals(normalizeHost(host))) {
      throw new UnknownHostException("Outbound DNS request was outside the configured partner.");
    }
    try {
      addressPolicy.validatePort(partnerKey, configuredPort);
    } catch (UnsafeOutboundDestinationException exception) {
      throw new DnsResolutionFailure(DnsResolutionFailure.Reason.DESTINATION_REJECTED);
    }
    List<InetAddress> addresses = vettedAddresses;
    if (addresses == null) {
      synchronized (this) {
        addresses = vettedAddresses;
        if (addresses == null) {
          addresses = resolveAndValidate();
          vettedAddresses = addresses;
        }
      }
    }
    return addresses.toArray(InetAddress[]::new);
  }

  @Override
  public String resolveCanonicalHostname(String host) throws UnknownHostException {
    if (!configuredHost.equals(normalizeHost(host))) {
      throw new UnknownHostException("Outbound DNS request was outside the configured partner.");
    }
    return configuredHost;
  }

  boolean isVettedPeer(InetAddress peer) {
    if (peer == null) {
      return false;
    }
    List<InetAddress> addresses = vettedAddresses;
    return addresses != null
        && addresses.stream()
            .anyMatch(address -> Arrays.equals(address.getAddress(), peer.getAddress()));
  }

  private List<InetAddress> resolveAndValidate() throws UnknownHostException {
    InetAddress[] answers;
    try {
      answers = addressLookup.lookup(configuredHost, dnsTimeout);
    } catch (DnsResolutionFailure failure) {
      throw failure;
    } catch (UnknownHostException exception) {
      throw new UnknownHostException("Configured partner DNS resolution failed.");
    } catch (RuntimeException exception) {
      UnknownHostException failure =
          new UnknownHostException("Configured partner DNS resolution failed.");
      failure.initCause(exception);
      throw failure;
    }
    List<InetAddress> answerList = answers == null ? null : List.of(answers.clone());
    try {
      return addressPolicy.validate(partnerKey, configuredPort, answerList);
    } catch (UnsafeOutboundDestinationException exception) {
      throw new DnsResolutionFailure(DnsResolutionFailure.Reason.DESTINATION_REJECTED);
    }
  }

  private static String normalizeHost(String host) {
    if (host == null || host.isBlank() || !host.equals(host.strip())) {
      throw new IllegalArgumentException("Configured partner hostname is invalid.");
    }
    String unbracketed =
        host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    if (unbracketed.indexOf(':') >= 0) {
      return unbracketed.toLowerCase(Locale.ROOT);
    }
    try {
      String ascii = IDN.toASCII(unbracketed, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
      if (ascii.isBlank() || ascii.endsWith(".")) {
        throw new IllegalArgumentException("Configured partner hostname is invalid.");
      }
      return ascii;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Configured partner hostname is invalid.");
    }
  }

  @FunctionalInterface
  interface AddressLookup {
    InetAddress[] lookup(String hostname, Duration timeout) throws UnknownHostException;
  }
}

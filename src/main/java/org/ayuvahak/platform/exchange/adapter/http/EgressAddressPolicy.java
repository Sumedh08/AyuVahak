package org.ayuvahak.platform.exchange.adapter.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.ayuvahak.platform.exchange.domain.PartnerKey;

/** Fails closed unless every DNS answer is a permitted unicast destination. */
final class EgressAddressPolicy implements OutboundAddressPolicy {

  private static final Set<CidrRange> HARD_DENY =
      ranges(
          "0.0.0.0/8",
          "127.0.0.0/8",
          "169.254.0.0/16",
          "169.254.170.2/32",
          "168.63.129.16/32",
          "100.100.100.200/32",
          "224.0.0.0/4",
          "240.0.0.0/4",
          "255.255.255.255/32",
          "192.0.0.0/24",
          "192.0.2.0/24",
          "192.88.99.0/24",
          "198.18.0.0/15",
          "198.51.100.0/24",
          "203.0.113.0/24",
          "64:ff9b::/96",
          "::/128",
          "::1/128",
          "64:ff9b:1::/48",
          "100::/64",
          "100:0:0:1::/64",
          "2001::/23",
          "2001::/32",
          "2001:2::/48",
          "2001:10::/28",
          "2001:db8::/32",
          "2002::/16",
          "3fff::/20",
          "5f00::/16",
          "fd00:ec2::254/128",
          "fec0::/10",
          "fe80::/10",
          "ff00::/8");

  private static final Set<CidrRange> PRIVATE =
      ranges("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "fc00::/7");

  private static final CidrRange IPV6_GLOBAL_UNICAST = CidrRange.parse("2000::/3");

  private final Set<Integer> allowedPorts;
  private final Map<PartnerKey, List<CidrRange>> allowedPrivateNetworksByPartner;

  EgressAddressPolicy(
      Set<Integer> allowedPorts,
      Map<String, ? extends Collection<String>> allowedPrivateNetworksByPartner) {
    this.allowedPorts = Set.copyOf(Objects.requireNonNull(allowedPorts, "allowedPorts"));
    if (this.allowedPorts.isEmpty()
        || this.allowedPorts.stream().anyMatch(port -> port == null || port < 1 || port > 65_535)) {
      throw new IllegalArgumentException("at least one valid outbound port is required");
    }
    this.allowedPrivateNetworksByPartner =
        Objects.requireNonNull(allowedPrivateNetworksByPartner, "allowedPrivateNetworksByPartner")
            .entrySet()
            .stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    entry -> new PartnerKey(entry.getKey()),
                    entry -> {
                      List<CidrRange> networks =
                          Objects.requireNonNull(entry.getValue(), "partner network list").stream()
                              .map(CidrRange::parse)
                              .toList();
                      if (new HashSet<>(networks).size() != networks.size()) {
                        throw new IllegalArgumentException(
                            "partner private networks must not contain duplicates");
                      }
                      return networks;
                    }));
  }

  @Override
  public void validatePort(PartnerKey partnerKey, int port) {
    Objects.requireNonNull(partnerKey, "partnerKey");
    if (!allowedPorts.contains(port)) {
      throw new UnsafeOutboundDestinationException(
          UnsafeOutboundDestinationException.Reason.PORT_NOT_ALLOWED);
    }
  }

  @Override
  public List<InetAddress> validate(PartnerKey partnerKey, int port, List<InetAddress> answers) {
    Objects.requireNonNull(partnerKey, "partnerKey");
    validatePort(partnerKey, port);
    if (answers == null || answers.isEmpty() || answers.stream().anyMatch(Objects::isNull)) {
      throw new UnsafeOutboundDestinationException(
          UnsafeOutboundDestinationException.Reason.NO_ADDRESSES);
    }

    List<InetAddress> normalized = new ArrayList<>();
    for (InetAddress answer : answers) {
      InetAddress address = normalizeMappedAddress(answer);
      if (matchesAny(HARD_DENY, address)) {
        throw new UnsafeOutboundDestinationException(
            UnsafeOutboundDestinationException.Reason.FORBIDDEN_ADDRESS);
      }
      if (matchesAny(PRIVATE, address)) {
        List<CidrRange> allowedPrivateNetworks =
            allowedPrivateNetworksByPartner.getOrDefault(partnerKey, List.of());
        if (!matchesAny(allowedPrivateNetworks, address)) {
          throw new UnsafeOutboundDestinationException(
              UnsafeOutboundDestinationException.Reason.PRIVATE_ADDRESS_NOT_ALLOWED);
        }
      } else if (!isGlobalUnicast(address)) {
        throw new UnsafeOutboundDestinationException(
            UnsafeOutboundDestinationException.Reason.FORBIDDEN_ADDRESS);
      }
      normalized.add(address);
    }
    return normalized.stream().distinct().toList();
  }

  private static boolean isGlobalUnicast(InetAddress address) {
    if (address instanceof Inet4Address) {
      return !matchesAny(HARD_DENY, address);
    }
    return address instanceof Inet6Address && IPV6_GLOBAL_UNICAST.contains(address);
  }

  private static InetAddress normalizeMappedAddress(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length == 16) {
      boolean mapped = true;
      for (int index = 0; index < 10; index++) {
        mapped &= bytes[index] == 0;
      }
      mapped &= bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
      if (mapped) {
        try {
          return InetAddress.getByAddress(java.util.Arrays.copyOfRange(bytes, 12, 16));
        } catch (UnknownHostException impossible) {
          throw new IllegalStateException("four-byte address must be valid", impossible);
        }
      }
    }
    return address;
  }

  private static boolean matchesAny(Collection<CidrRange> ranges, InetAddress address) {
    return ranges.stream().anyMatch(range -> range.contains(address));
  }

  private static Set<CidrRange> ranges(String... values) {
    return java.util.Arrays.stream(values)
        .map(CidrRange::parse)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private record CidrRange(byte[] network, int prefixLength) {

    private CidrRange {
      network = network.clone();
      int bits = network.length * Byte.SIZE;
      if (prefixLength < 0 || prefixLength > bits) {
        throw new IllegalArgumentException("network prefix is outside the address width");
      }
      if (!hasZeroHostBits(network, prefixLength)) {
        throw new IllegalArgumentException("network must use a canonical address boundary");
      }
    }

    static CidrRange parse(String value) {
      if (value == null || value.isBlank() || !value.equals(value.strip())) {
        throw new IllegalArgumentException("network must be a trimmed CIDR literal");
      }
      String[] parts = value.split("/", -1);
      if (parts.length != 2 || parts[1].isEmpty() || !parts[1].matches("[0-9]{1,3}")) {
        throw new IllegalArgumentException("network must use address/prefix notation");
      }
      byte[] address = parseAddress(parts[0]);
      int prefix = Integer.parseInt(parts[1]);
      return new CidrRange(address, prefix);
    }

    boolean contains(InetAddress address) {
      byte[] candidate = address.getAddress();
      if (candidate.length != network.length) {
        return false;
      }
      int wholeBytes = prefixLength / Byte.SIZE;
      int remainingBits = prefixLength % Byte.SIZE;
      for (int index = 0; index < wholeBytes; index++) {
        if (candidate[index] != network[index]) {
          return false;
        }
      }
      if (remainingBits == 0) {
        return true;
      }
      int mask = 0xff << (Byte.SIZE - remainingBits);
      return (candidate[wholeBytes] & mask) == (network[wholeBytes] & mask);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof CidrRange range
          && prefixLength == range.prefixLength
          && java.util.Arrays.equals(network, range.network);
    }

    @Override
    public int hashCode() {
      return 31 * java.util.Arrays.hashCode(network) + prefixLength;
    }

    private static byte[] parseAddress(String value) {
      if (value.indexOf(':') >= 0) {
        if (value.contains("%") || !value.matches("[0-9A-Fa-f:.]+")) {
          throw new IllegalArgumentException("IPv6 network must be a literal without a zone id");
        }
        try {
          InetAddress address = InetAddress.getByName(value);
          if (!(address instanceof Inet6Address)) {
            throw new IllegalArgumentException("network address family does not match its literal");
          }
          return address.getAddress();
        } catch (UnknownHostException exception) {
          throw new IllegalArgumentException("IPv6 network literal is invalid", exception);
        }
      }
      if (!value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) {
        throw new IllegalArgumentException("IPv4 network must be a dotted-decimal literal");
      }
      String[] octets = value.split("\\.");
      byte[] address = new byte[4];
      for (int index = 0; index < octets.length; index++) {
        int octet = Integer.parseInt(octets[index]);
        if (octet > 255) {
          throw new IllegalArgumentException("IPv4 network octet is outside its range");
        }
        address[index] = (byte) octet;
      }
      return address;
    }

    private static boolean hasZeroHostBits(byte[] address, int prefixLength) {
      int wholeBytes = prefixLength / Byte.SIZE;
      int remainingBits = prefixLength % Byte.SIZE;
      if (remainingBits != 0) {
        int hostMask = (1 << (Byte.SIZE - remainingBits)) - 1;
        if ((address[wholeBytes] & hostMask) != 0) {
          return false;
        }
      }
      int firstHostByte = wholeBytes + (remainingBits == 0 ? 0 : 1);
      for (int index = firstHostByte; index < address.length; index++) {
        if (address[index] != 0) {
          return false;
        }
      }
      return true;
    }
  }
}

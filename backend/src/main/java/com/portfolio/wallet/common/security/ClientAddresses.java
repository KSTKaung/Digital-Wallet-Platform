package com.portfolio.wallet.common.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The container's remote address, which is an IP literal. Everything here avoids DNS: anything that
 * does not look like a literal is never handed to {@link InetAddress}.
 */
public final class ClientAddresses {

  private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
  private static final int IPV6_NETWORK_BYTES = 8;

  private ClientAddresses() {}

  /**
   * @return the address, or null if {@code remoteAddress} is not an IP literal; an IPv4-mapped IPv6
   *     address comes back as IPv4
   */
  public static InetAddress parse(String remoteAddress) {
    if (remoteAddress == null) {
      return null;
    }
    int zone = remoteAddress.indexOf('%');
    String literal = zone < 0 ? remoteAddress : remoteAddress.substring(0, zone);
    if (!literal.contains(":") && !IPV4_LITERAL.matcher(literal).matches()) {
      return null;
    }
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      return null;
    }
  }

  /**
   * The key the per-IP limit counts under: an IPv4 address as it is, and for IPv6 the /64 prefix,
   * because one subscriber is routinely given a whole /64 and could otherwise use a new address for
   * every request.
   */
  public static String rateLimitKey(String remoteAddress) {
    InetAddress address = parse(remoteAddress);
    if (address instanceof Inet6Address) {
      byte[] bytes = address.getAddress();
      return HexFormat.of().formatHex(bytes, 0, IPV6_NETWORK_BYTES) + "/64";
    }
    return address == null ? remoteAddress : address.getHostAddress();
  }
}

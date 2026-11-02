package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet4Address;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientAddressesTest {

  @Test
  void ipv4IsTheFullAddress() {
    assertThat(ClientAddresses.rateLimitKey("192.0.2.1")).isEqualTo("192.0.2.1");
    assertThat(ClientAddresses.rateLimitKey("192.0.2.2")).isEqualTo("192.0.2.2");
  }

  @ParameterizedTest
  @ValueSource(strings = {"::ffff:192.0.2.1", "::ffff:c000:201", "0:0:0:0:0:ffff:192.0.2.1"})
  void ipv4MappedIpv6CountsAsThatIpv4(String mapped) {
    assertThat(ClientAddresses.rateLimitKey(mapped)).isEqualTo("192.0.2.1");
    assertThat(ClientAddresses.parse(mapped)).isInstanceOf(Inet4Address.class);
  }

  @Test
  void addressesInOneSlash64ShareAKey() {
    String key = ClientAddresses.rateLimitKey("2001:db8:1:2:aaaa::1");

    assertThat(ClientAddresses.rateLimitKey("2001:db8:1:2:ffff:ffff:ffff:ffff")).isEqualTo(key);
    assertThat(ClientAddresses.rateLimitKey("2001:0db8:0001:0002::")).isEqualTo(key);
    assertThat(key).isEqualTo("20010db800010002/64");
  }

  @Test
  void anotherSlash64HasAnotherKey() {
    assertThat(ClientAddresses.rateLimitKey("2001:db8:1:3::1"))
        .isNotEqualTo(ClientAddresses.rateLimitKey("2001:db8:1:2::1"));
  }

  @Test
  void aZoneIsIgnored() {
    assertThat(ClientAddresses.rateLimitKey("fe80::1%eth0"))
        .isEqualTo(ClientAddresses.rateLimitKey("fe80::2"));
  }

  @Test
  void anythingElseIsNeverLookedUp() {
    assertThat(ClientAddresses.parse("not.an.address.example")).isNull();
    assertThat(ClientAddresses.parse(null)).isNull();
    assertThat(ClientAddresses.parse("zz:zz")).isNull();
    assertThat(ClientAddresses.rateLimitKey("not.an.address.example"))
        .isEqualTo("not.an.address.example");
  }
}

package com.portfolio.wallet.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.securityfixture.RoleProbeController;
import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.security.CorsProperties;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.EmailMessage;
import com.portfolio.wallet.notification.application.RecordingEmailSender;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.portfolio.wallet.support.MutableClock;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared by the authentication flow tests: one context with a recording mail sender and a clock the
 * tests move. The clock only moves forward and every test uses a fresh account, so tests stay
 * independent.
 */
@AutoConfigureMockMvc
@Import({AuthFlowSupport.Beans.class, RoleProbeController.class})
abstract class AuthFlowSupport extends DatabaseIntegrationTest {

  static final String PASSWORD = "correct-horse-battery";
  static final String API = "/api/v1/auth/";
  static final long TIMEOUT_SECONDS = 60;
  private static final Pattern CODE = Pattern.compile("code is (\\d+)\\.");
  private static final Pattern REFRESH_COOKIE = Pattern.compile("refresh_token=([^;]*)");
  private static final Duration MAIL_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration NO_MAIL_WINDOW = Duration.ofMillis(300);

  @TestConfiguration
  static class Beans {
    @Bean
    @Primary
    RecordingEmailSender recordingEmailSender() {
      return new RecordingEmailSender();
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }
  }

  record Tokens(String accessToken, String refreshToken) {}

  @Autowired MockMvc mvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired RecordingEmailSender mail;
  @Autowired MutableClock clock;
  @Autowired CorsProperties cors;

  static String email() {
    return "user-" + UUID.randomUUID() + "@example.test";
  }

  static String phone() {
    return "+" + randomDigits(15);
  }

  MockHttpServletResponse postJson(String endpoint, Map<String, ?> body) throws Exception {
    return mvc.perform(
            post(API + endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  MockHttpServletResponse register(String email, String phone) throws Exception {
    return postJson("register", Map.of("email", email, "phoneNumber", phone, "password", PASSWORD));
  }

  /** With {@link #PASSWORD} for a registration, which is what {@link #register} set. */
  MockHttpServletResponse verify(String identifier, String purpose, String otp) throws Exception {
    return postJson("verify-otp", verifyBody(identifier, purpose, otp));
  }

  static Map<String, String> verifyBody(String identifier, String purpose, String otp) {
    return "REGISTRATION".equals(purpose)
        ? Map.of("identifier", identifier, "purpose", purpose, "otp", otp, "password", PASSWORD)
        : Map.of("identifier", identifier, "purpose", purpose, "otp", otp);
  }

  MockHttpServletResponse registerWith(String email, String phone, String password)
      throws Exception {
    return postJson("register", Map.of("email", email, "phoneNumber", phone, "password", password));
  }

  MockHttpServletResponse verifyWith(String email, String otp, String password) throws Exception {
    return postJson(
        "verify-otp",
        Map.of("identifier", email, "purpose", "REGISTRATION", "otp", otp, "password", password));
  }

  /** Waits until {@code count} backends wait on a lock, so an interleaving is really in place. */
  void awaitLockWaiters(int count) {
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(20))
        .until(
            () ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity"
                            + " WHERE wait_event_type = 'Lock' AND datname = current_database()",
                        Integer.class)
                    >= count);
  }

  MockHttpServletResponse resend(String identifier) throws Exception {
    return postJson("resend-otp", Map.of("identifier", identifier, "purpose", "REGISTRATION"));
  }

  MockHttpServletResponse login(String email, String password) throws Exception {
    return postJson("login", Map.of("email", email, "password", password));
  }

  /** Registers and verifies a new account and returns its email. */
  String activeUser() throws Exception {
    String email = email();
    assertThat(register(email, phone()).getStatus()).isEqualTo(201);
    assertThat(verify(email, "REGISTRATION", awaitOtp(email, 1)).getStatus()).isEqualTo(200);
    return email;
  }

  Tokens loginTokens(String email) throws Exception {
    MockHttpServletResponse response = login(email, PASSWORD);
    assertThat(response.getStatus()).isEqualTo(200);
    return tokens(response);
  }

  Tokens tokens(MockHttpServletResponse response) throws Exception {
    return new Tokens(
        jsonMapper.readTree(response.getContentAsString()).get("accessToken").asString(),
        refreshCookieValue(response));
  }

  static String refreshCookieValue(MockHttpServletResponse response) {
    Matcher matcher = REFRESH_COOKIE.matcher(response.getHeader(HttpHeaders.SET_COOKIE));
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  /** A request that passes the CSRF check. */
  MockHttpServletRequestBuilder browserPost(String endpoint) {
    return post(API + endpoint)
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Origin", cors.allowedOrigins().getFirst());
  }

  MockHttpServletResponse refresh(String refreshToken) throws Exception {
    return mvc.perform(browserPost("refresh").cookie(new Cookie("refresh_token", refreshToken)))
        .andReturn()
        .getResponse();
  }

  MockHttpServletResponse getWithToken(String path, String accessToken) throws Exception {
    return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andReturn()
        .getResponse();
  }

  MockHttpServletResponse probe(String accessToken) throws Exception {
    return getWithToken(RoleProbeController.ANY, accessToken);
  }

  /** The body without the fields that differ on every response. */
  static String stable(MockHttpServletResponse response) throws Exception {
    return response
        .getContentAsString()
        .replaceAll("\"timestamp\":\"[^\"]*\"", "")
        .replaceAll("\"" + "correlationId" + "\":\"[^\"]*\"", "");
  }

  List<EmailMessage> mailTo(String address, EmailKind kind) {
    return mail.messages().stream()
        .filter(message -> message.to().equals(address) && message.kind() == kind)
        .toList();
  }

  /** The code in the {@code count}-th OTP email to {@code address}, waiting for it to arrive. */
  String awaitOtp(String address, int count) {
    await().atMost(MAIL_TIMEOUT).until(() -> mailTo(address, EmailKind.OTP).size() >= count);
    Matcher matcher = CODE.matcher(mailTo(address, EmailKind.OTP).get(count - 1).body());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  void awaitMailCount(String address, EmailKind kind, int count) {
    await().atMost(MAIL_TIMEOUT).until(() -> mailTo(address, kind).size() >= count);
  }

  /** Mail is sent asynchronously, so absence is checked over a short window. */
  void assertMailCountStays(String address, EmailKind kind, int count) {
    await()
        .during(NO_MAIL_WINDOW)
        .atMost(MAIL_TIMEOUT)
        .until(() -> mailTo(address, kind).size() == count);
  }

  /** A wrong code for {@code realCode}, which it can never equal. */
  static String wrongCode(String realCode) {
    return realCode.equals("000000") ? "111111" : "000000";
  }

  /** The audit reason of the row written by the request with this correlation id. */
  String auditReason(String action, String correlationId) {
    return jdbc.queryForObject(
        "SELECT new_value ->> 'reason' FROM audit.audit_logs"
            + " WHERE action = ? AND correlation_id = ? ORDER BY occurred_at DESC LIMIT 1",
        String.class,
        action,
        correlationId);
  }

  MockHttpServletResponse postWithCorrelation(
      String endpoint, Map<String, ?> body, String correlationId) throws Exception {
    return mvc.perform(
            post(API + endpoint)
                .header(CorrelationId.HEADER, correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }
}

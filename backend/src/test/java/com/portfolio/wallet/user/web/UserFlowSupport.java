package com.portfolio.wallet.user.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.portfolio.wallet.auth.application.AccessTokenIssuer;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared by the profile and KYC flow tests: real HTTP through MockMvc and the full security chain.
 * Accounts are made directly in the database with a live session, and their access tokens are
 * signed by the application's issuer, as in {@code SessionFlowIT}: registration and OTP are tested
 * elsewhere and would only slow these tests down. Every test uses fresh accounts.
 */
@AutoConfigureMockMvc
abstract class UserFlowSupport extends DatabaseIntegrationTest {

  static final String PROFILE = "/api/v1/customers/me";
  static final String APPLICATIONS = "/api/v1/kyc/applications";
  static final String STATUS = "/api/v1/kyc/status";
  static final String REFERENCE = "AB1234567";
  static final String FIRST_NAME = "Somchai";
  static final String LAST_NAME = "Jaidee";

  @Autowired MockMvc mvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired AccessTokenIssuer issuer;
  @Autowired Clock clock;

  /** An account with its id and a valid access token. */
  record Account(UUID id, String email, String phone, String token) {}

  Account account(Role role) throws Exception {
    UUID id = UUID.randomUUID();
    String email = "user-" + id + "@example.test";
    String phone = role == Role.CUSTOMER ? "+" + randomDigits(15) : null;
    insert(
        "auth.users",
        row(
            "id",
            id,
            "email",
            email,
            "phone_number",
            phone,
            "status",
            "ACTIVE",
            "role",
            role.name()));
    UUID session = UUID.randomUUID();
    Instant now = clock.instant();
    insert(
        "auth.sessions",
        row("id", session, "user_id", id, "last_seen_at", now.atOffset(ZoneOffset.UTC)));
    String token = issuer.issue(id, role, session, now, now.plus(Duration.ofMinutes(15)));
    return new Account(id, email, phone, token);
  }

  Account customer() throws Exception {
    return account(Role.CUSTOMER);
  }

  static Map<String, Object> kycBody() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("verificationLevel", "BASIC");
    body.put("documentType", "ID_CARD");
    body.put("documentReference", REFERENCE);
    body.put("firstName", FIRST_NAME);
    body.put("lastName", LAST_NAME);
    return body;
  }

  static Map<String, Object> kycBodyWith(String field, Object value) {
    Map<String, Object> body = kycBody();
    body.put(field, value);
    return body;
  }

  static Map<String, Object> profileBody(String dateOfBirth, String countryCode) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("dateOfBirth", dateOfBirth);
    body.put("countryCode", countryCode);
    return body;
  }

  MockHttpServletResponse send(MockHttpServletRequestBuilder request, String token)
      throws Exception {
    return send(request, token, null);
  }

  MockHttpServletResponse send(
      MockHttpServletRequestBuilder request, String token, String correlationId) throws Exception {
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    if (correlationId != null) {
      request.header(CorrelationId.HEADER, correlationId);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body)
      throws Exception {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonMapper.writeValueAsString(body));
  }

  MockHttpServletResponse getAs(String path, String token) throws Exception {
    return send(get(path), token);
  }

  MockHttpServletResponse postAs(String path, Object body, String token) throws Exception {
    return send(body == null ? post(path) : json(post(path), body), token);
  }

  MockHttpServletResponse putAs(String path, Object body, String token) throws Exception {
    return send(json(put(path), body), token);
  }

  MockHttpServletResponse createApplication(String token) throws Exception {
    return postAs(APPLICATIONS, kycBody(), token);
  }

  /** Creates a draft and returns its id. */
  UUID draft(Account account) throws Exception {
    MockHttpServletResponse response = createApplication(account.token());
    if (response.getStatus() != 201) {
      throw new AssertionError("create returned " + response.getStatus());
    }
    return UUID.fromString(body(response).get("id").asString());
  }

  /** Sets the profile fields that submit needs. */
  void completeProfile(Account account) throws Exception {
    MockHttpServletResponse response =
        putAs(PROFILE, profileBody("1990-05-17", "TH"), account.token());
    if (response.getStatus() != 200) {
      throw new AssertionError("profile update returned " + response.getStatus());
    }
  }

  MockHttpServletResponse submit(UUID application, String token) throws Exception {
    return postAs(APPLICATIONS + "/" + application + "/submit", null, token);
  }

  JsonNode body(MockHttpServletResponse response) throws Exception {
    return jsonMapper.readTree(response.getContentAsString());
  }

  String errorCode(MockHttpServletResponse response) throws Exception {
    return body(response).get("errorCode").asString();
  }

  /** The body without the fields that differ on every response. */
  static String stable(MockHttpServletResponse response) throws Exception {
    return response
        .getContentAsString()
        .replaceAll("\"timestamp\":\"[^\"]*\"", "")
        .replaceAll("\"correlationId\":\"[^\"]*\"", "");
  }
}

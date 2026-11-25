package com.portfolio.wallet.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.wallet.support.PostgresTestDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The committed OpenAPI document, {@code openapi/openapi.json} at the repository root, equals the
 * one the application generates (documentations/08 §4E, decision E2 and acceptance criteria 2 and
 * 4). The document is served only in the {@code local} profile (documentations/06 MVP-ADR-15); this
 * test turns it on for its own context with {@code springdoc.api-docs.enabled=true}, and {@code
 * WalletApplicationIT} still proves it is off by default.
 *
 * <p>The comparison is on canonical JSON: object keys sorted at every level, array order kept, and
 * {@code servers} removed, because springdoc derives the server URL from the request. The file is
 * written in the same canonical form, so a regeneration gives a reviewable diff.
 *
 * <p><b>Regenerate</b> after an intended contract change, from {@code backend/}:
 *
 * <pre>
 * ./mvnw verify -Dit.test=OpenApiContractIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dopenapi.write=true
 * </pre>
 *
 * (on Windows {@code .\mvnw.cmd} with the same arguments), then review and commit the file and
 * regenerate the frontend types ({@code npm run api:types}). The direct checks below hold whatever
 * the file says, so a regeneration cannot silently bring back a gap closed in PR 7.
 */
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
class OpenApiContractIT {

  /** Set with {@code -Dopenapi.write=true} to rewrite the committed document. */
  static final String WRITE_PROPERTY = "openapi.write";

  /** Relative to {@code backend/}, the working directory of the Maven test run. */
  static final Path COMMITTED = Path.of("..", "openapi", "openapi.json");

  static final String REGENERATE =
      "./mvnw verify -Dit.test=OpenApiContractIT -Dtest=NONE"
          + " -Dsurefire.failIfNoSpecifiedTests=false -Dopenapi.write=true";

  private static final int MAX_REPORTED_DIFFERENCES = 20;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestDatabase.register(registry);
  }

  @Autowired MockMvc mvc;
  @Autowired JsonMapper jsonMapper;

  private JsonNode generated;

  @BeforeEach
  void fetchTheGeneratedDocument() throws Exception {
    String body =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    generated = canonical(jsonMapper.readTree(body));
  }

  @Test
  void theCommittedDocumentMatchesTheGeneratedOne() throws IOException {
    if (Boolean.getBoolean(WRITE_PROPERTY)) {
      Files.createDirectories(COMMITTED.getParent());
      Files.writeString(COMMITTED, serialise(generated), StandardCharsets.UTF_8);
    }
    if (!Files.exists(COMMITTED)) {
      fail(COMMITTED + " is missing. Generate it from backend/ with: " + REGENERATE);
    }
    JsonNode committed = canonical(jsonMapper.readTree(Files.readString(COMMITTED)));
    List<String> differences = new ArrayList<>();
    differences(committed, generated, "", differences);
    if (!differences.isEmpty()) {
      fail(
          "openapi/openapi.json differs from the generated OpenAPI document ("
              + (differences.size() >= MAX_REPORTED_DIFFERENCES
                  ? "at least " + MAX_REPORTED_DIFFERENCES
                  : differences.size())
              + " differences):\n  "
              + String.join("\n  ", differences)
              + "\nIf the contract change is intended, regenerate the file from backend/ with:\n  "
              + REGENERATE
              + "\nthen review and commit it, and regenerate the frontend types.");
    }
  }

  @Test
  void hasNoErrorPathNoWildcardMediaTypeAndNoEnvironmentValue() {
    assertThat(generated.path("paths").has("/error")).isFalse();
    assertThat(serialise(generated)).doesNotContain("*/*", "localhost", "127.0.0.1", "://");
  }

  /** Every operation, its stable id and its real success status (doc 02 §2). */
  @Test
  void everyOperationHasItsStableIdAndItsRealSuccessStatus() {
    Map<String, String> actual = new TreeMap<>();
    List<String> ids = new ArrayList<>();
    operations()
        .forEach(
            (key, operation) -> {
              String id = operation.path("operationId").asString();
              ids.add(id);
              List<String> successes = new ArrayList<>();
              for (String status : operation.path("responses").propertyNames()) {
                if (status.startsWith("2")) {
                  successes.add(status);
                }
              }
              assertThat(successes).as(key + " success statuses").hasSize(1);
              actual.put(id, key + " " + successes.getFirst());
            });

    assertThat(ids).doesNotHaveDuplicates();
    assertThat(actual).containsExactlyInAnyOrderEntriesOf(EXPECTED_OPERATIONS);
  }

  @Test
  void depositsWithdrawalsTransfersAndRegistrationsAnswer201WithJson() {
    for (String key :
        List.of(
            "POST /api/v1/wallet/deposits",
            "POST /api/v1/wallet/withdrawals",
            "POST /api/v1/transfers",
            "POST /api/v1/kyc/applications",
            "POST /api/v1/auth/register")) {
      JsonNode responses = operations().get(key).path("responses");
      assertThat(responses.has("200")).as(key).isFalse();
      assertThat(responses.path("201").path("content").propertyNames())
          .as(key)
          .containsExactly("application/json");
    }
  }

  @Test
  void emptySuccessesHaveNoBody() {
    for (String key :
        List.of(
            "POST /api/v1/auth/resend-otp",
            "POST /api/v1/auth/forgot-password",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/reset-password",
            "POST /api/v1/auth/change-password")) {
      JsonNode responses = operations().get(key).path("responses");
      responses
          .properties()
          .forEach(
              response -> {
                if (response.getKey().startsWith("2")) {
                  assertThat(response.getValue().has("content")).as(key).isFalse();
                }
              });
    }
  }

  @Test
  void theIdempotencyKeyIsARequiredUuidHeaderOnTheMoneyPostsOnly() {
    List<String> withKey = new ArrayList<>();
    operations()
        .forEach(
            (key, operation) -> {
              for (JsonNode parameter : list(operation.path("parameters"))) {
                if (OpenApiDocumentation.IDEMPOTENCY_KEY.equals(
                    parameter.path("name").asString())) {
                  withKey.add(key);
                  assertThat(parameter.path("in").asString()).isEqualTo("header");
                  assertThat(parameter.path("required").asBoolean()).as(key).isTrue();
                  assertThat(parameter.path("schema").path("format").asString()).isEqualTo("uuid");
                }
              }
            });

    assertThat(withKey)
        .containsExactlyInAnyOrder(
            "POST /api/v1/wallet/deposits",
            "POST /api/v1/wallet/withdrawals",
            "POST /api/v1/transfers");
  }

  /** Fields the server sends as {@code null}, from the Java records (documentations/08 §5). */
  @Test
  void nullableFieldsAreMarkedAndStillRequired() {
    Map<String, List<String>> nullable =
        Map.of(
            "TransferResponse", List.of("rejectionReason", "reviewExpiresAt", "ledgerStatus"),
            "TransferStatusResponse", List.of("ledgerStatus"),
            "KycStatusResponse", List.of("applicationId", "verificationLevel"),
            "KycApplicationResponse", List.of("rejectionReason", "submittedAt", "decidedAt"),
            "CustomerProfileResponse",
                List.of("phoneNumber", "firstName", "lastName", "dateOfBirth", "countryCode"),
            "KycReviewDetailResponse", List.of("decidedAt", "reviewerId", "rejectionReason"),
            "KycReviewSummaryResponse", List.of("decidedAt"),
            "TransferReviewSummaryResponse", List.of("score"),
            "TransferReviewDetailResponse",
                List.of("decidedAt", "rejectionReasonCategory", "ledgerStatus"),
            "History",
                List.of(
                    "requestedAction", "actorUserId", "reasonCategory", "reason", "systemReason"));
    JsonNode schemas = generated.path("components").path("schemas");
    nullable.forEach(
        (schema, fields) -> {
          for (String field : fields) {
            JsonNode type = schemas.path(schema).path("properties").path(field).path("type");
            assertThat(list(type).stream().map(JsonNode::asString))
                .as(schema + "." + field)
                .contains("null");
            assertThat(list(schemas.path(schema).path("required")).stream().map(JsonNode::asString))
                .as(schema + " required")
                .contains(field);
          }
        });
    JsonNode createdAt =
        schemas.path("TransferResponse").path("properties").path("createdAt").path("type");
    assertThat(createdAt.isArray()).as("TransferResponse.createdAt is never null").isFalse();
    assertThat(createdAt.asString()).isEqualTo("string");
  }

  @Test
  void requiredRequestAndResponseFieldsAreMarked() {
    JsonNode schemas = generated.path("components").path("schemas");
    assertThat(required(schemas, "TokenResponse")).contains("accessToken", "expiresIn");
    assertThat(required(schemas, "RejectTransferRequest")).containsExactly("reason");
    assertThat(required(schemas, "CreateTransferRequest"))
        .containsExactlyInAnyOrder("receiverWalletNumber", "amount", "currency");
    assertThat(required(schemas, "ApiErrorResponse"))
        .containsExactlyInAnyOrder("success", "errorCode", "message", "correlationId", "timestamp");
    assertThat(
            operations()
                .get("POST /api/v1/admin/transfer-reviews/{transferId}/reject")
                .path("requestBody")
                .path("required")
                .asBoolean())
        .isTrue();
  }

  /**
   * Every error is the documented body; a path id has one generic 404 (no existence distinction,
   * MVP-ADR-19) and a malformed one is the 400 VALIDATION_ERROR.
   */
  @Test
  void everyErrorResponseIsTheDocumentedErrorBody() {
    operations()
        .forEach(
            (key, operation) -> {
              JsonNode responses = operation.path("responses");
              for (String status : responses.propertyNames()) {
                if (status.compareTo("400") >= 0) {
                  assertThat(
                          responses
                              .path(status)
                              .path("content")
                              .path("application/json")
                              .path("schema")
                              .path("$ref")
                              .asString())
                      .as(key + " " + status)
                      .isEqualTo("#/components/schemas/ApiErrorResponse");
                }
              }
              assertThat(responses.has("429")).as(key).isTrue();
              if (key.contains("{")) {
                assertThat(responses.path("400").path("description").asString())
                    .as(key)
                    .contains("`VALIDATION_ERROR`", "not a UUID");
                assertThat(responses.path("404").path("description").asString())
                    .as(key)
                    .contains("`NOT_FOUND`")
                    .doesNotContain("WALLET_NOT_FOUND");
              }
            });
    assertThat(
            operations()
                .get("POST /api/v1/auth/reset-password")
                .path("responses")
                .path("400")
                .path("description")
                .asString())
        .contains("`INVALID_RESET_TOKEN`", "`VALIDATION_ERROR`");
  }

  @Test
  void signedInOperationsUseTheBearerSchemeAndThePublicAuthOnesDoNot() {
    assertThat(
            generated
                .path("components")
                .path("securitySchemes")
                .path(OpenApiDocumentation.BEARER)
                .path("scheme")
                .asString())
        .isEqualTo("bearer");
    operations()
        .forEach(
            (key, operation) -> {
              boolean secured = operation.has("security");
              if (PUBLIC_OPERATIONS.contains(key)) {
                assertThat(secured).as(key).isFalse();
                // Only bad credentials (login) and a bad refresh cookie are a 401 here.
                assertThat(operation.path("responses").has("401"))
                    .as(key + " 401")
                    .isEqualTo(key.endsWith("/login") || key.endsWith("/refresh"));
              } else {
                assertThat(operation.path("security").toString())
                    .as(key)
                    .isEqualTo("[{\"" + OpenApiDocumentation.BEARER + "\":[]}]");
                assertThat(operation.path("responses").has("401")).as(key).isTrue();
              }
            });
  }

  private static final List<String> PUBLIC_OPERATIONS =
      List.of(
          "POST /api/v1/auth/register",
          "POST /api/v1/auth/login",
          "POST /api/v1/auth/refresh",
          "POST /api/v1/auth/verify-otp",
          "POST /api/v1/auth/resend-otp",
          "POST /api/v1/auth/forgot-password",
          "POST /api/v1/auth/reset-password");

  /** Operation id → "METHOD path success-status": the scheme is verb + noun, in camel case. */
  private static final Map<String, String> EXPECTED_OPERATIONS =
      Map.ofEntries(
          Map.entry("registerAccount", "POST /api/v1/auth/register 201"),
          Map.entry("verifyOtp", "POST /api/v1/auth/verify-otp 200"),
          Map.entry("resendOtp", "POST /api/v1/auth/resend-otp 202"),
          Map.entry("requestPasswordReset", "POST /api/v1/auth/forgot-password 202"),
          Map.entry("resetPassword", "POST /api/v1/auth/reset-password 200"),
          Map.entry("changePassword", "POST /api/v1/auth/change-password 200"),
          Map.entry("loginUser", "POST /api/v1/auth/login 200"),
          Map.entry("refreshAccessToken", "POST /api/v1/auth/refresh 200"),
          Map.entry("logoutUser", "POST /api/v1/auth/logout 204"),
          Map.entry("getCustomerProfile", "GET /api/v1/customers/me 200"),
          Map.entry("updateCustomerProfile", "PUT /api/v1/customers/me 200"),
          Map.entry("createKycApplication", "POST /api/v1/kyc/applications 201"),
          Map.entry("getKycApplication", "GET /api/v1/kyc/applications/{id} 200"),
          Map.entry("updateKycApplication", "PUT /api/v1/kyc/applications/{id} 200"),
          Map.entry("submitKycApplication", "POST /api/v1/kyc/applications/{id}/submit 200"),
          Map.entry("getKycStatus", "GET /api/v1/kyc/status 200"),
          Map.entry("getWallet", "GET /api/v1/wallet 200"),
          Map.entry("getWalletBalance", "GET /api/v1/wallet/balance 200"),
          Map.entry("getWalletStatus", "GET /api/v1/wallet/status 200"),
          Map.entry("getWalletLimits", "GET /api/v1/wallet/limits 200"),
          Map.entry("freezeWallet", "POST /api/v1/wallet/freeze 200"),
          Map.entry("createDeposit", "POST /api/v1/wallet/deposits 201"),
          Map.entry("createWithdrawal", "POST /api/v1/wallet/withdrawals 201"),
          Map.entry("lookupWallet", "GET /api/v1/wallets/lookup 200"),
          Map.entry("createTransfer", "POST /api/v1/transfers 201"),
          Map.entry("listTransfers", "GET /api/v1/transfers 200"),
          Map.entry("getTransfer", "GET /api/v1/transfers/{id} 200"),
          Map.entry("getTransferStatus", "GET /api/v1/transfers/{id}/status 200"),
          Map.entry("cancelTransfer", "POST /api/v1/transfers/{id}/cancel 200"),
          Map.entry("listTransactions", "GET /api/v1/transactions 200"),
          Map.entry("listKycApplicationsForReview", "GET /api/v1/admin/kyc/applications 200"),
          Map.entry("getKycApplicationForReview", "GET /api/v1/admin/kyc/applications/{id} 200"),
          Map.entry(
              "approveKycApplication", "POST /api/v1/admin/kyc/applications/{id}/approve 200"),
          Map.entry("rejectKycApplication", "POST /api/v1/admin/kyc/applications/{id}/reject 200"),
          Map.entry("freezeCustomerWallet", "POST /api/v1/admin/customers/{customerId}/freeze 200"),
          Map.entry(
              "unfreezeCustomerWallet", "POST /api/v1/admin/customers/{customerId}/unfreeze 200"),
          Map.entry("listTransferReviews", "GET /api/v1/admin/transfer-reviews 200"),
          Map.entry("getTransferReview", "GET /api/v1/admin/transfer-reviews/{transferId} 200"),
          Map.entry(
              "approveTransferReview",
              "POST /api/v1/admin/transfer-reviews/{transferId}/approve 200"),
          Map.entry(
              "rejectTransferReview",
              "POST /api/v1/admin/transfer-reviews/{transferId}/reject 200"));

  /** "METHOD path" → operation, for every operation of the generated document. */
  private Map<String, JsonNode> operations() {
    Map<String, JsonNode> operations = new TreeMap<>();
    for (Map.Entry<String, JsonNode> path : generated.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
        operations.put(
            operation.getKey().toUpperCase(java.util.Locale.ROOT) + " " + path.getKey(),
            operation.getValue());
      }
    }
    return operations;
  }

  private static List<String> required(JsonNode schemas, String schema) {
    return list(schemas.path(schema).path("required")).stream().map(JsonNode::asString).toList();
  }

  /** The elements of an array node, or the node itself when it is a single value. */
  private static List<JsonNode> list(JsonNode node) {
    List<JsonNode> elements = new ArrayList<>();
    if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        elements.add(node.get(i));
      }
    } else if (!node.isMissingNode()) {
      elements.add(node);
    }
    return elements;
  }

  private ObjectNode canonical(JsonNode document) {
    ObjectNode copy = (ObjectNode) sorted(document);
    copy.remove("servers");
    return copy;
  }

  private JsonNode sorted(JsonNode node) {
    if (node.isObject()) {
      Map<String, JsonNode> fields = new TreeMap<>();
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        fields.put(field.getKey(), sorted(field.getValue()));
      }
      ObjectNode result = jsonMapper.createObjectNode();
      fields.forEach(result::set);
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = jsonMapper.createArrayNode();
      for (int i = 0; i < node.size(); i++) {
        result.add(sorted(node.get(i)));
      }
      return result;
    }
    return node;
  }

  /** Two-space indentation, LF line endings and a final newline, whatever the platform. */
  private String serialise(JsonNode node) {
    return jsonMapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(node)
            .replace("\r\n", "\n")
        + "\n";
  }

  /** JSON-pointer-like paths where the committed and the generated documents differ. */
  private static void differences(
      JsonNode committed, JsonNode generated, String path, List<String> out) {
    if (out.size() >= MAX_REPORTED_DIFFERENCES || committed.equals(generated)) {
      return;
    }
    if (committed.isObject() && generated.isObject()) {
      for (Map.Entry<String, JsonNode> field : committed.properties()) {
        String child = path + "/" + field.getKey();
        if (!generated.has(field.getKey())) {
          out.add(child + ": only in the committed file");
        } else {
          differences(field.getValue(), generated.get(field.getKey()), child, out);
        }
      }
      for (Map.Entry<String, JsonNode> field : generated.properties()) {
        if (!committed.has(field.getKey()) && out.size() < MAX_REPORTED_DIFFERENCES) {
          out.add(path + "/" + field.getKey() + ": only in the generated document");
        }
      }
      return;
    }
    if (committed.isArray() && generated.isArray() && committed.size() == generated.size()) {
      for (int i = 0; i < committed.size(); i++) {
        differences(committed.get(i), generated.get(i), path + "/" + i, out);
      }
      return;
    }
    out.add(
        (path.isEmpty() ? "/" : path) + ": committed " + committed + ", generated " + generated);
  }
}

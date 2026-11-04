package com.portfolio.wallet.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.EmailMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Extends {@code KycContentExposureIT} (customer KYC endpoints) to the staff, wallet and freeze
 * endpoints (documentations/08 §4B.4 AC 11; doc 06 MVP-ADR-10 amendment, MVP-ADR-12):
 *
 * <ul>
 *   <li>the document reference never appears unmasked in any response, staff views included;
 *   <li>the full legal name appears only where doc 06 shows it: the staff detail and decision view,
 *       and the customer's own application and profile. The staff list shows the masked form (first
 *       name and last initial), so the last name never appears there; no wallet, freeze or error
 *       response contains either part;
 *   <li>neither, nor the date of birth, appears in any log line, audit row or email.
 * </ul>
 *
 * Distinctive values are used, so a match cannot be a coincidence.
 */
@ExtendWith(OutputCaptureExtension.class)
class KycExposureAcrossEndpointsIT extends AdminFlowSupport {

  private static final String CUSTOMERS = "/api/v1/admin/customers/";

  private static String distinct(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
  }

  private static String text(List<MockHttpServletResponse> responses) throws Exception {
    StringBuilder all = new StringBuilder();
    for (MockHttpServletResponse response : responses) {
      all.append(response.getContentAsString()).append('\n');
    }
    return all.toString();
  }

  private UUID submittedWith(Account customer, String reference, String first, String last)
      throws Exception {
    assertThat(
            putAs(
                    PROFILE,
                    Map.of("dateOfBirth", "1979-11-23", "countryCode", "TH"),
                    customer.token())
                .getStatus())
        .isEqualTo(200);
    Map<String, Object> body = kycBody("BASIC");
    body.put("documentReference", reference);
    body.put("firstName", first);
    body.put("lastName", last);
    MockHttpServletResponse created = postAs(APPLICATIONS, body, customer.token());
    assertThat(created.getStatus()).isEqualTo(201);
    UUID id = UUID.fromString(body(created).get("id").asString());
    assertThat(postAs(APPLICATIONS + "/" + id + "/submit", null, customer.token()).getStatus())
        .isEqualTo(200);
    return id;
  }

  private static Map<String, Object> reason(String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", reason);
    return body;
  }

  @Test
  void noEndpointLogOrAuditRowShowsTheReferenceOrAnUnmaskedLegalName(CapturedOutput output)
      throws Exception {
    Account approved = customer();
    Account rejected = customer();
    String reference = distinct("QX");
    String rejectedReference = distinct("PZ");
    String first = distinct("First");
    String last = distinct("Last");
    String rejectedFirst = distinct("Rfirst");
    String rejectedLast = distinct("Rlast");
    String dateOfBirth = "1979-11-23";
    String officer = officer().token();
    Account adminAccount = account(Role.ADMIN);
    String admin = adminAccount.token();

    UUID application = submittedWith(approved, reference, first, last);
    UUID rejectedApplication =
        submittedWith(rejected, rejectedReference, rejectedFirst, rejectedLast);

    // Where doc 06 shows the full name: staff detail and decisions; the owner's own views.
    List<MockHttpServletResponse> nameViews = new ArrayList<>();
    // The staff lists: the masked name only.
    List<MockHttpServletResponse> lists = new ArrayList<>();
    // Everything else.
    List<MockHttpServletResponse> others = new ArrayList<>();

    // Staff KYC endpoints.
    // The last two pages: the newest submissions, even across a page boundary (oldest first, P2).
    List<MockHttpServletResponse> pendingPages = lastListPages("status=PENDING_REVIEW", officer);
    lists.addAll(pendingPages);
    nameViews.add(getAs(REVIEW + "/" + application, officer));
    nameViews.add(approve(application, officer));
    nameViews.add(reject(rejectedApplication, "Document unreadable", officer));
    lists.addAll(lastListPages("status=APPROVED", officer));
    lists.addAll(lastListPages("status=REJECTED", officer));
    others.add(approve(application, officer)); // 409, already decided
    others.add(reject(rejectedApplication, "Contains " + rejectedReference, officer)); // refused
    others.add(getAs(REVIEW + "/" + UUID.randomUUID(), officer)); // 404

    // The customer's own views.
    nameViews.add(getAs(APPLICATIONS + "/" + application, approved.token()));
    nameViews.add(getAs(PROFILE, approved.token()));
    others.add(getAs(STATUS, approved.token()));
    others.add(getAs(APPLICATIONS + "/" + application, rejected.token())); // foreign, 404

    // Wallet endpoints and the customer's own freeze.
    for (String path :
        List.of("/api/v1/wallet", "/api/v1/wallet/balance", "/api/v1/wallet/status")) {
      others.add(getAs(path, approved.token()));
    }
    others.add(getAs("/api/v1/wallet/limits", approved.token()));
    others.add(getAs("/api/v1/wallet", rejected.token())); // no wallet, 404
    others.add(postAs("/api/v1/wallet/freeze", null, approved.token()));

    // Staff freeze and unfreeze, including the refused and unknown cases.
    others.add(postAs(CUSTOMERS + approved.id() + "/unfreeze", reason("Cleared"), admin));
    others.add(postAs(CUSTOMERS + approved.id() + "/freeze", reason("Check"), admin));
    others.add(postAs(CUSTOMERS + approved.id() + "/freeze", reason("Again"), admin));
    others.add(postAs(CUSTOMERS + rejected.id() + "/freeze", reason("No wallet"), admin));
    others.add(postAs(CUSTOMERS + approved.id() + "/freeze", reason("Wrong role"), officer));
    others.add(postAs(CUSTOMERS + approved.id() + "/unfreeze", reason("Done"), admin));
    awaitMail(approved.email(), EmailKind.WALLET_UNFROZEN);
    awaitMail(approved.email(), EmailKind.WALLET_FROZEN);
    assertMailCountStays(approved.email(), EmailKind.WALLET_UNFROZEN, 2);

    String named = text(nameViews);
    String listed = text(lists);
    String rest = text(others);
    List<String> references = List.of(reference, rejectedReference);

    // The reference: never, anywhere in a response.
    assertThat(named + listed + rest).doesNotContain(references);
    // The views that show the name do show it, so the scan above really read them.
    assertThat(named).contains(first, last, rejectedFirst, rejectedLast);
    // The list masks the name: the first name and the last initial only.
    assertThat(text(pendingPages)).contains(first + " " + last.charAt(0) + ".");
    assertThat(listed).doesNotContain(last, rejectedLast, dateOfBirth);
    assertThat(rest)
        .as("no wallet, freeze, status or error response shows any part of the name")
        .doesNotContain(first, last, rejectedFirst, rejectedLast, dateOfBirth);

    // Audit rows of every actor and resource in the flow.
    String audit =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT row_to_json(a)::text FROM audit.audit_logs a"
                    + " WHERE actor_id IN (?, ?, ?) OR resource_id IN (?, ?)"
                    + " OR new_value ->> 'customerId' IN (?, ?)",
                String.class,
                approved.id(),
                rejected.id(),
                adminAccount.id(),
                application.toString(),
                rejectedApplication.toString(),
                approved.id().toString(),
                rejected.id().toString()));
    assertThat(audit).contains("KYC_APPLICATION_APPROVE", "WALLET_FREEZE", "WALLET_UNFREEZE");
    List<String> content =
        List.of(
            reference,
            rejectedReference,
            first,
            last,
            rejectedFirst,
            rejectedLast,
            dateOfBirth,
            approved.email(),
            rejected.email());
    assertThat(audit).doesNotContain(content);

    // Every email to either customer.
    String emails =
        String.join(
            "\n",
            mail.messages().stream()
                .filter(m -> m.to().equals(approved.email()) || m.to().equals(rejected.email()))
                .map(m -> m.subject() + " " + m.body())
                .toList());
    assertThat(mail.messages().stream().filter(m -> m.to().equals(approved.email())))
        .extracting(EmailMessage::kind)
        .contains(EmailKind.KYC_APPROVED, EmailKind.WALLET_FROZEN, EmailKind.WALLET_UNFROZEN);
    assertThat(emails).doesNotContain(content);

    // Logs of the whole flow, including the async mail threads.
    assertThat(output.getAll()).doesNotContain(content);
  }
}

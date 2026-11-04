package com.portfolio.wallet.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The document reference, the legal name and the date of birth never appear in logs or audit rows,
 * and the reference never appears unmasked in a response (documentations/08 §4B.4 AC 11).
 * Distinctive values are used, so a match cannot be a coincidence.
 */
@ExtendWith(OutputCaptureExtension.class)
class KycContentExposureIT extends UserFlowSupport {

  @Test
  void aFullFlowLeavesNoKycContentInLogsOrAuditRows(CapturedOutput output) throws Exception {
    Account account = customer();
    String reference = "QX" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String editedReference = "PZ" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String first = "Firstname" + UUID.randomUUID().toString().substring(0, 6);
    String last = "Lastname" + UUID.randomUUID().toString().substring(0, 6);
    String dateOfBirth = "1987-03-29";

    StringBuilder responses = new StringBuilder();
    responses.append(
        putAs(PROFILE, profileBody(dateOfBirth, "TH"), account.token()).getContentAsString());
    Map<String, Object> body = kycBody();
    body.put("documentReference", reference);
    body.put("firstName", first);
    body.put("lastName", last);
    var created = postAs(APPLICATIONS, body, account.token());
    responses.append(created.getContentAsString());
    UUID id = UUID.fromString(body(created).get("id").asString());
    body.put("documentReference", editedReference);
    responses.append(putAs(APPLICATIONS + "/" + id, body, account.token()).getContentAsString());
    // A refused second create and a refused edit run the error paths too.
    responses.append(postAs(APPLICATIONS, body, account.token()).getContentAsString());
    responses.append(submit(id, account.token()).getContentAsString());
    responses.append(putAs(APPLICATIONS + "/" + id, body, account.token()).getContentAsString());
    responses.append(getAs(APPLICATIONS + "/" + id, account.token()).getContentAsString());
    responses.append(getAs(STATUS, account.token()).getContentAsString());
    // Another customer probing the application.
    responses.append(putAs(APPLICATIONS + "/" + id, body, customer().token()).getContentAsString());

    String audit =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT row_to_json(a)::text FROM audit.audit_logs a WHERE actor_id = ?",
                String.class,
                account.id()));
    String logs = output.getAll();

    List<String> content = List.of(reference, editedReference, first, last, dateOfBirth);
    assertThat(audit).contains("KYC_APPLICATION_SUBMIT").doesNotContain(content);
    assertThat(logs).doesNotContain(content);
    assertThat(responses.toString()).doesNotContain(reference, editedReference);
    assertThat(audit + logs).doesNotContain(account.email(), account.phone());
  }
}

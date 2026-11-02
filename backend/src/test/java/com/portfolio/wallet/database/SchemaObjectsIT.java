package com.portfolio.wallet.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Catalogue checks on the functions and indexes the migrations create (documentations/06
 * MVP-ADR-02; doc 01 §7.7 access paths).
 */
class SchemaObjectsIT extends DatabaseIntegrationTest {

  private static final String[] MODULE_SCHEMAS = {
    "auth", "usr", "wallet", "transfer", "ledger", "risk", "audit", "notification"
  };

  /** Pinning search_path stops a caller from shadowing pg_catalog objects in a trigger. */
  @Test
  void everyFunctionInTheModuleSchemasPinsItsSearchPath() {
    List<Map<String, Object>> functions =
        jdbc.queryForList(
            "SELECT n.nspname || '.' || p.proname AS name,"
                + " coalesce(array_to_string(p.proconfig, ';'), '') AS config"
                + " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = ANY (?::text[])",
            (Object) MODULE_SCHEMAS);

    assertThat(functions).isNotEmpty();
    assertThat(functions)
        .allSatisfy(
            f ->
                assertThat((String) f.get("config"))
                    .as("proconfig of %s", f.get("name"))
                    .contains("search_path=pg_catalog, pg_temp"));
  }

  /** A SECURITY DEFINER function would run a {@code wallet_app} call with the owner's rights. */
  @Test
  void noFunctionInTheModuleSchemasIsSecurityDefiner() {
    assertThat(
            jdbc.queryForList(
                "SELECT n.nspname || '.' || p.proname"
                    + " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = ANY (?::text[]) AND p.prosecdef",
                String.class,
                (Object) MODULE_SCHEMAS))
        .isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      // The predicate contains single quotes, CsvSource's default quote character.
      quoteCharacter = '"',
      value = {
        "ledger.ix_ledger_entries_account_created | (account_id, created_at, id) | ",
        "transfer.ix_transfers_completed_sender_receiver"
            + " | (sender_customer_id, receiver_customer_id)"
            + " | WHERE ((status)::text = 'COMPLETED'::text)",
        "wallet.ix_lookup_attempts_attempted | (attempted_at) | ",
        "auth.ix_users_pending_created | (created_at)"
            + " | WHERE ((status)::text = 'PENDING_VERIFICATION'::text)",
        "auth.ix_password_history_user_created | (user_id, created_at) | ",
        "auth.ix_login_failure_counters_updated | (updated_at) | ",
        "auth.ix_sessions_user | (user_id) | ",
        "auth.ix_refresh_tokens_session | (session_id) | ",
        "auth.ix_refresh_tokens_expires | (expires_at) | ",
        "auth.uq_otp_challenges_live | (identifier_hash, purpose)"
            + " | WHERE ((consumed_at IS NULL) AND (cancelled_at IS NULL) AND (used_up_at IS NULL))",
        "auth.ix_otp_challenges_identifier_sent | (identifier_hash, purpose, sent_at) | ",
        "auth.ix_otp_challenges_identifier_used_up | (identifier_hash, used_up_at)"
            + " | WHERE (used_up_at IS NOT NULL)",
        "auth.ix_otp_challenges_sent | (sent_at) | ",
        "auth.ix_password_reset_tokens_user | (user_id) | ",
        "auth.ix_password_reset_tokens_expires | (expires_at) | "
      })
  void accessPathIndexExists(String index, String columns, String predicate) {
    String definition =
        jdbc.queryForObject("SELECT pg_get_indexdef(?::regclass)", String.class, index);

    assertThat(definition).contains(columns);
    if (predicate != null) {
      assertThat(definition).contains(predicate);
    }
  }
}

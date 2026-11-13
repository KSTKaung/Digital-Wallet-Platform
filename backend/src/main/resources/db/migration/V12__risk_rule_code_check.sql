-- V12: PR 5a step 3 (documentations/08 §4C; doc 06 MVP-ADR-24 "Score"). V1–V11 are unchanged.
--
-- Locks: the ALTER TABLE takes an ACCESS EXCLUSIVE lock on risk.risk_rule_hits for the length of
-- one scan of its existing rows, inside Flyway's single transaction for this file. The application
-- has never written risk.risk_rule_hits before PR 5 (the risk module gets its code in this step), so
-- the scan runs over no rows and a NOT VALID / VALIDATE split would gain nothing (as V11, AA1). A
-- later widening on a populated table should add its check NOT VALID in one migration and VALIDATE
-- it in the next.

-- Rule codes: exactly the four MVP rules (MVP-ADR-24; doc 07 D27) --------------------------------
-- One code per rule, so pk_risk_rule_hits (assessment_id, rule_code) also means "at most one hit per
-- rule and assessment", the highest matching weight. The codes are risk.domain.RiskRule:
--   TRANSFER_AMOUNT     the amount bands
--   TRANSFER_FREQUENCY  the sender's assessments in the window, the current one included
--   NEW_RECIPIENT       no completed transfer from this sender to this recipient yet
--   NEW_SENDER_WALLET   the sender wallet's age bands
-- A new rule is a new migration widening this list. rule_code is NOT NULL (V5), so the IN list
-- cannot be passed by a NULL.
ALTER TABLE risk.risk_rule_hits
  ADD CONSTRAINT ck_risk_rule_hits_rule_code
    CHECK (rule_code IN ('TRANSFER_AMOUNT', 'TRANSFER_FREQUENCY', 'NEW_RECIPIENT',
                         'NEW_SENDER_WALLET'));

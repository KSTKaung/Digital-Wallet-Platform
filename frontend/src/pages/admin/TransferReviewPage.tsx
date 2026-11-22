import { useCallback, useRef, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import {
  getTransferReview,
  type TransferReviewDetail,
  type TransferReviewHistory,
} from '../../api/admin';
import { ApiError } from '../../api/client';
import { STAFF_ERROR_OVERRIDES, type DescribedError } from '../../api/errorMessages';
import { BangkokTime } from '../../components/BangkokTime';
import { useLoad } from '../../components/dataHooks';
import { ErrorNotice, LoadErrorNotice } from '../../components/ErrorNotice';
import { useFocusOn } from '../../components/formHooks';
import { RefreshFailed } from '../../components/RefreshFailed';
import { StatusChip } from '../../components/StatusChip';
import { LINK_CLASSES, SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { formatMoney } from '../../format/money';
import { TransferLedger, TransferStatusChip } from '../transfer/TransferParts';
import { TransferDecision, type RefusalKind, type TransferDecisionKind } from './TransferDecision';
import {
  decidedByLabel,
  decisionMessage,
  isOverdue,
  OVERDUE_LABEL,
  reasonCategoryLabel,
  rejectionCategoryLabel,
  requestedActionLabel,
  reviewActionLabel,
  riskDecisionLabel,
  ruleLabel,
  systemReasonLabel,
  TRANSFER_REVIEW_NOT_FOUND_MESSAGE,
} from './transferReviewText';

/**
 * `/admin/transfer-reviews/:transferId` (COMPLIANCE_OFFICER): any transfer that was ever held for
 * review (doc 02 §2.7 "As built"), with the risk score and rules, both parties' legal names and
 * masked wallet numbers, and the review history. A missing id, a transfer that was never held and
 * a malformed id get one "not found" message. Approve and Reject are offered only while
 * `PENDING_REVIEW` (UI only: the server decides). Refreshed by a button and on window focus (D7).
 */
export function TransferReviewPage() {
  const { transferId = '' } = useParams();
  return (
    <section aria-labelledby="transfer-review-heading" className="mx-auto max-w-3xl space-y-4">
      <h1 id="transfer-review-heading" className="text-xl font-semibold">
        Transfer review
      </h1>
      <TransferReview key={transferId} transferId={transferId} />
      <p>
        <Link to="/admin/transfer-reviews" className={LINK_CLASSES}>
          Back to transfer reviews
        </Link>
      </p>
    </section>
  );
}

/** Stable, so ErrorNotice does not refocus on every re-render. */
const NOT_FOUND_ERROR: DescribedError = { message: TRANSFER_REVIEW_NOT_FOUND_MESSAGE };

/** A `404`, or a `400` for an id that is not a transfer id: the same "not found". */
function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 404 || error.status === 400);
}

/** `done`: a decision answered `200`; its message follows the returned status (decisionMessage). */
type Notice = { kind: 'done'; message: string } | { kind: RefusalKind; error: DescribedError };

interface Loaded {
  transfer: TransferReviewDetail;
  /** When the answer arrived (Date.now()). */
  checkedAt: number;
}

function TransferReview({ transferId }: { transferId: string }) {
  // With the time the answer arrived, for the overdue marker (the render stays pure).
  const fetcher = useCallback(
    async (signal: AbortSignal): Promise<Loaded> => {
      const transfer = await getTransferReview(transferId, signal);
      return { transfer, checkedAt: Date.now() };
    },
    [transferId],
  );
  const { load, refreshing, refreshError, reload, retry, setData } = useLoad(fetcher);
  const [notice, setNotice] = useState<Notice | null>(null);
  const doneRef = useRef<HTMLParagraphElement>(null);
  useFocusOn(doneRef, notice?.kind === 'done' ? notice : null);

  const onStart = useCallback(() => setNotice(null), []);
  const onDecided = useCallback(
    (result: TransferReviewDetail, decision: TransferDecisionKind) => {
      setData({ transfer: result, checkedAt: Date.now() });
      setNotice({ kind: 'done', message: decisionMessage(decision, result.status) });
    },
    [setData],
  );
  const onRefused = useCallback(
    (error: DescribedError, kind: RefusalKind) => {
      setNotice({ kind, error });
      // A 409 means the transfer changed (or a late decision has just expired it); a 403 changed
      // nothing, so the detail on screen is still current.
      if (kind === 'stale') {
        reload();
      }
    },
    [reload],
  );

  if (load.kind === 'loading') {
    return (
      <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
        Loading the transfer…
      </p>
    );
  }
  if (load.kind === 'error') {
    if (isNotFound(load.error)) {
      return <ErrorNotice error={NOT_FOUND_ERROR} />;
    }
    return (
      <LoadErrorNotice error={load.error} overrides={STAFF_ERROR_OVERRIDES}>
        <button type="button" onClick={retry} className={`${SECONDARY_BUTTON_CLASSES} mt-2`}>
          Try again
        </button>
      </LoadErrorNotice>
    );
  }

  const { transfer, checkedAt } = load.data;
  return (
    <div className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <TransferStatusChip status={transfer.status} />
        <button
          type="button"
          onClick={reload}
          disabled={refreshing}
          aria-busy={refreshing}
          className={SECONDARY_BUTTON_CLASSES}
        >
          {refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>
      {refreshError && (
        <RefreshFailed
          error={refreshError.error}
          what="transfer"
          overrides={STAFF_ERROR_OVERRIDES}
        />
      )}
      {notice?.kind === 'done' && (
        <p
          ref={doneRef}
          role="status"
          tabIndex={-1}
          className="rounded-md border border-blue-300 bg-blue-50 p-3 font-medium text-blue-950 focus:outline-2 focus:outline-offset-2 focus:outline-blue-600 dark:border-blue-800 dark:bg-blue-950 dark:text-blue-100"
        >
          {notice.message}
        </p>
      )}
      {(notice?.kind === 'stale' || notice?.kind === 'party') && (
        <ErrorNotice error={notice.error} />
      )}
      <TransferSection transfer={transfer} checkedAt={checkedAt} />
      {transfer.status === 'PENDING_REVIEW' && transfer.transferId !== undefined && (
        <TransferDecision
          transferId={transfer.transferId}
          onStart={onStart}
          onDecided={onDecided}
          onRefused={onRefused}
        />
      )}
      <RiskSection transfer={transfer} />
      <PartiesSection transfer={transfer} />
      <HistorySection history={transfer.history ?? []} />
    </div>
  );
}

function Row({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="grid gap-1 sm:grid-cols-3">
      <dt className="font-medium">{term}</dt>
      <dd className="break-words sm:col-span-2">{children}</dd>
    </div>
  );
}

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section aria-labelledby={id} className="space-y-2">
      <h2 id={id} className="text-lg font-medium">
        {title}
      </h2>
      {children}
    </section>
  );
}

/** A code as sent, with its plain label when it has one. */
function Code({ code, label }: { code: string | null | undefined; label: string }) {
  if (!code) {
    return <>—</>;
  }
  return label === code ? (
    <span className="font-mono">{code}</span>
  ) : (
    <>
      {label} <span className="font-mono text-slate-600 dark:text-slate-400">({code})</span>
    </>
  );
}

function TransferSection({
  transfer,
  checkedAt,
}: {
  transfer: TransferReviewDetail;
  checkedAt: number;
}) {
  const overdue =
    transfer.status === 'PENDING_REVIEW' && isOverdue(transfer.reviewExpiresAt, checkedAt);
  return (
    <Section id="transfer-review-transfer" title="Transfer">
      <dl className="space-y-2 text-sm">
        <Row term="Status">
          <TransferStatusChip status={transfer.status} />
        </Row>
        <Row term="Reference">
          <span className="font-mono break-all">{transfer.reference ?? '—'}</span>
        </Row>
        <Row term="Amount">{formatMoney(transfer.amount, transfer.currency)}</Row>
        <Row term="Created">
          <BangkokTime value={transfer.createdAt} />
        </Row>
        <Row term="Held">
          <BangkokTime value={transfer.heldAt} />
        </Row>
        <Row term="Review deadline">
          <BangkokTime value={transfer.reviewExpiresAt} />
          {overdue && (
            <span className="ml-2">
              <StatusChip tone="danger">{OVERDUE_LABEL}</StatusChip>
            </span>
          )}
        </Row>
        <Row term="Decided">
          <BangkokTime value={transfer.decidedAt} fallback="Not decided yet" />
        </Row>
        <Row term="Rejection category (customer sees)">
          <Code
            code={transfer.rejectionReasonCategory}
            label={rejectionCategoryLabel(transfer.rejectionReasonCategory)}
          />
        </Row>
        <Row term="Ledger">
          <TransferLedger status={transfer.ledgerStatus} />
        </Row>
        <Row term="Transfer ID">
          <span className="break-all">{transfer.transferId ?? '—'}</span>
        </Row>
      </dl>
    </Section>
  );
}

function RiskSection({ transfer }: { transfer: TransferReviewDetail }) {
  const risk = transfer.risk;
  const rules = risk?.rules ?? [];
  return (
    <Section id="transfer-review-risk" title="Risk assessment">
      <dl className="space-y-2 text-sm">
        <Row term="Score">{risk?.score ?? '—'}</Row>
        <Row term="Decision">
          <Code code={risk?.decision} label={riskDecisionLabel(risk?.decision)} />
        </Row>
        <Row term="Assessed">
          <BangkokTime value={risk?.assessedAt} />
        </Row>
      </dl>
      {rules.length === 0 ? (
        <p className="text-sm">No rules were triggered.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Triggered rules, highest weight first</caption>
            <thead>
              <tr className="border-b border-slate-300 dark:border-slate-600">
                <th scope="col" className="py-2 pr-4 font-medium">
                  Rule
                </th>
                <th scope="col" className="py-2 pr-4 text-right font-medium">
                  Weight
                </th>
              </tr>
            </thead>
            <tbody>
              {rules.map((rule, index) => (
                <tr
                  key={`${rule.code ?? ''}-${index}`}
                  className="border-b border-slate-200 last:border-0 dark:border-slate-700"
                >
                  <td className="py-2 pr-4">
                    <Code code={rule.code} label={ruleLabel(rule.code)} />
                  </td>
                  <td className="py-2 pr-4 text-right">{rule.weight ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Section>
  );
}

function PartiesSection({ transfer }: { transfer: TransferReviewDetail }) {
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <Party id="transfer-review-sender" title="Sender" party={transfer.sender} />
      <Party id="transfer-review-recipient" title="Recipient" party={transfer.recipient} />
    </div>
  );
}

function Party({
  id,
  title,
  party,
}: {
  id: string;
  title: string;
  party: TransferReviewDetail['sender'];
}) {
  const legalName = [party?.firstName, party?.lastName].filter(Boolean).join(' ');
  return (
    <Section id={id} title={title}>
      <dl className="space-y-2 text-sm">
        <div>
          <dt className="font-medium">Legal name</dt>
          <dd className="break-words">{legalName || '—'}</dd>
        </div>
        <div>
          <dt className="font-medium">Wallet number (masked)</dt>
          <dd className="font-mono">{party?.maskedWalletNumber ?? '—'}</dd>
        </div>
        <div>
          <dt className="font-medium">Customer ID</dt>
          <dd className="break-all">{party?.customerId ?? '—'}</dd>
        </div>
      </dl>
    </Section>
  );
}

function HistorySection({ history }: { history: TransferReviewHistory[] }) {
  return (
    <Section id="transfer-review-history" title="Review history">
      {history.length === 0 ? (
        <p className="text-sm">Not decided yet: no review outcome is recorded.</p>
      ) : (
        history.map((entry, index) => (
          <dl
            key={`${entry.recordedAt ?? ''}-${index}`}
            className="space-y-2 rounded-md border border-slate-200 p-3 text-sm dark:border-slate-700"
          >
            <Row term="Outcome">
              <Code code={entry.action} label={reviewActionLabel(entry.action)} />
            </Row>
            <Row term="Decided by">
              <Code code={entry.decidedBy} label={decidedByLabel(entry.decidedBy)} />
            </Row>
            <Row term="Requested action">
              <Code
                code={entry.requestedAction}
                label={requestedActionLabel(entry.requestedAction)}
              />
            </Row>
            <Row term="Actor ID">
              <span className="break-all">{entry.actorUserId ?? '—'}</span>
            </Row>
            <Row term="Reason category">
              <Code code={entry.reasonCategory} label={reasonCategoryLabel(entry.reasonCategory)} />
            </Row>
            <Row term="Reviewer's reason (staff only)">
              {entry.reason ? <span className="whitespace-pre-wrap">{entry.reason}</span> : '—'}
            </Row>
            <Row term="System reason">
              <Code code={entry.systemReason} label={systemReasonLabel(entry.systemReason)} />
            </Row>
            <Row term="Recorded">
              <BangkokTime value={entry.recordedAt} />
            </Row>
          </dl>
        ))
      )}
    </Section>
  );
}

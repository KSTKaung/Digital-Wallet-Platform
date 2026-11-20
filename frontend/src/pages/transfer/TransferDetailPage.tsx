import { useCallback } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '../../api/client';
import { describeError } from '../../api/errorMessages';
import { getTransfer } from '../../api/transfers';
import { BangkokTime } from '../../components/BangkokTime';
import { useLoad } from '../../components/dataHooks';
import { ErrorNotice } from '../../components/ErrorNotice';
import { RefreshFailed } from '../../components/RefreshFailed';
import { LINK_CLASSES, SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { SIMULATED_MONEY_NOTE } from '../wallet/walletText';
import { CancelTransfer } from './CancelTransfer';
import { TransferSummary } from './TransferParts';
import { HELD_FOR_REVIEW_NOTE, TRANSFER_NOT_FOUND_MESSAGE } from './transferText';

/**
 * One transfer (`/transfers/:id`; the transfer id in the URL is not sensitive). A missing transfer
 * and one the caller may not see are the same `404` and get one message. A held transfer the
 * caller sent can be cancelled. Refreshed by a button and on window focus (D7).
 */
export function TransferDetailPage() {
  const { id = '' } = useParams();
  return (
    <section aria-labelledby="transfer-detail-heading" className="mx-auto max-w-xl space-y-4">
      <h1 id="transfer-detail-heading" className="text-xl font-semibold">
        Transfer details
      </h1>
      <TransferDetail key={id} id={id} />
      <p>
        <Link to="/transfers" className={LINK_CLASSES}>
          Back to your transfers
        </Link>
      </p>
    </section>
  );
}

/** A `404`, or a `400` for an id that is not a transfer id: the same "not found". */
function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 404 || error.status === 400);
}

function TransferDetail({ id }: { id: string }) {
  const fetcher = useCallback((signal: AbortSignal) => getTransfer(id, signal), [id]);
  const { load, refreshing, refreshError, reload, retry, setData } = useLoad(fetcher);

  if (load.kind === 'loading') {
    return (
      <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
        Loading the transfer…
      </p>
    );
  }
  if (load.kind === 'error') {
    if (isNotFound(load.error)) {
      return <ErrorNotice error={{ message: TRANSFER_NOT_FOUND_MESSAGE }} />;
    }
    return (
      <ErrorNotice error={describeError(load.error)}>
        <button type="button" onClick={retry} className={`${SECONDARY_BUTTON_CLASSES} mt-2`}>
          Try again
        </button>
      </ErrorNotice>
    );
  }

  const transfer = load.data;
  return (
    <div className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <div className="flex flex-wrap items-center justify-end gap-2">
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
      {refreshError && <RefreshFailed error={refreshError.error} what="transfer" />}
      {transfer.status === 'PENDING_REVIEW' && transfer.direction === 'SENT' && (
        <p>
          {HELD_FOR_REVIEW_NOTE} Review deadline: <BangkokTime value={transfer.reviewExpiresAt} />.
        </p>
      )}
      <TransferSummary transfer={transfer} />
      <CancelTransfer transfer={transfer} onCancelled={setData} onStale={reload} />
      <p className="text-sm text-slate-600 dark:text-slate-400">{SIMULATED_MONEY_NOTE}</p>
    </div>
  );
}

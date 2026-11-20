import { useCallback, useRef, useState } from 'react';
import { Link } from 'react-router';
import { describeError } from '../../api/errorMessages';
import { listTransfers, type Transfer } from '../../api/transfers';
import { BangkokTime } from '../../components/BangkokTime';
import { useLoad, usePageParam } from '../../components/dataHooks';
import { ErrorNotice } from '../../components/ErrorNotice';
import { useFocusOn } from '../../components/formHooks';
import { Pager } from '../../components/Pager';
import { RefreshFailed } from '../../components/RefreshFailed';
import { LINK_CLASSES, SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { TransferLedger, TransferStatusChip } from './TransferParts';
import {
  counterpartyPrefix,
  counterpartyText,
  directionLabel,
  rejectionReasonText,
  signedTransferAmount,
} from './transferText';

/**
 * The caller's transfers, newest first (doc 02 §2.4): sent ones in every status, received ones
 * once completed. The page number is in `?page=` (not sensitive). Refreshed by a button and on
 * window focus (D7).
 */
export function TransfersPage() {
  const [page, setPage] = usePageParam();
  const [moved, setMoved] = useState(false);

  return (
    <section aria-labelledby="transfers-heading" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 id="transfers-heading" className="text-xl font-semibold">
          Transfers
        </h1>
        <Link to="/transfer" className={LINK_CLASSES}>
          Make a transfer
        </Link>
      </div>
      <TransferList
        key={page}
        page={page}
        focusHeading={moved}
        onPage={(next) => {
          setMoved(true);
          setPage(next);
        }}
      />
    </section>
  );
}

function TransferList({
  page,
  focusHeading,
  onPage,
}: {
  page: number;
  focusHeading: boolean;
  onPage: (page: number) => void;
}) {
  const fetcher = useCallback(
    (signal: AbortSignal) => listTransfers({ page: page - 1 }, signal),
    [page],
  );
  const { load, refreshing, refreshError, reload, retry } = useLoad(fetcher);
  const headingRef = useRef<HTMLHeadingElement>(null);
  useFocusOn(headingRef, focusHeading);

  return (
    <section aria-labelledby="transfers-list-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2
          id="transfers-list-heading"
          ref={headingRef}
          tabIndex={-1}
          className="text-lg font-medium focus:outline-none"
        >
          Your transfers, page {page}
        </h2>
        {load.kind === 'loaded' && (
          <button
            type="button"
            onClick={reload}
            disabled={refreshing}
            aria-busy={refreshing}
            className={SECONDARY_BUTTON_CLASSES}
          >
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        )}
      </div>
      {load.kind === 'loading' && (
        <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
          Loading your transfers…
        </p>
      )}
      {load.kind === 'error' && (
        <ErrorNotice error={describeError(load.error)}>
          <button type="button" onClick={retry} className={`${SECONDARY_BUTTON_CLASSES} mt-2`}>
            Try again
          </button>
        </ErrorNotice>
      )}
      {load.kind === 'loaded' && (
        <>
          {refreshError && <RefreshFailed error={refreshError.error} what="transfers" />}
          <TransferTable items={load.data.items ?? []} page={page} onPage={onPage} />
          <Pager
            label="Transfer pages"
            page={page}
            totalPages={load.data.page?.totalPages ?? 0}
            onPage={onPage}
          />
        </>
      )}
    </section>
  );
}

function TransferTable({
  items,
  page,
  onPage,
}: {
  items: Transfer[];
  page: number;
  onPage: (page: number) => void;
}) {
  if (items.length === 0) {
    return page > 1 ? (
      <div className="space-y-2">
        <p>There are no transfers on this page.</p>
        <button type="button" onClick={() => onPage(1)} className={SECONDARY_BUTTON_CLASSES}>
          Go to the first page
        </button>
      </div>
    ) : (
      <p>No transfers yet.</p>
    );
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">Your transfers, newest first</caption>
        <thead>
          <tr className="border-b border-slate-300 dark:border-slate-600">
            <th scope="col" className="py-2 pr-4 font-medium">
              Created
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Direction
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Other party
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Amount
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Status
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Ledger
            </th>
            <th scope="col" className="py-2 font-medium">
              <span className="sr-only">Details</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((transfer, index) => {
            const rejection =
              transfer.status === 'REJECTED' ? rejectionReasonText(transfer.rejectionReason) : null;
            return (
              <tr
                key={transfer.id ?? index}
                className="border-b border-slate-200 align-top last:border-0 dark:border-slate-700"
              >
                <td className="py-2 pr-4">
                  <BangkokTime value={transfer.createdAt} />
                </td>
                <td className="py-2 pr-4">{directionLabel(transfer.direction)}</td>
                <td className="py-2 pr-4">
                  <span className="sr-only">{counterpartyPrefix(transfer.direction)}:</span>{' '}
                  {counterpartyText(transfer.counterparty)}
                </td>
                <td className="py-2 pr-4 whitespace-nowrap">{signedTransferAmount(transfer)}</td>
                <td className="py-2 pr-4">
                  <TransferStatusChip status={transfer.status} />
                  {rejection && <p className="mt-1">{rejection}</p>}
                </td>
                <td className="py-2 pr-4">
                  <TransferLedger status={transfer.ledgerStatus} />
                </td>
                <td className="py-2">
                  {transfer.id !== undefined && (
                    <Link
                      to={`/transfers/${encodeURIComponent(transfer.id)}`}
                      className={LINK_CLASSES}
                    >
                      Details{' '}
                      <span className="sr-only">of transfer {transfer.reference ?? ''}</span>
                    </Link>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

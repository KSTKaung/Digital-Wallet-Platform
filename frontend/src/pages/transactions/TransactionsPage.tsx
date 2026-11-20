import { useCallback, useRef, useState } from 'react';
import { describeError } from '../../api/errorMessages';
import { listTransactions, type Transaction } from '../../api/transactions';
import { BangkokTime } from '../../components/BangkokTime';
import { useLoad, usePageParam } from '../../components/dataHooks';
import { ErrorNotice } from '../../components/ErrorNotice';
import { useFocusOn } from '../../components/formHooks';
import { Pager } from '../../components/Pager';
import { RefreshFailed } from '../../components/RefreshFailed';
import { SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { formatMoney } from '../../format/money';
import { LedgerStatusChip } from '../wallet/WalletChips';
import { SIMULATED_MONEY_NOTE } from '../wallet/walletText';

/** The documented item types (doc 02 §2.4 "MVP history"): a label and the side of the entry. */
const TYPES: Readonly<Record<string, { label: string; flow: 'in' | 'out' }>> = {
  DEPOSIT: { label: 'Deposit', flow: 'in' },
  WITHDRAWAL: { label: 'Withdrawal', flow: 'out' },
  TRANSFER_IN: { label: 'Transfer received', flow: 'in' },
  TRANSFER_OUT: { label: 'Transfer sent', flow: 'out' },
};

/**
 * The caller's completed movements from the ledger, newest first (doc 02 §2.4 "MVP history";
 * MVP-ADR-11). Before KYC approval the server answers an empty page. The page number is in
 * `?page=`. Refreshed by a button and on window focus (D7).
 */
export function TransactionsPage() {
  const [page, setPage] = usePageParam();
  const [moved, setMoved] = useState(false);

  return (
    <section aria-labelledby="transactions-heading" className="space-y-4">
      <h1 id="transactions-heading" className="text-xl font-semibold">
        History
      </h1>
      <p className="text-slate-700 dark:text-slate-300">
        Completed deposits, withdrawals and transfers. Transfers that are held, cancelled or refused
        are listed under Transfers.
      </p>
      <p className="text-sm text-slate-600 dark:text-slate-400">{SIMULATED_MONEY_NOTE}</p>
      <TransactionList
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

function TransactionList({
  page,
  focusHeading,
  onPage,
}: {
  page: number;
  focusHeading: boolean;
  onPage: (page: number) => void;
}) {
  const fetcher = useCallback(
    (signal: AbortSignal) => listTransactions({ page: page - 1 }, signal),
    [page],
  );
  const { load, refreshing, refreshError, reload, retry } = useLoad(fetcher);
  const headingRef = useRef<HTMLHeadingElement>(null);
  useFocusOn(headingRef, focusHeading);

  return (
    <section aria-labelledby="transactions-list-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2
          id="transactions-list-heading"
          ref={headingRef}
          tabIndex={-1}
          className="text-lg font-medium focus:outline-none"
        >
          Transactions, page {page}
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
          Loading your history…
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
          {refreshError && <RefreshFailed error={refreshError.error} what="history" />}
          <TransactionTable items={load.data.items ?? []} page={page} onPage={onPage} />
          <Pager
            label="History pages"
            page={page}
            totalPages={load.data.page?.totalPages ?? 0}
            onPage={onPage}
          />
        </>
      )}
    </section>
  );
}

/** "+150.00 THB" for money in, "−150.00 THB" for money out; unknown types without a sign. */
function signedAmount(item: Transaction): string {
  const money = formatMoney(item.amount, item.currency);
  const flow = item.type ? TYPES[item.type]?.flow : undefined;
  if (flow === 'in') {
    return `+${money}`;
  }
  return flow === 'out' ? `−${money}` : money;
}

function flowLabel(item: Transaction): string {
  const flow = item.type ? TYPES[item.type]?.flow : undefined;
  if (flow === 'in') {
    return 'Money in';
  }
  return flow === 'out' ? 'Money out' : '—';
}

function TransactionTable({
  items,
  page,
  onPage,
}: {
  items: Transaction[];
  page: number;
  onPage: (page: number) => void;
}) {
  if (items.length === 0) {
    return page > 1 ? (
      <div className="space-y-2">
        <p>There are no transactions on this page.</p>
        <button type="button" onClick={() => onPage(1)} className={SECONDARY_BUTTON_CLASSES}>
          Go to the first page
        </button>
      </div>
    ) : (
      <p>No transactions yet.</p>
    );
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">Your transactions, newest first</caption>
        <thead>
          <tr className="border-b border-slate-300 dark:border-slate-600">
            <th scope="col" className="py-2 pr-4 font-medium">
              Posted
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Type
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              In or out
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Amount
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Reference
            </th>
            <th scope="col" className="py-2 font-medium">
              Ledger
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item, index) => (
            <tr
              key={`${item.reference ?? ''}-${item.type ?? ''}-${index}`}
              className="border-b border-slate-200 align-top last:border-0 dark:border-slate-700"
            >
              <td className="py-2 pr-4">
                <BangkokTime value={item.postedAt} />
              </td>
              <td className="py-2 pr-4">
                {(item.type && TYPES[item.type]?.label) ?? item.type ?? 'Unknown'}
              </td>
              <td className="py-2 pr-4">{flowLabel(item)}</td>
              <td className="py-2 pr-4 whitespace-nowrap">{signedAmount(item)}</td>
              <td className="py-2 pr-4 break-all">{item.reference ?? '—'}</td>
              <td className="py-2">
                <LedgerStatusChip status={item.ledgerStatus} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

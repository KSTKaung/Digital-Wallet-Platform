import { useCallback, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import {
  DEFAULT_KYC_REVIEW_STATUS,
  isKycReviewStatus,
  listKycApplications,
  type KycReviewStatus,
  type KycReviewSummary,
} from '../../api/admin';
import { STAFF_ERROR_OVERRIDES } from '../../api/errorMessages';
import { BangkokTime } from '../../components/BangkokTime';
import { useLoad, usePageParam } from '../../components/dataHooks';
import { LoadErrorNotice } from '../../components/ErrorNotice';
import { useFocusOn } from '../../components/formHooks';
import { Pager } from '../../components/Pager';
import { RefreshFailed } from '../../components/RefreshFailed';
import { SelectField } from '../../components/SelectField';
import { StatusChip } from '../../components/StatusChip';
import { LINK_CLASSES, SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { DOCUMENT_OPTIONS, LEVEL_OPTIONS, optionLabel } from '../kyc/kycText';
import {
  EMPTY_QUEUE_MESSAGES,
  KYC_REVIEW_STATUS_OPTIONS,
  reviewStatusLabel,
  reviewStatusTone,
} from './kycReviewText';

/**
 * The status filter from `?status=` (not sensitive). A missing or unknown value is the default
 * and is never sent to the server. Changing it goes back to page 1; the default has no parameter.
 */
function useStatusParam(): [KycReviewStatus, (status: KycReviewStatus) => void] {
  const [params, setParams] = useSearchParams();
  const raw = params.get('status');
  const status = isKycReviewStatus(raw) ? raw : DEFAULT_KYC_REVIEW_STATUS;
  const setStatus = useCallback(
    (next: KycReviewStatus) =>
      setParams(next === DEFAULT_KYC_REVIEW_STATUS ? {} : { status: next }),
    [setParams],
  );
  return [status, setStatus];
}

/**
 * `/admin/kyc` (COMPLIANCE_OFFICER): submitted applications by status, oldest submission first
 * (doc 02 §2.7). Status and page live in the URL; refreshed by a button and on window focus (D7).
 */
export function KycQueuePage() {
  const [status, setStatus] = useStatusParam();
  const [page, setPage] = usePageParam();
  const [moved, setMoved] = useState(false);

  return (
    <section aria-labelledby="kyc-queue-heading" className="space-y-4">
      <h1 id="kyc-queue-heading" className="text-xl font-semibold">
        KYC reviews
      </h1>
      <div className="max-w-xs">
        <SelectField
          label="Status"
          name="status"
          value={status}
          onChange={(next) => {
            setMoved(false);
            setStatus(next);
          }}
          options={KYC_REVIEW_STATUS_OPTIONS}
        />
      </div>
      <KycQueue
        key={`${status}-${page}`}
        status={status}
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

function KycQueue({
  status,
  page,
  focusHeading,
  onPage,
}: {
  status: KycReviewStatus;
  page: number;
  focusHeading: boolean;
  onPage: (page: number) => void;
}) {
  const fetcher = useCallback(
    (signal: AbortSignal) => listKycApplications({ status, page: page - 1 }, signal),
    [status, page],
  );
  const { load, refreshing, refreshError, reload, retry } = useLoad(fetcher);
  const headingRef = useRef<HTMLHeadingElement>(null);
  useFocusOn(headingRef, focusHeading);
  const statusText = reviewStatusLabel(status);

  return (
    <section aria-labelledby="kyc-queue-list-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2
          id="kyc-queue-list-heading"
          ref={headingRef}
          tabIndex={-1}
          className="text-lg font-medium focus:outline-none"
        >
          {statusText}, page {page}
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
          Loading applications…
        </p>
      )}
      {load.kind === 'error' && (
        <LoadErrorNotice error={load.error} overrides={STAFF_ERROR_OVERRIDES}>
          <button type="button" onClick={retry} className={`${SECONDARY_BUTTON_CLASSES} mt-2`}>
            Try again
          </button>
        </LoadErrorNotice>
      )}
      {load.kind === 'loaded' && (
        <>
          {refreshError && (
            <RefreshFailed
              error={refreshError.error}
              what="applications"
              overrides={STAFF_ERROR_OVERRIDES}
            />
          )}
          <QueueTable items={load.data.items ?? []} status={status} page={page} onPage={onPage} />
          <Pager
            label="Application pages"
            page={page}
            totalPages={load.data.page?.totalPages ?? 0}
            onPage={onPage}
          />
        </>
      )}
    </section>
  );
}

function QueueTable({
  items,
  status,
  page,
  onPage,
}: {
  items: KycReviewSummary[];
  status: KycReviewStatus;
  page: number;
  onPage: (page: number) => void;
}) {
  if (items.length === 0) {
    return page > 1 ? (
      <div className="space-y-2">
        <p>There are no applications on this page.</p>
        <button type="button" onClick={() => onPage(1)} className={SECONDARY_BUTTON_CLASSES}>
          Go to the first page
        </button>
      </div>
    ) : (
      <p>{EMPTY_QUEUE_MESSAGES[status]}</p>
    );
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">KYC applications, oldest submission first</caption>
        <thead>
          <tr className="border-b border-slate-300 dark:border-slate-600">
            <th scope="col" className="py-2 pr-4 font-medium">
              Submitted
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Name
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Level
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Document
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Status
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              Decided
            </th>
            <th scope="col" className="py-2 font-medium">
              <span className="sr-only">Details</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item, index) => (
            <tr
              key={item.id ?? index}
              className="border-b border-slate-200 align-top last:border-0 dark:border-slate-700"
            >
              <td className="py-2 pr-4">
                <BangkokTime value={item.submittedAt} />
              </td>
              <td className="py-2 pr-4">
                <p>{item.maskedName ?? '—'}</p>
                <p className="text-slate-600 dark:text-slate-400">
                  Customer ID: <span className="break-all">{item.customerId ?? '—'}</span>
                </p>
              </td>
              <td className="py-2 pr-4">{optionLabel(LEVEL_OPTIONS, item.verificationLevel)}</td>
              <td className="py-2 pr-4">
                <p>{optionLabel(DOCUMENT_OPTIONS, item.documentType)}</p>
                <p className="font-mono">{item.documentReference ?? '—'}</p>
              </td>
              <td className="py-2 pr-4">
                <StatusChip tone={reviewStatusTone(item.status)}>
                  {reviewStatusLabel(item.status)}
                </StatusChip>
              </td>
              <td className="py-2 pr-4">
                <BangkokTime value={item.decidedAt} fallback="Not decided" />
              </td>
              <td className="py-2">
                {item.id !== undefined && (
                  <Link to={`/admin/kyc/${encodeURIComponent(item.id)}`} className={LINK_CLASSES}>
                    {item.status === 'PENDING_REVIEW' ? 'Review' : 'Details'}{' '}
                    <span className="sr-only">
                      of the application from {item.maskedName ?? '—'}
                    </span>
                  </Link>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

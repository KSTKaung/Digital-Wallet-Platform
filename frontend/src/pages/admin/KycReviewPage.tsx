import { useCallback, useRef, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { getKycApplication, type KycReviewDetail } from '../../api/admin';
import { ApiError } from '../../api/client';
import { STAFF_ERROR_OVERRIDES, type DescribedError } from '../../api/errorMessages';
import { BangkokTime } from '../../components/BangkokTime';
import { useLoad } from '../../components/dataHooks';
import { ErrorNotice, LoadErrorNotice } from '../../components/ErrorNotice';
import { useFocusOn } from '../../components/formHooks';
import { RefreshFailed } from '../../components/RefreshFailed';
import { StatusChip } from '../../components/StatusChip';
import { LINK_CLASSES, SECONDARY_BUTTON_CLASSES } from '../../components/SubmitButton';
import { countryName } from '../../format/country';
import { formatCalendarDate } from '../../format/dateTime';
import { DOCUMENT_OPTIONS, LEVEL_OPTIONS, optionLabel } from '../kyc/kycText';
import { KycDecision, type KycDecisionKind } from './KycDecision';
import {
  APPROVED_MESSAGE,
  KYC_APPLICATION_NOT_FOUND_MESSAGE,
  REJECTED_MESSAGE,
  reviewStatusLabel,
  reviewStatusTone,
} from './kycReviewText';

/**
 * `/admin/kyc/:id` (COMPLIANCE_OFFICER): one submitted application (doc 02 §2.7), with the legal
 * name in full, the document reference masked by the server and the date of birth and country as
 * copied at submit. A missing id and a draft are the same `404` and get one message. Approve and
 * Reject are offered only while `PENDING_REVIEW` (UI only: the server decides). Refreshed by a
 * button and on window focus (D7).
 */
export function KycReviewPage() {
  const { id = '' } = useParams();
  return (
    <section aria-labelledby="kyc-review-heading" className="mx-auto max-w-2xl space-y-4">
      <h1 id="kyc-review-heading" className="text-xl font-semibold">
        KYC application
      </h1>
      <KycReview key={id} id={id} />
      <p>
        <Link to="/admin/kyc" className={LINK_CLASSES}>
          Back to KYC reviews
        </Link>
      </p>
    </section>
  );
}

/** Stable, so ErrorNotice does not refocus on every re-render. */
const NOT_FOUND_ERROR: DescribedError = { message: KYC_APPLICATION_NOT_FOUND_MESSAGE };

/** A `404`, or a `400` for an id that is not an application id: the same "not found". */
function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 404 || error.status === 400);
}

type Notice = { kind: KycDecisionKind } | { kind: 'stale'; error: DescribedError };

function KycReview({ id }: { id: string }) {
  const fetcher = useCallback((signal: AbortSignal) => getKycApplication(id, signal), [id]);
  const { load, refreshing, refreshError, reload, retry, setData } = useLoad(fetcher);
  const [notice, setNotice] = useState<Notice | null>(null);
  const doneRef = useRef<HTMLParagraphElement>(null);
  useFocusOn(doneRef, notice?.kind === 'approved' || notice?.kind === 'rejected' ? notice : null);

  const onStart = useCallback(() => setNotice(null), []);
  const onDecided = useCallback(
    (result: KycReviewDetail, decision: KycDecisionKind) => {
      setData(result);
      setNotice({ kind: decision });
    },
    [setData],
  );
  const onStale = useCallback(
    (error: DescribedError) => {
      setNotice({ kind: 'stale', error });
      reload();
    },
    [reload],
  );

  if (load.kind === 'loading') {
    return (
      <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
        Loading the application…
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

  const application = load.data;
  return (
    <div className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <StatusChip tone={reviewStatusTone(application.status)}>
          {reviewStatusLabel(application.status)}
        </StatusChip>
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
          what="application"
          overrides={STAFF_ERROR_OVERRIDES}
        />
      )}
      {(notice?.kind === 'approved' || notice?.kind === 'rejected') && (
        <p
          ref={doneRef}
          role="status"
          tabIndex={-1}
          className="rounded-md border border-blue-300 bg-blue-50 p-3 font-medium text-blue-950 focus:outline-2 focus:outline-offset-2 focus:outline-blue-600 dark:border-blue-800 dark:bg-blue-950 dark:text-blue-100"
        >
          {notice.kind === 'approved' ? APPROVED_MESSAGE : REJECTED_MESSAGE}
        </p>
      )}
      {notice?.kind === 'stale' && <ErrorNotice error={notice.error} />}
      <ApplicationDetails application={application} />
      {application.status === 'PENDING_REVIEW' && application.id !== undefined && (
        <KycDecision
          id={application.id}
          onStart={onStart}
          onDecided={onDecided}
          onStale={onStale}
        />
      )}
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

function ApplicationDetails({ application }: { application: KycReviewDetail }) {
  const legalName = [application.firstName, application.lastName].filter(Boolean).join(' ');
  return (
    <dl className="space-y-2 text-sm">
      <Row term="Legal name">{legalName || '—'}</Row>
      <Row term="Date of birth (at submission)">
        {formatCalendarDate(application.dateOfBirth) ?? '—'}
      </Row>
      <Row term="Country (at submission)">{countryName(application.countryCode) ?? '—'}</Row>
      <Row term="Verification level">
        {optionLabel(LEVEL_OPTIONS, application.verificationLevel)}
      </Row>
      <Row term="Document type">{optionLabel(DOCUMENT_OPTIONS, application.documentType)}</Row>
      <Row term="Document reference (masked)">
        <span className="font-mono">{application.documentReference ?? '—'}</span>
      </Row>
      <Row term="Submitted">
        <BangkokTime value={application.submittedAt} />
      </Row>
      <Row term="Decided">
        <BangkokTime value={application.decidedAt} fallback="Not decided yet" />
      </Row>
      {application.rejectionReason && (
        <Row term="Rejection reason">
          <span className="whitespace-pre-wrap">{application.rejectionReason}</span>
        </Row>
      )}
      <Row term="Reviewer ID">
        <span className="break-all">{application.reviewerId ?? '—'}</span>
      </Row>
      <Row term="Customer ID">
        <span className="break-all">{application.customerId ?? '—'}</span>
      </Row>
      <Row term="Application ID">
        <span className="break-all">{application.id ?? '—'}</span>
      </Row>
      <Row term="Created">
        <BangkokTime value={application.createdAt} />
      </Row>
      <Row term="Last updated">
        <BangkokTime value={application.updatedAt} />
      </Row>
    </dl>
  );
}

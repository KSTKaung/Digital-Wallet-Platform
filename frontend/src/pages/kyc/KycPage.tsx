import { useCallback, useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../../api/client';
import { describeError, validationErrorField, type DescribedError } from '../../api/errorMessages';
import {
  createApplication,
  getApplication,
  getKycStatus,
  submitApplication,
  updateApplication,
  type KycApplication,
  type KycApplicationRequest,
  type KycStatusResponse,
} from '../../api/kyc';
import { BangkokTime } from '../../components/BangkokTime';
import { useRefetchOnFocus } from '../../components/dataHooks';
import { ErrorNotice, InfoNotice } from '../../components/ErrorNotice';
import { useFocusOn, useSubmitGuard } from '../../components/formHooks';
import {
  LINK_CLASSES,
  PRIMARY_BUTTON_CLASSES,
  SECONDARY_BUTTON_CLASSES,
} from '../../components/SubmitButton';
import { KycApplicationForm } from './KycApplicationForm';
import { DOCUMENT_OPTIONS, LEVEL_OPTIONS, optionLabel as label, statusLabel } from './kycText';

export const ALREADY_ACTIVE_MESSAGE =
  'You already have an active KYC application. The latest status is shown below.';
export const NOT_CHANGEABLE_MESSAGE =
  'This application can no longer be changed: it has already been submitted or decided. The latest status is shown below.';
export const APPLICATION_GONE_MESSAGE =
  'We could not find this application. The latest status is shown below.';
export const DRAFT_SAVED_MESSAGE = 'Draft saved. Check it, then submit it for review.';
export const SUBMITTED_MESSAGE = 'Application submitted for review.';
/** Defensive: the server documents an id for every status but NONE (doc 02 §2.2). */
export const DETAILS_UNAVAILABLE_MESSAGE =
  'The details of your application cannot be shown right now. Refresh the status or try again later.';
export const DETAILS_LOAD_FAILED_MESSAGE = 'The details of your application could not be loaded.';

/** Statuses that describe an application whose details GET /kyc/applications/{id} returns. */
const STATUSES_WITH_DETAILS: ReadonlySet<string> = new Set([
  'DRAFT',
  'PENDING_REVIEW',
  'APPROVED',
  'REJECTED',
]);

interface View {
  status: KycStatusResponse;
  /** Detail of the application /kyc/status reports (its `applicationId`). */
  application: KycApplication | null;
  /** Why the detail could not be loaded; the status is still shown. */
  detailError: DescribedError | null;
}

type Load =
  { kind: 'loading' } | { kind: 'error'; error: DescribedError } | { kind: 'loaded'; view: View };

/** The status, then the detail of the application it reports (documentations/08 §4D D11). */
async function fetchView(signal: AbortSignal): Promise<View> {
  const status = await getKycStatus(signal);
  const id = status.applicationId ?? null;
  if (id === null || status.status === undefined || !STATUSES_WITH_DETAILS.has(status.status)) {
    return { status, application: null, detailError: null };
  }
  try {
    return { status, application: await getApplication(id, signal), detailError: null };
  } catch (error) {
    if (signal.aborted) {
      throw error;
    }
    const described = describeError(error);
    return {
      status,
      application: null,
      detailError: { ...described, message: `${DETAILS_LOAD_FAILED_MESSAGE} ${described.message}` },
    };
  }
}

/**
 * KYC for the customer (doc 02 §2.2; doc 06 MVP-ADR-10; doc 05 §5.1 steps 4–6). The status comes
 * from GET /kyc/status, refreshed by a button and when the window regains focus (no polling,
 * documentations/08 §4D D7). The application it reports (`applicationId`, D11) is loaded from
 * GET /kyc/applications/{id} for its details, so a draft or a rejection reason is reachable after a
 * reload. The server decides every transition; a refused one reloads the status. KYC content stays
 * in component state and the masked reference is shown as returned.
 */
export function KycPage() {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [version, setVersion] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<DescribedError | null>(null);
  const [conflict, setConflict] = useState<DescribedError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [focusKey, setFocusKey] = useState(0);
  const statusHeadingRef = useRef<HTMLHeadingElement>(null);
  useFocusOn(statusHeadingRef, focusKey);
  // The status load in flight, so a mutation answer can cancel it (see applyApplication).
  const inFlight = useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    inFlight.current = controller;
    fetchView(controller.signal)
      .then((view) => {
        if (controller.signal.aborted) {
          return;
        }
        setLoad({ kind: 'loaded', view });
        setRefreshError(null);
        setRefreshing(false);
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        const described = describeError(error);
        setRefreshing(false);
        setRefreshError(described);
        setLoad((prev) => (prev.kind === 'loaded' ? prev : { kind: 'error', error: described }));
      });
    return () => controller.abort();
  }, [version]);

  const reload = useCallback(() => {
    setRefreshing(true);
    setVersion((n) => n + 1);
  }, []);

  useRefetchOnFocus(reload, load.kind === 'loaded');

  /**
   * A successful create, edit or submit: the response is the application's new state, and the
   * application /kyc/status reports from now on, so the next refetch loads the same id. A status
   * load still in flight (focus refetch, Refresh) is aborted: it may have been answered before
   * the change and would put the older state (and its buttons) back on screen.
   */
  const applyApplication = useCallback((application: KycApplication, message: string) => {
    inFlight.current?.abort();
    setRefreshing(false);
    setConflict(null);
    setNotice(message);
    setLoad((prev) =>
      prev.kind !== 'loaded'
        ? prev
        : {
            kind: 'loaded',
            view: {
              status: {
                ...prev.view.status,
                status: application.status,
                applicationId: application.id,
                verificationLevel: application.verificationLevel,
              },
              application,
              detailError: null,
            },
          },
    );
    setFocusKey((n) => n + 1);
  }, []);

  /**
   * A refused transition (another tab, a staff decision, a stale page): show why and reload the
   * status. Returns false for any other error, which the form shows itself.
   */
  const handleConflict = useCallback(
    (error: unknown): boolean => {
      if (!(error instanceof ApiError)) {
        return false;
      }
      let message: string;
      if (error.errorCode === 'KYC_ALREADY_ACTIVE') {
        message = ALREADY_ACTIVE_MESSAGE;
      } else if (error.errorCode === 'INVALID_KYC_STATE') {
        message = NOT_CHANGEABLE_MESSAGE;
      } else if (error.status === 404) {
        message = APPLICATION_GONE_MESSAGE;
      } else {
        return false;
      }
      setNotice(null);
      setConflict(describeError(error, { [error.errorCode]: message }));
      reload();
      return true;
    },
    [reload],
  );

  const create = useCallback(
    async (request: KycApplicationRequest) => {
      try {
        applyApplication(await createApplication(request), DRAFT_SAVED_MESSAGE);
      } catch (error) {
        if (!handleConflict(error)) {
          throw error;
        }
      }
    },
    [applyApplication, handleConflict],
  );

  return (
    <section aria-labelledby="kyc-heading" className="mx-auto max-w-2xl space-y-5">
      <h1 id="kyc-heading" className="text-xl font-semibold">
        KYC verification
      </h1>
      <p className="text-slate-700 dark:text-slate-300">
        Your wallet is created when your KYC application is approved. This demo asks for a document
        type and reference only, with no upload and no selfie. It is a basic review, not an identity
        check: use a made-up document reference.
      </p>
      {conflict && <ErrorNotice error={conflict} />}
      {load.kind === 'loading' && (
        <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
          Loading your KYC status…
        </p>
      )}
      {load.kind === 'error' && (
        <ErrorNotice error={load.error}>
          <button
            type="button"
            onClick={() => {
              setLoad({ kind: 'loading' });
              reload();
            }}
            className={`${SECONDARY_BUTTON_CLASSES} mt-2`}
          >
            Try again
          </button>
        </ErrorNotice>
      )}
      {load.kind === 'loaded' && (
        <>
          <StatusPanel
            status={load.view.status}
            refreshing={refreshing}
            refreshError={refreshError}
            onRefresh={reload}
            headingRef={statusHeadingRef}
          />
          {notice && <InfoNotice>{notice}</InfoNotice>}
          <StatusBody
            view={load.view}
            refreshing={refreshing}
            onRetry={reload}
            create={create}
            applyApplication={applyApplication}
            handleConflict={handleConflict}
          />
        </>
      )}
    </section>
  );
}

function StatusPanel({
  status,
  refreshing,
  refreshError,
  onRefresh,
  headingRef,
}: {
  status: KycStatusResponse;
  refreshing: boolean;
  refreshError: DescribedError | null;
  onRefresh: () => void;
  headingRef: RefObject<HTMLHeadingElement | null>;
}) {
  return (
    <section
      aria-labelledby="kyc-status-heading"
      className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2
          id="kyc-status-heading"
          ref={headingRef}
          tabIndex={-1}
          className="text-lg font-medium focus:outline-none"
        >
          Current status
        </h2>
        <button
          type="button"
          onClick={onRefresh}
          disabled={refreshing}
          aria-busy={refreshing}
          className={SECONDARY_BUTTON_CLASSES}
        >
          {refreshing ? 'Refreshing…' : 'Refresh status'}
        </button>
      </div>
      <dl className="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-[auto_1fr]">
        <dt className="font-medium">Status</dt>
        <dd>{statusLabel(status.status)}</dd>
        {status.verificationLevel && (
          <>
            <dt className="font-medium">Verification level</dt>
            <dd>{label(LEVEL_OPTIONS, status.verificationLevel)}</dd>
          </>
        )}
        <dt className="font-medium">Wallet</dt>
        <dd>{status.walletCreated ? 'Created' : 'Not created yet'}</dd>
      </dl>
      {refreshError && (
        <p className="text-sm font-medium text-red-700 dark:text-red-400">
          The status could not be refreshed. {refreshError.message}
          {refreshError.correlationId && ` Reference: ${refreshError.correlationId}`}
        </p>
      )}
    </section>
  );
}

interface BodyProps {
  view: View;
  refreshing: boolean;
  onRetry: () => void;
  create: (request: KycApplicationRequest) => Promise<void>;
  applyApplication: (application: KycApplication, message: string) => void;
  handleConflict: (error: unknown) => boolean;
}

function StatusBody({
  view,
  refreshing,
  onRetry,
  create,
  applyApplication,
  handleConflict,
}: BodyProps) {
  const { status, application } = view;
  const missing = <MissingDetails view={view} refreshing={refreshing} onRetry={onRetry} />;
  switch (status.status) {
    case 'NONE':
      return (
        <Panel id="kyc-apply" title="Apply">
          <p>Fill in your details and save them as a draft. You submit the draft in a next step.</p>
          <KycApplicationForm submitLabel="Save draft" busyText="Saving…" save={create} />
        </Panel>
      );
    case 'DRAFT':
      return application?.id ? (
        <DraftSection
          key={application.id}
          id={application.id}
          application={application}
          applyApplication={applyApplication}
          handleConflict={handleConflict}
        />
      ) : (
        <Panel id="kyc-draft" title="Your draft application">
          {missing}
        </Panel>
      );
    case 'PENDING_REVIEW':
      return (
        <Panel id="kyc-pending" title="Waiting for review">
          <p>
            Your application is with our compliance team. You will get an email when a decision is
            made. Your date of birth and country cannot change until then.
          </p>
          {application ? <ApplicationDetails application={application} /> : missing}
        </Panel>
      );
    case 'APPROVED':
      return (
        <Panel id="kyc-approved" title="Approved">
          {status.walletCreated ? (
            <p>
              Your KYC application is approved and your wallet has been created.{' '}
              <Link to="/" className={LINK_CLASSES}>
                Go to your wallet
              </Link>
            </p>
          ) : (
            <p>
              Your KYC application is approved. Your wallet is not shown yet: refresh in a moment.
            </p>
          )}
          {application ? <ApplicationDetails application={application} /> : missing}
        </Panel>
      );
    case 'REJECTED':
      return (
        <>
          <Panel id="kyc-rejected" title="Not approved">
            <p>Your KYC application was not approved.</p>
            {application ? (
              <>
                <p>
                  <span className="font-medium">Reason:</span>{' '}
                  {application.rejectionReason ?? 'No reason was given.'}
                </p>
                <ApplicationDetails application={application} />
              </>
            ) : (
              missing
            )}
            <p>
              You can apply again. Your date of birth and country can be corrected in your{' '}
              <Link to="/profile" className={LINK_CLASSES}>
                profile
              </Link>
              .
            </p>
          </Panel>
          <Panel id="kyc-reapply" title="Apply again">
            <KycApplicationForm submitLabel="Save draft" busyText="Saving…" save={create} />
          </Panel>
        </>
      );
    default:
      return (
        <Panel id="kyc-other" title="Status">
          <p>Your application status is {status.status ?? 'unknown'}.</p>
        </Panel>
      );
  }
}

/** The detail could not be loaded (with a retry), or the status unexpectedly carried no id. */
function MissingDetails({
  view,
  refreshing,
  onRetry,
}: {
  view: View;
  refreshing: boolean;
  onRetry: () => void;
}) {
  if (view.detailError === null) {
    return <p>{DETAILS_UNAVAILABLE_MESSAGE}</p>;
  }
  return (
    <ErrorNotice error={view.detailError}>
      <button
        type="button"
        onClick={onRetry}
        disabled={refreshing}
        aria-busy={refreshing}
        className={`${SECONDARY_BUTTON_CLASSES} mt-2`}
      >
        {refreshing ? 'Trying again…' : 'Try again'}
      </button>
    </ErrorNotice>
  );
}

function Panel({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section aria-labelledby={`${id}-heading`} className="space-y-3">
      <h2 id={`${id}-heading`} className="text-lg font-medium">
        {title}
      </h2>
      {children}
    </section>
  );
}

function ApplicationDetails({ application }: { application: KycApplication }) {
  const name = [application.firstName, application.lastName].filter(Boolean).join(' ');
  return (
    <dl className="grid grid-cols-1 gap-x-4 gap-y-1 rounded-lg border border-slate-200 bg-white p-4 sm:grid-cols-[auto_1fr] dark:border-slate-700 dark:bg-slate-900">
      <dt className="font-medium">Verification level</dt>
      <dd>{label(LEVEL_OPTIONS, application.verificationLevel)}</dd>
      <dt className="font-medium">Document type</dt>
      <dd>{label(DOCUMENT_OPTIONS, application.documentType)}</dd>
      <dt className="font-medium">Document reference</dt>
      <dd>{application.documentReference ?? '—'}</dd>
      <dt className="font-medium">Legal name</dt>
      <dd>{name || '—'}</dd>
      <dt className="font-medium">Created</dt>
      <dd>
        <BangkokTime value={application.createdAt} />
      </dd>
      {application.submittedAt && (
        <>
          <dt className="font-medium">Submitted</dt>
          <dd>
            <BangkokTime value={application.submittedAt} />
          </dd>
        </>
      )}
      {application.decidedAt && (
        <>
          <dt className="font-medium">Decided</dt>
          <dd>
            <BangkokTime value={application.decidedAt} />
          </dd>
        </>
      )}
    </dl>
  );
}

type DraftMode = 'view' | 'edit' | 'confirm';

function DraftSection({
  id,
  application,
  applyApplication,
  handleConflict,
}: {
  id: string;
  application: KycApplication;
  applyApplication: BodyProps['applyApplication'];
  handleConflict: BodyProps['handleConflict'];
}) {
  const [mode, setMode] = useState<DraftMode>('view');
  const [submitError, setSubmitError] = useState<{
    error: DescribedError;
    needsProfile: boolean;
  } | null>(null);
  const { busy, run } = useSubmitGuard();
  const confirmHeadingRef = useRef<HTMLHeadingElement>(null);
  const submitButtonRef = useRef<HTMLButtonElement>(null);
  const [returnFocus, setReturnFocus] = useState(0);
  useFocusOn(confirmHeadingRef, mode === 'confirm');
  useFocusOn(submitButtonRef, returnFocus);

  const save = async (request: KycApplicationRequest) => {
    try {
      const updated = await updateApplication(id, request);
      setMode('view');
      applyApplication(updated, DRAFT_SAVED_MESSAGE);
    } catch (error) {
      if (!handleConflict(error)) {
        throw error;
      }
    }
  };

  const submit = () =>
    void run(async () => {
      setSubmitError(null);
      try {
        applyApplication(await submitApplication(id), SUBMITTED_MESSAGE);
      } catch (error) {
        if (handleConflict(error)) {
          setMode('view');
          return;
        }
        const field = validationErrorField(error);
        setSubmitError({
          error: describeError(error),
          needsProfile: field === 'dateOfBirth' || field === 'countryCode',
        });
      }
    });

  const cancelConfirm = () => {
    setSubmitError(null);
    setMode('view');
    setReturnFocus((n) => n + 1);
  };

  return (
    <Panel id="kyc-draft" title="Your draft application">
      {mode === 'edit' ? (
        <KycApplicationForm
          key="edit"
          editing
          initial={{
            verificationLevel:
              application.verificationLevel as KycApplicationRequest['verificationLevel'],
            documentType: application.documentType as KycApplicationRequest['documentType'],
            firstName: application.firstName,
            lastName: application.lastName,
          }}
          submitLabel="Save changes"
          busyText="Saving…"
          save={save}
          onCancel={() => setMode('view')}
        />
      ) : (
        <>
          <ApplicationDetails application={application} />
          {mode === 'view' && (
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => setMode('edit')}
                className={SECONDARY_BUTTON_CLASSES}
              >
                Edit draft
              </button>
              <button
                ref={submitButtonRef}
                type="button"
                onClick={() => setMode('confirm')}
                className={PRIMARY_BUTTON_CLASSES}
              >
                Submit for review
              </button>
            </div>
          )}
          {mode === 'confirm' && (
            <section
              aria-labelledby="kyc-confirm-heading"
              className="space-y-3 rounded-lg border border-amber-400 bg-amber-50 p-4 text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100"
            >
              <h3
                id="kyc-confirm-heading"
                ref={confirmHeadingRef}
                tabIndex={-1}
                className="font-semibold focus:outline-none"
              >
                Submit your application?
              </h3>
              <p>
                After you submit, the application cannot be changed, and your date of birth and
                country are locked until a decision is made. Both must already be saved in your
                profile.
              </p>
              {submitError && (
                <ErrorNotice error={submitError.error}>
                  {submitError.needsProfile && (
                    <p>
                      <Link to="/profile" className={LINK_CLASSES}>
                        Update your profile
                      </Link>
                    </p>
                  )}
                </ErrorNotice>
              )}
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={submit}
                  disabled={busy}
                  aria-busy={busy}
                  className={PRIMARY_BUTTON_CLASSES}
                >
                  {busy ? 'Submitting…' : 'Yes, submit'}
                </button>
                <button
                  type="button"
                  onClick={cancelConfirm}
                  disabled={busy}
                  className={SECONDARY_BUTTON_CLASSES}
                >
                  Not yet
                </button>
              </div>
            </section>
          )}
        </>
      )}
    </Panel>
  );
}

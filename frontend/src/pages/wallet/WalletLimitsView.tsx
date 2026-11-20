import type { DescribedError } from '../../api/errorMessages';
import type { WalletLimits } from '../../api/wallet';
import { formatMoney } from '../../format/money';

export type LimitOperation = 'transfer' | 'deposit' | 'withdrawal';

type LimitKey = keyof Omit<WalletLimits, 'currency'>;

const KEYS: Readonly<
  Record<LimitOperation, { label: string; min: LimitKey; single: LimitKey; daily: LimitKey }>
> = {
  transfer: {
    label: 'Transfer',
    min: 'minTransfer',
    single: 'singleTransfer',
    daily: 'dailyTransfer',
  },
  deposit: { label: 'Deposit', min: 'minDeposit', single: 'singleDeposit', daily: 'dailyDeposit' },
  withdrawal: {
    label: 'Withdrawal',
    min: 'minWithdrawal',
    single: 'singleWithdrawal',
    daily: 'dailyWithdrawal',
  },
};

export const LIMITS_NOTE =
  'Daily limits count per Bangkok calendar day and reset at midnight Bangkok time. The server checks every request against them.';

function LimitsUnavailable({ error }: { error: DescribedError | null }) {
  return (
    <p className="text-sm text-slate-700 dark:text-slate-300">
      Your limits could not be loaded.{error ? ` ${error.message}` : ''}
      {error?.correlationId ? ` Reference: ${error.correlationId}` : ''}
    </p>
  );
}

/** Every operation's limits in one table (dashboard). Information only (D8). */
export function LimitsTable({
  limits,
  error,
}: {
  limits: WalletLimits | null;
  error: DescribedError | null;
}) {
  if (!limits) {
    return <LimitsUnavailable error={error} />;
  }
  const operations: LimitOperation[] = ['deposit', 'withdrawal', 'transfer'];
  return (
    <>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Your limits</caption>
          <thead>
            <tr className="border-b border-slate-300 dark:border-slate-600">
              <th scope="col" className="py-2 pr-4 font-medium">
                Operation
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                Minimum
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                Per transaction
              </th>
              <th scope="col" className="py-2 font-medium">
                Per day
              </th>
            </tr>
          </thead>
          <tbody>
            {operations.map((operation) => {
              const keys = KEYS[operation];
              return (
                <tr
                  key={operation}
                  className="border-b border-slate-200 last:border-0 dark:border-slate-700"
                >
                  <th scope="row" className="py-2 pr-4 font-medium">
                    {keys.label}
                  </th>
                  <td className="py-2 pr-4">{formatMoney(limits[keys.min], limits.currency)}</td>
                  <td className="py-2 pr-4">{formatMoney(limits[keys.single], limits.currency)}</td>
                  <td className="py-2">{formatMoney(limits[keys.daily], limits.currency)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="text-sm text-slate-600 dark:text-slate-400">{LIMITS_NOTE}</p>
    </>
  );
}

/** One operation's limits (deposit and withdrawal pages). Information only (D8). */
export function OperationLimits({
  operation,
  limits,
  error,
}: {
  operation: LimitOperation;
  limits: WalletLimits | null;
  error: DescribedError | null;
}) {
  if (!limits) {
    return <LimitsUnavailable error={error} />;
  }
  const keys = KEYS[operation];
  return (
    <>
      <dl className="grid grid-cols-1 gap-x-4 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
        <dt className="font-medium">Minimum</dt>
        <dd>{formatMoney(limits[keys.min], limits.currency)}</dd>
        <dt className="font-medium">Per transaction</dt>
        <dd>{formatMoney(limits[keys.single], limits.currency)}</dd>
        <dt className="font-medium">Per day</dt>
        <dd>{formatMoney(limits[keys.daily], limits.currency)}</dd>
      </dl>
      <p className="text-sm text-slate-600 dark:text-slate-400">{LIMITS_NOTE}</p>
    </>
  );
}

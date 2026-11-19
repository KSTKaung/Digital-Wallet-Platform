import { Link } from 'react-router';
import { InfoNotice } from './ErrorNotice';
import { LINK_CLASSES } from './SubmitButton';

export const KYC_REQUIRED_MESSAGE = 'Complete KYC to continue.';

/**
 * Shown where a wallet is needed but the customer has none yet (`404 WALLET_NOT_FOUND`,
 * `403 KYC_REQUIRED`; see isKycRequired). Text from doc 05 §5.3; the link leads to /kyc.
 */
export function KycRequiredNotice() {
  return (
    <InfoNotice>
      <p className="font-medium">{KYC_REQUIRED_MESSAGE}</p>
      <p className="mt-1 text-sm">
        Your wallet is created when your KYC application is approved.{' '}
        <Link to="/kyc" className={LINK_CLASSES}>
          Go to KYC
        </Link>
      </p>
    </InfoNotice>
  );
}

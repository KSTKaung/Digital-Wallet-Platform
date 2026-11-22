// The browser's format check for a staff reason validated by Bean Validation in the admin web layer
// (RejectKycRequest and WalletStatusReasonRequest carry the same annotations: @NotBlank,
// @Size(max = 500), RequestText.NO_CONTROL_CHARACTERS and RequestText.HAS_LETTER_OR_DIGIT). The
// transfer rejection reason has its own rules (TextRules.requireReason: NFC, stripped, code
// points), so it keeps its own check in transferReviewText.ts. The server decides in every case.

/**
 * Not blank, at most `maxLength` characters (UTF-16 units, as @Size counts), no control,
 * surrogate or format characters (a line break is a control character, so the reason is one line)
 * and at least one letter or digit. The text is still sent as typed.
 */
export function staffReasonError(reason: string, maxLength: number): string | undefined {
  if (reason.trim() === '') {
    return 'Enter a reason.';
  }
  if (reason.length > maxLength) {
    return `Reason must have at most ${maxLength} characters.`;
  }
  if (/[\r\n]/.test(reason)) {
    return 'Reason must be on one line, without line breaks.';
  }
  if (/[\p{Cc}\p{Cs}\p{Cf}]/u.test(reason)) {
    return 'Reason must not contain control characters or invisible formatting characters.';
  }
  if (!/[\p{L}\p{N}]/u.test(reason)) {
    return 'Reason must contain a letter or a digit.';
  }
  return undefined;
}

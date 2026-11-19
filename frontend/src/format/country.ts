// Country display for ISO 3166-1 alpha-2 codes, from the browser's own Intl data (no list is
// shipped). The server checks the code against its ISO list (CustomerProfileRules).

/** "TH" → "Thailand (TH)" where the browser knows the name; the code itself otherwise. */
export function countryName(code: string | null | undefined): string | undefined {
  if (!code) {
    return undefined;
  }
  try {
    const name = new Intl.DisplayNames(['en'], { type: 'region' }).of(code);
    return name && name !== code ? `${name} (${code})` : code;
  } catch {
    return code;
  }
}

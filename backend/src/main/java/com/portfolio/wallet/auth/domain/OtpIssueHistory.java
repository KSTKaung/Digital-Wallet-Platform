package com.portfolio.wallet.auth.domain;

import java.time.Instant;

/**
 * What the limits need to know about earlier challenges for one identifier.
 *
 * @param firstSentAt the identifier's first send for this purpose, or null if it has none
 * @param lastSentAt its latest send for this purpose, or null if it has none
 * @param sendsInLastHour sends for this purpose in the hour before now
 * @param usedUpInWindow challenges for any purpose used up within the policy's window
 * @param wrongGuessesInWindow wrong guesses against those and against any other challenge of any
 *     purpose sent within the window, however it ended, so cancelling a challenge before its last
 *     attempt does not reset the count
 */
public record OtpIssueHistory(
    Instant firstSentAt,
    Instant lastSentAt,
    long sendsInLastHour,
    long usedUpInWindow,
    long wrongGuessesInWindow) {}

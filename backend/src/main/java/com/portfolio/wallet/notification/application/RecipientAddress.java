package com.portfolio.wallet.notification.application;

import java.util.Objects;
import java.util.Optional;

/**
 * The result of looking up where an outbox email goes ({@link RecipientAddresses}), checked at send
 * time (owner, 2026-11-14, decision 8): an address exists only for an account whose status is
 * {@code ACTIVE}.
 *
 * <p>{@link #toString()} never shows the address, which is personal data.
 */
public final class RecipientAddress {

  /** What the lookup found. */
  public enum Outcome {
    /** An {@code ACTIVE} account with an address: the email is sent to it. */
    ACTIVE,
    /**
     * The account exists but is not {@code ACTIVE}: the email is never sent and the row ends {@code
     * FAILED} at once, without retries.
     */
    NOT_ACTIVE,
    /**
     * No such account, or an {@code ACTIVE} one without an address: the attempt fails and follows
     * the retry policy, as before the send-time check.
     */
    NOT_FOUND
  }

  private static final RecipientAddress NOT_ACTIVE = new RecipientAddress(Outcome.NOT_ACTIVE, null);
  private static final RecipientAddress NOT_FOUND = new RecipientAddress(Outcome.NOT_FOUND, null);

  private final Outcome outcome;
  private final String address;

  private RecipientAddress(Outcome outcome, String address) {
    this.outcome = outcome;
    this.address = address;
  }

  /** An {@code ACTIVE} account's address; must not be blank. */
  public static RecipientAddress active(String address) {
    Objects.requireNonNull(address, "address");
    if (address.isBlank()) {
      throw new IllegalArgumentException("address must not be blank");
    }
    return new RecipientAddress(Outcome.ACTIVE, address);
  }

  public static RecipientAddress notActive() {
    return NOT_ACTIVE;
  }

  public static RecipientAddress notFound() {
    return NOT_FOUND;
  }

  public Outcome outcome() {
    return outcome;
  }

  /** The address, present exactly for {@link Outcome#ACTIVE}. */
  public Optional<String> email() {
    return Optional.ofNullable(address);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof RecipientAddress that
        && outcome == that.outcome
        && Objects.equals(address, that.address);
  }

  @Override
  public int hashCode() {
    return Objects.hash(outcome, address);
  }

  @Override
  public String toString() {
    return "RecipientAddress[" + outcome + "]";
  }
}

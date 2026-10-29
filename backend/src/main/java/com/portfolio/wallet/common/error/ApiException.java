package com.portfolio.wallet.common.error;

/**
 * Business or request error that maps to a documented {@link ErrorCode}. The message is returned to
 * the client, so it must never contain secrets, tokens or personal data.
 */
public class ApiException extends RuntimeException {

  private final ErrorCode errorCode;

  public ApiException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  /**
   * The only way to signal a missing resource identified by a client-supplied id. Use it both when
   * the resource does not exist and when it belongs to another user, so responses never reveal
   * whether another user's wallet, account or transaction exists (documentations/03 §3.3, T3).
   */
  public static ApiException notFound() {
    return new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
  }

  static final String NOT_FOUND_MESSAGE = "Resource not found";

  public ErrorCode errorCode() {
    return errorCode;
  }
}

package com.portfolio.wallet.common.web;

import com.portfolio.wallet.common.error.ErrorCode;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents an error an endpoint can answer with, in the OpenAPI document only: it changes no
 * behaviour. {@link OpenApiDocumentation} groups the codes of an operation by their {@link
 * ErrorCode#status() status}, so the documented status always matches the real one, and adds the
 * error body of documentations/02 §1.7 to each.
 *
 * <p>Errors every operation of a kind shares are added by {@link OpenApiDocumentation} itself and
 * are not repeated here: {@code VALIDATION_ERROR} for requests with input, {@code UNAUTHORIZED} for
 * signed-in endpoints, {@code FORBIDDEN} for role-restricted ones, the generic {@code NOT_FOUND}
 * for a path id, {@code UNSUPPORTED_MEDIA_TYPE} for a body, {@code RATE_LIMITED} and {@code
 * SYSTEM_ERROR}. Name the endpoint's own codes here, with the condition in {@link #when()}.
 * Messages never distinguish a missing resource from another user's (documentations/06 MVP-ADR-19):
 * both are the one generic {@code NOT_FOUND}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ApiErrors.class)
public @interface ApiError {

  ErrorCode code();

  /** When the code is returned, in a few words, without any value from a request. */
  String when();
}

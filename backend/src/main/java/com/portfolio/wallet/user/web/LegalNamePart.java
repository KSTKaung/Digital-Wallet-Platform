package com.portfolio.wallet.user.web;

import com.portfolio.wallet.user.domain.InvalidKycDetailsException;
import com.portfolio.wallet.user.domain.LegalName;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A first or last name in a request follows the domain's rule for a legal name ({@link
 * LegalName#requirePart}; P1, R2): at least one letter, no control or format characters except a
 * zero-width joiner or non-joiner between two letters or combining marks. The domain's own check
 * runs here, one linear pass over the code points, so the request layer and the domain cannot
 * disagree, and long input cannot overflow the stack as a repeated regular-expression group could
 * (T3). The violation message is the broken rule, which names the field, never the value. Null is
 * left to {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = LegalNamePart.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface LegalNamePart {

  String message() default "is not a valid name";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  /** Runs {@link LegalName#requirePart} and reports its rule. */
  class Validator implements ConstraintValidator<LegalNamePart, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      if (value == null) {
        return true;
      }
      try {
        LegalName.requirePart("name", value);
        return true;
      } catch (InvalidKycDetailsException e) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(escape(e.rule())).addConstraintViolation();
        return false;
      }
    }

    // The rule is a constant text, but message templates treat { } $ \ as syntax.
    private static String escape(String rule) {
      return rule.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("$", "\\$");
    }
  }
}

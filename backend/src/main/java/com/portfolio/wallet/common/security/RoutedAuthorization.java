package com.portfolio.wallet.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Makes a role rule apply only to requests that reach an endpoint (owner, 2026-11-03, J3). Spring
 * Security authorises before the request is routed, so a role rule on a path pattern would refuse
 * an unknown path with {@code 403} and tell a signed-in caller which paths exist. Here a request
 * with no handler is let through for any authenticated caller, and the dispatcher answers with the
 * generic {@code 404} (documentations/06 MVP-ADR-19); a request with a handler gets the rule.
 *
 * <p>A path that matches a handler only with another method, media type or parameters counts as an
 * endpoint (Spring would answer {@code 405}, {@code 415}, {@code 406} or {@code 400}), so the rule
 * applies and a caller with the wrong role gets {@code 403}, never the {@code 405} that would
 * reveal the path. An unauthenticated caller is refused before anything is looked up, so it gets
 * the same {@code 401} on every path.
 *
 * <p>The lookup runs on a copy of the request's attributes, so it leaves nothing behind for the
 * dispatcher. Only annotated controllers are consulted: the static-resource handler matches every
 * path and would make every path look like an endpoint.
 */
final class RoutedAuthorization {

  private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

  private final Supplier<RequestMappingHandlerMapping> handlerMapping;

  RoutedAuthorization(Supplier<RequestMappingHandlerMapping> handlerMapping) {
    this.handlerMapping = handlerMapping;
  }

  /** {@code rule} for a request that reaches an endpoint; any authenticated caller otherwise. */
  AuthorizationManager<RequestAuthorizationContext> onEndpoints(
      AuthorizationManager<RequestAuthorizationContext> rule) {
    return new AuthorizationManager<>() {
      @Override
      public AuthorizationResult authorize(
          Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        if (!TRUST.isAuthenticated(authentication.get())) {
          return new AuthorizationDecision(false);
        }
        if (!hasEndpoint(context.getRequest())) {
          return new AuthorizationDecision(true);
        }
        return rule.authorize(authentication, context);
      }

      @Override
      public String toString() {
        return "onEndpoints(" + rule + ")";
      }
    };
  }

  /** Whether an annotated controller method maps the request's path. */
  boolean hasEndpoint(HttpServletRequest request) {
    try {
      return handlerMapping.get().getHandler(new IsolatedAttributes(request)) != null;
    } catch (Exception e) {
      // The path matches, but not the method, media type or parameters (405, 415, 406, 400):
      // an endpoint exists. Anything unexpected also keeps the rule: fail closed.
      return true;
    }
  }

  /** The request with attribute writes kept in a private map, so the lookup changes nothing. */
  static final class IsolatedAttributes extends HttpServletRequestWrapper {

    private static final Object REMOVED = new Object();

    private final Map<String, Object> attributes = new HashMap<>();

    IsolatedAttributes(HttpServletRequest request) {
      super(request);
    }

    @Override
    public Object getAttribute(String name) {
      if (attributes.containsKey(name)) {
        Object value = attributes.get(name);
        return value == REMOVED ? null : value;
      }
      return super.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
      Set<String> names = new LinkedHashSet<>(Collections.list(super.getAttributeNames()));
      attributes.forEach(
          (name, value) -> {
            if (value == REMOVED) {
              names.remove(name);
            } else {
              names.add(name);
            }
          });
      return Collections.enumeration(names);
    }

    @Override
    public void setAttribute(String name, Object value) {
      attributes.put(name, value == null ? REMOVED : value);
    }

    @Override
    public void removeAttribute(String name) {
      attributes.put(name, REMOVED);
    }
  }
}

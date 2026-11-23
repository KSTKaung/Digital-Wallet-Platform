package com.portfolio.wallet.common.web;

import com.portfolio.wallet.common.error.ErrorCode;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

/**
 * Completes the generated OpenAPI document (documentations/08 §4E, E2; doc 02 §1.3–§1.8). It only
 * describes the API and changes no behaviour; the document itself is served in the {@code local}
 * profile only (documentations/06 MVP-ADR-15). Per operation it adds:
 *
 * <ul>
 *   <li>the error responses, each with the error body of doc 02 §1.7 ({@link ApiErrorResponse}):
 *       the endpoint's own codes from {@link ApiError}, plus those every operation of a kind shares
 *       (see {@link #customize(Operation, HandlerMethod)});
 *   <li>{@code Idempotency-Key} as a required UUID header (doc 02 §1.3). The controllers read it as
 *       optional only to answer a missing key with their own {@code VALIDATION_ERROR} message.
 * </ul>
 *
 * And for the whole document:
 *
 * <ul>
 *   <li>the bearer JWT security scheme {@value #BEARER}, which the controllers of signed-in
 *       endpoints name with {@link SecurityRequirement};
 *   <li>the error body as a component;
 *   <li>{@code required} on every property of a response schema, because Jackson writes every
 *       record component, a null one too; a property the server can send as {@code null} is marked
 *       {@code @Schema(nullable = true)} on its record component and becomes {@code type: [...,
 *       "null"]} here (OpenAPI 3.1).
 * </ul>
 */
@Component
public class OpenApiDocumentation implements OpenApiCustomizer, OperationCustomizer {

  /** The name of the bearer JWT security scheme. */
  public static final String BEARER = "bearerAuth";

  static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private static final String JSON = org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
  private static final String ERROR_SCHEMA = ApiErrorResponse.class.getSimpleName();
  private static final String ERROR_SCHEMA_REF = "#/components/schemas/" + ERROR_SCHEMA;

  @Override
  public Operation customize(Operation operation, HandlerMethod handler) {
    Inputs inputs = Inputs.of(handler);
    Map<Integer, List<String>> errors = new TreeMap<>();
    if (!inputs.validationCauses().isEmpty()) {
      add(
          errors,
          ErrorCode.VALIDATION_ERROR,
          String.join("; ", inputs.validationCauses())
              + ". The message names the field, never its value.");
    }
    if (isAnnotated(handler, SecurityRequirement.class)
        || isAnnotated(handler, SecurityRequirements.class)) {
      add(
          errors,
          ErrorCode.UNAUTHORIZED,
          "missing, invalid or expired access token, or an ended session; identical on every path");
    }
    if (isAnnotated(handler, PreAuthorize.class)) {
      add(errors, ErrorCode.FORBIDDEN, "the caller's role may not use this endpoint");
    }
    if (inputs.uuidPath()) {
      add(
          errors,
          ErrorCode.NOT_FOUND,
          "no such resource, or it belongs to someone else: the response is identical");
    }
    if (inputs.body()) {
      add(errors, ErrorCode.UNSUPPORTED_MEDIA_TYPE, "the body is not application/json");
    }
    for (ApiError error :
        AnnotatedElementUtils.findMergedRepeatableAnnotations(
            handler.getMethod(), ApiError.class, ApiErrors.class)) {
      add(errors, error.code(), error.when());
    }
    add(errors, ErrorCode.RATE_LIMITED, "too many requests; retry after Retry-After seconds");
    add(errors, ErrorCode.SYSTEM_ERROR, "an unexpected error; no internal detail is returned");

    ApiResponses responses =
        operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
    errors.forEach(
        (status, lines) -> responses.addApiResponse(String.valueOf(status), errorResponse(lines)));
    operation.setResponses(responses);

    if (operation.getParameters() != null) {
      operation.getParameters().stream()
          .filter(p -> "header".equals(p.getIn()) && IDEMPOTENCY_KEY.equals(p.getName()))
          .forEach(OpenApiDocumentation::describeIdempotencyKey);
    }
    return operation;
  }

  // The swagger model exposes component schemas as a raw Map<String, Schema>.
  @SuppressWarnings("rawtypes")
  @Override
  public void customise(OpenAPI openApi) {
    openApi.info(
        new Info()
            .title("Digital Wallet Platform API")
            .version("v1")
            .description(
                "Portfolio MVP of a digital wallet. No real money is processed: deposits and"
                    + " withdrawals are simulated. Every error answers with the error body"
                    + " ApiErrorResponse; errorCode names the condition."));
    // An array keeps its order in the committed document, and springdoc lists the tags in the
    // order it scanned the controllers, which can differ between machines.
    if (openApi.getTags() != null) {
      openApi.getTags().sort(Comparator.comparing(Tag::getName));
    }
    Components components =
        openApi.getComponents() == null ? new Components() : openApi.getComponents();
    openApi.setComponents(components);
    components.addSecuritySchemes(
        BEARER,
        new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .description("The access token from login or refresh"));
    if (components.getSchemas() == null || !components.getSchemas().containsKey(ERROR_SCHEMA)) {
      ModelConverters.getInstance(true)
          .readAll(ApiErrorResponse.class)
          .forEach(
              (name, schema) -> {
                if (components.getSchemas() == null || !components.getSchemas().containsKey(name)) {
                  components.addSchemas(name, schema);
                }
              });
    }
    Map<String, Schema> schemas = components.getSchemas();
    schemas.values().forEach(OpenApiDocumentation::markNullableProperties);
    Set<String> responseSchemas = reachable(schemas, roots(openApi, false));
    responseSchemas.removeAll(reachable(schemas, roots(openApi, true)));
    for (String name : responseSchemas) {
      Schema<?> schema = schemas.get(name);
      if (schema != null && schema.getProperties() != null) {
        schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
      }
    }
  }

  private static void add(Map<Integer, List<String>> errors, ErrorCode code, String when) {
    errors
        .computeIfAbsent(code.status().value(), status -> new ArrayList<>())
        .add("`" + code.name() + "`: " + when);
  }

  // Schema.type(String) returns the raw type.
  @SuppressWarnings("unchecked")
  private static ApiResponse errorResponse(List<String> lines) {
    ApiResponse response =
        new ApiResponse()
            .description(String.join("\n\n", lines))
            .content(
                new Content()
                    .addMediaType(
                        JSON, new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA_REF))));
    if (lines.stream().anyMatch(line -> line.startsWith("`" + ErrorCode.RATE_LIMITED.name()))) {
      response.addHeaderObject(
          "Retry-After",
          new Header()
              .description("Whole seconds to wait before retrying")
              .schema(new Schema<>().type("integer").types(Set.of("integer"))));
    }
    return response;
  }

  @SuppressWarnings("unchecked")
  private static void describeIdempotencyKey(Parameter parameter) {
    parameter.setRequired(true);
    parameter.setSchema(new Schema<>().type("string").types(Set.of("string")).format("uuid"));
    parameter.setDescription(
        "Required. A client-generated UUID v4 (version and RFC variant are checked). The same key"
            + " with the same body replays the original status and body; the same key with a"
            + " different body is 409 IDEMPOTENCY_KEY_REUSED (documentations/02 §1.3, §1.4).");
  }

  private static boolean isAnnotated(HandlerMethod handler, Class<? extends Annotation> type) {
    return has(handler.getMethod(), type) || has(handler.getBeanType(), type);
  }

  private static boolean has(AnnotatedElement element, Class<? extends Annotation> type) {
    return AnnotatedElementUtils.hasAnnotation(element, type);
  }

  /** {@code nullable: true} (OpenAPI 3.0 style, from the annotation) as a 3.1 type union. */
  private static void markNullableProperties(Schema<?> schema) {
    if (schema.getProperties() == null) {
      return;
    }
    for (Schema<?> property : schema.getProperties().values()) {
      if (Boolean.TRUE.equals(property.getNullable())) {
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
          types.addAll(property.getTypes());
        } else if (property.getType() != null) {
          types.add(property.getType());
        }
        types.add("null");
        property.setTypes(types);
        property.setNullable(null);
      }
    }
  }

  /** The schemas the request bodies ({@code requests}) or the responses refer to directly. */
  private static List<Schema<?>> roots(OpenAPI openApi, boolean requests) {
    List<Schema<?>> roots = new ArrayList<>();
    if (openApi.getPaths() == null) {
      return roots;
    }
    for (PathItem path : openApi.getPaths().values()) {
      for (Operation operation : path.readOperations()) {
        List<Content> contents = new ArrayList<>();
        if (requests) {
          if (operation.getRequestBody() != null) {
            contents.add(operation.getRequestBody().getContent());
          }
        } else if (operation.getResponses() != null) {
          operation.getResponses().values().forEach(r -> contents.add(r.getContent()));
        }
        for (Content content : contents) {
          if (content != null) {
            content.values().stream()
                .map(MediaType::getSchema)
                .filter(s -> s != null)
                .forEach(roots::add);
          }
        }
      }
    }
    return roots;
  }

  /** The names of the component schemas reachable from {@code roots}. */
  @SuppressWarnings("rawtypes")
  private static Set<String> reachable(Map<String, Schema> schemas, List<Schema<?>> roots) {
    Set<String> names = new HashSet<>();
    Deque<Schema<?>> pending = new ArrayDeque<>(roots);
    while (!pending.isEmpty()) {
      Schema<?> schema = pending.pop();
      if (schema.get$ref() != null) {
        String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
        if (names.add(name) && schemas.get(name) != null) {
          pending.push(schemas.get(name));
        }
        continue;
      }
      if (schema.getProperties() != null) {
        schema.getProperties().values().forEach(pending::push);
      }
      if (schema.getItems() != null) {
        pending.push(schema.getItems());
      }
      Stream.of(schema.getAllOf(), schema.getOneOf(), schema.getAnyOf())
          .filter(Objects::nonNull)
          .forEach(composed -> composed.forEach(pending::push));
      if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
        pending.push(additional);
      }
    }
    return names;
  }

  /** What a request carries, which decides the shared {@code 400} and {@code 404} entries. */
  private record Inputs(boolean body, boolean uuidPath, List<String> validationCauses) {

    static Inputs of(HandlerMethod handler) {
      boolean body = false;
      boolean uuidPath = false;
      Set<String> causes = new LinkedHashSet<>();
      for (MethodParameter parameter : handler.getMethodParameters()) {
        if (parameter.hasParameterAnnotation(RequestBody.class)) {
          body = true;
          causes.add("a malformed body or a body field that fails validation");
        } else if (parameter.hasParameterAnnotation(RequestParam.class)) {
          causes.add("a missing or invalid query parameter");
        } else if (parameter.hasParameterAnnotation(PathVariable.class)) {
          if (UUID.class.equals(parameter.getParameterType())) {
            uuidPath = true;
            causes.add("a path id that is not a UUID (a well-formed unknown id is the 404)");
          }
        } else if (parameter.hasParameterAnnotation(RequestHeader.class)) {
          RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
          causes.add(
              IDEMPOTENCY_KEY.equals(header.name()) || IDEMPOTENCY_KEY.equals(header.value())
                  ? "a missing Idempotency-Key, or one that is not a UUID v4"
                  : "a missing or invalid header");
        }
      }
      return new Inputs(body, uuidPath, List.copyOf(causes));
    }
  }
}

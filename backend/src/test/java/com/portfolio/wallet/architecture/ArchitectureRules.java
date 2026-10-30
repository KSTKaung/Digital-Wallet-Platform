package com.portfolio.wallet.architecture;

import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import jakarta.persistence.Entity;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/**
 * Architecture rules for the modular application (documentations/06 MVP-ADR-01, -02, -09). Rules
 * are parameterised by root package so they can also be checked against violating fixtures.
 */
final class ArchitectureRules {

  /** Business modules. {@code common} is shared and handled separately. */
  static final List<String> MODULES =
      List.of(
          "auth",
          "user",
          "wallet",
          "transfer",
          "ledger",
          "risk",
          "transaction",
          "notification",
          "audit",
          "admin");

  private ArchitectureRules() {}

  /** Inside a module: web → application → domain ← persistence. */
  static ArchRule layers() {
    return layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .withOptionalLayers(true)
        .layer("Web")
        .definedBy("..web..")
        .layer("Application")
        .definedBy("..application..")
        .layer("Domain")
        .definedBy("..domain..")
        .layer("Persistence")
        .definedBy("..persistence..")
        .whereLayer("Web")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("Application")
        .mayOnlyBeAccessedByLayers("Web", "Application")
        .whereLayer("Persistence")
        .mayOnlyBeAccessedByLayers("Application");
  }

  /** Domain code knows nothing about HTTP, application services or persistence adapters. */
  static ArchRule domainIsIndependent() {
    return noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..web..",
            "..application..",
            "..persistence..",
            "org.springframework.web..",
            "jakarta.servlet..");
  }

  /**
   * The financial core and {@code Money} are pure Java (documentations/06 MVP-ADR-07): no JPA,
   * Hibernate or Spring. JPA entities and mappers live in {@code persistence}. The rule must match
   * at least one class, so it cannot pass vacuously.
   */
  static ArchRule financialDomainIsFrameworkFree() {
    return noClasses()
        .that()
        .resideInAnyPackage(
            "..wallet.domain..",
            "..transfer.domain..",
            "..ledger.domain..",
            "..risk.domain..",
            "..common.money..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("jakarta.persistence..", "org.hibernate..", "org.springframework..")
        .allowEmptyShould(false)
        .because("financial domain models are pure Java (documentations/06 MVP-ADR-07)");
  }

  /** Other modules may use a module only through its application package. */
  static ArchRule moduleInternalsArePrivate(String root) {
    return CompositeArchRule.of(
            MODULES.stream()
                .map(
                    module ->
                        (ArchRule)
                            noClasses()
                                .that()
                                .resideOutsideOfPackage(root + "." + module + "..")
                                .should()
                                .dependOnClassesThat()
                                .resideInAnyPackage(
                                    root + "." + module + ".web..",
                                    root + "." + module + ".domain..",
                                    root + "." + module + ".persistence..")
                                .as("module '" + module + "' internals are private"))
                .toList())
        .as("modules are used only through their application package");
  }

  static ArchRule noCyclesBetweenModules(String root) {
    return slices().matching(root + ".(*)..").should().beFreeOfCycles();
  }

  /** Shared code must not depend on any business module. */
  static ArchRule commonDependsOnNoModule(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".common..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            MODULES.stream().map(m -> root + "." + m + "..").toArray(String[]::new));
  }

  /** Controllers return DTOs, never JPA entities. */
  static ArchRule controllersDoNotReturnEntities() {
    return noMethods()
        .that()
        .areDeclaredInClassesThat()
        .areAnnotatedWith(RestController.class)
        .should()
        .haveRawReturnType(annotatedWith(Entity.class));
  }
}

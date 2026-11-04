package com.portfolio.wallet.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.anyElementThat;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
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

  /**
   * Customer profile and KYC data stay behind the user module: only {@code admin} (staff KYC
   * decisions, PR 4a) may depend on it (documentations/08 §4B.2 Q3). {@code wallet} reads the legal
   * name through an interface of its own that {@code user} implements, so it needs no dependency on
   * {@code user} either.
   */
  static ArchRule onlyAdminDependsOnUser(String root) {
    return noClasses()
        .that()
        .resideOutsideOfPackages(root + ".user..", root + ".admin..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(root + ".user..")
        .as("only the admin module depends on the user module");
  }

  /**
   * The money core sits below the customer and staff modules (PR 4a: admin → user → wallet →
   * ledger). The wallet module never depends on the modules that call it, and the ledger, the
   * system of record, depends on none of the modules that move money or hold customer data. Cycles
   * are refused separately; this rule names the intended direction, so a new dependency that would
   * reverse it fails even before a cycle exists.
   */
  static ArchRule moneyCoreDependsDownwardOnly(String root) {
    return CompositeArchRule.of(
            noClasses()
                .that()
                .resideInAPackage(root + ".wallet..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    root + ".user..", root + ".admin..", root + ".auth..", root + ".transfer..")
                .as("the wallet module does not depend on user, admin, auth or transfer"))
        .and(
            noClasses()
                .that()
                .resideInAPackage(root + ".ledger..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    root + ".wallet..",
                    root + ".transfer..",
                    root + ".user..",
                    root + ".admin..",
                    root + ".auth..",
                    root + ".risk..")
                .as(
                    "the ledger module does not depend on wallet, transfer, user, admin, auth or"
                        + " risk"))
        .as("the money core depends downward only (admin → user → wallet → ledger)");
  }

  /**
   * A module's public application API never exposes its persistence types: no public or protected
   * method of an application class takes or returns a persistence class, so callers in other
   * modules handle ids and views only (documentations/08 §4B, PR 4a step 3).
   */
  static ArchRule applicationApisHidePersistence() {
    return noMethods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..application..")
        .and()
        .arePublic()
        .should()
        .haveRawReturnType(resideInAPackage("..persistence.."))
        .orShould()
        .haveRawParameterTypes(anyElementThat(resideInAPackage("..persistence..")))
        .as("public application methods neither take nor return persistence types");
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

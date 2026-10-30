package com.portfolio.wallet.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Proves the rules catch real violations: the fixture package contains deliberately broken code.
 * Without this, rules that match nothing (empty modules) would pass silently.
 */
class ArchitectureRulesSelfTest {

  private static final String FIXTURE_ROOT = "com.portfolio.wallet.architecture.fixture";

  private static final JavaClasses FIXTURES = new ClassFileImporter().importPackages(FIXTURE_ROOT);

  @Test
  void detectsAccessToAnotherModulesPersistence() {
    var result = ArchitectureRules.moduleInternalsArePrivate(FIXTURE_ROOT).evaluate(FIXTURES);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("TransferService", "WalletRepository");
  }

  @Test
  void detectsDomainDependingOnWeb() {
    var result = ArchitectureRules.domainIsIndependent().evaluate(FIXTURES);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString()).contains("Wallet", "WalletController");
  }

  @Test
  void detectsCommonDependingOnModule() {
    var result = ArchitectureRules.commonDependsOnNoModule(FIXTURE_ROOT).evaluate(FIXTURES);

    assertThat(result.hasViolation()).isTrue();
  }

  /**
   * The JPA fixture lives in {@code com.portfolio.jpafixture}, outside the package Spring Boot
   * scans for entities (see its package-info).
   */
  @Test
  void detectsJpaEntityInFinancialDomain() {
    var result = ArchitectureRules.financialDomainIsFrameworkFree().evaluate(frameworkFixtures());

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("LedgerEntryRecord", "jakarta.persistence.Entity");
  }

  @Test
  void detectsSpringInFinancialDomain() {
    var result = ArchitectureRules.financialDomainIsFrameworkFree().evaluate(frameworkFixtures());

    assertThat(result.getFailureReport().toString())
        .contains(
            "TransactionalWalletRule", "org.springframework.transaction.annotation.Transactional");
  }

  @Test
  void detectsHibernateInFinancialDomain() {
    var result = ArchitectureRules.financialDomainIsFrameworkFree().evaluate(frameworkFixtures());

    assertThat(result.getFailureReport().toString())
        .contains("ImmutableTransferRecord", "org.hibernate.annotations.Immutable");
  }

  private static JavaClasses frameworkFixtures() {
    return new ClassFileImporter().importPackages("com.portfolio.jpafixture");
  }

  /** The production financial domain is checked, and it is pure Java (MVP-ADR-07). */
  @Test
  void acceptsRealFinancialDomainCode() {
    JavaClasses production =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(
                "com.portfolio.wallet.common.money",
                "com.portfolio.wallet.wallet.domain",
                "com.portfolio.wallet.transfer.domain",
                "com.portfolio.wallet.ledger.domain");

    var result = ArchitectureRules.financialDomainIsFrameworkFree().evaluate(production);

    assertThat(production.contain("com.portfolio.wallet.common.money.Money")).isTrue();
    assertThat(production.contain("com.portfolio.wallet.wallet.domain.Wallet")).isTrue();
    assertThat(production.contain("com.portfolio.wallet.transfer.domain.Transfer")).isTrue();
    assertThat(production.contain("com.portfolio.wallet.ledger.domain.FinancialTransaction"))
        .isTrue();
    assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
  }
}

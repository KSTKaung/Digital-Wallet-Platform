package com.portfolio.wallet.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
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
}

package com.portfolio.wallet.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.portfolio.wallet.wallet.application.RecipientNames;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Owner condition on Q3 (documentations/08 §4B.2): the lookup reads the legal name through {@link
 * RecipientNames}, declared in {@code wallet.application} and implemented in {@code
 * user.application}, so the dependency runs user → wallet and never back. The general rules ({@code
 * moneyCoreDependsDownwardOnly}, {@code noCyclesBetweenModules}) already forbid wallet → user;
 * these two pin the arrangement itself.
 */
class RecipientNamesArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.portfolio.wallet");

  @Test
  void theOnlyImplementationLivesInTheUserApplicationPackage() {
    classes()
        .that()
        .implement(RecipientNames.class)
        .should()
        .resideInAPackage("com.portfolio.wallet.user.application")
        .allowEmptyShould(false)
        .check(CLASSES);
  }

  @Test
  void theWalletModuleNeverDependsOnTheUserModule() {
    noClasses()
        .that()
        .resideInAPackage("com.portfolio.wallet.wallet..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.portfolio.wallet.user..")
        .check(CLASSES);
  }
}

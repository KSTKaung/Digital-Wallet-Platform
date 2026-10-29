package com.portfolio.wallet.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Checks the production code against the architecture rules. */
class ArchitectureTest {

  private static final String ROOT = "com.portfolio.wallet";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  void layersAreRespected() {
    ArchitectureRules.layers().check(CLASSES);
  }

  @Test
  void domainIsIndependent() {
    ArchitectureRules.domainIsIndependent().check(CLASSES);
  }

  @Test
  void moduleInternalsArePrivate() {
    ArchitectureRules.moduleInternalsArePrivate(ROOT).check(CLASSES);
  }

  @Test
  void noCyclesBetweenModules() {
    ArchitectureRules.noCyclesBetweenModules(ROOT).check(CLASSES);
  }

  @Test
  void commonDependsOnNoModule() {
    ArchitectureRules.commonDependsOnNoModule(ROOT).check(CLASSES);
  }

  @Test
  void controllersDoNotReturnEntities() {
    ArchitectureRules.controllersDoNotReturnEntities().check(CLASSES);
  }

  @Test
  void noFieldInjection() {
    NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(CLASSES);
  }

  @Test
  void noJavaUtilLogging() {
    NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(CLASSES);
  }

  /** Output goes through SLF4J so it is structured and never printed raw (no secrets on stdout). */
  @Test
  void noStandardStreams() {
    noClasses().should(ACCESS_STANDARD_STREAMS).check(CLASSES);
  }
}

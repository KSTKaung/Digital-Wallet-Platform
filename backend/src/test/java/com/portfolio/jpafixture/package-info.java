/**
 * Deliberately violating JPA, Hibernate and Spring classes used only by {@code
 * ArchitectureRulesSelfTest}.
 *
 * <p>They live outside {@code com.portfolio.wallet} on purpose: Spring Boot scans that package,
 * including test classes, for {@code @Entity} types and components, and Hibernate's schema
 * validation would fail every {@code @SpringBootTest} on a fixture entity that has no table.
 */
package com.portfolio.jpafixture;

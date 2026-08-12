package com.serviceatlas.parser.scala;

/**
 * A sub-project declared in a multi-module SBT build (FR-2.4).
 *
 * @param id           the {@code lazy val} identifier
 * @param name         the module's {@code name :=} value if it declares one, else the id
 * @param relativePath the module directory relative to the repo root
 * @param deployable   true when the module looks independently deployable — it has its own
 *                     {@code conf/routes} or a main class — which is what promotes it to a
 *                     {@code SUB_MODULE} node rather than a plain library module
 * @param line         1-based declaration line, for evidence
 */
public record SbtModule(String id, String name, String relativePath, boolean deployable, int line) {
}

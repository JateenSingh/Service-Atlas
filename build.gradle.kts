// Root build file. All real work happens in :backend (Java/Spring Boot) and the
// Angular CLI project under frontend/, which :backend bundles into its jar.
tasks.register("bootJar") {
    group = "build"
    description = "Builds the single deployable artifact (backend + bundled Angular frontend)."
    dependsOn(":backend:bootJar")
}

tasks.register("bootRun") {
    group = "application"
    description = "Runs Service Atlas locally."
    dependsOn(":backend:bootRun")
}

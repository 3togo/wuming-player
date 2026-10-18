# Java required by an existing contract

This source root contains only `language-required` facades registered in
`docs/java-retention.json` at the repository root. Keep these files in Java and
preserve their original JVM packages. Do not move them into ordinary `java` or
`kotlin` source folders during routine migration.

`HlsPipeline` must accept a null varargs array and retain the JVM varargs flag.
Its implementation belongs in `HlsPipelineEngine.kt`; this folder holds only the
Java entry surface. Read the root `docs/language-boundaries.md` before changing it.

New entries require an explicit contract reason, release condition, and Java
consumer tests. Compatibility holds belong outside this folder. Run
`verifyJavaRetention` and the affected contract tests after changes.

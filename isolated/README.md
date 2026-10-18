# Isolated code

This tree preserves work which is not allowed to enter a production TVBox build. Keeping it in
the repository may retain useful research or make an unfinished feature resumable, but physical
presence does not imply production support.

- [`incubator/`](incubator/) contains unfinished work with an explicit route to production.
- [`archive/`](archive/) contains abandoned or superseded work retained only for reference.
- [`vendor-snapshots/`](vendor-snapshots/) contains frozen source associated with separately
  packaged vendor artifacts. It is evidence and rebuild research material, not production source.

Production Gradle projects must not use any isolated directory as a project, source set, dependency,
or packaged artifact. `verifyCodeLifecycleIsolation` enforces the build-graph and artifact boundary.

Do not move code here merely because it is old. Compatibility readers, migrations, fallbacks, and
deprecated APIs still used by production are production code until their removal criteria are met.

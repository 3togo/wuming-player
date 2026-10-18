# Design core

Platform-neutral theme identities, semantic palettes, component metrics and layout
policy shared by Android and Swing. The module contains no widgets or persistence.

`design-tokens.json` is the editable token source. Generate adapters with
`python3 scripts/generate-design-tokens.py`; verify with
`./gradlew verifyDesignTokens :libraries:design-core:test`.

See [the framework plan](../../docs/layout-theme-framework.md) for platform
integration, compatibility decisions, migration scope and validation.

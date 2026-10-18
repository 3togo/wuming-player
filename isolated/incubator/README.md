# Incubator

Unfinished features live here when keeping them inside production source sets would make their
support status ambiguous.

Each incubating feature must have its own directory and a `LIFECYCLE.md` containing:

- owner or responsible area;
- problem and intended user value;
- current limitations and security assumptions;
- last validation date and command;
- dependencies and supported platforms;
- concrete graduation criteria;
- review date after which it should be archived or deleted.

Incubator code is opt-in developer material. It must not be added to `settings.gradle`, Android
source sets, `app/libs`, release packaging, or the production dependency graph. To graduate, move
the feature into a production module through a reviewed change and add normal regression tests.

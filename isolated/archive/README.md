# Archive

Archived code is abandoned, superseded, or retained solely to explain a historical data format.
It is not a dormant feature flag and must never be compiled or packaged.

Each archived item must have a `LIFECYCLE.md` recording:

- the archival decision and date;
- the last known commit or release;
- why deletion was deferred;
- any data migration or compatibility dependency;
- the condition under which it can be deleted permanently.

Restoring archived code is a new implementation decision. Move only the still-relevant concepts
through review; do not reconnect an archived directory directly to production.

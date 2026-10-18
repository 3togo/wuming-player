# user-library-core

Platform-neutral rules and ports for a user's playback history, favorites, and viewing
sessions. Android owns Room entities, migrations, preferences, scheduling, and UI adapters;
desktop clients can implement the same repository contracts with their own persistence.

`VideoLibraryEntry` and `EpisodeSelectionPolicy` provide the common non-Android identity and
episode-restoration behavior. Platform UIs remain responsible for rendering history/favorites and
for reporting successful playback launches.

Android implementations live under `app/.../repository`; Room and the in-memory cache are
not visible to entities or to this module's domain policies. Android workflows are composed by
`app/.../library/UserLibrary`, keeping persistence methods off the Room data models.

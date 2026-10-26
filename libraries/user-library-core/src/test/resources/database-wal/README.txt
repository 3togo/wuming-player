Synthetic SQLite fixtures; no user data. Generated with Python sqlite3 on 2026-10-26.

Set page_size=512, journal_mode=WAL and wal_autocheckpoint=0. Begin a transaction, create retained(value TEXT),
set user_version=46, insert the literal 'retained', and commit. While the connection
remains open, snapshot the database as main.db and its WAL as supported.wal. Set
user_version=47, commit, and snapshot newer.wal before closing the connection.

The main header remains version 0. In supported.wal, page one is updated before
the transaction's last (commit) frame, exercising multi-frame commit handling.
big-endian.wal contains the same supported frames with the other SQLite checksum
byte order (magic 0x377f0683) and recomputed checksums. SQLite itself was used to
reopen copies and confirm versions 46, 47 and 46 respectively.

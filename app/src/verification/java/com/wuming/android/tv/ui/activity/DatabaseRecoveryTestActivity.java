package com.wuming.android.tv.ui.activity;

import java.io.File;

/** Verification-only activity: recovery UI bound exclusively to test-owned cache fixtures. */
public final class DatabaseRecoveryTestActivity extends DatabaseRecoveryActivity {
    @Override protected File databaseFile() {
        String name = getIntent().getStringExtra("fixture");
        if (name == null || !name.matches("recovery-smoke-[a-zA-Z0-9-]+"))
            throw new IllegalArgumentException("Test-owned fixture required");
        return new File(new File(getCacheDir(), name), "tv");
    }
}

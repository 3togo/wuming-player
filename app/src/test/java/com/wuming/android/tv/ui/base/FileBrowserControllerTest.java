package com.wuming.android.tv.ui.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

public class FileBrowserControllerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void initialDirectoryIsConfinedToConfiguredRoot() throws Exception {
        File root = temporaryFolder.newFolder("root");
        File child = new File(root, "child");
        assertTrue(child.mkdir());
        File outside = temporaryFolder.newFolder("outside");
        FileBrowserController browser = new FileBrowserController(root);

        assertEquals(child.getCanonicalFile(), browser.openInitial(child.getPath()));
        assertTrue(browser.canGoBack());
        assertEquals(root.getCanonicalFile(), browser.goBack());
        assertFalse(browser.canGoBack());
        assertEquals(root.getCanonicalFile(), browser.openInitial(outside.getPath()));
    }

    @Test
    public void extensionFilterShowsOnlyMatchingFiles() throws Exception {
        File root = temporaryFolder.newFolder("root");
        new File(root, "backup.json").createNewFile();
        new File(root, "config.txt").createNewFile();
        File sub = new File(root, "sub");
        assertTrue(sub.mkdir());

        FileBrowserController browser = new FileBrowserController(root, ".json");
        List<File> entries = browser.entries();

        assertTrue(entries.stream().anyMatch(f -> f.getName().equals("backup.json")));
        assertTrue(entries.stream().anyMatch(f -> f.getName().equals("sub")));
        assertFalse(entries.stream().anyMatch(f -> f.getName().equals("config.txt")));
    }

    @Test
    public void noFilterShowsAllFiles() throws Exception {
        File root = temporaryFolder.newFolder("root");
        new File(root, "backup.json").createNewFile();
        new File(root, "config.txt").createNewFile();

        FileBrowserController browser = new FileBrowserController(root);
        List<File> entries = browser.entries();

        assertTrue(entries.stream().anyMatch(f -> f.getName().equals("backup.json")));
        assertTrue(entries.stream().anyMatch(f -> f.getName().equals("config.txt")));
    }
}

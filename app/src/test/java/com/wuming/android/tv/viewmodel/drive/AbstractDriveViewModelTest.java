package com.wuming.android.tv.viewmodel.drive;

import static org.junit.Assert.assertEquals;

import com.wuming.android.tv.bean.DriveFolderFile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AbstractDriveViewModelTest {

    @Test
    public void keepsBackItemFirstAndSortsMissingDatesSafely() {
        TestViewModel model = new TestViewModel();
        model.setSortType(2);
        DriveFolderFile back = item(null, null);
        DriveFolderFile newest = item("newest", 20L);
        DriveFolderFile missingDate = item("missing", null);
        DriveFolderFile oldest = item("oldest", 10L);
        List<DriveFolderFile> data = new ArrayList<>(
                List.of(back, newest, missingDate, oldest));

        model.sort(data);

        assertEquals(List.of(back, missingDate, oldest, newest), data);
    }

    @Test
    public void nameSortIsCaseInsensitiveInBothDirections() {
        TestViewModel model = new TestViewModel();
        DriveFolderFile alphaLower = item("alpha", 1L);
        DriveFolderFile alphaUpper = item("ALPHA", 1L);
        DriveFolderFile beta = item("beta", 1L);
        List<DriveFolderFile> data = new ArrayList<>(List.of(beta, alphaLower, alphaUpper));

        model.setSortType(0);
        model.sort(data);
        assertEquals(beta, data.get(2));

        model.setSortType(1);
        model.sort(data);
        assertEquals(beta, data.get(0));
    }

    private static DriveFolderFile item(String name, Long modified) {
        return new DriveFolderFile(null, name, 0, false, null, modified);
    }

    private static final class TestViewModel extends AbstractDriveViewModel {
        @Override
        public String loadData(LoadDataCallback callback) {
            return "";
        }

        private void sort(List<DriveFolderFile> data) {
            sortData(data);
        }
    }
}

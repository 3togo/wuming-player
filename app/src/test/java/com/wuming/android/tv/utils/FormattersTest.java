package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

public class FormattersTest {

    @Test
    public void dateAndLocalDateTimeFormatsAreStable() {
        assertEquals("2026-07-22", Formatters.DATE.format(LocalDate.of(2026, 7, 22)));
        assertEquals("2026/07/22 13:04:05",
                Formatters.LOCAL_DATETIME.format(LocalDateTime.of(2026, 7, 22, 13, 4, 5)));
    }

    @Test
    public void epgUtcRangeAlwaysUsesZuluTime() {
        assertEquals("20260722T050405Z", Formatters.EPG_RANGE.format(Instant.parse("2026-07-22T05:04:05Z")));
    }

    @Test
    public void epgDateTimeVariantsMatchProviderFormats() {
        LocalDateTime value = LocalDateTime.of(2026, 7, 22, 13, 4, 5);

        assertEquals("2026-07-2213:04", Formatters.EPG_DT_SHORT.format(value));
        assertEquals("2026-07-2213:04:05", Formatters.EPG_DT_LONG.format(value));
        assertEquals("20260722130405", Formatters.EPG_FULL_NO_TZ.format(value));
    }

    @Test
    public void epgOffsetsWithAndWithoutColonRepresentTheSameInstant() {
        assertEquals(OffsetDateTime.parse("20260911120000 +0800", Formatters.EPG_FULL).toInstant(),
                OffsetDateTime.parse("20260911120000 +08:00", Formatters.EPG_FULL_COLON).toInstant());
    }
}

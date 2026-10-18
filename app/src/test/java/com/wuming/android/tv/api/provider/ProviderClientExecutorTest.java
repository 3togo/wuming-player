package com.wuming.android.tv.api.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;

public class ProviderClientExecutorTest {

    @Test
    public void pipeExecutorHasBoundedThreadsAndQueue() throws Exception {
        Field field = ProviderClient.class.getDeclaredField("PIPE_EXECUTOR");
        field.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) field.get(null);

        assertEquals(8, executor.getMaximumPoolSize());
        assertEquals(64, executor.getQueue().remainingCapacity());
        assertTrue(executor.allowsCoreThreadTimeOut());
    }
}

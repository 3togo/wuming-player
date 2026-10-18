package com.wuming.android.tv.player.exo;

import androidx.media3.datasource.DataSource;
import org.junit.Test;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RangeProgressTest {
    private static final Class<?> PART;
    static { try { PART=Class.forName(ParallelRangeDataSource.class.getName()+"$Part"); }
        catch(Exception e){throw new ExceptionInInitializerError(e);} }
    private Object part(int length,DataSource source) throws Exception {
        for(Constructor<?> c:PART.getDeclaredConstructors()) if(c.getParameterCount()==5&&c.getParameterTypes()[0]==DataSource.class){
            c.setAccessible(true);return c.newInstance(source,(long)length,true,null,null);
        }
        throw new AssertionError("Missing buffered constructor");
    }
    private Object call(Object part,String name,Class<?>[] types,Object...args) throws Exception {
        Method method=PART.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(part,args);
    }
    private Object call(Object part,String name) throws Exception {return call(part,name,new Class<?>[0]);}
    private CompletableFuture<?> progress(Object part) throws Exception {return (CompletableFuture<?>)call(part,"progressFuture");}
    private DataSource source(int length,AtomicInteger closed,CountDownLatch first,CountDownLatch release) {
        AtomicInteger position=new AtomicInteger();
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            return switch(m.getName()) {
                case "read" -> {
                    if(position.get()==1&&release!=null){first.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("fixture timeout");}
                    if(position.get()==length)yield -1;
                    ((byte[])a[0])[(int)a[1]]=(byte)position.incrementAndGet();yield 1;
                }
                case "close" -> {closed.incrementAndGet();yield null;}
                case "getUri" -> null;
                case "getResponseHeaders" -> Map.of();
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
    }
    @Test public void unobservedReadsDoNotCreateProgressFuturesAndBytesRemainOrdered() throws Exception {
        AtomicInteger closed=new AtomicInteger();Object part=part(1024,source(1024,closed,null,null));
        call(part,"fillBuffer");Field field=PART.getDeclaredField("nextProgress");field.setAccessible(true);assertNull(field.get(part));
        byte[] bytes=new byte[1024];assertEquals(1024,call(part,"read",new Class<?>[]{byte[].class,int.class,int.class},bytes,0,1024));
        for(int i=0;i<bytes.length;i++)assertEquals((byte)(i+1),bytes[i]);
        call(part,"close");assertEquals(1,closed.get());
    }
    @Test(timeout=10000) public void observersShareSignalAndCanRearmAfterConsumingData() throws Exception {
        CountDownLatch first=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger closed=new AtomicInteger();
        Object part=part(2,source(2,closed,first,release));CompletableFuture<?> one=progress(part);assertSame(one,progress(part));
        CompletableFuture<Void> fill=CompletableFuture.runAsync(()->{try{call(part,"fillBuffer");}catch(Exception e){throw new CompletionException(e);}});
        try {
            assertTrue(first.await(5,TimeUnit.SECONDS));one.get(5,TimeUnit.SECONDS);
            byte[] out=new byte[1];call(part,"read",new Class<?>[]{byte[].class,int.class,int.class},out,0,1);assertEquals(1,out[0]);
            CompletableFuture<?> two=progress(part);assertNotSame(one,two);assertFalse(two.isDone());
            release.countDown();two.get(5,TimeUnit.SECONDS);fill.get(5,TimeUnit.SECONDS);
            call(part,"read",new Class<?>[]{byte[].class,int.class,int.class},out,0,1);assertEquals(2,out[0]);
        } finally {release.countDown();fill.get(5,TimeUnit.SECONDS);call(part,"close");}
    }
    @Test public void failureCompletesExistingAndLateObservers() throws Exception {
        Object part=part(1,source(1,new AtomicInteger(),null,null));CompletableFuture<?> waiting=progress(part);
        call(part,"setFailure",new Class<?>[]{IOException.class},new IOException("fixture"));
        assertTrue(waiting.isCompletedExceptionally());assertTrue(progress(part).isCompletedExceptionally());call(part,"close");
    }
    @Test public void closeWorksWithAndWithoutObservers() throws Exception {
        for(boolean observe:new boolean[]{false,true}) {
            AtomicInteger closed=new AtomicInteger();Object part=part(1,source(1,closed,null,null));
            CompletableFuture<?> waiting=observe?progress(part):null;call(part,"close");
            if(waiting!=null)assertTrue(waiting.isCompletedExceptionally());
            assertTrue(progress(part).isCompletedExceptionally());call(part,"close");assertEquals(1,closed.get());
        }
    }
}

package com.wuming.android.tv.player.exo;
import androidx.media3.datasource.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic buffered Part only: no socket, provider, seek, or decoder benchmark. */
public final class RangeProgressBenchmark {
    public static void main(String[] args) throws Exception {
        Class<?> partClass=Class.forName("com.wuming.android.tv.player.exo."+args[0]+"$Part");
        int chunk=Integer.parseInt(args[1]);Constructor<?> constructor=null;
        for(Constructor<?> c:partClass.getDeclaredConstructors())if(c.getParameterCount()==5&&c.getParameterTypes()[0]==DataSource.class)constructor=c;
        if(constructor==null)throw new AssertionError();constructor.setAccessible(true);
        Method fill=partClass.getDeclaredMethod("fillBuffer");fill.setAccessible(true);
        Field buffer=partClass.getDeclaredField("buffer");buffer.setAccessible(true);
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        for(int sample=-2;sample<4;sample++) {
            System.gc();long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();long checksum=0;int reads=0;
            for(int part=0;part<16;part++) {
                AtomicInteger position=new AtomicInteger(),calls=new AtomicInteger(),closed=new AtomicInteger();int length=128*1024;
                DataSource source=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,values)->{
                    if(method.getName().equals("close")){closed.incrementAndGet();return null;}
                    if(!method.getName().equals("read"))throw new UnsupportedOperationException(method.getName());
                    int n=Math.min(chunk,Math.min((int)values[2],length-position.get()));
                    Arrays.fill((byte[])values[0],(int)values[1],(int)values[1]+n,(byte)7);position.addAndGet(n);calls.incrementAndGet();return n;
                });
                Object value=constructor.newInstance(source,(long)length,true,null,null);fill.invoke(value);
                if(position.get()!=length||closed.get()!=1)throw new AssertionError("Transfer/close mismatch");
                for(byte b:(byte[])buffer.get(value))checksum+=b;
                reads+=calls.get();
            }
            long nanos=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
            if(checksum!=16L*128*1024*7)throw new AssertionError("Output mismatch");
            if(sample>=0)System.out.printf(Locale.ROOT,"{\"sample\":%d,\"elapsedMs\":%.6f,\"allocatedBytes\":%d,\"reads\":%d,\"checksum\":%d}%n",sample,nanos/1e6,allocated,reads,checksum);
        }
    }
}

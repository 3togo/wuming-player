import com.wuming.tv.desktop.python.DesktopPythonRuntime;
import com.wuming.tv.core.runtime.python.NativePythonBridge;
import java.nio.file.*;
import java.util.*;
public class RelocatedPythonSmoke {
    public static void main(String[] args) throws Exception {
        DesktopPythonRuntime.INSTANCE.start();
        String source = "from base.spider import Spider as Base\nclass Spider(Base):\n def action(self,v):\n  import requests\n  from lxml import etree\n  from Crypto.Cipher import AES\n  return {'requests':requests.__file__, 'xml':etree.fromstring(b'<x>ok</x>').text, 'aes':len(AES.new(b'0'*16,AES.MODE_ECB).encrypt(b'1'*16))}\n";
        Path data = Files.createTempDirectory("wuming-distribution-smoke-");
        String handle = NativePythonBridge.INSTANCE.request(Map.of("op","open","source",source,"api","https://example.invalid/fixture.py","directory",data.toString(),"providerKey","fixture")).getAsString();
        var result = NativePythonBridge.INSTANCE.request(Map.of("op","call","handle",handle,"method","action","args",List.of("test"))).getAsJsonObject();
        if (!result.get("requests").getAsString().contains("/lib/python-runtime/home/")) throw new AssertionError(result);
        if (result.get("aes").getAsInt()!=16 || !result.get("xml").getAsString().equals("ok")) throw new AssertionError(result);
        String maps = Files.readString(Path.of("/proc/self/maps"));
        if (!maps.contains("/lib/python-runtime/lib/libpython")) throw new AssertionError("Bundled libpython was not loaded");
        NativePythonBridge.INSTANCE.request(Map.of("op","close","handle",handle));
        Files.delete(data);
        System.out.println("PASS: installed distribution loaded its bundled CPython, requests, lxml and AES through JNI from an unrelated working directory.");
    }
}

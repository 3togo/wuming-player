package com.github.catvod.crawler;

import android.content.Context;
import java.util.List;

/** Compile-only ABI stub authored for the synthetic fixtures. Never packaged. */
public abstract class Spider {
    public void init(Context context) throws Exception {}
    public String homeContent(boolean filter) throws Exception { return "{}"; }
    public String detailContent(List<String> ids) throws Exception { return "{}"; }
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception { return "{}"; }
}

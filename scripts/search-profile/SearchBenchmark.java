package com.wuming.tv.core.search;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Offline single-thread policy benchmark. Not packaged into any client. */
public final class SearchBenchmark {
    static final TitleNormalizer NORMALIZER = new TitleNormalizer(value -> value.replace('無','无').replace('間','间'));
    static final Access ACCESS = new Access();
    static final SearchResultPolicy<Item> CURRENT = new SearchResultPolicy<>(ACCESS, NORMALIZER);
    static final LegacySearchResultPolicy<Item> LEGACY = new LegacySearchResultPolicy<>(ACCESS, NORMALIZER);
    public static void main(String[] args) throws Exception {
        String algorithm = args[0], work = args[1], shape = args[2];
        int count = Integer.parseInt(args[3]), warmups = Integer.parseInt(args[4]), samples = Integer.parseInt(args[5]);
        var allocation = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        for (int sample = -warmups; sample < samples; sample++) {
            List<Item> items = fixture(shape, count);
            System.gc();
            long before = allocation.getThreadAllocatedBytes(thread), begin = System.nanoTime();
            if (work.equals("merge")) items = algorithm.equals("legacy") ? LEGACY.merge(items) : CURRENT.merge(items);
            else if (algorithm.equals("legacy")) items.sort((a,b) -> {
                int rank = Integer.compare(b.rank, a.rank);
                return rank != 0 ? rank : LEGACY.compareRelevance(a,b,"Needle");
            });
            else CURRENT.sortByRelevance(items,"Needle", item -> item.rank);
            long elapsed = System.nanoTime()-begin, bytes = allocation.getThreadAllocatedBytes(thread)-before;
            String digest = fingerprint(items);
            if (sample >= 0) System.out.printf(Locale.ROOT,
                    "{\"sample\":%d,\"elapsedMs\":%.6f,\"allocatedBytes\":%d,\"outputs\":%d,\"fingerprint\":\"%s\"}%n",
                    sample, elapsed/1e6, bytes, items.size(), digest);
        }
    }
    static List<Item> fixture(String shape, int count) {
        Random random = new Random(5041);
        List<Item> items = new ArrayList<>();
        for (int i=0;i<count;i++) {
            Item item = new Item(); item.ordinal=i;
            item.title = "Unique " + i;
            if (shape.equals("mixed")) {
                item.title = switch(i%5) { case 0 -> "Needle"; case 1 -> "無間道"; case 2 -> "Needle Documentary"; case 3 -> "Series " + i%31; default -> "The Thing"; };
                item.id = i%4==0 ? "canonical-"+i%11 : "";
                item.year = i%3==0 ? "" : "year-"+i%7;
                item.type = i%2==0 ? "movie" : "";
                item.aliases.add(i%6==0 ? "Needle" : "Alias " + i%9);
            }
            item.sources.add(i); item.rank=random.nextInt(3); item.artwork=i%3==0 ? "poster-"+i : "";
            items.add(item);
        }
        Collections.shuffle(items, new Random(73));
        return items;
    }
    static String fingerprint(List<Item> items) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for(Item i:items) digest.update((i.ordinal+"|"+i.title+"|"+i.id+"|"+i.year+"|"+i.type+"|"+i.artwork+"|"+i.aliases+"|"+i.sources+"\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }
    static final class Item {
        int ordinal,rank; String title="",id="",year="",type="",artwork="";
        List<String> aliases=new ArrayList<>(); List<Integer> sources=new ArrayList<>();
    }
    static final class Access implements SearchResultAccess<Item> {
        public String title(Item i){return i.title;} public String year(Item i){return i.year;}
        public String mediaType(Item i){return i.type;} public String artwork(Item i){return i.artwork;}
        public String canonicalId(Item i){return i.id;} public List<String> aliases(Item i){return i.aliases;}
        public String originalQuery(Item i){return "";} public String matchedQuery(Item i){return "";}
        public void assignIdentity(Item i,String id,List<String> aliases,String original,String matched){i.id=id;i.aliases=new ArrayList<>(aliases);}
        public void mergeSources(Item a,Item b){for(Integer source:b.sources)if(!a.sources.contains(source))a.sources.add(source);}
        public void setArtwork(Item i,String artwork){i.artwork=artwork;}
    }
}

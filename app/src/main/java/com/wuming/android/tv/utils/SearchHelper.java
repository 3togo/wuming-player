package com.wuming.android.tv.utils;

import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.search.SearchSourceStore;
import com.wuming.tv.core.search.SearchTextMatcher;
import com.wuming.tv.core.search.SearchSuggestions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Search helper utility for text searching, filtering, and checked sources management
 */
public final class SearchHelper {

    private SearchHelper() {
    }

    private static String getApiUrl() {
        try {
            String url = VodConfig.get().getUrl();
            return url != null ? url : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static HashMap<String, String> getSourcesForSearch() {
        String api = getApiUrl();
        if (api.isEmpty()) {
            return null;
        }
        HashMap<String, String> checkedSources = SearchSourceStore.get(api);
        if (checkedSources != null && !checkedSources.isEmpty()) return checkedSources;

        checkedSources = new HashMap<>();
        try {
            for (Site bean : VodConfig.get().getSites()) {
                if (bean.isSearchable()) checkedSources.put(bean.getKey(), "1");
            }
        } catch (Exception ignored) {
        }
        return checkedSources;
    }

    public static void putCheckedSources(HashMap<String, String> checkedSources) {
        String api = getApiUrl();
        if (api.isEmpty()) {
            return;
        }
        SearchSourceStore.put(api, checkedSources);
    }

    public static void putCheckedSource(String siteKey, boolean checked) {
        String api = getApiUrl();
        if (api.isEmpty()) {
            return;
        }
        SearchSourceStore.put(api, siteKey, checked);
    }

    public static List<String> splitWords(String text) {
        return SearchSuggestions.local(text);
    }

    public static List<String> search(List<String> items, String query) {
        List<String> results = new ArrayList<>();
        if (items == null || query == null || query.trim().isEmpty()) {
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        }

        String lowerQuery = query.toLowerCase(Locale.getDefault()).trim();

        for (String item : items) {
            if (item != null && item.toLowerCase(Locale.getDefault()).contains(lowerQuery)) {
                results.add(item);
            }
        }

        return results;
    }

    public static List<String> searchFuzzy(List<String> items, String query) {
        List<String> results = new ArrayList<>();
        if (items == null || query == null || query.trim().isEmpty()) {
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        }

        String lowerQuery = query.toLowerCase(Locale.getDefault()).trim();

        for (String item : items) {
            if (item != null && fuzzyMatch(item.toLowerCase(Locale.getDefault()), lowerQuery)) {
                results.add(item);
            }
        }

        return results;
    }

    public static boolean fuzzyMatch(String text, String query) {
        return SearchTextMatcher.fuzzyMatch(text, query);
    }

    public static int levenshteinDistance(String s1, String s2) {
        return SearchTextMatcher.levenshteinDistance(s1, s2);
    }

    public static double similarity(String s1, String s2) {
        return SearchTextMatcher.similarity(s1, s2);
    }

    public static List<String> searchSimilar(List<String> items, String query, double threshold) {
        List<String> results = new ArrayList<>();
        if (items == null || query == null || query.trim().isEmpty()) {
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        }

        String lowerQuery = query.toLowerCase(Locale.getDefault()).trim();

        for (String item : items) {
            if (item != null) {
                double sim = similarity(item.toLowerCase(Locale.getDefault()), lowerQuery);
                if (sim >= threshold) {
                    results.add(item);
                }
            }
        }

        return results;
    }

    public static List<SearchResult> searchRanked(List<String> items, String query) {
        List<SearchResult> results = new ArrayList<>();
        if (items == null || query == null || query.trim().isEmpty()) {
            return results;
        }

        String lowerQuery = query.toLowerCase(Locale.getDefault()).trim();

        for (String item : items) {
            if (item != null) {
                double sim = similarity(item.toLowerCase(Locale.getDefault()), lowerQuery);
                if (sim > 0.0) {
                    results.add(new SearchResult(item, sim));
                }
            }
        }

        results.sort((a, b) -> Double.compare(b.score, a.score));

        return results;
    }

    public static class SearchResult {
        public String text;
        public double score;

        public SearchResult(String text, double score) {
            this.text = text;
            this.score = score;
        }

        @Override
        public String toString() {
            return String.format(Locale.getDefault(), "SearchResult{text='%s', score=%.2f}", text, score);
        }
    }

    public static String[] search(String[] items, String query) {
        List<String> results = search(java.util.Arrays.asList(items), query);
        return results.toArray(new String[0]);
    }

    public static List<String> searchPrefix(List<String> items, String query) {
        List<String> results = new ArrayList<>();
        if (items == null || query == null || query.trim().isEmpty()) {
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        }

        String lowerQuery = query.toLowerCase(Locale.getDefault()).trim();

        for (String item : items) {
            if (item != null && item.toLowerCase(Locale.getDefault()).startsWith(lowerQuery)) {
                results.add(item);
            }
        }

        return results;
    }

    public static List<String> searchSuffix(List<String> items, String query) {
        List<String> results = new ArrayList<>();
        if (items == null || query == null || query.trim().isEmpty()) {
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        }

        String lowerQuery = query.toLowerCase(Locale.getDefault()).trim();

        for (String item : items) {
            if (item != null && item.toLowerCase(Locale.getDefault()).endsWith(lowerQuery)) {
                results.add(item);
            }
        }

        return results;
    }

    public static List<String> searchExact(List<String> items, String query) {
        List<String> results = new ArrayList<>();
        if (items == null || query == null) {
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        }

        for (String item : items) {
            if (item != null && item.equals(query)) {
                results.add(item);
            }
        }

        return results;
    }

    public static List<String> searchExactIgnoreCase(List<String> items, String query) {
        List<String> results = new ArrayList<>();
        if (items == null || query == null) {
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        }

        String lowerQuery = query.toLowerCase(Locale.getDefault());

        for (String item : items) {
            if (item != null && item.toLowerCase(Locale.getDefault()).equals(lowerQuery)) {
                results.add(item);
            }
        }

        return results;
    }

    public static List<String> searchRegex(List<String> items, String regex) {
        List<String> results = new ArrayList<>();
        if (items == null || regex == null || regex.trim().isEmpty()) {
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        }

        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);

            for (String item : items) {
                if (item != null && pattern.matcher(item).find()) {
                    results.add(item);
                }
            }
        } catch (java.util.regex.PatternSyntaxException e) {
        }

        return results;
    }

    public static List<String> searchAnd(List<String> items, List<String> queries) {
        List<String> results = new ArrayList<>();
        if (items == null || queries == null || queries.isEmpty()) {
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        }

        for (String item : items) {
            if (item != null) {
                boolean matchesAll = true;
                String lowerItem = item.toLowerCase(Locale.getDefault());

                for (String query : queries) {
                    if (query != null && !query.trim().isEmpty()) {
                        String lowerQuery = query.toLowerCase(Locale.getDefault()).trim();
                        if (!lowerItem.contains(lowerQuery)) {
                            matchesAll = false;
                            break;
                        }
                    }
                }

                if (matchesAll) {
                    results.add(item);
                }
            }
        }

        return results;
    }

    public static List<String> searchOr(List<String> items, List<String> queries) {
        List<String> results = new ArrayList<>();
        if (items == null || queries == null || queries.isEmpty()) {
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        }

        for (String item : items) {
            if (item != null) {
                boolean matchesAny = false;
                String lowerItem = item.toLowerCase(Locale.getDefault());

                for (String query : queries) {
                    if (query != null && !query.trim().isEmpty()) {
                        String lowerQuery = query.toLowerCase(Locale.getDefault()).trim();
                        if (lowerItem.contains(lowerQuery)) {
                            matchesAny = true;
                            break;
                        }
                    }
                }

                if (matchesAny) {
                    results.add(item);
                }
            }
        }

        return results;
        }

    public static List<String> searchExclude(List<String> items, String excludeQuery) {
        List<String> results = new ArrayList<>();
        if (items == null) {
            return results;
        }

        if (excludeQuery == null || excludeQuery.trim().isEmpty()) {
            return new ArrayList<>(items);
        }

        String lowerExclude = excludeQuery.toLowerCase(Locale.getDefault()).trim();

        for (String item : items) {
            if (item != null && !item.toLowerCase(Locale.getDefault()).contains(lowerExclude)) {
                results.add(item);
            }
        }

        return results;
    }

    public static String highlight(String text, String query, String startTag, String endTag) {
        if (text == null || query == null || query.trim().isEmpty()) {
            return text;
        }

        String lowerText = text.toLowerCase(Locale.getDefault());
        String lowerQuery = query.toLowerCase(Locale.getDefault()).trim();

        if (!lowerText.contains(lowerQuery)) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        int lastIndex = 0;
        int index = lowerText.indexOf(lowerQuery);

        while (index >= 0) {
            result.append(text, lastIndex, index);
            result.append(startTag);
            result.append(text, index, index + lowerQuery.length());
            result.append(endTag);

            lastIndex = index + lowerQuery.length();
            index = lowerText.indexOf(lowerQuery, lastIndex);
        }

        result.append(text, lastIndex, text.length());
        return result.toString();
    }

    public static String highlightHtml(String text, String query) {
        return highlight(text, query, "<b>", "</b>");
    }

    public static List<String> getSuggestions(List<String> dictionary, String input, int maxSuggestions) {
        List<String> suggestions = new ArrayList<>();
        if (dictionary == null || input == null || input.trim().isEmpty()) {
            return suggestions;
        }

        String lowerInput = input.toLowerCase(Locale.getDefault()).trim();
        List<SearchResult> ranked = searchRanked(dictionary, lowerInput);

        for (int i = 0; i < Math.min(ranked.size(), maxSuggestions); i++) {
            suggestions.add(ranked.get(i).text);
        }

        return suggestions;
    }

    public static boolean containsAllWords(String text, String query) {
        return SearchTextMatcher.containsAllWords(text, query);
    }

    public static boolean containsAnyWord(String text, String query) {
        return SearchTextMatcher.containsAnyWord(text, query);
    }

    public static double wordSimilarity(String s1, String s2) {
        return SearchTextMatcher.wordSimilarity(s1, s2);
    }

    public static List<SearchResult> searchWordSimilarity(List<String> items, String query, double threshold) {
        List<SearchResult> results = new ArrayList<>();
        if (items == null || query == null || query.trim().isEmpty()) {
            return results;
        }

        for (String item : items) {
            if (item != null) {
                double sim = wordSimilarity(item, query);
                if (sim >= threshold) {
                    results.add(new SearchResult(item, sim));
                }
            }
        }

        results.sort((a, b) -> Double.compare(b.score, a.score));

        return results;
    }
}

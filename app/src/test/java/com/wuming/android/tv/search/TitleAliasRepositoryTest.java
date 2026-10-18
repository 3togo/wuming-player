package com.wuming.android.tv.search;

import static org.junit.Assert.assertEquals;

import com.wuming.tv.core.search.TitleIdentity;

import org.junit.Test;

import java.util.List;

public class TitleAliasRepositoryTest {

    @Test
    public void prettyWomanBuiltInAliasWorksWithoutNetwork() {
        TitleIdentity identity = new TitleAliasRepository(false).resolve("pretty woman");

        assertEquals("wikidata:Q207954", identity.canonicalId());
        assertEquals("风月俏佳人", identity.aliases().get(1));
    }

    @Test
    public void doubanEnglishLookupProducesProperChineseAlias() {
        String json = """
                [{
                  "id": "1307914",
                  "title": "无间道",
                  "sub_title": "無間道",
                  "year": "2002",
                  "type": "movie"
                }]
                """;

        TitleIdentity identity = TitleAliasRepository.parseDouban(json, "Infernal Affairs");

        assertEquals("douban:1307914", identity.canonicalId());
        assertEquals(List.of("Infernal Affairs", "无间道", "無間道"), identity.aliases());
        assertEquals("2002", identity.year());
    }

    @Test
    public void prettyWomanUsesFilmAliasInsteadOfUnrelatedExactMatches() {
        String searchJson = """
                {
                  "search": [
                    {
                      "id": "Q1472773",
                      "description": "song written and composed by Roy Orbison",
                      "match": {"type": "alias", "text": "Pretty Woman"}
                    },
                    {
                      "id": "Q207954",
                      "description": "1990 film directed by Garry Marshall",
                      "match": {"type": "label", "text": "Pretty Woman"}
                    }
                  ]
                }
                """;
        String entityJson = """
                {
                  "entities": {
                    "Q207954": {
                      "labels": {
                        "zh-cn": {"value": "漂亮女人"},
                        "zh": {"value": "麻雀變鳳凰"}
                      },
                      "aliases": {
                        "zh": [
                          {"value": "风月俏佳人"},
                          {"value": "風月俏佳人"}
                        ]
                      }
                    }
                  }
                }
                """;

        TitleIdentity identity = TitleAliasRepository.parseWikidata(searchJson, entityJson, "Pretty Woman");

        assertEquals("wikidata:Q207954", identity.canonicalId());
        assertEquals(List.of("Pretty Woman", "风月俏佳人", "漂亮女人", "麻雀变凤凰"), identity.aliases());
        assertEquals("1990", identity.year());
        assertEquals("movie", identity.mediaType());
    }
}

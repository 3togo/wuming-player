package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class CatalogDescriptionHtmlTest {
    @Test fun preservesParagraphsChineseLineBreaksAndBasicFormatting() {
        val html = CatalogDescriptionHtml.fragment("<p>里米的故事<br/>第二行 <b>電影</b></p><p>改編小說。</p><ul><li>演員</li></ul>")
        assertTrue(html.contains("<p>里米的故事<br>第二行 <b>電影</b></p>"))
        assertTrue(html.contains("<p>改編小說。</p><ul><li>演員</li></ul>"))
        assertTrue(CatalogDescriptionHtml.fragment("結尾</p><br/>改編小說").contains("<br>改編小說"))
    }

    @Test fun retainsPlainLinesAndEscapesLiteralMetadata() {
        assertEquals("第一行<br>第二行 &amp; 其他", CatalogDescriptionHtml.fragment("第一行\n第二行 & 其他"))
        assertEquals("A &lt; B &amp; C", CatalogDescriptionHtml.text("A < B & C"))
        assertEquals("一 &lt; 二<br>三", CatalogDescriptionHtml.fragment("一 < 二\r\n三"))
    }

    @Test fun removesActiveContentResourcesAndProviderStyling() {
        val html = CatalogDescriptionHtml.fragment("""<style>body{color:red}</style><script>alert(1)</script><img src="https://example.invalid/x"><p style="color:red" onclick="evil()">保留 <a href="javascript:evil()">文字</a></p><iframe src="https://example.invalid"></iframe>""")
        assertEquals("<p>保留 文字</p>", html)
        assertFalse(html.contains("example.invalid"))
    }
}

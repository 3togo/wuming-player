package com.wuming.tv.desktop.python

import com.wuming.tv.desktop.config.DesktopTvBoxConfigurationLoader
import com.wuming.tv.desktop.net.DesktopResourceLoader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopPythonRuntimeTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun localPythonConfigExecutesThroughCatalogAndScopedProxy() {
        File(temp.root, "index.json").writeText("""{"wumingPackageId":"${"b".repeat(32)}","sites":[{"key":"demo","type":3,"api":"./spider.py"}]}""")
        File(temp.root, "spider.py").writeText("""
            from base.spider import Spider as Base
            class Spider(Base):
                def homeContent(self,filter): return {'class':[{'type_id':'1','type_name':'Demo'}], 'list':[{'vod_id':'id','vod_name':self.siteKey}]}
                def searchContent(self,key,quick,pg='1'): return {'list':[{'vod_id':'id','vod_name':key}]}
                def detailContent(self,ids): return {'list':[{'vod_id':ids[0],'vod_name':'Detail','vod_play_from':'direct','vod_play_url':'One${'$'}play'}]}
                def playerContent(self,flag,id,vipFlags): return {'parse':0,'url':self.getProxyUrl() + '&id=' + id}
                def localProxy(self,params): return [200,'video/mp2t',b'fixture-' + params['id'].encode()]
        """.trimIndent())
        val config = DesktopTvBoxConfigurationLoader(cache = null).load(File(temp.root, "index.json").toURI().toString())
        val site = config.sites.single()
        assertNull(site.jvmExecution)
        assertEquals("demo", site.pythonExecution!!.providerKey)
        assertTrue(site.key.startsWith("wmp:"))
        val executor = DesktopPythonRuntime.executor(site.pythonExecution!!, temp.newFolder("state"))
        val url: String
        executor.use {
            assertEquals("demo", it.home().single().name)
            assertEquals("query", it.search("query").single().name)
            assertEquals("Detail", it.detail("id").item.name)
            val playback = it.play("direct", "play")
            url = playback.url
            assertEquals("fixture-play", DesktopResourceLoader(logRequests = false).load(url).toString(Charsets.UTF_8))
        }
        assertThrows(Exception::class.java) { DesktopResourceLoader(logRequests = false).load(url) }
    }
}

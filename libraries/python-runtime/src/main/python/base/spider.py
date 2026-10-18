"""Platform-neutral TVBox Python provider helpers, without Java reflection."""
import json
import re
import time
import urllib.parse


class Spider:
    def __init__(self):
        self.extend = ''

    def init(self, extend=''): self.extend = extend
    def getDependence(self): return []
    def getName(self): return type(self).__name__
    def homeContent(self, filter): return {}
    def homeVideoContent(self): return {}
    def categoryContent(self, tid, pg, filter, extend): return {}
    def detailContent(self, ids): return {}
    def searchContent(self, key, quick, pg='1'): return {}
    def playerContent(self, flag, id, vipFlags): return {}
    def liveContent(self, url): return ''
    def localProxy(self, param): return [404, 'text/plain', b'']
    def manualVideoCheck(self): return False
    def isVideoFormat(self, url): return False
    def action(self, action): return {}
    def destroy(self): pass

    def loadModule(self, name):
        return self._wuming_scope.load(name.removesuffix('.py'))

    def loadSpider(self, name):
        spider = self.loadModule(name).Spider()
        spider._wuming_scope = self._wuming_scope
        spider.siteKey = self.siteKey
        return spider

    def getProxyUrl(self, local=True):
        url = self._wuming_scope.host.get('proxyUrl', '')
        if not url: raise RuntimeError('This host has no Python proxy endpoint')
        return url

    def fetch(self, url, **kwargs):
        import requests
        kwargs.setdefault('timeout', 15)
        response = requests.get(url, **kwargs)
        response.encoding = 'utf-8'
        return response

    def post(self, url, **kwargs):
        import requests
        kwargs.setdefault('timeout', 15)
        response = requests.post(url, **kwargs)
        response.encoding = 'utf-8'
        return response

    def html(self, content):
        from lxml import etree
        return etree.HTML(content)

    @staticmethod
    def regStr(reg, src, group=1):
        result = re.search(reg, src)
        return result.group(group) if result else ''

    @staticmethod
    def removeHtmlTags(src): return re.sub('<.*?>', '', src)
    @staticmethod
    def cleanText(src): return re.sub('[\U0001F000-\U0001FAFF]', '', src)
    @staticmethod
    def str2json(value): return json.loads(value)
    @staticmethod
    def json2str(value): return json.dumps(value, ensure_ascii=False)
    @staticmethod
    def log(value): print(value)

    def _cache_path(self, key):
        import hashlib
        directory = self._wuming_scope.root / 'cache'
        directory.mkdir(exist_ok=True)
        return directory / (hashlib.sha256(key.encode('utf-8')).hexdigest() + '.json')

    def getCache(self, key):
        try:
            value = json.loads(self._cache_path(key).read_text('utf-8'))
            if isinstance(value, dict):
                expires = value.get('expiresAt')
                if isinstance(expires, (int, float)) and expires < time.time():
                    self.delCache(key)
                    return None
            return value
        except (FileNotFoundError, ValueError): return None

    def setCache(self, key, value):
        path = self._cache_path(key)
        import os
        import tempfile
        fd, temporary = tempfile.mkstemp(dir=path.parent)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump(value, stream, ensure_ascii=False)
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary): os.unlink(temporary)
        return 'succeed'

    def delCache(self, key):
        self._cache_path(key).unlink(missing_ok=True)
        return 'succeed'

import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / 'libraries/python-runtime/src/main/python'))
import wuming_bridge as bridge


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
    def tearDown(self):
        for key in list(bridge._instances):
            self.request(op='close', handle=key)
        self.temp.cleanup()
    def request(self, **value): return json.loads(bridge.dispatch(json.dumps(value)))
    def open(self, source, **extra):
        return self.request(op='open', source=source, api='https://example.invalid/a.py', directory=self.temp.name, providerKey='raw', **extra)
    def test_failed_initialization_releases_modules_and_handles(self):
        before = set(sys.modules)
        result = self.open('from base.spider import Spider as Base\nclass Spider(Base):\n def init(self,x): raise RuntimeError("failure")')
        self.assertFalse(result['ok'])
        self.assertEqual({}, bridge._instances)
        self.assertFalse(any(x.startswith('_wuming_') for x in set(sys.modules) - before))
    def test_unknown_java_api_is_explicit_error(self):
        result = self.open('from com.chaquo.python import Python')
        self.assertFalse(result['ok'])
        self.assertIn('Java interop', result['error'])
    def test_dependency_path_cannot_escape_scope(self):
        result = self.open('from base.spider import Spider as Base\nclass Spider(Base):\n def getDependence(self): return ["../outside"]')
        self.assertFalse(result['ok'])
        self.assertIn('simple Python module', result['error'])
    def test_base64_proxy_and_serialized_json(self):
        result = self.open('''from base.spider import Spider as Base
class Spider(Base):
 def localProxy(self,p): return [200,'text/plain','data:;base64,YQ==',{},1]
 def homeContent(self,f): return '{"list":[]}'
''')
        key = result['value']
        self.assertEqual({'list':[]}, self.request(op='call', handle=key, method='homeContent', args=[True])['value'])
        self.assertEqual('YQ==', self.request(op='call', handle=key, method='localProxy', args=[{}])['value']['body'])
        self.assertFalse(self.request(op='call', handle=key, method='__getattribute__')['ok'])
    def test_video_checks_use_python_truthiness(self):
        key = self.open('from base.spider import Spider as Base\nclass Spider(Base):\n def manualVideoCheck(self): return 1')['value']
        self.assertIs(True, self.request(op='call',handle=key,method='manualVideoCheck')['value'])

    def test_dependency_modules_do_not_collide(self):
        from unittest.mock import patch
        import io
        source = """from base.spider import Spider as Base
class Spider(Base):
 def getDependence(self): return ['helper']
 def action(self,value): return self.loadModule('helper').VALUE
"""
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b"VALUE = 'first'")):
            first = self.open(source)['value']
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b"VALUE = 'second'")):
            second = self.open(source)['value']
        self.assertEqual('first', self.request(op='call',handle=first,method='action',args=[''])['value'])
        self.assertEqual('second', self.request(op='call',handle=second,method='action',args=[''])['value'])
        self.request(op='close',handle=first)
        self.assertEqual('second', self.request(op='call',handle=second,method='action',args=[''])['value'])

    def test_cache_is_scoped_and_expiration_is_preserved(self):
        source = '''from base.spider import Spider as Base
class Spider(Base):
 def action(self,value):
  if value == 'save': return self.setCache('x',{'expiresAt':1,'secret':'old'})
  return self.getCache('x')
'''
        key = self.open(source)['value']
        self.request(op='call',handle=key,method='action',args=['save'])
        self.assertIsNone(self.request(op='call',handle=key,method='action',args=['get'])['value'])

    def test_destroy_is_optional_but_failure_still_clears_the_provider(self):
        # Providers predate a mandatory destroy(); teardown must not fail over it.
        source = 'class Spider:\n def init(self,ext=""): pass\n def action(self,v): return {"value":v}'
        key = self.open(source)['value']
        self.assertEqual({'value': 'kept'}, self.request(op='call', handle=key, method='action', args=['kept'])['value'])
        result = self.request(op='close', handle=key)
        self.assertTrue(result['ok'], result.get('error'))
        self.assertNotIn(key, bridge._instances)
        self.assertFalse(self.request(op='call', handle=key, method='action', args=['gone'])['ok'])

    def test_legacy_search_content_without_a_page_argument_still_works(self):
        source = ('class Spider:\n'
                  ' def init(self,ext=""): pass\n'
                  ' def searchContent(self,key,quick): return {"list":[key,str(quick)]}\n'
                  ' def detailContent(self,ids): return {"list":[ids[0]]}')
        key = self.open(source)['value']
        self.assertEqual({'list': ['query', 'False']},
                         self.request(op='call', handle=key, method='searchContent', args=['query', False, '2'])['value'])
        self.assertEqual({'list': ['id']},
                         self.request(op='call', handle=key, method='detailContent', args=[['id']])['value'])

    def test_get_name_is_part_of_the_provider_contract(self):
        source = ('from base.spider import Spider as Base\n'
                  'class Spider(Base):\n'
                  ' def init(self,ext=""): pass\n'
                  ' def getName(self): return "demo"')
        key = self.open(source)['value']
        self.assertEqual('demo', self.request(op='call', handle=key, method='getName')['value'])


if __name__ == '__main__': unittest.main()

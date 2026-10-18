"""JSON-only CPython embedding contract. No Chaquopy or general Java reflection.

Providers have independent module namespaces; they remain trusted in-process code.
"""
import base64
import builtins
import importlib.util
import inspect
import json
import pathlib
import re
import sys
import types
import urllib.parse
import urllib.request
import uuid
import ssl

_MAX_SOURCE = 4 * 1024 * 1024
_instances = {}
# The legacy CatVod Python contract also exposed getName.
_ALLOWED = frozenset(('homeContent', 'homeVideoContent', 'categoryContent', 'detailContent',
    'searchContent', 'playerContent', 'liveContent', 'localProxy', 'manualVideoCheck',
    'isVideoFormat', 'action', 'getName'))


class Scope:
    def __init__(self, request):
        self.api = request['api']
        self.root = pathlib.Path(request['directory']).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.host = request.get('host', {})
        self.modules = {}
        self.prefix = '_wuming_' + uuid.uuid4().hex
        self.total = 0

    def import_module(self, name, globals=None, locals=None, fromlist=(), level=0):
        # Explicit host compatibility: no arbitrary Android or Java class access.
        if name in ('com.github.catvod', 'com.github.catvod.Proxy') and ('Proxy' in fromlist or name == 'com.github.catvod.Proxy'):
            return types.SimpleNamespace(Proxy=types.SimpleNamespace(
                getUrl=lambda local=True: self.host.get('proxyUrl', '').split('?')[0],
                getPort=lambda: urllib.parse.urlsplit(self.host.get('proxyUrl', '')).port or 0))
        if name == 'com.chaquo.python' or name == 'java' or name.startswith('android.'):
            raise ImportError('Provider requires a Java interop API not supplied by the native Python bridge: ' + name)
        if name in self.modules:
            return self.modules[name]
        if level or name.startswith('py_'):
            return self.load(name)
        return builtins.__import__(name, globals, locals, fromlist, level)

    def load(self, name, source=None):
        if name in self.modules:
            return self.modules[name]
        if not re.fullmatch(r'[A-Za-z_\w][\w]*', name):
            raise ValueError('Dependency must be a simple Python module name')
        if len(self.modules) >= 32:
            raise ValueError('Provider dependency limit exceeded')
        if source is None:
            url = urllib.parse.urljoin(self.api, urllib.parse.quote(name) + '.py')
            if urllib.parse.urlsplit(url).scheme not in ('http', 'https'):
                raise ValueError('Dependency must use HTTP(S)')
            # urllib validates TLS by default. Redirect count is bounded by its handler.
            import certifi
            with urllib.request.urlopen(url, timeout=15, context=ssl.create_default_context(cafile=certifi.where())) as response:
                source = response.read(_MAX_SOURCE + 1)
            source = source.decode('utf-8-sig')
        size = len(source.encode('utf-8'))
        self.total += size
        if size > _MAX_SOURCE or self.total > 16 * 1024 * 1024:
            raise ValueError('Provider code exceeds size limit')
        module = types.ModuleType(self.prefix + '.' + name)
        module.__file__ = str(self.root / (name + '.py'))
        module.__package__ = self.prefix
        module.__dict__['__builtins__'] = dict(vars(builtins), __import__=self.import_module)
        self.modules[name] = module
        sys.modules[module.__name__] = module
        try:
            exec(compile(source, module.__file__, 'exec'), module.__dict__)
        except BaseException:
            self.clear()
            raise
        return module

    def clear(self):
        for module in self.modules.values():
            sys.modules.pop(module.__name__, None)
        self.modules.clear()


def _proxy(value):
    if not isinstance(value, (tuple, list)) or len(value) < 3:
        raise ValueError('localProxy must return [status, contentType, body, headers?, base64?]')
    body = value[2]
    if isinstance(body, str):
        if len(value) > 4 and value[4] == 1:
            try:
                body = base64.b64decode(body.split('base64,')[-1], validate=True)
            except (ValueError, base64.binascii.Error):
                raise ValueError('localProxy base64 body is not valid base64')
        else:
            body = body.encode('utf-8')
    if body is None:
        body = b''
    if not isinstance(body, bytes) or len(body) > 8 * 1024 * 1024:
        raise ValueError('Proxy body must be bounded bytes or text')
    return {'status': int(value[0]), 'contentType': str(value[1]),
            'body': base64.b64encode(body).decode('ascii'),
            'headers': value[3] if len(value) > 3 and value[3] else {}}


def _destroy(spider):
    # Providers predate a mandatory destroy(); never fail teardown over it.
    destroy = getattr(spider, 'destroy', None)
    if destroy is None: return
    try:
        destroy()
    except BaseException:
        pass


def _invoke(function, args):
    # Some providers predate the search page argument. Drop trailing positionals the
    # callable cannot accept instead of failing the whole request.
    try:
        parameters = inspect.signature(function).parameters.values()
    except (TypeError, ValueError):
        return function(*args)
    positional = [p for p in parameters if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
    if any(p.kind is p.VAR_POSITIONAL for p in parameters) or len(positional) >= len(args):
        return function(*args)
    if not positional:
        return function()
    return function(*args[:len(positional)])


def dispatch(payload):
    request = json.loads(payload)
    try:
        operation = request['op']
        if operation == 'open':
            scope = Scope(request)
            spider = None
            try:
                spider = scope.load('entry', request['source']).Spider()
                spider._wuming_scope = scope
                spider.siteKey = request['providerKey']
                dependencies = _invoke(getattr(spider, 'getDependence', lambda: []), []) or []
                if not isinstance(dependencies, (list, tuple)) or len(dependencies) > 31:
                    raise ValueError('Invalid getDependence result')
                for name in dependencies:
                    scope.load(name.removesuffix('.py'))
                spider.init(request.get('extension', ''))
                handle = uuid.uuid4().hex
                _instances[handle] = (scope, spider)
                value = handle
            except BaseException:
                if spider is not None: _destroy(spider)
                scope.clear()
                raise
        elif operation == 'close':
            item = _instances.pop(request['handle'], None)
            if item:
                scope, spider = item
                try:
                    _destroy(spider)
                finally:
                    scope.clear()
            value = None
        elif operation == 'call':
            _, spider = _instances[request['handle']]
            method = request['method']
            if method not in _ALLOWED:
                raise ValueError('Unknown provider method')
            function = getattr(spider, method, None)
            value = _invoke(function, request.get('args', [])) if function else None
            if method == 'localProxy':
                value = _proxy(value)
            elif method in ('manualVideoCheck', 'isVideoFormat'):
                value = bool(value)
            elif method not in ('manualVideoCheck', 'isVideoFormat', 'liveContent') and isinstance(value, str):
                # Some spiders already serialize their CatVod response.
                try:
                    value = json.loads(value)
                except ValueError:
                    pass
        else:
            raise ValueError('Unknown bridge operation')
        return json.dumps({'ok': True, 'value': value}, ensure_ascii=False)
    except BaseException as error:
        # Provider SystemExit must never terminate the application.
        return json.dumps({'ok': False, 'error': type(error).__name__ + ': ' + str(error)}, ensure_ascii=False)

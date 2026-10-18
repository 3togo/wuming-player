#!/usr/bin/env python3
"""Direct PC -> explicitly selected internal DLNA renderer; Python standard library only.

Example:
  python3 scripts/cast-profile/pc_dlna_probe.py --interface 192.168.123.123 \
    --udn 00000000-0000-0000-0000-000000000001
Add --cast /path/to/sample.mp4 to play a bounded test. No Android/TVBox dependency.
"""
import argparse
import datetime as dt
import html
import http.server
import json
import pathlib
import re
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        with OPENER.open(req, timeout=5) as response:
            return response.status, response.read(1048576)
    except urllib.error.HTTPError as error:
        return error.code, error.read(1048576)


def discover(interface, udn, seconds=5):
    found = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind((interface, 0))
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(interface))
        sock.settimeout(0.5)
        packet = ('M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n'
                  'MAN: "ssdp:discover"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n').encode()
        for _ in range(2):
            sock.sendto(packet, ('239.255.255.250', 1900))
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                raw, peer = sock.recvfrom(65535)
            except socket.timeout:
                continue
            headers = dict((k.lower().strip(), v.strip()) for line in raw.decode(errors='replace').splitlines()
                           if ':' in line for k, v in [line.split(':', 1)])
            if headers.get('usn', '').split('::')[0].removeprefix('uuid:') != udn:
                continue
            location = headers.get('location')
            if location and urllib.parse.urlparse(location).hostname == peer[0]:
                found[location] = dict(headers, peer=peer[0])
    return found


def xml_text(node, name):
    return node.findtext('{*}' + name, default='')


def inspect(location, udn):
    status, raw = request(location)
    if status != 200:
        raise RuntimeError(f'Description HTTP {status}')
    root = ET.fromstring(raw)
    device = next((d for d in root.iter() if d.tag.endswith('}device') and
                   xml_text(d, 'UDN').removeprefix('uuid:') == udn), None)
    if device is None or 'MediaRenderer' not in xml_text(device, 'deviceType'):
        raise RuntimeError('Selected UDN is not a MediaRenderer')
    base = xml_text(root, 'URLBase') or location
    info = {key: xml_text(device, key) for key in
            ('friendlyName', 'manufacturer', 'manufacturerURL', 'modelName', 'modelNumber', 'modelDescription', 'UDN', 'deviceType')}
    services = {}
    for service in device.findall('{*}serviceList/{*}service'):
        kind = xml_text(service, 'serviceType')
        scpd = urllib.parse.urljoin(base, xml_text(service, 'SCPDURL'))
        control = urllib.parse.urljoin(base, xml_text(service, 'controlURL'))
        if any(urllib.parse.urlparse(u).hostname != urllib.parse.urlparse(location).hostname for u in (scpd, control)):
            raise RuntimeError('Refusing service URL outside selected TV')
        code, description = request(scpd)
        actions = []
        if code == 200:
            actions = [xml_text(a, 'name') for a in ET.fromstring(description).findall('{*}actionList/{*}action')]
        services[kind.split(':')[-2]] = dict(type=kind, control=control, scpd=scpd, actions=actions)
    return info, services, raw.decode(errors='replace')


class Report:
    def __init__(self, directory):
        self.directory = directory
        directory.mkdir(parents=True, exist_ok=True)
        self.data = {'started': dt.datetime.now().astimezone().isoformat(), 'events': []}
        self.lock = threading.Lock()

    def event(self, kind, **values):
        with self.lock:
            row = dict(time=dt.datetime.now().astimezone().isoformat(timespec='milliseconds'), kind=kind, **values)
            self.data['events'].append(row)
            print(json.dumps(row, ensure_ascii=False), flush=True)
            self.save()

    def save(self):
        (self.directory / 'report.json').write_text(json.dumps(self.data, ensure_ascii=False, indent=2))
        events = self.data['events']
        play = next((e for e in events if e.get('action') == 'Play' and e.get('http') == 200), None)
        origin = dt.datetime.fromisoformat(play['time'] if play else self.data['started'])
        points = []
        for event in events:
            position = event.get('values', {}).get('RelTime', '')
            if re.fullmatch(r'\d+:\d+:\d+', position):
                h, m, sec = map(int, position.split(':'))
                elapsed = (dt.datetime.fromisoformat(event['time']) - origin).total_seconds()
                points.append((max(0, elapsed), h*3600+m*60+sec))
        requests = [e for e in events if e['kind'] == 'TV media request']
        chart = ''
        if points:
            xmax = max(1, max(x for x,y in points))
            ymax = max(15, max(y for x,y in points))
            coords = ' '.join(f'{45+x/xmax*700:.1f},{210-y/ymax*175:.1f}' for x,y in points)
            chart = (f'<h2>TV-reported playhead</h2><svg viewBox="0 0 800 255" style="max-width:1000px;width:100%">'
                     '<path d="M45 25V210H750" fill="none" stroke="#94a3b8"/>'
                     f'<polyline points="{coords}" fill="none" stroke="#67e8f9" stroke-width="3"/>'
                     f'<text x="50" y="245" fill="white">Elapsed since Play: 0 → {xmax:.1f}s</text>'
                     f'<text x="50" y="20" fill="white">Playhead: 0 → {ymax}s</text></svg>')
        summary = dict(mediaRequests=len(requests), bytesWritten=sum(e['bytes'] for e in requests),
                       maxReportedPositionSeconds=max((y for x,y in points), default=0),
                       firstProgressSeconds=next((round(x, 2) for x,y in points if y > 0), None))
        def display(event):
            value = {k:v for k,v in event.items() if k not in ('time','kind')}
            if 'values' in value:
                value['values'] = {k:v for k,v in value['values'].items() if k not in ('TrackMetaData','TrackURI')}
            return html.escape(json.dumps(value, ensure_ascii=False))
        rows = ''.join('<tr><td>' + html.escape(e['time'][11:23]) + '</td><td>' + html.escape(e['kind']) +
                       '</td><td><pre>' + display(e) + '</pre></td></tr>' for e in events)
        (self.directory / 'report.html').write_text('<!doctype html><meta charset="utf-8"><title>PC → TV DLNA trace</title>'
            '<style>body{font:16px system-ui;margin:32px;background:#101827;color:#e5e7eb}td{padding:8px;border-bottom:1px solid #475569;vertical-align:top}pre{white-space:pre-wrap;margin:0}h1{color:#67e8f9}</style>'
            '<h1>PC → TV internal DLNA</h1><p>SOAP success and playhead progress are protocol evidence. Picture and sound still require observation.</p><pre>' +
            html.escape(json.dumps(summary, indent=2)) + '</pre>' + chart + '<details><summary>TV identity and advertised services</summary><pre>' +
            html.escape(json.dumps(self.data.get('device', {}), ensure_ascii=False, indent=2)) + '</pre></details><h2>Request timeline</h2><table>' + rows + '</table>')


def soap(service, action, arguments, report):
    body = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
            f'<u:{action} xmlns:u="{html.escape(service["type"])}">' +
            ''.join(f'<{key}>{html.escape(str(value))}</{key}>' for key,value in arguments.items()) +
            f'</u:{action}></s:Body></s:Envelope>').encode()
    start = time.monotonic()
    code, raw = request(service['control'], body, {'Content-Type': 'text/xml; charset="utf-8"',
                       'SOAPAction': f'"{service["type"]}#{action}"'})
    root = ET.fromstring(raw)
    values = {node.tag.split('}')[-1]: node.text or '' for node in root.iter() if not list(node)}
    report.event('SOAP', action=action, http=code, elapsedMs=round((time.monotonic()-start)*1000), values=values)
    return code, values


def cast(interface, peer, service, media, seconds, report):
    media = media.resolve(strict=True)
    size = media.stat().st_size
    prefix = '/' + uuid.uuid4().hex + '/'
    token = prefix + urllib.parse.quote(media.name)
    files = {media.name: media}
    if media.suffix.lower() == '.mpd':
        for pattern in ('init-stream*.m4s', 'chunk-stream*.m4s'):
            for candidate in media.parent.glob(pattern):
                if candidate.resolve().parent == media.parent:
                    files[candidate.name] = candidate
    def mime(path):
        return {'.mpd':'application/dash+xml', '.m4s':'video/iso.segment'}.get(path.suffix.lower(), 'video/mp4')

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def serve(self, send_body):
            name = urllib.parse.unquote(self.path.removeprefix(prefix))
            if self.client_address[0] != peer or not self.path.startswith(prefix) or name not in files:
                self.send_error(404)
                return
            selected = files[name]
            size = selected.stat().st_size
            start, end = 0, size - 1
            value = self.headers.get('Range')
            if value:
                match = re.fullmatch(r'bytes=(\d*)-(\d*)', value)
                if not match or not any(match.groups()):
                    self.send_error(416)
                    return
                a, b = match.groups()
                if a:
                    start = int(a)
                    if b: end = min(int(b), end)
                else:
                    start = max(0, size - int(b))
                if start > end or start >= size:
                    self.send_error(416)
                    return
            sent = 0
            began = time.monotonic()
            try:
                self.send_response(206 if value else 200)
                self.send_header('Content-Type', mime(selected))
                self.send_header('Content-Length', str(end-start+1))
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('transferMode.dlna.org', 'Streaming')
                if value: self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
                self.end_headers()
                if send_body:
                    with selected.open('rb') as source:
                        source.seek(start)
                        while sent < end-start+1:
                            block = source.read(min(65536, end-start+1-sent))
                            if not block: break
                            self.wfile.write(block)
                            sent += len(block)
            except (ConnectionError, TimeoutError) as error:
                report.event('HTTP disconnect', error=str(error))
            finally:
                report.event('TV media request', resource=name, method=self.command, range=value, bytes=sent,
                             elapsedMs=round((time.monotonic()-began)*1000))

    server = http.server.ThreadingHTTPServer((interface, 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = f'http://{interface}:{server.server_port}{token}'
    metadata = ('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
                'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
                '<item id="probe" parentID="0" restricted="1"><dc:title>PC DLNA test</dc:title>'
                '<upnp:class>object.item.videoItem</upnp:class>'
                f'<res protocolInfo="http-get:*:{mime(media)}:*" size="{size}">{html.escape(url)}</res></item></DIDL-Lite>')
    attempted = False
    try:
        report.event('PC server ready', address=interface, port=server.server_port, file=media.name, size=size)
        attempted = True
        code, _ = soap(service, 'SetAVTransportURI', {'InstanceID':0, 'CurrentURI':url, 'CurrentURIMetaData':metadata}, report)
        if code != 200: raise RuntimeError('TV rejected SetAVTransportURI')
        code, _ = soap(service, 'Play', {'InstanceID':0, 'Speed':1}, report)
        if code != 200: raise RuntimeError('TV rejected Play')
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            for action in ('GetTransportInfo', 'GetPositionInfo'):
                if action in service['actions']:
                    soap(service, action, {'InstanceID':0}, report)
            time.sleep(1)
    finally:
        if attempted and 'Stop' in service['actions']:
            try: soap(service, 'Stop', {'InstanceID':0}, report)
            except Exception as error: report.event('Stop failed', error=str(error))
        server.shutdown()
        server.server_close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--interface', required=True, help='PC LAN IPv4 address')
    parser.add_argument('--udn', required=True, help='Exact authorized renderer UDN, without uuid:')
    parser.add_argument('--cast', type=pathlib.Path, help='Local MP4 or bundled MPD; omission only inspects the TV')
    parser.add_argument('--seconds', type=int, default=25)
    parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path('app/build/reports/pc-dlna') / dt.datetime.now().strftime('%Y%m%d-%H%M%S'))
    args = parser.parse_args()
    if not 1 <= args.seconds <= 120: parser.error('--seconds must be 1..120')
    if args.cast and (not args.cast.is_file() or args.cast.suffix.lower() not in ('.mp4', '.mpd')):
        parser.error('--cast requires an existing MP4 or MPD file')
    report = Report(args.output)
    try:
        found = discover(args.interface, args.udn)
        if not found: raise RuntimeError('Selected renderer did not answer SSDP on this PC interface')
        location, ssdp = next(iter(found.items()))
        info, services, raw = inspect(location, args.udn)
        report.data['device'] = dict(info, address=ssdp['peer'], server=ssdp.get('server'), services=services)
        (args.output / 'device-description.xml').write_text(raw)
        report.event('Identified TV', **report.data['device'])
        cm = services.get('ConnectionManager')
        if cm and 'GetProtocolInfo' in cm['actions']: soap(cm, 'GetProtocolInfo', {}, report)
        else: report.event('Capability limit', detail='TV exposes no GetProtocolInfo format list')
        avt = services.get('AVTransport')
        if avt and 'GetDeviceCapabilities' in avt['actions']:
            soap(avt, 'GetDeviceCapabilities', {'InstanceID':0}, report)
        if args.cast:
            avt = services.get('AVTransport')
            if not avt: raise RuntimeError('No AVTransport service')
            cast(args.interface, ssdp['peer'], avt, args.cast, args.seconds, report)
        report.event('Finished', report=str(args.output / 'report.html'))
    except Exception as error:
        report.event('Error', error=str(error))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

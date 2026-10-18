import io
import json
import unittest
from unittest import mock

from scripts import fake_dlna_renderer as renderer


def response(url, body=b"", status=200, content_type="application/octet-stream"):
    return {"url": url, "ok": 200 <= status < 300, "status": status,
            "headers": {"content-type": content_type}, "body": body,
            "bytes": len(body), "error": None}


class FakeDlnaRendererHlsTest(unittest.TestCase):

    def setUp(self):
        renderer.reset_debug_state()

    def test_media_kind_ignores_query_and_fragment(self):
        self.assertEqual("hls", renderer.media_kind("https://cdn/video.m3u8?token=x#live"))
        self.assertEqual("progressive", renderer.media_kind("https://cdn/video.mp4?token=x"))
        self.assertEqual("progressive", renderer.media_kind("https://cdn/aggregated.ts"))

    def test_http_fetch_rewrites_only_renderer_self_address(self):
        response_mock = mock.MagicMock()
        response_mock.__enter__.return_value = response_mock
        response_mock.status = 200
        response_mock.getheaders.return_value = []
        response_mock.read.return_value = b"ok"
        with mock.patch("urllib.request.urlopen", return_value=response_mock) as opening:
            renderer.http_fetch(
                f"http://{renderer.HOST_IP}:{renderer.HTTP_PORT}/test/segment.ts")

        requested = opening.call_args.args[0]
        self.assertEqual("127.0.0.1", requested.host.split(":", 1)[0])

    def test_hls_capable_fetches_playlist_and_segments_and_stays_playing(self):
        playlist = (b"#EXTM3U\n#EXTINF:4,\nhttp://relay/one.ts\n"
                    b"#EXTINF:4,\nhttp://relay/two.ts\n")

        def fetch(url, headers=None):
            if url.endswith(".m3u8"):
                return response(url, playlist, content_type="application/vnd.apple.mpegurl")
            return response(url, b"\x47" * 188)

        session = renderer.SESSION["id"]
        report = renderer.emulate_hls_capable("http://relay/stream.m3u8", session, fetch)

        self.assertEqual("HLS_PLAYING", report["verdict"])
        self.assertEqual(2, len(report["segments"]))
        self.assertEqual("PLAYING", renderer.STATE["transport"])

    def test_hls_reject_does_not_fetch_media(self):
        session = renderer.SESSION["id"]
        report = renderer.emulate_hls_reject("http://relay/stream.m3u8", session)

        self.assertEqual("HLS_REJECTED", report["verdict"])
        self.assertEqual([], report["playlists"])
        self.assertEqual("STOPPED", renderer.STATE["transport"])

    def test_hp_hd03_fetches_two_segments_then_stops(self):
        playlist = b"#EXTM3U\nhttp://relay/one.ts\nhttp://relay/two.ts\nhttp://relay/three.ts\n"
        fetched = []

        def fetch(url, headers=None):
            fetched.append((url, headers))
            if url.endswith(".m3u8"):
                return response(url, playlist, content_type="application/vnd.apple.mpegurl")
            return response(url, b"\x47" * 188)

        session = renderer.SESSION["id"]
        report = renderer.emulate_dongle_hls_fail(
            "http://relay/stream.m3u8", session, fetch, sleep_fn=lambda _: None)

        self.assertEqual("STOPPED_SENT", report["verdict"])
        self.assertEqual(2, len(report["segments"]))
        self.assertEqual("STOPPED", renderer.STATE["transport"])
        self.assertEqual("bytes=0-", fetched[1][1]["Range"])

    def test_dispatch_uses_profile_and_classifies_urls_with_queries(self):
        session = renderer.SESSION["id"]
        with mock.patch.object(renderer, "emulate_hls_capable") as hls, \
                mock.patch.object(renderer, "emulate_dongle_progressive") as progressive:
            renderer.emulate_tv("http://relay/stream.m3u8?token=1", session,
                                renderer.PROFILE_HLS_CAPABLE)
            renderer.emulate_tv("http://relay/aggregated.mp4?token=1", session,
                                renderer.PROFILE_HLS_CAPABLE)

        hls.assert_called_once_with("http://relay/stream.m3u8?token=1", session)
        progressive.assert_called_once_with("http://relay/aggregated.mp4?token=1", session)


class HandlerHarness(renderer.Handler):

    def __init__(self, path, body=b"", headers=None):
        self.path = path
        self.headers = {"Content-Length": str(len(body)), **(headers or {})}
        self.rfile = io.BytesIO(body)
        self.wfile = io.BytesIO()
        self.status = None
        self.response_headers = {}

    def send_response(self, code, message=None):
        self.status = code

    def send_header(self, keyword, value):
        self.response_headers[keyword] = value

    def end_headers(self):
        pass


class ImmediateThread:

    def __init__(self, target, args=(), daemon=None):
        self.target = target
        self.args = args

    def start(self):
        self.target(*self.args)


class FakeDlnaRendererHandlerTest(unittest.TestCase):

    def setUp(self):
        renderer.reset_debug_state()

    def request(self, method, path, body=None, headers=None):
        handler = HandlerHarness(path, body or b"", headers)
        getattr(handler, f"do_{method}")()
        return handler.status, handler.wfile.getvalue()

    def test_debug_state_and_reset_are_machine_readable(self):
        renderer.STATE["transport"] = "PLAYING"
        status, payload = self.request("GET", "/debug/state")
        self.assertEqual(200, status)
        self.assertEqual("PLAYING", json.loads(payload)["transport"])

        status, _ = self.request("POST", "/debug/reset", body=b"")
        self.assertEqual(204, status)
        self.assertEqual("NO_MEDIA_PRESENT", renderer.STATE["transport"])

    def test_self_contained_hls_fixture_is_playable(self):
        status, playlist = self.request("GET", "/test/stream.m3u8")
        self.assertEqual(200, status)
        self.assertIn(b"#EXTM3U", playlist)
        self.assertIn(b"/test/segment.ts", playlist)

        status, segment = self.request("GET", "/test/segment.ts")
        self.assertEqual(200, status)
        self.assertTrue(renderer.ts_sync_ok(segment))

    def test_set_uri_updates_state_and_report(self):
        soap = (b'<s:Envelope><s:Body><u:SetAVTransportURI>'
                b'<CurrentURI>http://relay/stream.m3u8?x=1&amp;y=2</CurrentURI>'
                b'</u:SetAVTransportURI></s:Body></s:Envelope>')
        headers = {"Content-Type": "text/xml", "SOAPACTION":
                   '"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"'}
        with mock.patch.object(renderer, "emulate_tv") as emulate, \
                mock.patch.object(renderer.threading, "Thread", ImmediateThread):
            status, _ = self.request("POST", "/control/AVTransport", soap, headers)

        self.assertEqual(200, status)
        self.assertEqual("http://relay/stream.m3u8?x=1&y=2", renderer.STATE["uri"])
        self.assertEqual(1, len(renderer.REPORT["set_uri_calls"]))
        emulate.assert_called_once()


if __name__ == "__main__":
    unittest.main()

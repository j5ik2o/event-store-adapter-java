"""central-portal.py の照会の失敗の扱いと、コマンド全体の締め切り（#870。scala#934、rs#375 の指摘の写し）。"""
import contextlib
import http.client
import io
import json
import os
import socket
import threading
import time
import unittest
import urllib.error
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

from hypothesis import given, settings, strategies as st

from central_portal_support import ID_A, RUN, commands, passed_pre_steps, portal

LOOPBACK = '127.0.0.1,localhost'


class FakeResponse:
    """urlopen の応答の代わり。pieces を read のたびに1つずつ返し、尽きたら b''。"""

    closed = False

    def __init__(self, status, pieces=(b'',), on_read=None):
        self.status = status
        self.pieces = list(pieces)
        self.on_read = on_read

    def read(self, amount=-1):
        if amount is None or amount < 0:   # read() は残りを全部読む
            chunks = []
            while chunk := self.read(1 << 20):
                chunks.append(chunk)
            return b''.join(chunks)
        if self.on_read:
            self.on_read()
        piece = self.pieces.pop(0) if self.pieces else b''
        if isinstance(piece, BaseException):
            raise piece
        return piece

    def close(self):
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def truncated():
    return http.client.IncompleteRead(b'{"deploymentState": "PUB', 40)


class HttpTest(unittest.TestCase):
    def test_a_body_cut_in_the_middle_is_a_failed_query(self):
        for deadline in (None, commands.net.Deadline(3600)):
            with self.subTest(deadline=deadline is not None), mock.patch.object(commands.net, 'DEADLINE', deadline):
                with mock.patch.object(commands.net.urllib.request, 'urlopen',
                                       return_value=FakeResponse(200, [truncated()])):
                    self.assertEqual(commands.http('POST', 'http://127.0.0.1/x'), (None, b''))
                error = urllib.error.HTTPError('http://127.0.0.1/x', 503, 'x', {}, FakeResponse(503, [truncated()]))
                with mock.patch.object(commands.net.urllib.request, 'urlopen', side_effect=error):
                    self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (None, b''))

    def test_every_kind_of_broken_answer_is_a_failed_query(self):
        broken = (http.client.IncompleteRead(b'x', 3), http.client.RemoteDisconnected('closed'),
                  http.client.BadStatusLine('x'), ConnectionResetError(), socket.timeout(), TimeoutError(),
                  urllib.error.URLError('down'), ValueError('bad chunk'))
        for error in broken:
            for where in ('open', 'read'):
                with self.subTest(error=type(error).__name__, where=where):
                    if where == 'open':
                        patch = mock.patch.object(commands.net.urllib.request, 'urlopen', side_effect=error)
                    else:
                        patch = mock.patch.object(commands.net.urllib.request, 'urlopen',
                                                  return_value=FakeResponse(200, [b'{"a"', error]))
                    with patch:
                        self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (None, b''))

    def test_complete_answers_are_returned_as_they_are(self):
        for deadline in (None, commands.net.Deadline(3600)):
            with self.subTest(deadline=deadline is not None), mock.patch.object(commands.net, 'DEADLINE', deadline):
                with mock.patch.object(commands.net.urllib.request, 'urlopen',
                                       return_value=FakeResponse(200, [b'o', b'k'])):
                    self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (200, b'ok'))
                error = urllib.error.HTTPError('http://127.0.0.1/x', 404, 'x', {}, io.BytesIO(b'missing'))
                with mock.patch.object(commands.net.urllib.request, 'urlopen', side_effect=error):
                    self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (404, b'missing'))

    def test_a_truncated_status_answer_is_queried_again(self):
        # wait は切断を照会の失敗として数え、次の照会で PUBLISHED を読む
        answers = [FakeResponse(200, [truncated()]), FakeResponse(200, [b'{"deploymentState": "PUBLISHED"}'])]
        with mock.patch.object(commands.net.urllib.request, 'urlopen', side_effect=answers), \
                mock.patch.object(commands, 'central_auth', return_value={}), contextlib.redirect_stdout(io.StringIO()):
            args = type('Args', (), {'id': 'x', 'attempts': 3, 'interval': 0})()
            self.assertEqual(commands.wait(args, sleep=lambda _: None), 0)

    def test_no_request_is_sent_after_the_deadline(self):
        now = [0.0]
        opened = []
        with mock.patch.object(commands.net, 'DEADLINE', commands.net.Deadline(10, lambda: now[0])), \
                mock.patch.object(commands.net.urllib.request, 'urlopen', side_effect=lambda *a, **k: opened.append(a)):
            now[0] = 10
            self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (None, b''))
        self.assertEqual(opened, [])

    def test_the_timeout_is_cut_to_the_remaining_time(self):
        now = [0.0]
        seen = []

        def urlopen(request, timeout):
            seen.append(timeout)
            raise TimeoutError()
        with mock.patch.object(commands.net, 'DEADLINE', commands.net.Deadline(100, lambda: now[0])), \
                mock.patch.object(commands.net.urllib.request, 'urlopen', urlopen):
            now[0] = 97.5
            self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (None, b''))
        self.assertEqual(seen, [2.5])

    def test_the_upload_has_no_overall_deadline(self):
        # upload は締め切りの外（DEADLINE は None）。1回の通信ごとの 600 秒だけ（設計レビュー M1）
        self.assertIsNone(commands.net.DEADLINE)
        seen = []

        def urlopen(request, timeout):
            seen.append(timeout)
            return FakeResponse(201, [ID_A.encode()])
        with mock.patch.object(commands.net.urllib.request, 'urlopen', urlopen):
            self.assertEqual(commands.http('POST', 'http://127.0.0.1/x', timeout=600), (201, ID_A.encode()))
        self.assertEqual(seen, [600])


class SlowBodyTest(unittest.TestCase):
    """urlopen の timeout は通信の1回ごと。本文を少しずつ読み、締め切りで打ち切る。"""

    def body(self, pieces, step, clock):
        def advance():
            clock[0] += step
        return FakeResponse(200, pieces, on_read=advance)

    @settings(max_examples=200, deadline=None)
    @given(st.lists(st.binary(min_size=1, max_size=8), max_size=20), st.floats(0, 30), st.floats(0.5, 300))
    def test_the_body_is_read_whole_only_when_it_ends_before_the_deadline(self, pieces, step, limit):
        clock = [0.0]
        response = self.body(list(pieces), step, clock)
        # 読み終わり（b'' を読んだ時点）の時刻。読むたびに step 進む（最後の b'' の読みも数える）
        finish = step * (len(pieces) + 1)
        with mock.patch.object(commands.net, 'DEADLINE', commands.net.Deadline(limit, lambda: clock[0])), \
                mock.patch.object(commands.net.urllib.request, 'urlopen', return_value=response):
            result = commands.http('GET', 'http://127.0.0.1/x')
        if finish < limit:
            self.assertEqual(result, (200, b''.join(pieces)))
        else:
            self.assertEqual(result, (None, b''))
            self.assertLessEqual(clock[0], limit + step)

    def test_a_body_that_never_ends_is_cut_at_the_deadline(self):
        clock = [0.0]
        response = self.body([b'x'] * 1000, 1.0, clock)
        with mock.patch.object(commands.net, 'DEADLINE', commands.net.Deadline(10, lambda: clock[0])), \
                mock.patch.object(commands.net.urllib.request, 'urlopen', return_value=response):
            self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (None, b''))
        self.assertEqual(clock[0], 10)

    def test_the_body_of_an_error_response_is_cut_at_the_deadline_too(self):
        clock = [0.0]
        slow = self.body([b'x'] * 1000, 1.0, clock)

        def raising(request, timeout):
            raise urllib.error.HTTPError('http://127.0.0.1:9/x', 404, 'Not Found', {}, slow)
        with mock.patch.object(commands.net, 'DEADLINE', commands.net.Deadline(10, lambda: clock[0])), \
                mock.patch.object(commands.net.urllib.request, 'urlopen', raising):
            self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (None, b''))
        self.assertEqual(clock[0], 10)


class TrickleServer:
    """少しずつ送り続ける本物の HTTP サーバー（127.0.0.1 のスレッド）。mode は
    'length'（200、Content-Length つきの本文を 1 バイトずつ）、'error'（404 で同じ）、
    'chunked'（200、chunked の長さの行を 1 文字ずつ。1回の read1 の中で何度も受信させる）。"""

    def __init__(self, mode, every=0.05, total=8.0):
        stop = self.stop = threading.Event()

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, *args):
                pass

            def do_GET(self):
                count = int(total / every)
                self.send_response(404 if mode == 'error' else 200)
                if mode == 'chunked':
                    self.send_header('Transfer-Encoding', 'chunked')
                else:
                    self.send_header('Content-Length', str(count))
                self.end_headers()
                self.wfile.flush()
                try:
                    if mode == 'chunked':
                        self.wfile.write(b'1;')
                    for _ in range(count):
                        if stop.wait(every):
                            return
                        self.wfile.write(b'x')
                        self.wfile.flush()
                except OSError:
                    pass

            do_POST = do_GET

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.url = f'http://127.0.0.1:{self.server.server_address[1]}/x'
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.stop.set()
        self.server.shutdown()
        self.server.server_close()


class TrickleTest(unittest.TestCase):
    """read(65536) は1回の呼び出しの中で何度も受信するので、偽の応答では待ちを再現できない。
    本物の HTTP サーバーが少しずつ送り続けても、http は締め切り（1 秒）で打ち切る。"""

    def setUp(self):
        no_proxy = mock.patch.dict(os.environ, {'no_proxy': LOOPBACK, 'NO_PROXY': LOOPBACK})
        no_proxy.start()
        self.addCleanup(no_proxy.stop)

    def test_a_trickling_body_is_cut_at_the_deadline(self):
        for mode in ('length', 'error', 'chunked'):
            with self.subTest(mode):
                server = TrickleServer(mode)
                self.addCleanup(server.close)
                started = time.monotonic()
                with mock.patch.object(commands.net, 'DEADLINE', commands.net.Deadline(1.0)):
                    result = commands.http('POST', server.url)
                elapsed = time.monotonic() - started
                self.assertEqual(result, (None, b''))
                self.assertLess(elapsed, 2.5, mode)

    def test_a_body_that_arrives_in_time_is_read(self):
        server = TrickleServer('length', every=0.01, total=0.2)
        self.addCleanup(server.close)
        with mock.patch.object(commands.net, 'DEADLINE', commands.net.Deadline(10)):
            self.assertEqual(commands.http('GET', server.url), (200, b'x' * 20))


class SlowHeaderServer:
    """見出し（headers）を少しずつ送り続ける本物の HTTP サーバー（#871 の CodeRabbit の指摘）。urlopen は見出しを
    読み終えるまで戻らないので、本文の締め切りだけでは止まらない。"""

    def __init__(self, every=0.05, total=8.0):
        stop = self.stop = threading.Event()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                try:
                    self.wfile.write(b'HTTP/1.1 200 OK\r\n')
                    self.wfile.flush()
                    for index in range(int(total / every)):
                        if stop.wait(every):
                            return
                        self.wfile.write(f'X-Slow-{index}: x\r\n'.encode())
                        self.wfile.flush()
                except OSError:
                    pass

            do_POST = do_HEAD = do_GET

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.url = f'http://127.0.0.1:{self.server.server_address[1]}/x'
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.stop.set()
        self.server.shutdown()
        self.server.server_close()


class SlowHeaderTest(unittest.TestCase):
    def setUp(self):
        no_proxy = mock.patch.dict(os.environ, {'no_proxy': LOOPBACK, 'NO_PROXY': LOOPBACK})
        no_proxy.start()
        self.addCleanup(no_proxy.stop)

    def test_trickling_headers_are_cut_at_the_deadline(self):
        for method in ('GET', 'HEAD', 'POST'):
            with self.subTest(method):
                server = SlowHeaderServer()
                self.addCleanup(server.close)
                started = time.monotonic()
                with mock.patch.object(commands.net, 'DEADLINE', commands.net.Deadline(1.0)):
                    result = commands.http(method, server.url)
                self.assertEqual(result, (None, b''))
                self.assertLess(time.monotonic() - started, 2.5)

    def test_an_unexpected_error_of_the_query_is_not_swallowed(self):
        with mock.patch.object(commands.net, 'DEADLINE', commands.net.Deadline(10)), \
                mock.patch.object(commands.net.urllib.request, 'urlopen', side_effect=KeyError('bug')), \
                self.assertRaises(KeyError):
            commands.http('GET', 'http://127.0.0.1:9/x')


class ShortBodyServer:
    """本文を宣言より短く送って接続を閉じる、本物の HTTP サーバー（#871 の Devin の指摘）。mode は
    'length'（Content-Length: 39 で、31 バイトの正しい JSON だけを送って閉じる）、'error'（404 で同じ）、
    'chunked'（終わりの 0 の chunk を送らずに閉じる）。"""

    BODY = b'{"deploymentState":"PUBLISHED"}'

    def __init__(self, mode):
        body = self.BODY

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, *args):
                pass

            def do_GET(self):
                self.send_response(404 if mode == 'error' else 200)
                if mode == 'chunked':
                    self.send_header('Transfer-Encoding', 'chunked')
                    self.end_headers()
                    self.wfile.write(f'{len(body):x}\r\n'.encode() + body + b'\r\n')
                else:
                    self.send_header('Content-Length', str(len(body) + 8))
                    self.end_headers()
                    self.wfile.write(body)
                self.wfile.flush()
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)

            do_POST = do_GET

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.url = f'http://127.0.0.1:{self.server.server_address[1]}/x'
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class ShortBodyTest(unittest.TestCase):
    """read1 は、宣言より短く閉じた本文でも例外を投げずに b'' を返す。途中で切れた本文を照会の失敗にする
    （受け取った部分が正しい JSON でも、それで PUBLISHED と決めない）。締め切りのある照会も、無い照会も。"""

    def setUp(self):
        no_proxy = mock.patch.dict(os.environ, {'no_proxy': LOOPBACK, 'NO_PROXY': LOOPBACK})
        no_proxy.start()
        self.addCleanup(no_proxy.stop)

    def test_a_body_shorter_than_its_length_is_a_failed_query(self):
        for mode in ('length', 'error', 'chunked'):
            for deadline in (None, 3600):
                with self.subTest(mode=mode, deadline=deadline):
                    server = ShortBodyServer(mode)
                    self.addCleanup(server.close)
                    with mock.patch.object(commands.net, 'DEADLINE',
                                           None if deadline is None else commands.net.Deadline(deadline)):
                        self.assertEqual(commands.http('POST', server.url), (None, b''))

    def test_the_wait_does_not_take_a_cut_status_as_published(self):
        server = ShortBodyServer('length')
        self.addCleanup(server.close)
        args = type('Args', (), {'id': 'x', 'attempts': 2, 'interval': 0})()
        with mock.patch.object(commands, 'central_url', return_value=server.url), \
                mock.patch.object(commands, 'central_auth', return_value={}), \
                contextlib.redirect_stdout(io.StringIO()), self.assertRaises(portal.Stop):
            commands.wait(args, sleep=lambda _: None)


class WatchdogTest(unittest.TestCase):
    def test_a_socket_that_cannot_be_shut_down_fails_the_query(self):
        """締め切りに socket を閉じられなかったら、握りつぶさずにその照会を時間切れにする。"""
        class Broken:
            def fileno(self):
                return 3

            def shutdown(self, how):
                raise OSError(107, 'Transport endpoint is not connected')
        watchdog = commands.net.Watchdog(Broken(), 60)
        watchdog.cancel()
        watchdog.fire()
        with self.assertRaises(TimeoutError):
            watchdog.raise_failure()

    def test_a_closed_socket_is_left_alone(self):
        class Closed:
            def fileno(self):
                return -1

            def shutdown(self, how):
                raise AssertionError('a closed socket is not shut down')
        watchdog = commands.net.Watchdog(Closed(), 60)
        watchdog.cancel()
        watchdog.fire()
        watchdog.raise_failure()


class DeadlineTest(unittest.TestCase):
    """照会に時間がかかっても、コマンドは --deadline 秒を超えて照会も待ちもしない。"""

    def run_command(self, command, args, seconds_per_query, answer, extra=()):
        now = [0.0]
        timeouts = []
        started = []

        def urlopen(request, timeout):
            started.append(now[0])
            timeouts.append(timeout)
            now[0] += min(seconds_per_query, timeout)
            if seconds_per_query > timeout:
                raise TimeoutError()
            return FakeResponse(*answer(request))

        def sleep(seconds):
            self.assertGreaterEqual(seconds, 0)
            now[0] += seconds

        with mock.patch.object(commands.net.urllib.request, 'urlopen', side_effect=urlopen), \
                mock.patch.object(commands, 'central_auth', return_value={}), contextlib.redirect_stdout(io.StringIO()), \
                contextlib.ExitStack() as stack:
            for patch in extra:
                stack.enter_context(patch(now, started))
            try:
                result = command(args, sleep=sleep, monotonic=lambda: now[0])
            except portal.Stop:
                result = 'stopped'
        self.assertIsNone(commands.net.DEADLINE)
        return result, now[0], timeouts, started

    @settings(max_examples=60, deadline=None)
    @given(st.floats(0, 20), st.floats(0, 120), st.floats(1, 600))
    def test_confirm_stays_within_the_deadline(self, per_query, interval, limit):
        args = type('Args', (), {'version': '5.3.0', 'attempts': 1000, 'interval': interval, 'deadline': limit})()
        result, elapsed, timeouts, started = self.run_command(commands.confirm, args, per_query,
                                                               lambda request: (404, [b'']))
        self.assertEqual(result, 'stopped')
        self.assertLessEqual(elapsed, limit + 1e-6)
        self.assertTrue(all(0 < timeout <= 60 for timeout in timeouts))
        self.assertTrue(all(at < limit for at in started))

    @settings(max_examples=60, deadline=None)
    @given(st.floats(0, 20), st.floats(0, 120), st.floats(1, 600))
    def test_wait_stays_within_the_deadline(self, per_query, interval, limit):
        args = type('Args', (), {'id': 'x', 'attempts': 1000, 'interval': interval, 'deadline': limit})()
        pending = lambda request: (200, [b'{"deploymentState": "PUBLISHING"}'])
        result, elapsed, _, started = self.run_command(commands.wait, args, per_query, pending)
        self.assertEqual(result, 'stopped')
        self.assertLessEqual(elapsed, limit + 1e-6)
        self.assertTrue(all(at < limit for at in started))

    def test_an_answer_before_the_deadline_succeeds_at_once(self):
        args = type('Args', (), {'id': 'x', 'attempts': 1000, 'interval': 30, 'deadline': 3600})()
        published = lambda request: (200, [b'{"deploymentState": "PUBLISHED"}'])
        result, elapsed, _, _ = self.run_command(commands.wait, args, 5, published)
        self.assertEqual((result, elapsed), (0, 5))

    def precheck_answer(self, request):
        """v5.3.0 の前の run の deployment が処理中のまま。今の run（v5.3.0 の試み 2）は待ち続ける。"""
        url = request.full_url
        created = (datetime.now(timezone.utc) - timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ')
        if '/actions/workflows/release.yml/runs' in url:
            return 200, [json.dumps({'total_count': 1, 'workflow_runs': [
                {'id': RUN, 'created_at': created, 'head_branch': 'v5.3.0', 'event': 'push', 'run_attempt': 2}]}).encode()]
        if url.endswith('/jobs?filter=all&per_page=100&page=1'):
            steps = [{'name': name, 'status': 'completed', 'conclusion': conclusion}
                     for name, conclusion in passed_pre_steps() + ((portal.UPLOAD_STEP, 'success'),)]
            return 200, [json.dumps({'total_count': 1, 'jobs': [
                {'name': portal.RELEASE_JOB, 'run_attempt': 1, 'started_at': 's', 'completed_at': 'c', 'runner_id': 1,
                 'runner_name': 'r', 'steps': steps}]}).encode()]
        if '/artifacts' in url:
            expires = (datetime.now(timezone.utc) + timedelta(days=89)).strftime('%Y-%m-%dT%H:%M:%SZ')
            return 200, [json.dumps({'total_count': 1, 'artifacts': [
                {'name': portal.record_name(1, ID_A), 'expired': False, 'expires_at': expires}]}).encode()]
        if '/publisher/status' in url:
            return 200, [json.dumps({'deploymentName': portal.deployment_name('5.3.0', RUN, 1),
                                     'deploymentState': 'PUBLISHING'}).encode()]
        return 404, [b'']

    def ls_remote(self, seconds):
        def patch(now, started):
            def run(command, **kwargs):
                started.append(now[0])
                now[0] += min(seconds, kwargs['timeout'])
                if seconds > kwargs['timeout']:
                    raise commands.subprocess.TimeoutExpired(command, kwargs['timeout'])
                return type('Result', (), {'returncode': 0, 'stdout': 'abc\trefs/tags/v5.3.0\n', 'stderr': ''})()
            return mock.patch.object(commands.subprocess, 'run', side_effect=run)
        return patch

    @settings(max_examples=40, deadline=None)
    @given(st.floats(0, 20), st.floats(0, 200), st.floats(0, 120), st.floats(1, 900))
    def test_precheck_stays_within_the_deadline(self, per_query, per_ls_remote, interval, limit):
        args = type('Args', (), {'version': '5.3.0', 'remote': 'origin', 'attempts': 1000, 'interval': interval,
                                 'deadline': limit})()
        environment = {'GH_TOKEN': 'x', 'GITHUB_REPOSITORY': 'o/r', 'GITHUB_RUN_ID': str(RUN), 'GITHUB_RUN_ATTEMPT': '2',
                       'CENTRAL_DEPLOYMENTS': '', 'GITHUB_OUTPUT': '', 'GITHUB_API_URL': 'http://127.0.0.1:9'}
        with mock.patch.dict(os.environ, environment):
            result, elapsed, _, started = self.run_command(
                commands.precheck, args, per_query, self.precheck_answer, extra=(self.ls_remote(per_ls_remote),))
        self.assertEqual(result, 'stopped')
        self.assertLessEqual(elapsed, limit + 1e-6)
        self.assertTrue(all(at < limit for at in started))

    def test_listing_the_tags_stays_within_the_deadline(self):
        now = [0.0]
        calls = []

        def run(command, **kwargs):
            calls.append(kwargs['timeout'])
            return type('Result', (), {'returncode': 0, 'stdout': '', 'stderr': ''})()

        with mock.patch.object(commands.subprocess, 'run', side_effect=run):
            for left, expected in ((3600, 120), (30, 30), (0.5, 0.5)):
                with self.subTest(left=left), mock.patch.object(commands.net, 'DEADLINE',
                                                                commands.net.Deadline(left, lambda: now[0])):
                    commands.remote_tags('origin')
                    self.assertEqual(calls[-1], expected)
            with mock.patch.object(commands.net, 'DEADLINE', commands.net.Deadline(0, lambda: now[0])), \
                    self.assertRaises(portal.Stop):
                commands.remote_tags('origin')
            self.assertEqual(len(calls), 3)
            commands.remote_tags('origin')
        self.assertEqual(calls[-1], 120)

    def test_the_default_deadline_is_sixty_minutes(self):
        for command, extra in (('confirm', ['--version', '5.3.0']), ('wait', ['--id', 'x']),
                               ('precheck', ['--version', '5.3.0'])):
            with self.subTest(command), mock.patch.object(commands, command, side_effect=lambda args: args.deadline) as fake:
                fake.__name__ = command
                self.assertEqual(commands.main(['central-portal.py', command] + extra), 3600)


if __name__ == '__main__':
    unittest.main()

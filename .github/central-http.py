#! /usr/bin/env python3
"""central-portal.py の照会の層：HTTP の照会と、コマンド全体の締め切り（#870）。

http は (状態の番号, 本文) を返し、接続の失敗・時間切れ・本文の途中の切断・締め切りを (None, b'') にする。
DEADLINE があるあいだ（precheck・wait・confirm を with_deadline で動かすあいだ）は、照会をその残りで打ち切る。
本文が少しずつ届く場合の打ち切り方（read_body・Watchdog）は event-store-adapter-rs#375（81edc85）の写し。
"""
import http.client as http_client  # 関数 http と名前がぶつからないように
import socket
import threading
import time
import urllib.error
import urllib.request

BODY_CHUNK = 64 * 1024


class Deadline:
    """コマンド全体の締め切り（単調時計）。照会の時間も待ちに数える（#870）。"""

    def __init__(self, seconds, clock=time.monotonic):
        self.clock = clock
        self.end = clock() + seconds

    def remaining(self):
        return max(0.0, self.end - self.clock())


# 今のコマンドの締め切り（precheck・wait・confirm の間だけ）。http はこの残りで照会を打ち切り、残りが無ければ照会しない
DEADLINE = None


def response_socket(response):
    """応答の socket。CPython の http.client では、HTTPResponse.fp は socket.makefile('rb') の BufferedReader で、
    その raw（SocketIO）が socket を持つ。HTTPError は fp に HTTPResponse を持つ。取れなければ None。"""
    fp = getattr(response, 'fp', None)
    raw = getattr(fp, 'raw', None)
    sock = getattr(raw, '_sock', None)
    return sock if isinstance(sock, socket.socket) else None


class Watchdog:
    """締め切りに socket を閉じ、受信を止める timer。閉じられなかったときは、その照会を時間切れとして失敗にする
    （raise_failure）。読み終えて閉じた socket には何もしない。"""

    def __init__(self, sock, seconds):
        self.sock = sock
        self.failure = None
        self.timer = threading.Timer(max(seconds, 0), self.fire)
        self.timer.daemon = True
        self.timer.start()

    def fire(self):
        if self.sock.fileno() < 0:
            return
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError as error:
            self.failure = error

    def cancel(self):
        self.timer.cancel()

    def raise_failure(self):
        if self.failure is not None:
            raise TimeoutError(f'could not shut the socket down at the deadline: {self.failure}') from self.failure


def read_body(response, deadline, timeout):
    """本文を読む。deadline があれば締め切りで打ち切る（過ぎたら TimeoutError）。

    urlopen の timeout は通信の1回ごとにかかり、read(n) は n バイトそろうまで何度も受信するので、本文が少しずつ届くと
    締め切りを超えて待つ（event-store-adapter-rs#375 の指摘、#870）。そこで、
    - 受信の前ごとに socket の timeout を締め切りの残りにし、read1（受信は1回）で読み、読むたびに締め切りを確かめる。
    - chunked の長さの行のように1回の read1 の中で何度も受信する場合に備え、締め切りに socket を閉じる（shutdown）。
      閉じたあとの受信は失敗し、照会の失敗になる。"""
    if deadline is None:
        return response.read()
    sock = response_socket(response)
    read = getattr(response, 'read1', None) or response.read
    watchdog = Watchdog(sock, deadline.remaining()) if sock is not None else None
    try:
        chunks = []
        while True:
            left = deadline.remaining()
            if left <= 0:
                raise TimeoutError('the deadline passed while reading the body')
            if sock is not None and sock.fileno() >= 0:   # 本文を読み終えると http.client が socket を閉じる
                sock.settimeout(min(timeout, left))
            chunk = read(BODY_CHUNK)
            if not chunk:
                break
            chunks.append(chunk)
        if deadline.remaining() <= 0:
            raise TimeoutError('the deadline passed while reading the body')
        return b''.join(chunks)
    finally:
        if watchdog is not None:
            watchdog.cancel()
            watchdog.raise_failure()


def http(method, url, headers=None, body=None, timeout=60):
    """(状態の番号, 本文) を返す。接続の失敗、時間切れ、本文の途中の切断（IncompleteRead など）は (None, b'')。

    DEADLINE があれば、その残りで照会を打ち切り、残りが無ければ照会しない。締め切りのあとに読み終えた応答は使わない。
    接続と見出しの受信は、1回の通信ごとに締め切りの残りまで（本文は read_body が締め切りで切る）。"""
    deadline = DEADLINE
    if deadline is not None:
        left = deadline.remaining()
        if left <= 0:
            return None, b''
        timeout = min(timeout, left)
    request = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, read_body(response, deadline, timeout)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, read_body(getattr(error, 'fp', None) or error, deadline, timeout)
    except (urllib.error.URLError, http_client.HTTPException, TimeoutError, OSError, ValueError):
        return None, b''


def with_deadline(command):
    """precheck・wait・confirm を、--deadline 秒の締め切りの中で動かす。待ちは残り時間を超えない。"""
    def run(args, sleep=time.sleep, monotonic=time.monotonic, **kwargs):
        global DEADLINE
        deadline = Deadline(getattr(args, 'deadline', 3600), monotonic)
        DEADLINE = deadline

        def bounded_sleep(seconds):
            sleep(min(seconds, deadline.remaining()))

        try:
            return command(args, sleep=bounded_sleep, deadline=deadline, **kwargs)
        finally:
            DEADLINE = None
    run.__name__ = command.__name__
    run.__doc__ = command.__doc__
    return run

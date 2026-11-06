#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AI 拍照助手 · 最小原型启动器

双击本文件，或命令行运行：
    python run.py

它会：
  1. 在 127.0.0.1 上挑一个空闲端口起静态服务器（8765 起，被占用就自动 +1）
  2. 自动用默认浏览器打开 index.html
  3. 保持运行；关闭窗口或按 Ctrl+C 停止

参数：
    python run.py --no-open     只起服务，不自动开浏览器
    python run.py --port 9000   指定起始端口
"""
import http.server
import os
import socket
import sys
import threading
import webbrowser

APP_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_PORT = 8765


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    """静态文件服务器：关缓存（改代码刷新即生效），安静模式。"""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=APP_DIR, **kwargs)

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store, max-age=0')
        super().end_headers()

    def log_message(self, fmt, *args):  # 不刷屏
        pass


def pick_port(start: int, tries: int = 50) -> int:
    for port in range(start, start + tries):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(('127.0.0.1', port))
                return port
            except OSError:
                continue
    raise SystemExit('端口都被占用了，试试 --port 9000')


def main() -> int:
    if not os.path.exists(os.path.join(APP_DIR, 'index.html')):
        print('错误：index.html 不在这个目录里 ——', APP_DIR)
        return 1

    args = sys.argv[1:]
    no_open = '--no-open' in args
    start_port = DEFAULT_PORT
    if '--port' in args:
        i = args.index('--port')
        if i + 1 < len(args):
            try:
                start_port = int(args[i + 1])
            except ValueError:
                print('--port 后面要跟数字')
                return 1

    port = pick_port(start_port)
    for entry in ('live.html', 'showcase.html', 'index.html'):
        if os.path.exists(os.path.join(APP_DIR, entry)):
            break
    url = f'http://127.0.0.1:{port}/{entry}'

    httpd = http.server.ThreadingHTTPServer(('127.0.0.1', port), QuietHandler)
    httpd.daemon_threads = True

    print('=' * 56)
    print('  AI 拍照助手 · 最小原型')
    print(f'  地址：{url}')
    print('  关闭本窗口或按 Ctrl+C 停止')
    print('=' * 56)

    if not no_open:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print('\n已停止。')
    finally:
        httpd.server_close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

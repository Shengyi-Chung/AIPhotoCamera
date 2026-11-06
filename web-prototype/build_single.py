#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 detect.js + live.js 内联进 live.html，生成可双击运行的单文件版 live-single.html

原因：file:// 协议下浏览器（Chrome/Edge）会以 CORS 为由拒绝加载 <script type="module" src="...">，
导致页面按钮点不动。把脚本内联后就没有跨文件加载问题了。
"""
import os
import re

APP = os.path.dirname(os.path.abspath(__file__))


def read(name):
    with open(os.path.join(APP, name), encoding='utf-8') as f:
        return f.read()


def strip_export(code: str) -> str:
    return re.sub(r'^\s*export\s+(function|const|let|class)\b', r'\1', code, flags=re.M)


def main():
    detect = strip_export(read('detect.js'))
    aim = strip_export(read('aim.js'))
    live = read('live.js')
    # 去掉 import 行（此时依赖已在同一作用域）
    live = re.sub(r'^\s*import\s+[^;]+;\s*$', '', live, flags=re.M)
    # 去掉 export（如有）
    live = strip_export(live)

    html = read('live.html')
    # 单文件版不需要 file:// 提示块（它本来就能双击跑），构建时移除
    html = re.sub(r'<!-- file-hint:start -->.*?<!-- file-hint:end -->\s*', '', html, flags=re.S)

    bundle = (
        "<script>\n"
        f"/* ===== detect.js ===== */\n{detect}\n"
        f"/* ===== aim.js ===== */\n{aim}\n"
        f"/* ===== live.js ===== */\n{live}\n"
        "</script>"
    )
    html2 = html.replace('<script type="module" src="./live.js"></script>', bundle)
    if html2 == html:
        raise SystemExit('没找到要替换的 script 标签，检查 live.html')

    # 单文件版标题标记
    html2 = html2.replace('<title>AI 拍照助手 · Live</title>', '<title>AI 拍照助手 · Live（单文件版）</title>')

    out = os.path.join(APP, 'live-single.html')
    with open(out, 'w', encoding='utf-8') as f:
        f.write(html2)
    print('生成成功：', out)
    print('大小：', round(os.path.getsize(out) / 1024, 1), 'KB')


if __name__ == '__main__':
    main()

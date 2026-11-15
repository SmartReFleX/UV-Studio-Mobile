#!/usr/bin/env python3
"""Builds test/icons.html: every res/drawable/*.xml rendered as SVG, so the
vector icons can be checked in a browser without an Android device.
Run from the android/ folder: python tools/check_icons.py
"""
import glob
import io
import os
import re

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")

HEAD = """<!doctype html>
<html lang="en">
<meta charset="utf-8">
<title>UV Studio Android - card icons</title>
<body style="margin:0;background:#0b1220;font-family:'Segoe UI',sans-serif;padding:18px">
<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:16px">
"""

CELL = """<div style="text-align:center">
  <div style="width:88px;height:88px;margin:0 auto;border-radius:24px;
              background:rgba(45,212,191,.1);border:1px solid rgba(45,212,191,.25);
              display:flex;align-items:center;justify-content:center">
    <svg viewBox="0 0 24 24" width="56" height="56">{body}</svg>
  </div>
  <div style="color:#8fa3bf;font-size:12px;margin-top:8px">{name}</div>
</div>
"""

FOOT = "</div></body>\n"


def body_for(path):
    xml = io.open(path, encoding="utf-8").read()
    body = ""
    for chunk in re.findall(r"<path(.*?)/>", xml, re.S):
        data = re.search(r'pathData="([^"]+)"', chunk).group(1)
        stroke = "#2dd4bf" if 'strokeColor="#FFFFFFFF"' in chunk else "none"
        fill = "#2dd4bf" if 'fillColor="#FFFFFFFF"' in chunk else "none"
        width = re.search(r'strokeWidth="([^"]+)"', chunk)
        width = width.group(1) if width else "1.8"
        body += ('<path d="%s" stroke="%s" stroke-width="%s" fill="%s" '
                 'stroke-linecap="round" stroke-linejoin="round"/>'
                 % (data, stroke, width, fill))
    return body


def main():
    cells = []
    files = sorted(glob.glob(os.path.join(ROOT, "res", "drawable", "*.xml")))
    for path in files:
        name = os.path.splitext(os.path.basename(path))[0]
        cells.append(CELL.format(body=body_for(path), name=name))

    out = os.path.join(ROOT, "test", "icons.html")
    with io.open(out, "w", encoding="utf-8") as f:
        f.write(HEAD + "".join(cells) + FOOT)
    print(out, len(files), "icons")


if __name__ == "__main__":
    main()

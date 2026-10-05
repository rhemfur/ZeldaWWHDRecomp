#!/usr/bin/env python3
"""Makes a Windows icon from the game's own icon (game/meta/iconTex.tga, 128x128).

usage: python tools/make_windows_icon.py [game_dir]   (default: game)

Writes build/icon/wwhd.ico (16 to 256 px). build/ is git-ignored: the picture is the game's, like the
recompiled code it stays on your machine. On Windows, CMake embeds the icon in wwhd.exe when the
file exists (taskbar, window and Explorer); without it the executable has no icon. Needs Pillow
(pip install pillow).
"""
import os
import sys

from PIL import Image

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
game = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "game")
src = os.path.join(game, "meta", "iconTex.tga")
if not os.path.exists(src):
    sys.exit(f"{src} not found (the extracted game's meta folder)")
out = os.path.join(root, "build", "icon", "wwhd.ico")
os.makedirs(os.path.dirname(out), exist_ok=True)
art = Image.open(src).convert("RGBA")
sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
art.resize((256, 256), Image.LANCZOS).save(out, format="ICO", sizes=sizes)
print("wrote", os.path.relpath(out, root))

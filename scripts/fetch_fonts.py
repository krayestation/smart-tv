"""Download the Fraunces serif into the app at build time, so the TV never waits on Google Fonts.

Usage: python3 scripts/fetch_fonts.py app/src/main/assets/fonts
"""
import os
import re
import sys
import urllib.request

OUT = sys.argv[1]
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")  # makes Google serve woff2
CSS_URL = ("https://fonts.googleapis.com/css2?family=Fraunces:ital,opsz,wght@"
           "0,9..144,400..700;1,9..144,400..700&display=swap")


def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=30).read()


os.makedirs(OUT, exist_ok=True)
css = fetch(CSS_URL).decode("utf-8")
blocks = []
for n, m in enumerate(re.finditer(r"/\*\s*([\w-]+)\s*\*/\s*(@font-face\s*\{.*?\})", css, re.S)):
    subset, block = m.group(1), m.group(2)
    if subset not in ("latin", "latin-ext"):
        continue
    src = re.search(r"url\((https://[^)]+)\)", block).group(1)
    name = "fraunces-%d.woff2" % n
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(fetch(src))
    blocks.append(block.replace(src, name))

if not blocks:
    sys.exit("no font blocks found")
with open(os.path.join(OUT, "fonts.css"), "w") as f:
    f.write("\n".join(blocks) + "\n")
print("saved %d font files" % len(blocks))

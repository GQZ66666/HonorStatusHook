import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(APP, "res")
JAVA = os.path.join(APP, "java")

problems = []
xml_files = []
for base, _, files in os.walk(RES):
    for f in files:
        if f.endswith(".xml"):
            xml_files.append(os.path.join(base, f))
xml_files.append(os.path.join(APP, "AndroidManifest.xml"))

for f in xml_files:
    try:
        ET.parse(f)
    except Exception as e:
        problems.append("XML ERROR %s: %s" % (f, e))

strings, colors, styles, arrays = set(), set(), set(), set()
for name in os.listdir(os.path.join(RES, "values")):
    if not name.endswith(".xml"):
        continue
    root = ET.parse(os.path.join(RES, "values", name)).getroot()
    for child in root:
        n = child.get("name")
        if child.tag == "string":
            strings.add(n)
        elif child.tag == "color":
            colors.add(n)
        elif child.tag == "style":
            styles.add(n)
        elif child.tag == "string-array":
            arrays.add(n)

drawables, mipmaps = set(), set()
for base, _, files in os.walk(RES):
    for f in files:
        n, ext = os.path.splitext(f)
        if ext in (".xml", ".png", ".webp", ".jpg"):
            if "drawable" in base:
                drawables.add(n)
            if "mipmap" in base:
                mipmaps.add(n)
layouts = {os.path.splitext(f)[0] for f in os.listdir(os.path.join(RES, "layout"))}

ref_re = re.compile(r"@(string|color|style|array|drawable|mipmap|layout)/([A-Za-z0-9_.]+)")
for f in xml_files:
    text = open(f, encoding="utf-8").read()
    for typ, name in ref_re.findall(text):
        ok = {
            "string": strings, "color": colors, "style": styles, "array": arrays,
            "drawable": drawables, "mipmap": mipmaps, "layout": layouts,
        }[typ] if typ in ("string", "color", "style", "array", "drawable", "mipmap", "layout") else None
        if ok is not None and name not in ok:
            problems.append("MISSING @%s/%s in %s" % (typ, name, os.path.relpath(f, ROOT)))

java_files = []
for base, _, files in os.walk(JAVA):
    for f in files:
        if f.endswith(".java"):
            java_files.append(os.path.join(base, f))

ids = set(re.findall(r'android:id="@\+id/([A-Za-z0-9_]+)"',
                     open(os.path.join(RES, "layout", "activity_main.xml"), encoding="utf-8").read()))

r_re = re.compile(r"\bR\.(id|string|drawable|layout|array|color|style)\.([A-Za-z0-9_]+)")
for f in java_files:
    src = open(f, encoding="utf-8").read()
    for typ, name in r_re.findall(src):
        table = {
            "id": ids, "string": strings, "drawable": drawables, "layout": layouts,
            "array": arrays, "color": colors, "style": styles,
        }[typ]
        if name not in table:
            problems.append("MISSING R.%s.%s in %s" % (typ, name, os.path.relpath(f, ROOT)))


def strip_java(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "'":
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


for f in java_files:
    src = strip_java(open(f, encoding="utf-8").read())
    for oc, cc in (("{", "}"), ("(", ")"), ("[", "]")):
        if src.count(oc) != src.count(cc):
            problems.append("UNBALANCED %s%s in %s (%d vs %d)" % (
                oc, cc, os.path.relpath(f, ROOT), src.count(oc), src.count(cc)))

print("XML files:", len(xml_files), "Java files:", len(java_files))
print("strings:", len(strings), "ids:", len(ids), "drawables:", len(drawables))
if problems:
    print("\nPROBLEMS (%d):" % len(problems))
    for p in problems:
        print(" -", p)
    sys.exit(1)
print("\nAll checks passed.")

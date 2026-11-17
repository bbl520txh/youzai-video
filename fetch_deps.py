#!/usr/bin/env python3
"""Walk Maven POMs (curl only) and download the full dependency tree as .aar/.jar."""
import os, re, subprocess, sys
import xml.etree.ElementTree as ET

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "deps")
os.makedirs(OUT, exist_ok=True)
REPOS = ["https://dl.google.com/android/maven2", "https://repo1.maven.org/maven2"]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

def fetch(url, dest=None):
    args = ["curl", "-sfL", "--max-time", "120", url]
    if dest:
        args += ["-o", dest]
        r = subprocess.run(args, capture_output=True)
        return r.returncode == 0
    r = subprocess.run(args, capture_output=True)
    return r.stdout if r.returncode == 0 else None

def gpath(g): return g.replace(".", "/")

def pom_url(g, a, v, repo):
    return f"{repo}/{gpath(g)}/{a}/{v}/{a}-{v}.pom"

_pom_cache = {}
def get_pom(g, a, v):
    key = (g, a, v)
    if key in _pom_cache: return _pom_cache[key]
    root = None
    for repo in REPOS:
        data = fetch(pom_url(g, a, v, repo))
        if data:
            try: root = ET.fromstring(data); break
            except Exception: pass
    _pom_cache[key] = root
    return root

def txt(el, tag):
    c = el.find(f"m:{tag}", NS)
    return c.text.strip() if c is not None and c.text else ""

def resolve(s, props):
    def rep(m):
        return props.get(m.group(1), m.group(0))
    return re.sub(r"\$\{([^}]+)\}", rep, s or "")

def collect(g, a, v, props, dmgmt, depth=0):
    """Merge properties + dependencyManagement from this pom and its parents."""
    root = get_pom(g, a, v)
    if root is None: return
    props.setdefault("project.version", v)
    props.setdefault("pom.version", v)
    props.setdefault("project.groupId", g)
    props.setdefault("project.artifactId", a)
    pel = root.find("m:properties", NS)
    if pel is not None:
        for child in pel:
            tag = child.tag.split("}")[-1]
            props[tag] = (child.text or "").strip()
    parent = root.find("m:parent", NS)
    if parent is not None and depth < 6:
        pg, pa, pv = txt(parent, "groupId"), txt(parent, "artifactId"), txt(parent, "version")
        if pg and pa and pv:
            collect(pg, pa, resolve(pv, props), props, dmgmt, depth + 1)
    dm = root.find("m:dependencyManagement/m:dependencies", NS)
    if dm is not None:
        for d in dm.findall("m:dependency", NS):
            dg, da = txt(d, "groupId"), txt(d, "artifactId")
            dv, dt = txt(d, "version"), txt(d, "type") or "jar"
            if dg and da and dv:
                dmgmt[(resolve(dg, props), resolve(da, props))] = (resolve(dv, props), resolve(dt, props))

def pom_deps(g, a, v):
    root = get_pom(g, a, v)
    if root is None: return None, []
    props, dmgmt = {}, {}
    collect(g, a, v, props, dmgmt)
    packaging = txt(root, "packaging") or "jar"
    deps = []
    dl = root.find("m:dependencies", NS)
    if dl is not None:
        for d in dl.findall("m:dependency", NS):
            dg, da = resolve(txt(d, "groupId"), props), resolve(txt(d, "artifactId"), props)
            dv, dt = resolve(txt(d, "version"), props), resolve(txt(d, "type") or "jar", props)
            scope = txt(d, "scope") or "compile"
            optional = txt(d, "optional") == "true"
            if not dv and (dg, da) in dmgmt:
                dv, dt2 = dmgmt[(dg, da)]
                if dt == "jar": dt = dt2
            if not dg or not da or not dv or "${" in dv: continue
            if scope in ("test", "provided") or optional: continue
            deps.append((dg, da, dv, dt))
    return packaging, deps

seen = {}
queue = [("androidx.media3", "media3-exoplayer", "1.4.1", "aar"),
         ("androidx.media3", "media3-exoplayer-hls", "1.4.1", "aar")]
order = []
while queue:
    g, a, v, t = queue.pop(0)
    if (g, a) in seen: continue
    seen[(g, a)] = (v, t)
    order.append((g, a, v, t))
    packaging, deps = pom_deps(g, a, v)
    if packaging and t == "aar" and packaging != "aar":
        seen[(g, a)] = (v, packaging)
    for dg, da, dv, dt in deps:
        if (dg, da) not in seen:
            queue.append((dg, da, dv, dt))

print(f"== {len(order)} artifacts")
failed = []
for g, a, v, t in order:
    ext = "aar" if t == "aar" else "jar"
    name = f"{a}-{v}.{ext}"
    dest = os.path.join(OUT, name)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print("have", name); continue
    ok = False
    for repo in REPOS:
        url = f"{repo}/{gpath(g)}/{a}/{v}/{name}"
        if fetch(url, dest): ok = True; break
    if ok:
        print("dl  ", name, os.path.getsize(dest))
    else:
        # maybe the other packaging
        alt = "jar" if ext == "aar" else "aar"
        name2 = f"{a}-{v}.{alt}"
        dest = os.path.join(OUT, name2)
        for repo in REPOS:
            url = f"{repo}/{gpath(g)}/{a}/{v}/{name2}"
            if fetch(url, dest): ok = True; print("dl  ", name2, os.path.getsize(dest)); break
    if not ok:
        failed.append(name)
        print("FAIL", name)
print("FAILED:", failed if failed else "none")

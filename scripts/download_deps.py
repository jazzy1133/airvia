#!/usr/bin/env python3
"""
Download all Maven dependencies (full transitive closure) for the Jazzy app.

- Resolves POMs transitively (compile+runtime scopes, skips test/provided/optional).
- Handles parent POMs, properties, dependencyManagement, BOM imports, version ranges.
- Downloads AAR for Android library groups, JAR otherwise.
- Saves to ~/workspace/airvia/libs/<group>/<artifact>/<version>/<artifact>-<version>.<ext>
- Idempotent: skips files that already exist.

Usage: python3 download_deps.py
"""

import os
import re
import sys
import subprocess
import xml.etree.ElementTree as ET
from collections import deque

LIBS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "libs")
CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".pomcache")
GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"
REPOS = [GOOGLE, CENTRAL]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

LIFECYCLE_VERSION = "2.11.0"

# Artifacts folded into another artifact in newer releases.
MERGED_INTO_NEWER = {
    ("androidx.activity", "activity-ktx"): "merged into activity 1.8+",
}

ANDROID_GROUP_PREFIXES = ("androidx.", "com.google.android.", "com.google.firebase",
                         "com.google.accompanist", "io.coil-kt")

os.makedirs(LIBS_DIR, exist_ok=True)
os.makedirs(CACHE_DIR, exist_ok=True)


def log(*a):
    print(*a, flush=True)


# ---------------------------------------------------------------- curl ---

def curl_fetch(url):
    """Return response bytes or None."""
    try:
        r = subprocess.run(
            ["curl", "-sfL", "--retry", "2", "--max-time", "90", url],
            capture_output=True, timeout=120)
        if r.returncode == 0 and r.stdout:
            return r.stdout
    except Exception:
        pass
    return None


def curl_download(url, dest):
    """Download url -> dest. Returns True on success (or if already present)."""
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return True
    tmp = dest + ".part"
    try:
        r = subprocess.run(
            ["curl", "-sfL", "--retry", "2", "--max-time", "600",
             "-o", tmp, url],
            capture_output=True, timeout=660)
        if r.returncode == 0 and os.path.exists(tmp) and os.path.getsize(tmp) > 0:
            os.rename(tmp, dest)
            return True
    except Exception:
        pass
    if os.path.exists(tmp):
        try:
            os.remove(tmp)
        except OSError:
            pass
    return False


# ---------------------------------------------------------------- POMs ---

_pom_cache = {}


def get_pom(g, a, v):
    """Fetch and parse a POM. Returns Element or None. Cached on disk + memory."""
    key = (g, a, v)
    if key in _pom_cache:
        return _pom_cache[key]
    cpath = os.path.join(CACHE_DIR, f"{g}__{a}__{v}.pom".replace("/", "_"))
    data = None
    if os.path.exists(cpath) and os.path.getsize(cpath) > 0:
        with open(cpath, "rb") as f:
            data = f.read()
    else:
        for repo in REPOS:
            url = f"{repo}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom"
            data = curl_fetch(url)
            if data:
                with open(cpath, "wb") as f:
                    f.write(data)
                break
    root = None
    if data:
        try:
            root = ET.fromstring(data)
        except Exception as e:
            log(f"  WARN: cannot parse POM {g}:{a}:{v}: {e}")
    _pom_cache[key] = root
    return root


def _text(elem, path):
    n = elem.find(path, NS)
    if n is not None and n.text and n.text.strip():
        return n.text.strip()
    return None


_prop_re = re.compile(r"\$\{([^}]+)\}")


def substitute(s, props, _depth=0):
    """Replace ${...} placeholders using props. Returns None if unresolvable."""
    if not s or _depth > 10:
        return s
    def repl(m):
        key = m.group(1)
        if key in props:
            return props[key]
        return m.group(0)  # leave unresolved
    out = _prop_re.sub(repl, s)
    if out != s and "${" in out:
        out = substitute(out, props, _depth + 1)
    return out


def effective_data(g, a, v, depth=0):
    """
    Return (properties, depMgmt) for an artifact, merging parent POMs
    (parents first, child overrides) and import-scope BOMs.
    depMgmt maps (group, artifact) -> raw version string.
    """
    props, mgmt = {}, {}
    if depth > 8:
        return props, mgmt
    root = get_pom(g, a, v)
    if root is None:
        return props, mgmt

    parent = root.find("m:parent", NS)
    pver = None
    if parent is not None:
        pg = _text(parent, "m:groupId")
        pa = _text(parent, "m:artifactId")
        pv = _text(parent, "m:version")
        if pg and pa and pv:
            pv = substitute(pv, props) or pv
            if pv and "${" not in pv:
                pprops, pmgmt = effective_data(pg, pa, pv, depth + 1)
                props.update(pprops)
                mgmt.update(pmgmt)
                pver = pv

    # this pom's own properties
    for p in root.findall("m:properties/*", NS):
        tag = p.tag.split("}", 1)[1] if "}" in p.tag else p.tag
        if p.text and p.text.strip():
            props[tag] = p.text.strip()
    own_ver = _text(root, "m:version") or pver
    if own_ver:
        props.setdefault("project.version", own_ver)
        props.setdefault("version", own_ver)

    # dependencyManagement (child entries override parent/imports)
    for d in root.findall("m:dependencyManagement/m:dependencies/m:dependency", NS):
        dg = _text(d, "m:groupId")
        da = _text(d, "m:artifactId")
        dv = _text(d, "m:version")
        dscope = _text(d, "m:scope")
        dtype = _text(d, "m:type")
        if not (dg and da):
            continue
        if dscope == "import" and dv:
            bver = substitute(dv, props)
            if bver and "${" not in bver:
                bprops, bmgmt = effective_data(dg, da, bver, depth + 1)
                for k, val in bprops.items():
                    props.setdefault(k, val)
                for k, val in bmgmt.items():
                    mgmt.setdefault(k, val)
            continue
        if dv:
            mgmt[(dg, da)] = dv
    return props, mgmt


# ------------------------------------------------------- versions ---

def _version_key(v):
    """Rough Maven version ordering key. Higher sorts later."""
    # split into numeric/alpha tokens
    tokens = re.findall(r"\d+|[a-zA-Z]+", v.lower())
    key = []
    for t in tokens:
        if t.isdigit():
            key.append((0, int(t), ""))
        else:
            # qualifier ordering: alpha<beta<milestone<rc<snapshot<''<sp
            order = {"alpha": 1, "beta": 2, "milestone": 3, "m": 3,
                     "rc": 4, "cr": 4, "snapshot": 5, "": 6, "sp": 7,
                     "final": 6, "release": 6}
            key.append((1, order.get(t, 5), t))
    return key


def ver_gt(a, b):
    return _version_key(a) > _version_key(b)


_metadata_cache = {}


def get_all_versions(g, a):
    """List available versions from maven-metadata.xml (cached)."""
    key = (g, a)
    if key in _metadata_cache:
        return _metadata_cache[key]
    versions = []
    for repo in REPOS:
        url = f"{repo}/{g.replace('.', '/')}/{a}/maven-metadata.xml"
        data = curl_fetch(url)
        if data:
            try:
                root = ET.fromstring(data)
                for v in root.findall(".//version"):
                    if v.text and v.text.strip():
                        versions.append(v.text.strip())
                if versions:
                    break
            except Exception:
                pass
    _metadata_cache[key] = versions
    return versions


def resolve_range(g, a, spec):
    """Resolve a version range like [1.0,2.0) or LATEST to a concrete version."""
    spec = spec.strip()
    if not spec.startswith(("[", "(")) and spec not in ("LATEST", "RELEASE"):
        return spec
    versions = get_all_versions(g, a)
    if not versions:
        return None
    if spec in ("LATEST", "RELEASE"):
        return max(versions, key=_version_key)
    m = re.match(r"^([\[\(])\s*([^,\s]*)\s*,\s*([^,\s]*)\s*([\]\)])$", spec)
    if not m:
        # single version in brackets like [1.2]
        m2 = re.match(r"^[\[\(]\s*([^,\s]+)\s*[\]\)]$", spec)
        if m2:
            return m2.group(1)
        return None
    lo_inc, lo, hi, hi_inc = m.group(1) == "[", m.group(2), m.group(3), m.group(4) == "]"
    cands = []
    for v in versions:
        if lo:
            c = _version_key(v) > _version_key(lo) or (lo_inc and _version_key(v) == _version_key(lo))
            if not c:
                continue
        if hi:
            c = _version_key(v) < _version_key(hi) or (hi_inc and _version_key(v) == _version_key(hi))
            if not c:
                continue
        cands.append(v)
    if not cands:
        return None
    return max(cands, key=_version_key)


# ------------------------------------------------------- deps ---

def parse_dependencies(g, a, v):
    """
    Return list of (group, artifact, version, exclusions) for compile+runtime deps.
    exclusions: tuple of (group, artifact) with '*' wildcard support.
    """
    props, mgmt = effective_data(g, a, v)
    root = get_pom(g, a, v)
    if root is None:
        return []
    deps = []
    for d in root.findall("m:dependencies/m:dependency", NS):
        dg = _text(d, "m:groupId")
        da = _text(d, "m:artifactId")
        if not (dg and da):
            continue
        scope = _text(d, "m:scope") or "compile"
        if scope not in ("compile", "runtime"):
            continue
        if _text(d, "m:optional") == "true":
            continue
        exclusions = []
        for e in d.findall("m:exclusions/m:exclusion", NS):
            eg, ea = _text(e, "m:groupId"), _text(e, "m:artifactId")
            if eg and ea:
                exclusions.append((eg, ea))
        raw_v = _text(d, "m:version")
        if not raw_v:
            raw_v = mgmt.get((dg, da))
        if not raw_v:
            log(f"  WARN: no version for {dg}:{da} (needed by {g}:{a}:{v}); skipping")
            continue
        dv = substitute(raw_v, props)
        if not dv or "${" in dv:
            log(f"  WARN: unresolvable version '{raw_v}' for {dg}:{da}; skipping")
            continue
        dv = resolve_range(dg, da, dv)
        if not dv:
            log(f"  WARN: cannot resolve range '{raw_v}' for {dg}:{da}; skipping")
            continue
        dg = substitute(dg, props) or dg
        da = substitute(da, props) or da
        deps.append((dg, da, dv, tuple(exclusions)))
    return deps


def excluded(g, a, exclusions):
    for eg, ea in exclusions:
        if (eg == "*" or eg == g) and (ea == "*" or ea == a):
            return True
    return False


# ------------------------------------------------------- main ---

def main():
    log("== Airvia dependency downloader ==")

    # 1. Fetch the Compose BOM to get managed versions.
    bom_g, bom_a, bom_v = "androidx.compose", "compose-bom", "2026.09.00"
    log(f"Fetching BOM {bom_g}:{bom_a}:{bom_v} ...")
    bprops, bmgmt = effective_data(bom_g, bom_a, bom_v)

    def bom_ver(artifact, group="androidx.compose.ui"):
        v = bmgmt.get((group, artifact))
        if v:
            v = substitute(v, bprops)
        return v

    # 2b. BOM-managed versions apply to transitive deps too (like Gradle).
    # Build a flat map of (group, artifact) -> version for all compose groups.
    bom_all = {}
    for (bg, ba), bv in bmgmt.items():
        if bg.startswith("androidx.compose."):
            bom_all[(bg, ba)] = substitute(bv, bprops) or bv

    # 2. Direct dependencies.
    direct = [
        ("androidx.core", "core-ktx", "1.19.1"),
        ("androidx.lifecycle", "lifecycle-runtime-ktx", "2.11.0"),
        ("androidx.activity", "activity-compose", "1.13.0"),
        ("androidx.compose.ui", "ui", bom_ver("ui")),
        ("androidx.compose.ui", "ui-graphics", bom_ver("ui-graphics")),
        ("androidx.compose.material3", "material3", bom_ver("material3", "androidx.compose.material3")),
        ("androidx.compose.material", "material-icons-core",
         bom_ver("material-icons-core", "androidx.compose.material")),
        ("com.google.android.gms", "play-services-cast-framework", "23.0.0"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.21"),
    ]
    for g, a, v in direct:
        if not v:
            log(f"FATAL: no version for direct dep {g}:{a}")
            sys.exit(1)
    log(f"BOM versions: ui={bom_ver('ui')}, material3="
        f"{bom_ver('material3', 'androidx.compose.material3')}")

    # 3. BFS transitive closure (nearest-wins, like Maven).
    chosen = {}          # (g, a) -> version
    queue = deque()
    for g, a, v in direct:
        queue.append((g, a, v, ()))
    while queue:
        g, a, v, excl = queue.popleft()
        key = (g, a)
        if key in chosen:
            continue
        if excluded(g, a, excl):
            continue
        chosen[key] = v
        for dg, da, dv, depexcl in parse_dependencies(g, a, v):
            if (dg, da) not in chosen and not excluded(dg, da, excl):
                # Prefer the BOM-managed version for compose artifacts.
                dv = bom_all.get((dg, da), dv)
                queue.append((dg, da, dv, excl + depexcl))

    log(f"Resolved {len(chosen)} artifacts (transitive closure).")

    # 3b. Evict obsolete artifacts whose classes were merged into successors.
    # (Gradle/Maven would also fail on these duplicate classes at dex time.)
    def evict(old, *news):
        if old in chosen:
            for new in news:
                if new in chosen:
                    log(f"Evicting obsolete {old[0]}:{old[1]}:{chosen[old]} "
                        f"(superseded by {new[0]}:{new[1]}:{chosen[new]})")
                    del chosen[old]
                    return
    evict(("androidx.collection", "collection"),
          ("androidx.collection", "collection-jvm"))
    evict(("androidx.collection", "collection-ktx"),
          ("androidx.collection", "collection-jvm"))
    evict(("androidx.annotation", "annotation"),
          ("androidx.annotation", "annotation-jvm"))
    evict(("androidx.datastore", "datastore-core-jvm"),
          ("androidx.datastore", "datastore-core-android"))
    evict(("com.google.guava", "listenablefuture"),
          ("com.google.guava", "guava"))

    # 3c. Keep every androidx.lifecycle artifact on ONE version. The old
    # per-artifact pins (2.7.0 / 2.8.3) existed for a D8 8.2.2 NPE that the
    # current build-tools no longer have, and mixing lifecycle versions risks
    # NoSuchMethodError at runtime. Since 2.8 the real viewmodel classes live
    # in lifecycle-viewmodel-android; the bare/-ktx artifacts duplicate them.
    for (g, a) in list(chosen):
        if g == "androidx.lifecycle":
            chosen[(g, a)] = LIFECYCLE_VERSION
    for _old in [("androidx.lifecycle", "lifecycle-viewmodel"),
                 ("androidx.lifecycle", "lifecycle-viewmodel-ktx")]:
        if _old in chosen:
            log(f"Evicting obsolete {_old[0]}:{_old[1]} (superseded by lifecycle-viewmodel-android)")
            del chosen[_old]
    chosen[("androidx.lifecycle", "lifecycle-viewmodel-android")] = LIFECYCLE_VERSION

    # 3d. Repair versions stomped by the Cast SDK subtree's stale floor versions.
    # play-services-cast-framework:21.4.0 declares androidx.appcompat:appcompat:1.0.0
    # and mediarouter:1.6.0-beta01 declares appcompat:1.1.0 /
    # appcompat-resources:1.2.0 / vectordrawable:1.0.0. This resolver is
    # first-seen-wins (not Gradle's highest-wins), so those stale floors beat the
    # newer versions the rest of the graph already uses. appcompat:1.0.0 still
    # bundles AppCompatResources, so D8 fails with duplicate classes against any
    # appcompat-resources. Restore the known-good versions from the pre-Cast graph.
    for (g, a), v in [
        (("androidx.appcompat", "appcompat"), "1.6.1"),
        (("androidx.appcompat", "appcompat-resources"), "1.6.1"),
        (("androidx.vectordrawable", "vectordrawable"), "1.1.0"),
        (("androidx.vectordrawable", "vectordrawable-animated"), "1.1.0"),
    ]:
        if (g, a) in chosen:
            log(f"Pinned {g}:{a} to {v} (was {chosen[(g, a)]}, stomped by Cast subtree)")
            chosen[(g, a)] = v

    # 3e. Artifacts that duplicate classes at dex time because this resolver
    # is first-seen-wins (Gradle is highest-wins and follows module metadata).
    for (g, a), why in MERGED_INTO_NEWER.items():
        if (g, a) in chosen:
            log(f"Evicting {g}:{a} ({why})")
            del chosen[(g, a)]
    for (g, a) in list(chosen):
        if g.startswith("androidx.") and a.endswith("-jvm") and \
                (g, a[:-4] + "-android") in chosen:
            log(f"Evicting {g}:{a} (Gradle resolves it to {a[:-4]}-android)")
            del chosen[(g, a)]

    # 4. Download artifacts (AAR for Android groups, JAR otherwise).
    failures = []
    ok = 0
    total_bytes = 0
    resolved = []  # (group, artifact, version, ext) for the build script
    items = sorted(chosen.items())
    for i, ((g, a), v) in enumerate(items, 1):
        is_android = g.startswith(ANDROID_GROUP_PREFIXES)
        exts = ["aar", "jar"] if is_android else ["jar"]
        dest_dir = os.path.join(LIBS_DIR, g, a, v)
        os.makedirs(dest_dir, exist_ok=True)
        # also stash the pom for later build steps
        pom_dest = os.path.join(dest_dir, f"{a}-{v}.pom")
        if not (os.path.exists(pom_dest) and os.path.getsize(pom_dest) > 0):
            for repo in REPOS:
                data = curl_fetch(f"{repo}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom")
                if data:
                    with open(pom_dest, "wb") as f:
                        f.write(data)
                    break
        got = None
        got_ext = None
        for ext in exts:
            dest = os.path.join(dest_dir, f"{a}-{v}.{ext}")
            found = False
            for repo in REPOS:
                url = f"{repo}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"
                if curl_download(url, dest):
                    found = True
                    break
            if found:
                got = dest
                got_ext = ext
                break
        if got:
            ok += 1
            resolved.append(f"{g}:{a}:{v}:{got_ext}")
            total_bytes += os.path.getsize(got)
        else:
            failures.append(f"{g}:{a}:{v}")
        if i % 20 == 0 or i == len(items):
            log(f"  ... {i}/{len(items)}")

    log("")
    log(f"Downloaded/verified: {ok}/{len(items)} artifacts, "
        f"{total_bytes / 1024 / 1024:.1f} MB total.")
    if failures:
        log(f"FAILURES ({len(failures)}):")
        for f in failures:
            log(f"  - {f}")
    log("All artifacts downloaded successfully." if not failures else
        "Done with failures (see above).")
    with open(os.path.join(LIBS_DIR, "resolved-artifacts.txt"), "w") as fh:
        fh.write("\n".join(resolved) + "\n")
    log(f"Wrote resolved artifact list ({len(resolved)} entries).")
    if failures:
        sys.exit(2)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Import checker for the document-vault plan's Java code blocks.

Checks, per ```java block:
  1. TYPES      — every capitalised identifier resolves to an import, an in-block declaration,
                  java.lang, or a TOP-LEVEL same-package sibling (declared in the plan or in the
                  real repo). Nested types do NOT count as siblings: javac needs Outer.Nested or
                  an explicit nested import.
  2. FQNs       — every import is resolved with javap against the REAL per-module testCompileClasspath
                  (captured from Gradle into .module-classpaths.txt, which is hash-stamped against the
                  build files and refused when stale). Dependencies the plan itself introduces are
                  RESOLVED too (.plan-new-deps-cp.txt), not waved through by namespace: skipping a
                  namespace let a typo under it pass. Static imports name a MEMBER, so the owning type
                  must also DECLARE it (javap -p).
  3. REACHABILITY— existence in the repo is not reachability: an internal import is only valid if the
                  importing module declares project(":thatModule"). document_vault's dependency block
                  is read from the plan itself, since the module does not exist yet.
  4. SHADOWING  — an import whose simple name the repo consistently takes from a different package
                  (e.g. java.awt.List when everything else uses java.util.List) is flagged. javap
                  cannot catch this: the class exists, it is just the wrong one.
  5. STATICS    — assert*/Mockito verbs are lowercase and invisible to check 1 by construction;
                  they are resolved against each file's accumulated static imports, ignoring
                  dot-qualified calls and methods the file declares itself.

Known residual gap: this validates IMPORTS, not semantics. `@String` (a class used as an annotation)
resolves fine here and is rejected by javac. That is compilation, not import resolution, and a plan
document is not compiled — the executor's first `./gradlew test` catches it.

Fragments ("add this method to an existing class") are attributed to their target file and share
that file's accumulated imports; for a file the plan MODIFIES, the real file's imports are read
from disk.

Run:  python3 docs/superpowers/plans/.import-sweep.py docs/superpowers/plans/<plan>.md
Exit: non-zero if anything is unresolved.
"""
import re, sys, os, subprocess, collections

JAVA_LANG = set("""String Integer Long Boolean Object Exception IllegalArgumentException IllegalStateException
Override Math System Class RuntimeException CharSequence Byte Double Float Short Character Void Thread
StringBuilder Comparable Iterable Runnable SuppressWarnings Deprecated FunctionalInterface SafeVarargs Error
Throwable Number Enum Record Cloneable AutoCloseable UnsupportedOperationException NullPointerException
InterruptedException NoSuchMethodException NoSuchFieldException ClassNotFoundException SecurityException
ArithmeticException""".split())
GENERIC = set("T V E K R U S".split())

# ---------------------------------------------------------------- repo facts
repo_pkg  = collections.defaultdict(set)   # simple name -> package(s), TOP-LEVEL types in the repo
repo_path = {}
repo_fqns = collections.Counter()          # every import FQN the repo actually uses
for root, _, fs in os.walk("."):
    if "/build/" in root or "/.git" in root:
        continue
    for fn in fs:
        if not fn.endswith(".java"):
            continue
        p = os.path.join(root, fn)
        try:
            t = open(p, errors="ignore").read()
        except OSError:
            continue
        m = re.search(r"^package ([\w.]+);", t, re.M)
        if m:
            repo_pkg[fn[:-5]].add(m.group(1))
            repo_path[fn[:-5]] = p
        repo_fqns.update(re.findall(r"^import (?:static )?([\w.]+);", t, re.M))

# ---------------------------------------------------------------- helpers
def declarations(body):
    """Top-level vs nested type declarations. Depth is counted in braces."""
    top, nested, depth = set(), set(), 0
    for line in body.split("\n"):
        m = re.search(r"\b(?:class|interface|enum|record|@interface)\s+(\w+)", line)
        if m:
            (top if depth == 0 else nested).add(m.group(1))
        depth += line.count("{") - line.count("}")
    return top, nested

def strip(b):
    b = re.sub(r"^\s*(package|import) .*$", "", b, flags=re.M)
    b = re.sub(r'"(?:[^"\\]|\\.)*"', '""', b)
    b = re.sub(r"//.*$", "", b, flags=re.M)
    return re.sub(r"/\*.*?\*/", "", b, flags=re.S)

HELPERS = re.compile(r"(?<![\w.])(assert\w+|when|verify|never|times|lenient|mock|spy|any|anyString"
                     r"|anyCollection|anyLong|anyInt|eq|argThat|doReturn|doThrow|doNothing)\s*\(")
METHDEF = re.compile(r"(?<![\w.])(\w+)\s*\([^)]*\)\s*\{")
TARGET  = re.compile(r"(?:Modify|Create|Test):\s*`[^`]*?(\w+)\.java`|`(\w+)\.java`"
                     r"|(?:Add|add)(?:\s+\w+){0,4}\s+to\s+`(\w+)`|In\s+`(\w+)`|\*\*`(\w+)`")

src = open(sys.argv[1]).read()

# plan-declared types: top-level ones are same-package siblings, nested ones are NOT
plan_pkg, plan_nested = collections.defaultdict(set), set()
for m in re.finditer(r"```java\n(.*?)```", src, re.S):
    b = m.group(1)
    pm = re.search(r"^package ([\w.]+);", b, re.M)
    if pm:
        top, nest = declarations(b)
        for t in top:
            plan_pkg[t].add(pm.group(1))
        plan_nested |= nest

sibling = collections.defaultdict(set)
for d in (repo_pkg, plan_pkg):
    for k, v in d.items():
        sibling[k] |= v

# ---------------------------------------------------------------- walk blocks
files, cur, pos = {}, None, 0
bad_type, bad_static, imports_seen = [], [], {}

for m in re.finditer(r"```java\n(.*?)```", src, re.S):
    prose, pos = src[pos:m.start()], m.end()
    for tm in TARGET.finditer(prose):
        n = next((g for g in tm.groups() if g), None)
        if n in files:
            cur = n
        elif n in repo_path:                      # a file the plan MODIFIES: load its real imports
            t = open(repo_path[n], errors="ignore").read()
            files[n] = dict(pkg=re.search(r"^package ([\w.]+);", t, re.M).group(1),
                            imports=set(re.findall(r"^import (?:static )?[\w.]*?\.(\w+);", t, re.M)),
                            declared=set(re.findall(r"\b(?:class|interface|enum|record)\s+(\w+)", t)),
                            statics=set(re.findall(r"^import static [\w.]+\.(\w+);", t, re.M)))
            cur = n

    b = m.group(1)
    line0 = src[:m.start()].count("\n") + 2
    pkgm = re.search(r"^package ([\w.]+);", b, re.M)
    imps = set(re.findall(r"^import (?:static )?[\w.]*?\.(\w+);", b, re.M))
    stat = set(re.findall(r"^import static [\w.]+\.(\w+);", b, re.M))
    top, nest = declarations(b)
    owner_pkg = pkgm.group(1) if pkgm else (files.get(cur, {}).get("pkg") or "")
    for st, fq in re.findall(r"^import (static )?([\w.]+);", b, re.M):
        imports_seen.setdefault((fq, owner_pkg), (line0, bool(st)))

    if pkgm:
        cm = re.search(r"(?:public )?(?:final |abstract )?(?:class|interface|enum|record)\s+(\w+)", b)
        if not cm:
            continue
        cur = cm.group(1)
        files[cur] = dict(pkg=pkgm.group(1), imports=set(imps),
                          declared=top | nest, statics=set(stat))
    else:
        if cur is None or cur not in files:
            continue
        files[cur]["imports"] |= imps
        files[cur]["declared"] |= top | nest
        files[cur]["statics"]  |= stat

    f = files[cur]
    body = strip(b)
    used = set(re.findall(r"(?<![\w.])([A-Z][A-Za-z0-9_]*)\b", body))
    miss = []
    for t in sorted(used):
        if t in f["imports"] or t in f["declared"] or t in JAVA_LANG or t in GENERIC or t.isupper():
            continue
        if f["pkg"] in sibling.get(t, ()):          # top-level same-package sibling
            continue
        if t in plan_nested:                        # nested type used bare -> javac needs Outer.Nested
            miss.append(t + " (nested: needs Outer.Nested or an explicit import)")
            continue
        miss.append(t)
    if miss:
        bad_type.append((line0, cur, "file" if pkgm else "frag", miss))

    f["statics"] |= {x.group(1) for x in METHDEF.finditer(body)}   # methods the file declares itself
    missh = sorted(h for h in {x.group(1) for x in HELPERS.finditer(body)}
                   if h not in f["statics"] and h not in f["declared"])
    if missh:
        bad_static.append((line0, cur, missh))

# ---------------------------------------------------------------- FQN + member existence, via javap
# Classpaths are the REAL per-module testCompileClasspath, captured from Gradle into
# .module-classpaths.txt (regenerate with the printTestCp init script). Resolving against the whole
# Gradle cache was unsound: it accepted classes from jars no module actually depends on
# (e.g. yaci-store, which only the out-of-tree ledger-follower app declares).
java_home = subprocess.run(["/usr/libexec/java_home", "-v", "21"],
                           capture_output=True, text=True).stdout.strip()
javap = os.path.join(java_home, "bin", "javap") if java_home else "javap"

# document_vault does not exist yet: its dependency block is read out of the plan itself
dv_block = re.search(r"Create `document_vault/build\.gradle\.kts`.*?```kotlin\n(.*?)```", src, re.S)

CPFILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".module-classpaths.txt")
module_cp, stamped = {}, None
if os.path.exists(CPFILE):
    for line in open(CPFILE):
        if line.startswith("CPOUT|"):
            _, proj, paths = line.strip().split("|", 2)
            module_cp[proj] = paths
        elif line.startswith("BUILDHASH|"):
            stamped = line.strip().split("|", 1)[1]
if not module_cp:
    sys.exit("no .module-classpaths.txt — regenerate it before trusting this sweep")

# A captured classpath that no longer matches the build files is worse than none: it would report
# clean against a world that no longer exists. Refuse rather than reassure.
import hashlib, glob
_h = hashlib.sha256()
for _f in sorted(glob.glob("*/build.gradle.kts") + ["build.gradle.kts", "settings.gradle.kts", "gradle.properties"]):
    if os.path.exists(_f):
        _h.update(_f.encode()); _h.update(open(_f, "rb").read())
if stamped != _h.hexdigest()[:16]:
    sys.exit("STALE .module-classpaths.txt: build files changed since capture (%s != %s).\n"
             "Regenerate with the printTestCp init script before trusting this sweep."
             % (stamped, _h.hexdigest()[:16]))

# document_vault does not exist yet; its build.gradle.kts mirrors funding's, so funding's classpath
# is the honest proxy for it (plus whatever deps the plan itself adds, handled below).
def module_of(pkg):
    if ".blockchain_publisher" in pkg: return ":blockchain_publisher"
    if ".blockchain_common"    in pkg: return ":blockchain_common"
    if ".support"              in pkg: return ":support"
    return ":funding"

plan_pkgs = {p for ps in plan_pkg.values() for p in ps} | {
    "org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents",
    "org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document",
    "org.cardanofoundation.lob.app.blockchain_common.service",
}
repo_pkgs = {p for ps in repo_pkg.values() for p in ps}

# Dependencies the PLAN introduces (e.g. archunit) are RESOLVED, not skipped: skipping a whole
# namespace meant a typo under it — com.tngtech.archunit.nope.DoesNotExist — sailed through.
DEPFILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".plan-new-deps-cp.txt")
plan_dep_cp = open(DEPFILE).read().strip() if os.path.exists(DEPFILE) else ""

# ...but only for the modules whose build files ACTUALLY add them. Appending the new-dep classpath to
# every module made ArchUnit resolve in :support, which never declares it — a plan-added dependency is
# not a global one.
PLAN_DEP_MODULES = set()
for coord in re.findall(r'(?:test)?[iI]mplementation\("([\w.\-]+:[\w.\-]+):[\w.\-]+"\)', src):
    if dv_block and coord in dv_block.group(1):
        PLAN_DEP_MODULES.add(":funding")            # :funding stands in for document_vault
    for mod in re.findall(r'`?([\w_]+)/build\.gradle\.kts`?[^\n]*' + re.escape(coord), src):
        PLAN_DEP_MODULES.add(":" + mod)

# ---- module reachability: existence in the repo is NOT reachability from the importing module.
# A module may only import from itself and the projects its build file declares.
pkg_module = {}
for root, _, fs in os.walk("."):
    if "/build/" in root or "/.git" in root or "/_backend-services" in root:
        continue
    for fn in fs:
        if not fn.endswith(".java"):
            continue
        t = open(os.path.join(root, fn), errors="ignore").read()
        m = re.search(r"^package ([\w.]+);", t, re.M)
        if m:
            mod = root.lstrip("./").split("/")[0]
            pkg_module[m.group(1)] = mod

def project_deps(build_text):
    return set(re.findall(r'project\("\:([\w_]+)"\)', build_text))

module_deps = {}
for d in os.listdir("."):
    bf = os.path.join(d, "build.gradle.kts")
    if os.path.isdir(d) and os.path.exists(bf):
        module_deps[d] = project_deps(open(bf).read())
module_deps["document_vault"] = project_deps(dv_block.group(1)) if dv_block else set()
# the plan also adds document_vault to blockchain_publisher
if re.search(r'blockchain_publisher/build\.gradle\.kts.*?project\("\:document_vault"\)', src, re.S):
    module_deps.setdefault("blockchain_publisher", set()).add("document_vault")

by_module, bad_fqn, bad_member, shadow, unreachable = collections.defaultdict(list), [], [], [], []
for (fq, owner_pkg), (line, is_static) in imports_seen.items():
    pkg, simple = fq.rsplit(".", 1)
    if pkg in plan_pkgs or pkg in repo_pkgs:
        # internal — but is the owning module actually on this module's compile classpath?
        src_mod = pkg_module.get(owner_pkg) or ("document_vault" if ".document_vault" in owner_pkg else None)
        tgt_mod = pkg_module.get(pkg)     or ("document_vault" if ".document_vault" in pkg      else None)
        if src_mod and tgt_mod and src_mod != tgt_mod \
           and tgt_mod not in module_deps.get(src_mod, set()):
            unreachable.append((line, fq, src_mod, tgt_mod))
        continue
    by_module[module_of(owner_pkg)].append((fq, pkg, simple, line, is_static))

for mod, items in by_module.items():
    cp = module_cp.get(mod)
    if not cp:
        continue
    if plan_dep_cp and mod in PLAN_DEP_MODULES:
        cp = cp + ":" + plan_dep_cp   # deps the plan adds, only where its build files add them
    # a static import names a MEMBER: check the owning type exists AND actually declares it
    probe = sorted({(fq.rsplit(".", 1)[0] if st else fq) for fq, _, _, _, st in items})
    r = subprocess.run([javap, "-cp", cp, "-p"] + probe, capture_output=True, text=True)
    out = r.stdout + r.stderr          # javap reports "class not found" on stderr
    missing = set(re.findall(r"class not found: ([\w.$]+)", out))
    # map each resolved type -> the member names javap printed for it
    members, current = collections.defaultdict(set), None
    for ln in out.split("\n"):
        m = re.match(r"(?:public |private |protected |final |abstract |static )*"
                     r"(?:class|interface|enum|@interface) ([\w.$]+)", ln.strip())
        if m:
            current = m.group(1)
            continue
        if current:
            mm = re.search(r"(\w+)\s*\(", ln) or re.search(r"(\w+);\s*$", ln)
            if mm:
                members[current].add(mm.group(1))
    for fq, pkg, simple, line, st in items:
        owner = fq.rsplit(".", 1)[0] if st else fq
        if owner in missing:
            bad_fqn.append((line, fq))
        elif st and members.get(owner) and simple not in members[owner]:
            bad_member.append((line, fq, owner))

# shadowing: the repo consistently takes this simple name from another package
for (fq, _owner), (line, is_static) in imports_seen.items():
    pkg, simple = fq.rsplit(".", 1)
    if is_static or not simple[:1].isupper():
        continue
    known = {f.rsplit(".", 1)[0] for f in repo_fqns if f.rsplit(".", 1)[1] == simple}
    if known and pkg not in known and pkg not in plan_pkgs and pkg not in repo_pkgs:
        shadow.append((line, fq, sorted(known)[:2]))

# ---------------------------------------------------------------- report
for l, c, k, ms in bad_type:
    print("%-6s %-32s [%s] %s" % (l, c, k, ", ".join(ms)))
for l, fq in sorted(bad_fqn):
    print("%-6s BAD FQN (no such class on the classpath): %s" % (l, fq))
for l, fq, owner in sorted(bad_member):
    print("%-6s BAD STATIC MEMBER (%s declares no such member): %s" % (l, owner, fq))
for l, fq, sm, tm in sorted(unreachable):
    print("%-6s UNREACHABLE (%s does not declare project(\":%s\")): %s" % (l, sm, tm, fq))
for l, fq, k in sorted(shadow):
    print("%-6s SHADOWED (this repo imports %s from %s): %s" % (l, fq.rsplit('.', 1)[1], ", ".join(k), fq))
for l, c, hs in bad_static:
    print("%-6s %-32s [static] %s" % (l, c, ", ".join(hs)))

n = len(bad_type) + len(bad_fqn) + len(bad_member) + len(shadow) + len(bad_static) + len(unreachable)
print("\n%d unresolved type(s), %d bad FQN(s), %d bad static member(s), %d unreachable import(s), "
      "%d shadowed import(s), %d missing static import(s)"
      % (len(bad_type), len(bad_fqn), len(bad_member), len(unreachable), len(shadow), len(bad_static)))
sys.exit(1 if n else 0)

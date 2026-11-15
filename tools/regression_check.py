# -*- coding: utf-8 -*-
"""AiChat 界面文案回归检查：资源、引用、格式化、转义、硬编码、菜单判断。"""
import os, re, sys, glob, xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/mm"
RES = os.path.join(ROOT, "app/src/main/res")
JAVA = os.path.join(ROOT, "app/src/main/java")
VALUES = os.path.join(RES, "values")

issues = defaultdict(list)
def report(cat, msg): issues[cat].append(msg)

# ---------- 1. 所有 res XML 可解析 ----------
xml_files = glob.glob(os.path.join(RES, "**", "*.xml"), recursive=True)
bad_xml = []
for f in xml_files:
    try: ET.parse(f)
    except Exception as e: bad_xml.append((os.path.relpath(f, ROOT), str(e)))
print("1. XML 文件数:", len(xml_files), " 解析失败:", len(bad_xml))
for f, e in bad_xml: report("XML", f + " -> " + e)

# ---------- 2. 收集所有 string 定义 ----------
defs = {}          # name -> (value, formatted_attr, file)
dups = []
for f in glob.glob(os.path.join(VALUES, "*.xml")):
    try: tree = ET.parse(f)
    except Exception: continue
    for el in tree.getroot():
        if el.tag != "string" or "name" not in el.attrib: continue
        n = el.attrib["name"]
        v = "".join(el.itertext())
        fa = el.attrib.get("formatted", "true")
        if n in defs: dups.append((n, os.path.basename(defs[n][2]), os.path.basename(f)))
        defs[n] = (v, fa, f)
print("2. string 定义总数:", len(defs), " (strings_ui.xml):", sum(1 for x in defs.values() if x[2].endswith("strings_ui.xml")),
      " 重复键:", len(dups))
for d in dups: report("重复键", str(d))

# ---------- 3. 空值 / 仅占位符 / 转义 ----------
empty = placeholder_only = 0
esc_bad = []
pct_bad = []
for n, (v, fa, f) in defs.items():
    if not v.strip(): empty += 1; report("空值", n)
    if v.strip() and re.fullmatch(r"(?:\s|%\\d+\\$[sdf]|\\n)+", v.strip()):
        placeholder_only += 1; report("仅占位符", n + " = " + repr(v))
    # 孤立反斜杠：末尾的 \ ，或后面不是合法转义字符
    LEGAL = set('ntr"\'\\@?$u')
    bad = False
    i = 0
    while i < len(v):
        if v[i] == "\\":
            if i + 1 >= len(v): bad = True; break
            if v[i+1] not in LEGAL: bad = True; break
            i += 2
        else: i += 1
    if bad: esc_bad.append((n, "孤立/非法转义 " + repr(v)))
    # 双重转义：两个及以上反斜杠后紧跟 n 或 " （会丢换行/引号）
    if re.search(r"\\\\+[\"n]", v): esc_bad.append((n, "双重转义 " + repr(v)))
    # 字面 % 且带位置参数但没 formatted=false
    has_pos = bool(re.search(r"%\d+\$[sdf]", v))
    literal_pct = bool(re.search(r"%(?!\d+\$[sdf])", v))
    if literal_pct and fa != "false": pct_bad.append((n, repr(v)))
print("3. 空值:", empty, " 仅占位符:", placeholder_only,
      " 转义异常:", len(esc_bad), " 字面%未关格式化:", len(pct_bad))
for n, v in esc_bad: report("转义", n + " " + v)
for n, v in pct_bad: report("字面%", n + " " + v)

# ---------- 4. 引用是否有定义 ----------
refs = set()
for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
    s = open(f, encoding="utf-8", errors="ignore").read()
    refs.update(re.findall(r"R\.string\.(\w+)", s))
for f in xml_files:
    s = open(f, encoding="utf-8", errors="ignore").read()
    refs.update(re.findall(r"@string/(\w+)", s))
missing = sorted(r for r in refs if r not in defs)
print("4. 被引用的 string 键:", len(refs), " 缺失定义:", len(missing))
for m in missing: report("缺失引用", m)

# ---------- 5. 带参数调用 vs 占位符数量 ----------
def parse_calls(s):
    """找出 Strings.get(R.string.X, args...) / getString(R.string.X, args...)，返回 (key, argc)"""
    out = []
    for m in re.finditer(r"(?:Strings\.get|getString|getText)\s*\(", s):
        p = m.end(); depth = 1; i = p; args = []; cur = ""
        while i < len(s) and depth:
            c = s[i]
            if c == "(": depth += 1; cur += c
            elif c == ")":
                depth -= 1
                if depth == 0: args.append(cur.strip()); break
                cur += c
            elif c == "," and depth == 1: args.append(cur.strip()); cur = ""
            elif c == '"':
                j = i + 1
                while j < len(s) and (s[j] != '"' or s[j-1] == "\\"): j += 1
                cur += s[i:j+1]; i = j
            else: cur += c
            i += 1
        if not args: continue
        km = re.search(r"R\.string\.(\w+)", args[0])
        if km: out.append((km.group(1), len(args) - 1))
    return out

calls = []
for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
    calls += [(k, a, os.path.basename(f)) for k, a in parse_calls(open(f, encoding="utf-8", errors="ignore").read())]
with_args = [c for c in calls if c[1] > 0]
mismatch = []
for k, a, f in with_args:
    if k not in defs: continue
    v = defs[k][0]
    n = len(re.findall(r"%\d+\$[sdf]", v))
    if n != a: mismatch.append((k, a, n, f))
print("5. 带参数调用:", len(with_args), " 参数数量不匹配:", len(mismatch))
for k, a, n, f in mismatch: report("参数不匹配", "%s(%s) 传 %d 实残，资源 %d 占位符" % (k, f, a, n))

# 同键混用（带参 / 不带参）
by_key = defaultdict(set)
for k, a, f in calls: by_key[k].add(a)
mixed = {k: sorted(v) for k, v in by_key.items() if 0 in v and any(x > 0 for x in v)}
print("   同键混用(0参/带参):", len(mixed))
for k, v in mixed.items(): report("同键混用", k + " " + str(v))

# ---------- 6. 运行时中文字符串字面量（先剥离注释，避免把文档注释算进来） ----------
def strip_comments(s):
    out = []; i = 0; n = len(s); mode = 'code'
    while i < n:
        if mode == 'code':
            if s.startswith('/*', i): mode = 'block'; i += 2; continue
            if s.startswith('//', i): mode = 'line'; i += 2; continue
            if s[i] == '"': mode = 'str'; out.append('"'); i += 1; continue
            out.append(s[i]); i += 1
        elif mode == 'block':
            if s.startswith('*/', i): mode = 'code'; out.append('  '); i += 2; continue
            out.append('\n' if s[i] == '\n' else ' '); i += 1
        elif mode == 'line':
            if s[i] == '\n': mode = 'code'; out.append('\n'); i += 1; continue
            out.append(' '); i += 1
        else:  # 字符串内部
            if s[i] == '\\': out.append(s[i:i+2]); i += 2; continue
            if s[i] == '"': mode = 'code'; out.append('"'); i += 1; continue
            out.append(s[i]); i += 1
    return ''.join(out)

hard = []
for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
    s = strip_comments(open(f, encoding="utf-8", errors="ignore").read())
    for i, line in enumerate(s.splitlines(), 1):
        for m in re.finditer(r'"([^"\\]*(?:\\.[^"\\]*)*)"', line):
            if re.search(r"[\u4e00-\u9fff]", m.group(1)):
                hard.append((os.path.basename(f), i, m.group(1)[:60]))
print("6. 运行时中文字符串字面量(剥离注释):", len(hard),
      " -> 按 §11.9 均为工具/提示词/协议标记/内部日志例外")

# ---------- 7. 菜单判断是否按下标 ----------
for fn, pat in [("StorageAttachmentsActivity.java", r"which\s*==\s*\d"),
                ("UpdateActivity.java", r"\bw\s*==\s*\d")]:
    p = os.path.join(JAVA, "xyz/zip8919/app/aichat", fn)
    s = open(p, encoding="utf-8", errors="ignore").read() if os.path.exists(p) else ""
    print("7. %s 下标判断命中: %d 处, 依赖显示文案的 startsWith/equals: %d 处" %
          (fn, len(re.findall(pat, s)), len(re.findall(r"(?:label|item|title)\.(?:startsWith|equals)", s))))

# ---------- 8. 初始化顺序 ----------
app = os.path.join(JAVA, "xyz/zip8919/app/aichat/AcApp.java")
s = open(app, encoding="utf-8", errors="ignore").read() if os.path.exists(app) else ""
i1, i2 = s.find("Strings.init"), s.find("CrashCatcher.install")
print("8. AcApp: Strings.init@%d, CrashCatcher.install@%d -> 顺序正确: %s" % (i1, i2, 0 <= i1 < i2))

print()
print("========== 汇总 ==========")
total = sum(len(v) for v in issues.values())
print("问题总数:", total)
for cat in sorted(issues):
    print(" -", cat, len(issues[cat]))
    for x in issues[cat][:8]: print("     ", x)

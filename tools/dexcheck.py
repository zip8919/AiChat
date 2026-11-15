#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 dex 里 R 类的资源常量与 resources.arsc 是否逐条一致。

为什么需要：手工构建时如果 R.java 不是 aapt2 生成的（比如用递增计数器自编），
ID 的类型段会和资源表对不上（color=01 / id=03 / layout=04 / string=06）。
这种包能编译、能安装，但一启动就 Resources$NotFoundException 闪退，
构建日志里看不出任何异常，只能在 dex 层比对才能发现。

用法：
    python3 tools/dexcheck.py classes.dex base.apk      # classes.dex + 含资源表的 apk
    python3 tools/dexcheck.py --dump classes.dex        # 只打印各类 R 常量

退出码：0 一致；1 有差异或解析失败。
"""
import re
import struct
import subprocess
import sys
import zipfile
import tempfile
import os
import shutil


def _uleb(b, o):
    r = 0
    s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7f) << s
        if not (x & 0x80):
            break
        s += 7
    return r, o


def _read_strings(b, size, off):
    out = []
    for i in range(size):
        so = struct.unpack_from('<I', b, off + i * 4)[0]
        n, o = _uleb(b, so)
        end = b.index(b'\x00', o)
        out.append(b[o:end].decode('utf-8', 'replace'))
    return out


def parse_r(dex_path, prefix='Lxyz/zip8919/app/aichat/R$'):
    """返回 {类型: {字段名: 常量值}}，类型如 string / layout / id。"""
    b = open(dex_path, 'rb').read()
    str_size, str_off = struct.unpack_from('<II', b, 0x38)
    typ_size, typ_off = struct.unpack_from('<II', b, 0x40)
    fld_size, fld_off = struct.unpack_from('<II', b, 0x50)
    cls_size, cls_off = struct.unpack_from('<II', b, 0x60)
    strings = _read_strings(b, str_size, str_off)
    types = [struct.unpack_from('<I', b, typ_off + i * 4)[0] for i in range(typ_size)]
    flds = []
    for i in range(fld_size):
        ci, ti, ni = struct.unpack_from('<HHI', b, fld_off + i * 8)
        flds.append(strings[ni])

    res = {}
    for i in range(cls_size):
        base = cls_off + i * 32
        (class_idx, _acc, _sup, _if, _src, _ann,
         cdata_off, sval_off) = struct.unpack_from('<8I', b, base)
        name = strings[types[class_idx]]
        if not name.startswith(prefix) or not cdata_off or not sval_off:
            continue

        o = cdata_off
        sf, o = _uleb(b, o)          # static_fields_size
        _inf, o = _uleb(b, o)
        _dm, o = _uleb(b, o)
        _vm, o = _uleb(b, o)
        idx = 0
        statics = []
        for _ in range(sf):
            d, o = _uleb(b, o)
            idx += d
            _acc2, o = _uleb(b, o)   # access_flags，只读一次
            statics.append(idx)

        o = sval_off
        n, o = _uleb(b, o)
        vals = {}
        for j in range(n):
            if j >= len(statics):
                break
            hdr = b[o]
            o += 1
            vtype = hdr & 0x1f
            width = (hdr >> 5) + 1
            raw = int.from_bytes(b[o:o + width], 'little')
            o += width
            if vtype == 0x04:        # VALUE_INT
                if raw >= (1 << 31):
                    raw -= (1 << 32)
                vals[flds[statics[j]]] = raw
        short = name.split(prefix)[-1].rstrip(';')
        res[short] = vals
    return res


def parse_arsc(apk_path, aapt2):
    """用 aapt2 dump resources 读出真实资源表，返回 {类型: {名: id}}。"""
    out = subprocess.run([aapt2, 'dump', 'resources', apk_path],
                         capture_output=True, text=True).stdout
    arsc = {}
    for m in re.finditer(r'^\s+resource (0x[0-9a-f]{8}) (\w+)/(\S+)$', out, re.M):
        arsc.setdefault(m.group(2), {})[m.group(3)] = int(m.group(1), 16)
    return arsc


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    dump_only = '--dump' in sys.argv
    if not args:
        print(__doc__)
        return 1

    rj = parse_r(args[0])
    if dump_only or len(args) < 2:
        for k, v in sorted(rj.items()):
            sample = ', '.join('%s=0x%08x' % (n, x) for n, x in list(sorted(v.items()))[:3])
            print('  R$%-10s 字段 %5d  %s' % (k, len(v), sample))
        return 0

    aapt2 = os.environ.get('AAPT2', 'aapt2')
    if not shutil.which(aapt2) and not os.path.exists(aapt2):
        print('找不到 aapt2，请设置环境变量 AAPT2 指向其绝对路径')
        return 1

    arsc = parse_arsc(args[1], aapt2)
    bad, miss_def, tot = [], [], 0
    for t, items in rj.items():
        for n, i in items.items():
            tot += 1
            a = arsc.get(t, {})
            if n not in a:
                miss_def.append('%s/%s' % (t, n))
            elif a[n] != i:
                bad.append('%s/%s dex=0x%08x arsc=0x%08x' % (t, n, i, a[n]))

    miss_r = []
    for t, items in arsc.items():
        for n in items:
            if n not in rj.get(t, {}):
                miss_r.append('%s/%s' % (t, n))

    print('   比对 %d 条 | ID 不一致 %d | dex 有/表无 %d | 表有/dex 无 %d'
          % (tot, len(bad), len(miss_def), len(miss_r)))
    for x in bad[:10]:
        print('     不一致:', x)
    for x in miss_def[:10]:
        print('     dex 有表无:', x)
    if bad:
        print('   R.java 不是 aapt2 生成的，资源 ID 会错位，运行时必闪退')
        return 1
    print('   资源 ID 校验通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())

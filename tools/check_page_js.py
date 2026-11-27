"""把 RendererAssets.JS 还原成浏览器实际执行的脚本并做语法自检。

Java 字符串里 \" 编译后是 "、\\ 编译后是 \，校验前必须按编译语义折叠一次，
否则得到的是源码形态而不是浏览器形态。

用法: python3 tools/check_page_js.py [输出文件]
"""
import sys

BS = chr(92)
QUOTE = chr(34)
SLASH_QUOTE = BS + QUOTE
TWO_BS = BS + BS

SRC = 'app/src/main/java/xyz/zip8919/app/aichat/RendererAssets.java'
src = open(SRC, encoding='utf-8').read()
# JS 现在拆成 JS_PART_1/JS_PART_2 两段（见 jsPack），拼接后在最后一行：
#   static final String JS = jsPack(JS_PART_1, JS_PART_2);
# 早先这里找的是 'static final String JS ='，改成分段后整段变成空串，
# 校验永远「通过」——等于没校验。改为从第一段开头一直读到 JS 那一行。
try:
    start = src.index('static final String JS_PART_1 =')
except ValueError:
    start = src.index('static final String JS =')
# 语句之间还会夹着下一个 private static final String JS_PART_2 = 这样的声明行，
# 它既不是字符串行也不是「读完了」——continue 继续扫，否则只还原出第一段。
parts = []
for ln in src[start:].split('\n')[1:]:
    t = ln.strip()
    if not t or t.startswith('//'):
        continue
    if t[0] != QUOTE:
        if 'static final String' in t:
            continue
        break
    i = 0
    buf = []
    while i < len(t):
        c = t[i]
        if c in ' \t':
            i += 1
            continue
        if c in '+;':
            i += 1
            continue
        if t.startswith('//', i):
            break
        i += 1
        while i < len(t) and t[i] != QUOTE:
            if t[i] == BS:
                buf.append(t[i] + t[i + 1])
                i += 2
                continue
            buf.append(t[i])
            i += 1
        i += 1
    parts.append(''.join(buf))

js = ''.join(parts)
# Java 编译语义：\\ -> \，\" -> "
# Java 编译器语义：单遍解码所有转义（\n -> 换行、\" -> "、\\ -> \ …）。
# 解码结果就是浏览器实际执行的文本；少折叠任何一种都会漏掉非法转义。
out_chars = []
j = 0
while j < len(js):
    if js[j] == BS and j + 1 < len(js):
        n = js[j + 1]
        if n == 'n':
            out_chars.append(chr(10))
        elif n == 't':
            out_chars.append(chr(9))
        elif n == 'r':
            out_chars.append(chr(13))
        elif n in ('"', "'", BS):
            out_chars.append(n)
        else:
            out_chars.append(BS + n)
        j += 2
    else:
        out_chars.append(js[j])
        j += 1
js = ''.join(out_chars)

out = sys.argv[1] if len(sys.argv) > 1 else '/tmp/page_check.js'
open(out, 'w', encoding='utf-8').write(js)
print('还原 %d 字符 -> %s' % (len(js), out))

bad = js.count(SLASH_QUOTE)
if bad:
    print('警告: 有 %d 处反斜杠引号，单引号字符串里属于非法转义，浏览器会整段不执行' % bad)

package xyz.zip8919.app.aichat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话对图片附件的引用索引。
 *
 * <p>判断一张图「还有没有用」只有一条依据：有没有任何一条消息还引用它。
 * 附件文件按内容哈希命名、被消息以路径引用，删会话只删 JSON，所以没有这张索引
 * 就只能按时间猜——按时间猜必然删掉老会话里还在用的图。
 *
 * <p><b>为什么不解析 JSON</b>：会话文件里 {@code apiText} 是整段请求文本，
 * 单文件可达数 MB，且 {@code JSONObject.toString()} 不换行——一个 10 MB 的文件
 * 就是一行。{@code readLine()} 会把它整行读进堆，{@code JSONObject} 再解析成对象树
 * 又要翻几倍内存，128 MB 堆的机器上必 OOM。这里改为定长分块读 + 正则，
 * 内存占用与文件大小无关。
 *
 * <p><b>为什么按文件名而不是路径</b>：老会话存的是发送当时的绝对路径。
 * 用户换过存储位置（外部 → 内部回退）之后，那些路径全部失效，
 * 按路径比对会把所有在用图片判成孤儿——这是能删掉用户数据的 bug。
 */
final class StorageRefs {

    /**
     * 只认 attachments 目录下、带扩展名的文件名，不认字段名、不认是否被转义。
     *
     * <p><b>为什么不再绑 {@code "path"} 字段</b>：索引的全部用途是回答
     * 「这个文件还有没有人引用」。绑定字段名等于把判定结果押在序列化格式上——
     * 字段一旦改名（{@code path} / {@code p} / {@code file}）、被包进转义字符串
     * （{@code apiText} 里的 {@code \"path\"}）、或者路径前面多了前缀，
     * 整批图片就会同时被判成「无引用」，一键清理就把用户的历史图片删光。
     * 这类漏判的代价是数据丢失，误判的代价只是「该删的图多留一张」，两者不对等。
     *
     * <p>放宽后误命中的来源只剩「正文里恰好写了 attachments/xxx.jpg」这种文本，
     * 而图片本来就不进请求体（见 {@link AttachmentText#KIND_IMAGE}），
     * 正文里不会出现自己的路径，实际噪音可以忽略。
     */
    private static final Pattern ATTACH_PATH = Pattern.compile(
            "attachments\\\\?/([A-Za-z0-9_-]{1,96}\\.[A-Za-z0-9]{1,8})");

    /** 分块大小。8K 字符约 16 KB 内存，对 API 18 的机器足够温和。 */
    private static final int CHUNK = 8192;

    /**
     * 跨块残留长度。必须大于 pattern 可能的最大匹配长度，
     * 否则一个正好跨越块边界的 path 会被切成两半、两边都匹配不上。
     * 路径前缀 + 40 位 SHA-1 + 扩展名，实际不超过 200 字符。
     */
    private static final int CARRY = 512;

    private final Map<String, Integer> refs = new HashMap<String, Integer>();

    /** 有会话没能读完：此时索引不可信，禁止据此删除任何东西。 */
    boolean incomplete;

    /** 读过的会话文件数，用于「N 个会话无法读取」的提示。 */
    int unreadable;

    /**
     * 成功扫过的会话文件数。
     *
     * <p>用来区分「索引为空是因为会话全删了」（正常，图片可以清）与
     * 「索引为空是因为一个会话都没读到」（可疑，禁止按无引用删除）。
     */
    int scanned;

    /** 命中次数，用于判断「索引为空到底是真没引用，还是根本没对上格式」。 */
    int matched;

    /** 第一个命中的文件名。出问题时抓日志能立刻看出路径长什么样。 */
    String sample;

    void addPath(String path) {
        String name = fileNameOf(path);
        if (name.length() == 0) return;
        Integer old = refs.get(name);
        refs.put(name, old == null ? 1 : old + 1);
        matched++;
        if (sample == null) sample = name;
    }

    int count(String fileName) {
        Integer n = refs.get(fileName);
        return n == null ? 0 : n;
    }

    boolean has(String fileName) {
        return refs.containsKey(fileName);
    }

    int size() {
        return refs.size();
    }

    /** 引用到的文件名集合。调用方只读，返回副本避免外部改到索引。 */
    java.util.Set<String> names() {
        return new java.util.HashSet<String>(refs.keySet());
    }

    /** 把一个会话文件读进索引。失败时置 {@link #incomplete}，绝不让上层误以为「没有引用」。 */
    void scanConversation(File file) {
        BufferedReader reader = null;
        try {
            scanned++;
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), "UTF-8"), CHUNK);
            char[] buf = new char[CHUNK];
            StringBuilder carry = new StringBuilder(CARRY);
            // 每块的拼装缓冲复用：原来每块都要 new String(8K) 再 carry + 拼一次，
            // 扫 20MB 会话就是 2500 块 × 约 17KB 字符 ≈ 40MB 临时垃圾，在 128MB 堆的
            // 老机上表现为「存储页转圈时周期性地卡」。Matcher 接受任意 CharSequence，
            // 不需要先转成 String。
            StringBuilder chunk = new StringBuilder(CHUNK + CARRY);
            int n;
            while ((n = reader.read(buf, 0, CHUNK)) > 0) {
                int processed = carry.length();
                chunk.setLength(0);
                chunk.append(carry).append(buf, 0, n);
                Matcher m = ATTACH_PATH.matcher(chunk);
                while (m.find()) {
                    // 只接受「结束位置在本次新增区域」的匹配：
                    // 完全落在 carry 里的匹配上一轮已经算过，重复计数会让引用数虚高；
                    // 跨块被截断的匹配这一轮才完整，end 必然大于 carry 长度，不会漏。
                    if (m.end() > processed) {
                        addPath(m.group(1));
                    }
                }
                int keep = Math.min(CARRY, chunk.length());
                carry.setLength(0);
                carry.append(chunk, chunk.length() - keep, chunk.length());
            }
        } catch (IOException e) {
            incomplete = true;
            unreadable++;
            LogUtil.w("StorageRefs", "scan failed: %s : %s", file.getName(), e.getMessage());
        } catch (Throwable t) {
            // 编码异常、超大文件被系统截断等：同样按「不可信」处理
            incomplete = true;
            unreadable++;
            LogUtil.w("StorageRefs", "scan threw: %s : %s", file.getName(), t.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** 从绝对路径取文件名。路径分隔符两种都处理，避免个别 ROM 给出反斜杠。 */
    static String fileNameOf(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.trim();
    }

    /**
     * 扫整个会话目录。
     *
     * <p>目录列不出来（外部存储被拔、权限异常）时直接标 {@code incomplete}：
     * 否则索引是空的，所有图片都会被判成「无引用」，一键清理等于把图库删光。
     * 目录存在但为空是正常状态（新装用户），不标。
     */
    static StorageRefs scan(File convDir) {
        StorageRefs refs = new StorageRefs();
        if (convDir == null) {
            refs.incomplete = true;
            return refs;
        }
        File[] files = convDir.listFiles();
        if (files == null) {
            refs.incomplete = true;
            LogUtil.w("StorageRefs", "conversation dir unlistable: %s", convDir.getAbsolutePath());
            return refs;
        }
        return scanListed(files);
    }

    /**
     * 用「已经列好的目录项」建索引。
     *
     * <p>调用方（如 {@link StorageScanner}）往往已经为统计体积列过一次目录。
     * 再走 {@link #scan(File)} 会重复一次 {@code listFiles()}——在外部存储上
     * 这一次是每个目录项一次 stat，几百个会话就是几百次系统调用，纯浪费。
     */
    static StorageRefs scanListed(File[] files) {
        StorageRefs refs = new StorageRefs();
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isFile() && f.getName().endsWith(".json")) {
                refs.scanConversation(f);
            }
        }
        return refs;
    }
}

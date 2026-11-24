package xyz.zip8919.app.aichat;

/**
 * WebView 页面的内联样式与脚本。
 *
 * <p>纯资源，没有任何逻辑。拆出来只是为了让 {@link MessageHtmlRenderer} 里
 * 真正干活的代码不被 500 行字符串淹没——原来这个文件 2000 行，一半是 CSS 和 JS。
 */
final class RendererAssets {

    private RendererAssets() {
    }


    /** 把脚本里的文案占位符换成资源里的文字。页面每次重灌都要调用。 */
    static String localize(String js) {
        return js
                .replace("@@L_COPY@@", Strings.get(R.string.renderer_001))
                .replace("@@L_COPIED@@", Strings.get(R.string.renderer_002))
                .replace("@@L_SAVE@@", Strings.get(R.string.renderer_003))
                .replace("@@L_IMPORT@@", Strings.get(R.string.renderer_004))
                .replace("@@L_IMPORTED@@", Strings.get(R.string.renderer_005))
                .replace("@@L_RUN@@", Strings.get(R.string.renderer_006))
                .replace("@@L_PREVIEW@@", Strings.get(R.string.renderer_007))
                .replace("@@L_FOLD@@", Strings.get(R.string.renderer_008))
                .replace("@@L_EXPAND@@", Strings.get(R.string.renderer_009))
                .replace("@@L_TAP@@", Strings.get(R.string.renderer_010))
                .replace("@@L_FOLD_EARLIER@@", Strings.get(R.string.renderer_011))
                .replace("@@L_EARLIER_TOOLS@@", Strings.get(R.string.renderer_012))
                .replace("@@L_SCROLL_EARLIER@@", Strings.get(R.string.renderer_013))
                .replace("@@L_EARLIEST@@", Strings.get(R.string.renderer_014))
                .replace("@@L_RETRY@@", Strings.get(R.string.renderer_015))
                .replace("@@L_RETRY_IN@@", Strings.get(R.string.renderer_016))
                .replace("@@L_429_PREFIX@@", Strings.get(R.string.renderer_017))
                .replace("@@L_429_DEFAULT@@", Strings.get(R.string.renderer_018))
                .replace("@@L_RETRYING@@", Strings.get(R.string.renderer_019))
                .replace("@@L_SHOW_ALL@@", Strings.get(R.string.renderer_020))
                .replace("@@L_THINKING@@", Strings.get(R.string.renderer_021))
                .replace("@@L_FOLD_N@@", Strings.get(R.string.renderer_022));
    }

    /**
     * 页面脚本：思考块之后出现多少个正文字符才算「思考结束」（80）。
     *
     * <p>取 80 的原因：一段回答的开场白一般一两行就到这个量，读到它用户已经在
     * 看正文了；再小会退化成「刚闭包就折叠」，再大则思考块一直撑开着挤占屏幕。
     * 必须作为数字拼进脚本——Chromium 30 的字符串拼接不支持把 Java 常量塞进
     * 跨字符串表达式的中间。
     */
    private static final int THINK_SETTLE_CHARS = 80;

    /** 气泡、工具卡片、代码块、表格、错误卡片等全部样式。 */
    static final String CSS =
            // 通配符只把颜色钉成「继承」：逐字渐显靠「浅灰 → 正文色」的过渡，
            // 主题层又要按 token 改正文颜色，两者都要求颜色能一路继承下去；
            // 有通配符颜色时，子元素永远拿到通配符那一档，继承链就断了。
            "*{margin:0;padding:0;box-sizing:border-box;color:inherit;}" +
            "body{font-family:Roboto,sans-serif;font-size:14px;color:#1a1a1a;" +
            "line-height:1.6;background:#fff;padding:8px;word-wrap:break-word;" +
            "-webkit-text-size-adjust:100%;}" +
            ".msg{margin:8px 0;}" +
            ".msg.user{text-align:right;}" +
            ".msg.user .bubble{white-space:pre-wrap;word-wrap:break-word;" +
            "display:inline-block;background:#E3F2FD;" +
            "padding:8px 12px;border-radius:12px;max-width:85%;text-align:left;}" +
            ".msg.ai{border-top:1px solid #e0e0e0;padding-top:12px;margin-top:8px;}" +
            // overflow:hidden 只裁不滚：内容的真实宽度仍参与外层布局，
            // 一旦里面出现超宽元素（长代码行、宽表格），body 的排版宽度会被顶大，
            // 页面上就出现「代码块溢出到屏幕外」。overflow-x:hidden 同理成立，
            // 真正的横向滚动交给 pre 与 .table-scroll 自己。
            ".msg.ai .content{max-width:100%;}" +
            "h1,h2,h3,h4,h5,h6{margin:12px 0 6px;font-weight:bold;line-height:1.3;}" +
            "h1{font-size:1.6em;border-bottom:2px solid #eee;padding-bottom:4px;}" +
            "h2{font-size:1.4em;border-bottom:1px solid #eee;padding-bottom:3px;}" +
            "h3{font-size:1.2em;}" +
            "h4{font-size:1.1em;}" +
            "h5{font-size:1.0em;}" +
            "h6{font-size:0.9em;color:#666;}" +
            "p{margin:4px 0;}" +
            "blockquote{border-left:3px solid #2196F3;margin:8px 0;padding:4px 12px;" +
            "background:#f5f5f5;color:#555;}" +
            "code{font-family:'Courier New',monospace;background:#f0f0f0;" +
            "padding:1px 4px;border-radius:3px;font-size:0.9em;color:#C7254E;}" +
            // 顶部不再靠 pre 的 padding 给按钮留位置。
            // 语言标签 / 复制 / 保存 都是绝对定位，压在 pre 的 padding 上——
            // 只要主题层写了 pre{padding:8px}（很常见），按钮就正好盖住第一行代码。
            // 现在改由流内的 .code-head 撑出高度，padding 被主题改小也压不到正文。
            // pre 的内容宽度必须由自己决定，不能被外层撑开：正文根节点是 body，
            // 一条很长的代码行会让 pre 的「内容宽」把整个文档的宽度顶出去，
            // 表现为代码块右侧溢出到屏幕外、整页可以左右拖动。width:auto + max-width:100%
            // 让 pre 老实按容器宽排版，超长的行在内部横向滚动。
            "pre{position:relative;display:block;background:#f6f8fa;border:1px solid #e1e4e8;" +
            "border-radius:4px;padding:0 12px 10px 12px;margin:8px 0;" +
            // width:100% 而不是 auto：auto 在老 WebKit 上是「先按内容收缩再夹到
            // max-width」，中间那一步会把文档宽度顶出去（代码块右侧溢出到屏幕外、
            // 整页能左右拖）。给一个确定的宽度，里面多长的行都只能在 pre 里横滚。
            "width:100%;min-width:0;max-width:100%;overflow-x:auto;overflow-y:hidden;" +
            "-webkit-overflow-scrolling:touch;}" +
            "pre .code-head{display:block;height:28px;font-size:0;line-height:0;}" +
            // display:inline-block + min-width:100%：代码块按最长行决定滚动宽度，
            // 但短代码也不会缩成一条；white-space:pre 保证缩进与换行原样保留。
            "pre code{background:transparent;padding:0;color:#333;white-space:pre;" +
            "word-wrap:normal;display:inline-block;min-width:100%;vertical-align:top;" +
            "font-size:13px;line-height:1.5;}" +
            "pre .lang-tag{position:absolute;top:6px;left:12px;font-size:10px;line-height:14px;" +
            "color:#999;font-family:Roboto,sans-serif;}" +
            "pre .copy-btn{position:absolute;top:4px;right:8px;padding:2px 8px;line-height:16px;" +
            "font-size:11px;background:#e1e4e8;border:1px solid #ccc;border-radius:3px;" +
            "color:#555;font-family:Roboto,sans-serif;}" +
            "pre .save-btn{position:absolute;top:4px;right:56px;padding:2px 8px;line-height:16px;" +
            "font-size:11px;background:#e8f5e9;border:1px solid #a5d6a7;border-radius:3px;" +
            "color:#2e7d32;font-family:Roboto,sans-serif;}" +
            "pre .import-btn{position:absolute;top:4px;right:56px;padding:2px 8px;line-height:16px;" +
            "font-size:11px;background:#f3e5f5;border:1px solid #ce93d8;border-radius:3px;" +
            "color:#6a1b9a;font-family:Roboto,sans-serif;}" +
            "pre .preview-btn{position:absolute;top:4px;right:104px;padding:2px 8px;line-height:16px;" +
            "font-size:11px;background:#e1f5fe;border:1px solid #81d4fa;border-radius:3px;" +
            "color:#0277bd;font-family:Roboto,sans-serif;}" +
            "pre .run-btn{position:absolute;top:4px;right:104px;padding:2px 8px;line-height:16px;" +
            "font-size:11px;background:#fff3e0;border:1px solid #ffcc80;border-radius:3px;" +
            "color:#e65100;font-family:Roboto,sans-serif;}" +
            // 表格同样不能把文档撑宽：.table-scroll 自身限宽，宽表格在容器内滑动。
            // 以前 table 直接以内容宽参与布局，列一多整页就被顶宽，代码块跟着跑出屏幕。
            "table{border-collapse:collapse;margin:8px 0;font-size:13px;white-space:nowrap;}" +
            "th,td{border:1px solid #ddd;padding:6px 10px;text-align:left;}" +
            "th{background:#f0f0f0;font-weight:bold;}" +
            ".table-scroll{max-width:100%;overflow-x:auto;-webkit-overflow-scrolling:touch;" +
            "-webkit-tap-highlight-color:rgba(100,180,255,0.3);}" +
            "a{color:#2196F3;text-decoration:none;}" +
            "img{max-width:100%;height:auto;}" +
            "svg{max-width:100%;}" +
            ".msg svg{display:block;overflow:hidden;}" +
            ".svg-scroll{overflow:auto;-webkit-overflow-scrolling:touch;margin:8px 0;}" +
            ".svg-scroll svg{width:100%;height:auto;display:block;overflow:visible;}" +
            ".math-block img,.math-block svg,.math-inline,.msg img,.msg svg{-webkit-tap-highlight-color:rgba(255,255,255,0.3);}" +
            ".math-block{display:block;text-align:center;margin:8px 0;padding:4px 8px;}" +
            ".math-block img,.math-block svg{max-width:100%;}" +
            ".math-block img{height:auto;}" +
            "svg.math-inline{display:inline;vertical-align:middle;}" +
            "img.math-inline{display:inline;height:1.6em;width:auto;vertical-align:middle;}" +
            ".math-inline img,.math-inline svg{height:1.6em;vertical-align:middle;}" +
            "ul,ol{padding-left:24px;margin:4px 0;}" +
            "li{margin:2px 0;}" +
            "hr{border:none;border-top:1px solid #ddd;margin:12px 0;}" +
            "mark{background:#FFF176;padding:1px 2px;}" +
            "sup,sub{font-size:0.75em;}" +
            "del,s{color:#999;}" +
            "em{font-style:italic;}" +
            "strong{font-weight:bold;}" +
            ".thinking-block{margin:8px 0;padding:8px 12px;background:#fafafa;" +
            "border-left:3px solid #ccc;color:#888;font-style:italic;font-size:13px;}" +
            ".thinking-toggle{display:block;margin-top:8px;color:#2196F3;" +
            "font-style:normal;font-weight:bold;font-size:13px;cursor:pointer;}" +
            ".tool-card{margin:8px 0;border:1px solid #dbe4ee;border-radius:8px;" +
            "background:#f8fbff;overflow:hidden;}" +
            ".tool-card-head{padding:6px 10px;background:#eaf2fd;color:#1565c0;" +
            "font-size:12px;font-weight:bold;word-wrap:break-word;}" +
            // trace 超长被裁剪后的说明行：灰底灰字，明确是「提示」不是「结果」，
            // 但必须看得见——它替代的是真正消失掉的几张卡片。
            ".tool-trim{background:#f2f4f7;color:#78909c;font-weight:normal;font-style:italic;}" +
            // 折叠条：卡片太多时替代「更早的那几十张」。刻意做得像一行可点的说明，
            // 而不是按钮——它是目录，不是操作。
            ".tool-fold{margin:8px 0;padding:7px 10px;border:1px dashed #cfd8dc;" +
            "border-radius:8px;background:#fafbfc;color:#607d8b;font-size:12px;" +
            "text-align:center;cursor:pointer;" +
            "-webkit-tap-highlight-color:rgba(100,180,255,0.3);}" +
            ".tool-head-click{cursor:pointer;-webkit-tap-highlight-color:rgba(100,180,255,0.3);}" +
            ".tool-arrow{display:inline-block;width:12px;color:#1565c0;font-size:10px;}" +
            ".tool-hint{color:#90a4ae;font-weight:normal;font-size:11px;}" +
            ".tool-body{display:none;}" +
            ".tool-item{padding:8px 10px;border-top:1px solid #e6eef8;}" +
            // 图片搜索结果：三列网格。用 background-image + cover 而不是 <img>：
            // Chromium 30 没有 object-fit，图片会被按格子比例拉变形。
            ".tool-imgs{padding:8px 8px 2px 8px;font-size:0;}" +
            ".tool-img-cell{display:inline-block;width:30%;height:86px;" +
            "margin:0 1.5% 6px 1.5%;vertical-align:top;border-radius:6px;" +
            "background-color:#eef2f6;background-size:cover;background-position:center;" +
            "border:1px solid #dbe4ee;" +
            "-webkit-tap-highlight-color:rgba(100,180,255,0.3);}" +
            ".tool-title{color:#1565c0;font-weight:bold;font-size:13px;text-decoration:none;" +
            "word-wrap:break-word;}" +
            ".tool-meta{color:#90a4ae;font-size:11px;margin-top:2px;word-wrap:break-word;}" +
            ".tool-summary{color:#546e7a;font-size:12px;margin-top:4px;line-height:1.5;}" +
            ".tool-fetch-content{padding:8px 10px;color:#37474f;font-size:12px;" +
            "line-height:1.6;white-space:pre-wrap;word-wrap:break-word;" +
            "max-height:240px;overflow-y:auto;}" +
            ".tool-error{padding:8px 10px;color:#c62828;font-size:12px;}" +
            ".tool-empty{padding:8px 10px;color:#90a4ae;font-size:12px;}" +
            ".tool-status{padding:6px 10px;color:#1565c0;font-size:12px;" +
            "font-style:italic;background:#eaf2fd;border-radius:6px;margin:8px 0;}" +
            // 429 等待卡片上的「立即重试」。float 而不是 flex：Chromium 30 对 flex 的
            // 支持有坑，float 在这个 WebView 上从 4.x 到 14 表现都一致。
            ".retry-btn{float:right;margin:-1px 0 -1px 8px;padding:2px 10px;" +
            "font-size:12px;font-style:normal;color:#fff;background:#1976d2;" +
            "border:none;border-radius:12px;line-height:18px;" +
            "-webkit-tap-highlight-color:transparent;}" +
            ".retry-btn:active{background:#0d47a1;}" +
            // 429 服务端原因单独一行：倒计时每秒刷新只改 .retry-text，
            // 原因不会被覆盖，也不会像 Toast 那样一闪而过。
            ".retry-reason{display:block;margin-top:4px;font-style:normal;" +
            "color:#c62828;font-size:12px;line-height:1.5;" +
            "white-space:pre-wrap;word-wrap:break-word;}" +
            // 按钮原本直接写 U+22EF「⋯」。4.3/4.4 的系统字体没有这个字形，
            // 落到 fallback 后渲染成缺字方框（用户描述成「交叉眼」），既认不出也点不准。
            // 改成用 CSS 画三个圆点：不依赖任何字体，任何 ROM 上都长一样；
            // padding 同时把热区从 ~21px 撑到 ~30px，手指才点得中。
            ".msg-menu-btn{display:inline-block;background:none;border:none;" +
            "padding:8px 12px;line-height:0;-webkit-tap-highlight-color:transparent;}" +
            ".mdot{display:inline-block;width:5px;height:5px;margin:0 2px;" +
            "border-radius:3px;background:#9aa3ab;vertical-align:middle;}" +
            ".msg-menu-btn:active{background:#e8e8e8;border-radius:14px;}" +
            ".msg-menu-btn:active .mdot{background:#555;}" +
            ".ai-menu-wrap{text-align:left;margin-top:4px;}" +
            ".user-menu-wrap{text-align:right;margin-top:2px;}" +
            // 时间/用量行。用户侧靠 .msg.user 的 text-align:right 自然右对齐，不用另写规则。
            ".msg-meta{font-size:11px;color:#90a4ae;margin-top:4px;line-height:1.4;}" +
            // 逐轮明细：比主行再淡一档、再小半号，长对话里不至于抢正文的注意力
            ".msg-meta-sub{font-size:10px;color:#b0bec5;margin-top:1px;line-height:1.4;" +
            "word-wrap:break-word;}" +
            ".tk-kw{color:#d73a49;font-weight:bold;}" +
            ".tk-str{color:#032f62;}" +
            ".tk-cmt{color:#6a737d;font-style:italic;}" +
            ".tk-num{color:#005cc5;}" +
            ".tk-type{color:#6f42c1;}" +
            ".tk-fn{color:#6f42c1;}" +
            ".tk-op{color:#d73a49;}" +
            ".fn-ref{font-size:0.75em;vertical-align:super;}" +
            ".fn-ref a{color:#2196F3;text-decoration:none;}" +
            ".fn-def{font-size:12px;color:#888;border-top:1px solid #eee;" +
            "padding:4px 0;margin:6px 0;}" +
            ".fn-def sup a{color:#888;text-decoration:none;}" +
            ".sources-block{margin:8px 0;border:1px solid #e3e8ef;border-radius:8px;" +
            "background:#fafbfc;overflow:hidden;}" +
            ".sources-head{padding:6px 10px;background:#f2f5f9;color:#455a64;" +
            "font-size:12px;font-weight:bold;cursor:pointer;" +
            "-webkit-tap-highlight-color:rgba(100,180,255,0.3);}" +
            ".src-arrow{display:inline-block;width:12px;color:#78909c;font-size:10px;}" +
            ".sources-body{padding:4px 0;}" +
            ".src-item{padding:4px 10px;font-size:12px;line-height:1.5;word-wrap:break-word;}" +
            ".src-item a{color:#1565c0;text-decoration:none;}" +
            ".msg.user.failed .bubble{background:#FFF3E0;}" +
            // 用户消息下方的附件卡片。独立于 .bubble 存在：编辑正文只会替换气泡，
            // 附件不会跟着消失；早先附件摘要写在正文里，改一次消息就没了。
            ".attach-list{margin-top:6px;text-align:right;}" +
            // padding-right 给「查看 ›」留位：它用绝对定位压在右侧，而不是 float——
            // Chromium 30 里 float 与 overflow:hidden 的 .attach-body 相撞会把卡片撑变形。
            ".attach-item{display:inline-block;position:relative;text-align:left;background:#fff;" +
            "border:1px solid #bbdefb;border-radius:8px;padding:6px 10px 6px 10px;" +
            "padding-right:44px;margin:4px 0 0 6px;max-width:100%;vertical-align:top;}" +
            ".attach-ico{float:left;width:16px;height:20px;margin:2px 8px 0 0;" +
            "background:#e3f2fd;border:1px solid #90caf9;border-radius:2px 7px 2px 2px;}" +
            ".attach-thumb{float:left;width:36px;height:36px;margin:1px 8px 0 0;" +
            "border-radius:3px;border:1px solid #bbdefb;background:#eceff1;" +
            "background-size:cover;background-position:center;}" +
            ".attach-body{display:block;overflow:hidden;}" +
            ".attach-name{display:block;font-size:13px;font-weight:bold;color:#1565c0;" +
            "line-height:1.3;word-wrap:break-word;}" +
            ".attach-sub{display:block;font-size:11px;color:#78909c;line-height:1.3;margin-top:1px;}" +
            // 截断提示：这次请求实际只发了其中一部分。橙红色而不是灰色——
            // 灰字会被当成又一行元信息直接略过，而这行是要用户动手（调大预算/换文件）的。
            ".attach-cut{display:block;font-size:11px;color:#e65100;line-height:1.3;margin-top:2px;" +
            "word-wrap:break-word;}" +
            ".attach-view{position:absolute;right:8px;top:50%;margin-top:-9px;" +
            "font-size:11px;color:#1976d2;line-height:18px;}" +
            ".error-card{display:inline-block;text-align:left;margin-top:4px;padding:8px 10px;" +
            "border:1px solid #ffcdd2;border-radius:8px;background:#fff5f5;max-width:85%;}" +
            ".err-title{color:#c62828;font-size:12px;font-weight:bold;}" +
            ".err-summary{color:#6d4c41;font-size:12px;margin-top:2px;line-height:1.4;" +
            "word-wrap:break-word;}" +
            ".err-actions{margin-top:6px;}" +
            ".err-btn{font-size:11px;padding:3px 10px;margin-right:6px;border-radius:4px;" +
            "border:1px solid #cfd8dc;background:#fff;color:#37474f;}" +
            ".err-btn.retry{background:#e3f2fd;border-color:#90caf9;color:#1565c0;}" +
            // 流式时正文分两段：base 是已完成的思考/工具卡片（渲染一次后不再动），
            // tail 是本轮增量。关掉实时渲染时 tail 走纯文本，保留换行，开销远小于 markdown
            ".stream-tail{word-wrap:break-word;position:relative;}" +
            // 逐字渐显的显示层：只放「已经显示到切点」的内容，按真实结构一层层建。
            // 未显示的部分不建节点——旧版把剩余内容挂在 visibility:hidden 的占位层里，
            // 每帧整段重解析一次 HTML，既拖慢低端机，也让滚动高度先跳到底、文字后到。
            ".smooth-shown{word-wrap:break-word;}" +
            // 逐字渐显的深浅：末尾的字浅、往前逐渐加深。
            //
            // 深浅不用 CSS 过渡写死：定了时长，打字速度一快，刚浮出的字还没过渡完
            // 就已经被推到「末尾之外」，屏幕上就是一串同样灰度的字。现在由脚本
            // 按「离末尾几个字」逐帧给每个字设透明度（越靠末尾越浅，末尾几乎看不见），
            // 读者看到的就是一段连续变淡的尾巴，而不是一块同色的灰。
            //
            // 字色继续继承主题：透明度不影响色相，深浅主题下都是「淡→浓」。
            // 不写死过渡时长：4.4 的 WebView 重绘 opacity 过渡很贵，一百多个字同时
            // 过渡时每帧都要合成一层，低端机上会明显掉帧，所以这里只保留一组
            // 静态透明度，由脚本逐帧改写。
            ".stream-new{opacity:1;}" +
            // 「直接显示」不是网页里的一条提示，而是主界面输入框左上方那颗小气泡
            // （与「回到底部」共用一套外观），由页面在文档底部留出一条空白给它浮上来：
            // 固定悬浮的气泡会盖住正在输出的最后一行字。
            "body{padding-bottom:52px;}" +
            ".stream-tail.plain{white-space:pre-wrap;}" +
            ".stream-tail.plain::after{content:'▍';color:#90a4ae;}" +
            // 关掉实时渲染时，每一轮的纯文本并进 base 后要包一层：base 本身不保留
            // 换行（里面放的是 HTML 卡片），裸文本节点落进去会被塌成一行。
            ".stream-plain{white-space:pre-wrap;word-wrap:break-word;}" +
            ".error-card.inline{display:block;margin:8px 0;max-width:100%;}" +
            "#older-hint{padding:10px 8px;text-align:center;color:#90a4ae;font-size:12px;" +
            "border-bottom:1px dashed #e0e0e0;margin-bottom:6px;" +
            "-webkit-tap-highlight-color:rgba(100,180,255,0.2);}";

    /** 页面脚本：复制 / 保存按钮、工具卡片折叠、流式更新、图片预览桥接。 */
    static final String JS =
            "(function(){" +
            "function addCopyBtns(root){" +
            "var pres=(root||document).querySelectorAll('pre');" +
            "for(var i=0;i<pres.length;i++){(function(pre){" +
            // 顶部占位块：语言标签/复制/保存都是绝对定位，原来靠 pre 的 padding-top
            // 给它们腾地方。主题 CSS 一旦把 pre 的 padding 改小，按钮就压到第一行代码上
            // （用户反馈的「部分主题代码块第一行被遮住」）。改成流内占位，
            // padding 怎么变都压不到正文。放在 copy-btn 判重之前，已有按钮的老节点
            // 也能补上这块，不必等下次重灌页面。
            "if(!pre.querySelector('.code-head')){" +
            "var hd=document.createElement('div');hd.className='code-head';" +
            "if(pre.firstChild)pre.insertBefore(hd,pre.firstChild);else pre.appendChild(hd);}" +
            "if(pre.querySelector('.copy-btn'))return;" +
            "var btn=document.createElement('button');" +
            "btn.className='copy-btn';btn.textContent='@@L_COPY@@';" +
            "btn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:pre.textContent;" +
            "if(window.Android)Android.copyCode(text);" +
            "btn.textContent='@@L_COPIED@@';" +
            "setTimeout(function(){btn.textContent='@@L_COPY@@';},2000);};" +
            "pre.appendChild(btn);" +
            "var langTag=pre.querySelector('.lang-tag');" +
            "var lang=langTag?langTag.textContent.trim().toLowerCase():'';" +
            "var narrow=(window.innerWidth<320);" +
            "var sbtn=document.createElement('button');" +
            "sbtn.className='save-btn';sbtn.textContent='@@L_SAVE@@';" +
            "sbtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:pre.textContent;" +
            "if(window.Android)Android.saveCode(lang,text);};" +
            "if(lang==='actheme'){" +
            "if(narrow)sbtn.style.display='none';else sbtn.style.right='104px';}" +
            "pre.appendChild(sbtn);" +
            "if(lang==='actheme'&&!pre.querySelector('.import-btn')){" +
            "var ibtn=document.createElement('button');" +
            "ibtn.className='import-btn';ibtn.textContent='@@L_IMPORT@@';" +
            "ibtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:'';" +
            "if(window.Android)Android.importTheme(text);" +
            "ibtn.textContent='@@L_IMPORTED@@';" +
            "setTimeout(function(){ibtn.textContent='@@L_IMPORT@@';},2000);};" +
            "pre.appendChild(ibtn);}" +
            "if(langTag){" +
            "if((lang==='js'||lang==='javascript')&&!pre.querySelector('.run-btn')){" +
            "var rbtn=document.createElement('button');" +
            "rbtn.className='run-btn';rbtn.textContent='@@L_RUN@@';" +
            "rbtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:'';" +
            "if(window.Android)Android.runJs(text);};" +
            "pre.appendChild(rbtn);}" +
            "if((lang==='html'||lang==='svg')&&!pre.querySelector('.preview-btn')){" +
            "var pbtn=document.createElement('button');" +
            "pbtn.className='preview-btn';pbtn.textContent='@@L_PREVIEW@@';" +
            "pbtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:'';" +
            "if(window.Android)Android.previewCode(lang,text);};" +
            "pre.appendChild(pbtn);}}})" +
            "(pres[i]);}};" +
            // 长按目标必须在 touchstart 时就把 data-idx 解析出来：流式期间 tail
            // 每 200ms 整段重建，700ms 后原节点早已脱离文档，parentElement 链断掉，
            // 再向上找 data-idx 只会走到 null —— 菜单永远不弹。
            "var lpTimer=null,lpStartX=0,lpStartY=0,lpIdx=null;" +
            // 长按判定 900ms、位移容差 6px。
            // 原来 700ms / 10px：词典笔屏幕小、手指落点抖动大，正常滑动阅读时
            // 很容易在滚动开始前就攒满 700ms，菜单莫名其妙弹出来。900ms 仍短于
            // 系统长按（约 500ms 起算但带按压反馈），对「有意识长按」没有负担。
            "document.addEventListener('touchstart',function(e){" +
            "if(!e.touches||e.touches.length!==1)return;" +
            "var t=e.touches[0];" +
            "lpStartX=t.clientX;lpStartY=t.clientY;lpIdx=null;" +
            "var el=e.target;" +
            "while(el&&el!==document.body){" +
            "var idx=el.getAttribute&&el.getAttribute('data-idx');" +
            "if(idx!==null&&idx!==undefined){lpIdx=idx;break;}" +
            "el=el.parentElement;}" +
            "if(lpIdx===null)return;" +
            "if(lpTimer)clearTimeout(lpTimer);" +
            "lpTimer=setTimeout(function(){lpTimer=null;" +
            "if(lpIdx===null)return;" +
            "window.__suppressClickUntil=Date.now()+700;" +
            "if(window.Android)Android.messageLongPress(lpIdx);},900);});" +
            "document.addEventListener('touchmove',function(e){" +
            "if(!lpTimer||!e.touches||e.touches.length!==1)return;" +
            "var t=e.touches[0];" +
            // 容差 6px：超过就说明是在滑动，不是在长按。
            "if(Math.abs(t.clientX-lpStartX)>6||Math.abs(t.clientY-lpStartY)>6)" +
            "{clearTimeout(lpTimer);lpTimer=null;}" +
            "});" +
            "document.addEventListener('touchend',function(){" +
            "if(lpTimer){clearTimeout(lpTimer);lpTimer=null;}});" +
            "document.addEventListener('touchcancel',function(){" +
            "if(lpTimer){clearTimeout(lpTimer);lpTimer=null;}});" +
            "document.addEventListener('click',function(e){" +
            // 长按弹菜单后抬手会补发一次 click，这段窗口内的点击直接丢弃
            "if(window.__suppressClickUntil&&Date.now()<window.__suppressClickUntil)return;" +
            "var t=e.target,imgEl=null,tableEl=null,failEl=null,isBtn=false;" +
            "while(t&&t!==document.body){" +
            "var tag=t.tagName.toUpperCase();" +
            "if(tag==='BUTTON')isBtn=true;" +
            "if(!failEl&&(''+t.className).indexOf('msg user failed')>=0)failEl=t;" +
            "if(!imgEl&&(tag==='IMG'||tag==='SVG'))imgEl=t;" +
            "if(!tableEl&&tag==='TABLE')tableEl=t;" +
            "if(!imgEl&&tag==='A'){e.preventDefault();" +
            "if(window.Android)Android.openUrl(t.href);return false;}" +
            "t=t.parentElement;}" +
            // 点击失败的用户气泡 → 立即重试（排除菜单按钮、来源链接、详情/重试按钮、图片/表格）
            "if(failEl&&!isBtn&&!imgEl&&!tableEl){" +
            "e.preventDefault();e.stopPropagation();" +
            "var fidx=failEl.getAttribute('data-idx');" +
            "if(fidx&&window.Android)Android.retryFailedUserMessage(fidx);" +
            "return false;}" +
            "if(tableEl&&!imgEl){" +
            "e.preventDefault();e.stopPropagation();" +
            "var wrap=tableEl.parentElement;" +
            "if(wrap&&wrap.className.indexOf('table-scroll')>=0)tableEl=wrap;" +
            "var html='';" +
            "try{html=new XMLSerializer().serializeToString(tableEl);}" +
            "catch(e3){html=tableEl.outerHTML||'';}" +
            "if(html&&window.Android)Android.showTableViewer(html);" +
            "return false;}" +
            "if(imgEl){" +
            "e.preventDefault();e.stopPropagation();" +
            "var all=[],items=document.querySelectorAll('.msg img, .msg svg'),ci=-1;" +
            "for(var i=0;i<items.length;i++){" +
            "if(items[i]===imgEl)ci=all.length;" +
            "var itag=items[i].tagName.toUpperCase();" +
            "if(itag==='IMG'){" +
            "all.push({src:items[i].src,alt:items[i].alt||'',type:'raster'});" +
            "}else{" +
            "var imgChild=items[i].querySelector('image');" +
            "if(imgChild){" +
            "var href=imgChild.getAttribute('href')||imgChild.getAttribute('xlink:href')||'';" +
            "all.push({src:href,alt:items[i].getAttribute('aria-label')||'',type:'raster'});" +
            "}else{" +
            "var svgHtml='';" +
            "try{svgHtml=new XMLSerializer().serializeToString(items[i]);}" +
            "catch(e2){svgHtml=items[i].outerHTML||'';}" +
            "all.push({src:'',alt:items[i].getAttribute('aria-label')||'',type:'svg',svg:svgHtml});" +
            "}}}" +
            "if(ci>=0&&window.Android)Android.showImageViewer(''+ci,JSON.stringify(all));" +
            "return false;}" +
            "});" +
            "window.fmtTok=function(n){" +
            "n=n|0;if(n<1000)return ''+n;" +
            "if(n<1000000)return (n/1000).toFixed(1)+'k';" +
            "return (n/1000000).toFixed(1)+'M';};" +
            // 思考内容的 token 估算（与 MessageHtmlRenderer.estimateTokens 同一口径：
            // 中日韩/全角 0.6，其余 0.3，按 0.1 向上取整）。
            //
            // 只在「块已经不再输出、但渲染时没来得及写数字」时用：流式期间的那个
            // 折叠条是 data-tok="0" 的常量版（为了逐帧字节不变），它被
            // streamCommitTail 整棵搬进 base 之后就再也不会重渲染，属性一直是 0，
            // 折叠条也就一直显示「思考中…」，永远看不到 token 数。
            "window.__thTok=function(b){" +
            "if(!b)return 0;" +
            "var s=b.textContent||'',wide=0,narrow=0,n=s.length;" +
            "for(var i=0;i<n;i++){" +
            "var c=s.charCodeAt(i);" +
            "if(c>=0xD800&&c<=0xDBFF&&i+1<n){var d=s.charCodeAt(i+1);" +
            "if(d>=0xDC00&&d<=0xDFFF){i++;wide++;continue;}}" +
            "if((c>=0x1100&&c<=0x115F)||(c>=0x2E80&&c<=0x303E)||(c>=0x3041&&c<=0x33FF)||" +
            "(c>=0x3400&&c<=0x4DBF)||(c>=0x4E00&&c<=0x9FFF)||(c>=0xA000&&c<=0xA4CF)||" +
            "(c>=0xAC00&&c<=0xD7A3)||(c>=0xF900&&c<=0xFAFF)||(c>=0xFE30&&c<=0xFE4F)||" +
            "(c>=0xFF00&&c<=0xFF60)||(c>=0xFFE0&&c<=0xFFE6)){wide++;}else{narrow++;}}" +
            "var tenths=wide*6+narrow*3;" +
            "if(tenths<=0)return 0;" +
            "return Math.max(1,Math.ceil(tenths/10));};" +
            // 思考块的展开状态必须存在 JS 变量里：流式每 200ms 会整段重建
            // .stream-tail 的 innerHTML，只改 DOM style 的话下一次重绘就回到
            // HTML 里写死的 display:none，表现为「展开闪一下又折叠回去」。
            // __thinkOpen 只记录「用户手动点过 toggle」的块（hasOwnProperty 区分
            // 显式折叠 false 与「从未操作过」），手动状态优先级最高，不被自动流程覆盖。
            "window.__thinkOpen=window.__thinkOpen||{};" +
            // 每条折叠记录带上「第几轮生成」的戳。
            //
            // 流式期间 tail 的结构每帧都可能变（markdown 补全、渐显层重画），
            // 变一次思考块的 DOM 节点就是全新的一个：节点上的 __thWasLive
            // 自然是空的。旧代码把「节点是新的 + 正在输出」当成「上一轮的
            // 残留记录」，直接 delete 掉用户刚写进去的折叠状态 —— 用户点一下
            // 收起，下一帧就被删记录、下一帧 applyThinkingStates 又按 live 自动
            // 展开，屏幕上就是「怎么点都折不起来」。
            // 改用轮次戳判定：只有记录来自「另一轮生成」才作废，同一轮内的
            // 重建一律保留用户的选择。
            "window.__thinkRnd=window.__thinkRnd||{};" +
            "window.__thRound=window.__thRound||1;" +
            // 展开状态的实际 key：消息位置 + 块序号。
            //
            // 服务端写进 data-think-key 的是「这块在本段文本里的序号」，同一条
            // 消息的 base / tail 各自从 0 数，光用序号会让不同消息、不同段落
            // 的块重名——用户展开一条消息的思考，别的消息也跟着展开。
            //
            // 消息这一维不能用 data-idx：流式期间气泡是 data-idx="-1" 的占位，
            // 收尾 finalizeLast 才改成真实下标。拿它拼 key，生成结束那一刻 key
            // 会整段变一次，用户展开的块正好被折回去。改用「这条消息在 .msg
            // 列表里的位置」：整轮生成、finalize、整页重建都不会变，位置算一次
            // 就缓存在消息节点上（流式只重建 tail 内容，消息节点本身不重建）。
            "window.__thinkKey=function(it){" +
            "if(it.__thKey!==undefined)return it.__thKey;" +
            // 先定位所属消息：消息这一维不看属性，只看 DOM 位置，
            // 这样即使某份节点缺属性，也不会退化成全局共用一个 key。
            "var p=it,mi='',n=0;" +
            "while(p&&p.nodeType===1&&n++<14){" +
            "if((' '+((p.className&&p.className.baseVal)||p.className||'')+' ').indexOf(' msg ')>=0)break;" +
            "p=p.parentNode;}" +
            "var host=null;" +
            "if(p&&p.nodeType===1){host=p;" +
            "if(p.__mi===undefined){var all=document.getElementsByClassName('msg'),pos=-1;" +
            "for(var j=0;j<all.length;j++){if(all[j]===p){pos=j;break;}}" +
            "p.__mi='p'+pos;}" +
            "mi=p.__mi;}" +
            "var raw=it.getAttribute('data-think-key')||'';" +
            // 属性缺失时按「块在所属消息里的序号」兜底。
            // 实测日志里出现过 key 尾巴为空（p5#）：同一时刻页面上并存着
            // 分属两份容器的思考块，其中一份没有 data-think-key。点击记在
            // p5#、apply 读 p5#t0，两个 key 对不上，用户点展开就永远不生效。
            // 用位置兜底后，两份节点各自拿到互不相同的稳定 key。
            "if(!raw){" +
            "var host2=host||document;" +
            "var ws=host2.getElementsByClassName('thinking-wrap'),idx=-1;" +
            "for(var q=0;q<ws.length;q++){if(ws[q]===it){idx=q;break;}}" +
            "raw='w'+idx;}" +
            "var k=mi+'#'+raw;it.__thKey=k;return k;};" +
            // 只有仍在 .stream-tail 里的块才算「正在输出」：commit 进 base、或整页
            // 重建后落在 .content 里的历史消息，一律视为已结束，不自动展开。
            "window.__inTail=function(n){var p=n,i=0;" +
            "while(p&&i++<10){var cn=''+(p.className||'');" +
            "if(cn.indexOf('stream-tail')>=0)return true;" +
            "if(cn.indexOf('stream-base')>=0)return false;" +
            "p=p.parentElement||p.parentNode;}return false;};" +
            // 思考块后面是否已经出现「回答正文」。
            //
            // 旧实现只看紧邻的下一个非空兄弟：思考一闭合，紧跟的就是第一段正文，
            // 于是块被当成「思考结束」立刻自动折叠。用户体感是「生成时思考会自己
            // 折起来」，想读还得再展开一次。
            //
            // 现在按「末尾」判：只有在思考块之后已经出现了实打实的正文（超过
            // {@link #THINK_SETTLE_CHARS} 个可见字符）才算思考结束。
            // 工具卡片不算正文——思考与工具调用经常交替出现，卡片落地时折叠会让
            // 后面的思考被藏起来。
            //
            // 判断范围是从思考块到「末尾」，而不是它的下一个兄弟，因为两者之间
            // 可能隔着渐显的显示层：逐字渐显时正文装在 .smooth-shown 里，与新字
            // 一起被追加成思考块的下一个兄弟，于是「已到末尾」永远比实际晚一段。
            // 不能再把「本轮已收尾」（__sm.settled）算作结束信号：它是整页共享的
            // 一个值，收尾之后任何一次重绘都会让仍在 tail 里的思考块被当成已结束
            // 自动折叠，这正是「生成过程中思考自己折起来」的直接原因。
            "window.__nextReal=function(n){" +
            "var root=document.querySelector('.stream-tail');" +
            "if(!root||!root.contains(n))return n.nextSibling;" +
            "var txt='',s=window.__smNextEl(n);" +
            "while(s){" +
            "if(s.nodeType===3){if(/\\S/.test(s.nodeValue||''))txt+=s.nodeValue;}" +
            "else if(s.nodeType===1){" +
            "var cn=''+(s.className||'');" +
            "if(cn.indexOf('tool-card')>=0||cn.indexOf('tool-live')>=0||cn.indexOf('thinking-wrap')>=0||" +
            "cn.indexOf('copy-btn')>=0||cn.indexOf('code-head')>=0||cn.indexOf('lang-tag')>=0){" +
            "s=s.nextSibling;continue;}" +
            "txt+=(s.textContent||'');}" +
            "s=s.nextSibling;}" +
            "return txt.replace(/\\s+/g,'').length>window.__smSettleChars;};" +
            // 计数起点：思考块之后、渐显显示层里「已经画到」的范围之内。
            //
            // 渐显时正文装在 .smooth-shown 里，与新字一起被追加成思考块的下一个
            // 兄弟；思考块本身是上一个兄弟，它的已显示内容不计入。再往后的兄弟
            // 还一个都没画，计进来只会提前判成「正文已出现」。所以扫到显示层
            // 最后一个已画元素为止。
            // 不在渐显通道里（没有 .smooth-shown）时退回读全部后续兄弟。
            "window.__smNextEl=function(n){" +
            "var st=window.__sm;" +
            "if(!st||!st.on||!st.root||!st.stack||!st.stack[0])return n.nextSibling;" +
            // 已画出内容的终点：显示层里最后那一层元素
            "var last=st.root.lastElementChild;" +
            "if(!last)return n.nextSibling;" +
            "var inside=(function(x){while(x&&x!==document.body){if(x===last)return true;" +
            "x=x.parentNode;}return false;})(n);" +
            "if(!inside)return n.nextSibling;" +
            // 块内已画到末尾：再往后的兄弟都还没画，没有正文可计
            "if(n===last)return null;" +
            "var s=n.nextSibling,p=n.parentNode;" +
            "while(!s&&p&&p!==last){s=p.nextSibling;p=p.parentNode;}" +
            "if(!s||s===last)return null;" +
            "return s;};" +
            // 思考块的展开状态逐「块」记在 DOM 节点上（__thOpen），而不是全局一个指纹。
            //
            // 旧实现每次落地都做两件很贵、且会闪的事：
            //   1) 对整棵 root 做 querySelectorAll('.thinking-wrap') —— 长回答里
            //      渐显层有几千个节点，这个扫描随正文线性增长；
            //   2) 用「块数 / 末块文本长度」当指纹，思考内容每长几个字指纹就变，
            //      于是每帧都把全部块重扫一遍、重新写 style.display 与按钮文案。
            //      而 tail 的节点本来就在随渐显增删，写 display 会触发布局，
            //      几块一起抖动 —— 用户看到的就是「思考块时不时折叠、下一步又展开」。
            //
            // 现在：块的状态记在节点上（__thOpen / __thSig），只在「节点首次出现」
            // 和「块离开 / 进入 tail」这两个时刻定一次态，之后只有用户点击才改。
            // 中间每帧一律跳过，不重写 display，也就不再有抖动。
            // 结构归一化：把流式途中套坏的思考块重排回 wrap > toggle + block。
            //
            // 逐字渐显是按「单位」往已显示区追加的，一旦某帧的锚点栈指到了
            // 上一层的 .thinking-toggle，后面所有标签就都往它里面长，形成
            // wrap > toggle > toggle > … > block（实测日志里连出 5 层）。
            // 这种树有两个后果：
            //   1) 每个 toggle 都带 onclick，一次点击冒泡触发多次，展开/折叠
            //      互相抵消——日志里同一毫秒两条 click（open=1 后紧接 open=0）
            //      就是它，用户感受是「偶尔能、偶尔不能」；
            //   2) block 的父节点不再是 wrap，按父节点算 key 会算错。
            //
            // 只把内层 toggle 解包还不够：block 会落在上一层 toggle 里，
            // 父节点仍然不对。这里直接把 wrap 的内部重排成标准结构：
            // 取最外层 toggle（带 onclick 的那个）和最后一个 block（内容最全），
            // 摘下来重新挂到 wrap 下，中间那些壳全部丢掉。
            // 不动 style.display，用户当前的展开状态原样保留。
            // 结构归一化：只摘掉多余 toggle 的点击与显示，绝不搬动任何节点。
            //
            // 逐字渐显是按「单位」往已显示区追加的，某一帧锚点栈指到上一层的
            // .thinking-toggle 后，后面所有标签就都往它里面长，形成
            // wrap > toggle > toggle > … > block（日志里连出 5 层）。
            // 每个 toggle 都带 onclick，点一次事件冒泡把几个全触发一遍，
            // 奇数层展开、偶数层折回——用户感受是「偶尔能、偶尔不能」。
            //
            // 上一版在这里把 block 摘下来重挂、把 wrap 清空重建，结果渐显
            // 下一帧又套回去，__thFix 每帧都返回 1（日志里几十条 flatten
            // fixed=1），DOM 被反复替换：用户点中的那个节点在下一帧就没了，
            // 于是「点展开没反应 / 按钮写着展开但内容已经展着」。
            //
            // 现在只做两件不动树的事：
            //   1) 第一个 toggle 之外的 toggle 全部去掉 onclick（冒泡不再重复触发）
            //   2) 这些多余的 toggle 设 display:none（不占版面、不显示重复文案）
            // block 留在原处，逐字渐显的锚点栈全部有效，DOM 完全稳定。
            "window.__thFix=function(w){" +
            "if(!w||!w.getElementsByClassName)return 0;" +
            "var ts=w.getElementsByClassName('thinking-toggle');" +
            "if(ts.length<=1)return 0;" +
            // 顺序不能反：先把内容块提到 wrap 的直接子层，再去隐藏多余的折叠条。
            //
            // 上一版直接给多余的 toggle 设 display:none，而块恰好套在它里面——
            // 于是「把思考内容一起藏掉了」，用户看到的就是「完全不显示思考内容、
            // 点折叠按钮也没反应」（点了只改了块的 display，祖先还是 none）。
            // 这里只移动节点、不重建：块里的文字、渐显锚点、展开状态全部保持有效。
            "var bl=w.getElementsByClassName('thinking-block');" +
            "var b=bl.length?bl[0]:null;" +
            "if(b&&b.parentNode!==w){try{w.appendChild(b);}catch(x){}}" +
            "var n=0;" +
            // 文档序 = 外层在前，ts[0] 就是最外层那个（带正确 onclick 的）
            "for(var i=1;i<ts.length;i++){" +
            "var e=ts[i];if(!e)continue;" +
            "if(e.__thMute===1)continue;" +
            "e.__thMute=1;e.onclick=null;" +
            // 兜底：万一这层里还包着内容块（上面没提干净），绝不隐藏它
            "var eb=e.getElementsByClassName('thinking-block');" +
            "if(eb.length===0){try{e.style.display='none';}catch(x){}}" +
            "n++;" +
            "}" +
            "return n;};" +
            "window.__thFlatten=function(root){" +
            "if(!root||!root.getElementsByClassName)return 0;" +
            "var wraps=root.getElementsByClassName('thinking-wrap');" +
            "var fixed=0;" +
            // 倒序 + 复查 length：修好一个 wrap 会改变集合，从尾部往前不会漏
            "for(var i=wraps.length-1;i>=0;i--){" +
            "if(i<wraps.length&&window.__thFix(wraps[i]))fixed++;" +
            "}" +
            "if(fixed)window.__thRec('flatten fixed='+fixed);" +
            "return fixed;};" +
            "window.applyThinkingStates=function(root){" +
            "if(!root||!root.getElementsByClassName)return;" +
            // getElementsByClassName 返回活动集合，取 length/索引都是 O(1) 级；
            // querySelectorAll 每帧都要遍历整棵子树做选择器匹配，正文越长越慢。
            "var w=root.getElementsByClassName('thinking-wrap');" +
            "if(w.length===0)return;" +
            // 健康的树里每块正好一个 wrap、一个 toggle、一个 block。数量对不上
            // 说明逐字渐显在某一帧把 toggle 套进了 toggle（见 __thFlatten 注释）。
            // 判等是活动集合的 length，常数级，不会拖慢每一帧。
            "var togs=root.getElementsByClassName('thinking-toggle');" +
            "var blks=root.getElementsByClassName('thinking-block');" +
            // 数量对不上就修：toggle 多了（套娃）或 block 少了（被吃掉/藏起来）都要修。
            // 两个 length 都是活动集合的常数级读取，不会拖慢每一帧。
            "if(togs.length!==w.length||blks.length!==w.length){window.__thFlatten(root);" +
            "w=root.getElementsByClassName('thinking-wrap');" +
            "if(w.length===0)return;}" +
            "for(var i=0;i<w.length;i++){" +
            "var it=w[i];" +
            // 判断依据排优先级，越靠前越可靠：
            //   1. 用户点过 → 按用户的（唯一有绝对权限的来源）；
            //   2. data-think-live="1" → 服务端明确说「这段思考还没闭合」（正在写）；
            //   3. 块在 .stream-tail 里 → 本轮正在生成；
            //   4. 以上都不是 → 已归档，默认折叠。
            //
            // 反复出问题的根源是只认第 3 条：流式重建时块可能被写进 base、
            // 也可能因 JSON/HTML 转义差异落到 tail 之外，此时「不在 tail」
            // 会被误判成「已结束」，块在思考中途就被折起来，用户点开下一帧
            // 又折回。把服务端自己的 live 标记提到前面，就不依赖节点位置。
            // 属性缺失时（标签在流式途中被截断，重建出来的 wrap 没有
            // data-think-live，日志里表现为 live=null），用「是否还在
            // .stream-tail 里」推断是否正在输出：tail 里的一定是本轮内容。
            // 不这样兜底，残缺节点会走「已结束」分支被折起来，而用户点它
            // 又因为 key 缺尾巴写不进记录，于是永远展不开。
            "var inTail=window.__inTail(it);" +
            "var lvA=it.getAttribute('data-think-live');" +
            "var live=(lvA==null||lvA==='')?inTail:(lvA==='1');" +
            // 已经归档（不在 .stream-tail 里）的块不可能还在输出。
            //
            // 这种节点是流式途中按「未闭合」渲染出来的：data-tok="0"，折叠条写着
            // 「思考中…」。轮次切换时 streamCommitTail 把它们整棵搬进 base，属性
            // 原样跟着走，之后再没有任何路径会重渲染这一块 —— 于是一直被当成
            // 「正在写」：自动展开、折叠条永远停在「思考中…」，用户永远看不到
            // 这段思考到底花了多少 token。
            // 归档即收尾：就地纠正 live 标记，下面按「已结束」走，把数字补上。
            "if(live&&!inTail){live=false;it.setAttribute('data-think-live','0');}" +
            "var sig=(live?'L':'')+(inTail?'T':'');" +
            "var b=it.querySelector('.thinking-block');" +
            "var t=it.querySelector('.thinking-toggle');" +
            "if(!b||!t)continue;" +
            "var k=window.__thinkKey(it);" +
            // 「上一轮已结束」→「这一轮又开始输出」时，把上一轮留下的折叠记录作废。
            //
            // 用户对一段已闭合的思考点过收起，那条 false 会一直挂在
            // __thinkOpen 上；下一轮思考复用了同一个块（服务端续写、或重新生成
            // 落在同一条消息），live 又变成 1，这段旧记录就会把自动展开压掉，
            // 表现正是「重新测试一次，又是上次那样折着」。
            //
            // 判「上一轮」只能靠轮次戳，不能靠节点新旧：流式期间 tail 每帧
            // 都可能重画，节点一直是新的，按节点判会把同一轮里用户刚点的
            // 收起也一并作废（v1.21.36 的「生成过程折叠不了」）。
            "if(live&&it.__thWasLive!==1&&k&&window.__thinkRnd[k]!==window.__thRound){" +
            "delete window.__thinkOpen[k];delete window.__thinkRnd[k];}" +
            "it.__thWasLive=live?1:0;" +
            "var m=(k&&Object.prototype.hasOwnProperty.call(window.__thinkOpen,k))" +
            "?!!window.__thinkOpen[k]:null;" +
            // 正在输出的思考块（live=1）无论如何都要展开：
            // 只有用户亲手折叠过（m===false）才允许收起。这一段必须是「硬」的——
            // 用户报的「生成中自动折叠、点了又折回」就是这里被软判定放过了。
            "var open;" +
            "if(live){open=(m===false)?false:true;}" +
            "else if(m!==null){open=m;}" +
            "else{open=inTail;}" +
            "var same=(it.__thOpen===(open?1:0))&&it.__thSig===sig;" +
            "it.__thOpen=open?1:0;" +
            "it.__thSig=sig;" +
            // display 与按钮文案必须各自对齐实际状态，两者都要幂等。
            //
            // 旧写法只在「定态标记没变」(same) 时写文案，而 display 是
            // 「实际值不符才写」。一旦 display 被外部改动、定态标记却还是旧值，
            // 就只改了 display 不改文案——用户看到按钮写着「点击展开」，
            // 内容却已经摆在那儿展开着，点一下反而收紧，观感就是「按钮反了」。
            // 改成各自比对实际值：一致时不产生任何写入（安静时零重排），
            // 不一致时立刻纠正，文案与 display 永远同步。
            "var want=open?'block':'none';" +
            "var cur=b.style.display||'';" +
            "var dispChanged=(cur!==want);" +
            "if(dispChanged)b.style.display=want;" +
            // 文案按 data-tok 算；data-tok 为 0 的是「还在输出」的块，
            // 这时候根本还没有可信的数字，显示占位文案而不是「1 tokens」。
            "var tokN=parseInt(t.getAttribute('data-tok'),10);if(!(tokN>0))tokN=0;" +
            // 正在输出（live=1）的块也要显示数字。
            //
            // 流式那一段 HTML 里折叠条固定是 data-tok="0"（服务端不能写实时数，
            // 每帧字节一变，逐字渐显的差分就会把整块思考拆掉重画，见
            // MessageHtmlRenderer 的「未闭合」分支），于是生成期间折叠条一直
            // 停在「思考中…」，要等这一轮结束、节点被搬进 base 之后才补上数字。
            // 用户看到的正是「生成结束后才有数字，生成过程中只有思考中」。
            //
            // 这里由页面按当前正文估算并写回 data-tok：只改属性与折叠条文本，
            // 不碰节点结构，差分照旧只落在思考正文末尾，不会引起重绘。
            // 逐字渐显每帧都会调一次本函数，而一段思考可能几千字，全量扫描
            // 每帧做一次会白白吃排版时间，所以按 280ms 节流：一个节流窗口内
            // 最多扫一遍，肉眼看数字仍在跳，低端机上开销可忽略。
            "if(live){" +
            "var nowT=Date.now();" +
            "if(!it.__thTokAt||nowT-it.__thTokAt>280){" +
            "it.__thTokAt=nowT;" +
            "var el=window.__thTok(b);" +
            // 太短先不报数：刚开写时估出来是 1、2，数字每帧往上蹦比
            // 「思考中…」更吵，也容易被当成错误。等攒够一行再切过去。
            "if(el>=12&&el!==tokN){tokN=el;t.setAttribute('data-tok',''+el);}}}" +
            // 已结束的块：按正文估算一个写回 data-tok，之后的文案/折叠/重建都用这个数。
            // 只在第一次（__thTokFinal 未置位）算一次；live 期间可能停在节流窗口里，
            // 归档这一下重算等于按最终正文校正，数字与消息底部的用量口径一致。
            "else if(!it.__thTokFinal){" +
            "it.__thTokFinal=1;" +
            "var ef=window.__thTok(b);" +
            "if(ef>0&&ef!==tokN){tokN=ef;t.setAttribute('data-tok',''+ef);}}" +
            // 展开着的块也带上数字：只写「折叠思考」的话，用户在生成期间
            // 展开看内容，反而看不到这段已经花了多少 token。
            "var wantTxt=open?(tokN>0" +
            "?('@@L_FOLD_N@@'+window.fmtTok(tokN)+' tokens）──')" +
            ":'@@L_FOLD@@')" +
            ":(tokN>0" +
            "?('@@L_EXPAND@@'+window.fmtTok(tokN)+' tokens）──')" +
            ":'@@L_EXPAND@@@@L_THINKING@@');" +
            "var txtChanged=(t.textContent!==wantTxt);" +
            "if(txtChanged)t.textContent=wantTxt;" +
            "if(dispChanged||txtChanged){" +
            "window.__thMark(it,b,(dispChanged?'fix:':'txt:')+sig,open);" +
            "}else if(!same){" +
            "window.__thMark(it,b,'apply:'+sig,open);" +
            "}" +
            "}" +
            "};" +
            // ---- 思考块诊断（仅在 debug 包启用）----
            //
            // 现象是「生成中自动折叠、点开又折回」，但脚本逻辑在所有模拟场景下
            // 都正确。剩下的可能是别的路径在写 display，或者是别的层遮住了它。
            // 这里只记账、不修复，记录三类事实：
            //   1) 每次定态：块标识、判定依据、结果、当时的实际 display；
            //   2) 每次点击：点击前/后的 display 与 __thOpen；
            //   3) 每次 display 被外部改写：observer 抓到 style 变更就记一条。
            // 读法：Java 侧落在 ThinkLog 的文件里（debug 包）。
            "window.__thBuf=[];window.__thT0=0;" +
            "window.__thStamp=function(){if(!window.__thT0)window.__thT0=Date.now();" +
            "return ((Date.now()-window.__thT0)/1000).toFixed(2)+'s';};" +
            "window.__thRec=function(s){s=window.__thStamp()+' '+s;" +
            "if(window.__thBuf.length>400)window.__thBuf.shift();" +
            "try{if(window.Android&&Android.thinkTrace)Android.thinkTrace(s);}catch(e){}};" +
            "window.__thLog=function(){return window.__thBuf.join('\\n');};" +
            "window.__thMark=function(it,b,why,open){" +
            "var k='';try{k=window.__thinkKey(it);}catch(e){}" +
            "window.__thRec('mark key='+k+' why='+why+' open='+(open?1:0)+" +
            "' disp='+(b?(b.style.display||'(empty)'):'(no-block)')+" +
            "' live='+(it&&it.getAttribute?it.getAttribute('data-think-live'):'-')+" +
            "' open0='+it.__thOpen+' sig='+it.__thSig);};" +
            "window.__thOnMut=function(tg){" +
            "if(!tg||tg.nodeType!==1)return;" +
            "var cn=''+(tg.className||'');" +
            "if(cn.indexOf('thinking-block')<0&&cn.indexOf('thinking-wrap')<0)return;" +
            "var wr=cn.indexOf('thinking-wrap')>=0?tg:tg.parentNode;" +
            "window.__thRec('mut cn='+cn.slice(0,18)+' disp='+(tg.style.display||'(empty)')+" +
            "' open0='+(wr?wr.__thOpen:'-')+' sig='+(wr?wr.__thSig:'-'));};" +
            "window.__thWatch=function(){" +
            "if(!window.__TH_DIAG||!window.MutationObserver||window.__thObs)return;" +
            "window.__thObs=new MutationObserver(function(muts){" +
            "for(var i=0;i<muts.length;i++){window.__thOnMut(muts[i].target);}});" +
            "var rt=document.getElementById('msgs')||document.body;" +
            "window.__thObs.observe(rt,{subtree:true,attributes:true,attributeFilter:['style']});" +
            // 节流的布局采样：display 说「展开」但块其实看不见时，
            // 必须知道是尺寸为 0、被裁掉、还是被挡住。每 3 秒最多量一次。
            "window.__thGeomTimer=setInterval(function(){" +
            "try{window.__thGeom('poll');}catch(e){}},3000);};" +
            // 布局探针：既然日志证明 display 一直是 block，那「看不见」只可能是
            // 尺寸为 0、被裁掉、或被别的元素盖住。这里把这三件事都量出来。
            "window.__thGeom=function(tag){" +
            // 取「最后一个」思考块：第一个往往是历史消息里的，采样到的是
            // 屏幕外很远的旧节点（日志里 wrapTop=-15923 就是这么来的），
            // 对定位「当前正在生成的块」没有帮助。
            "var _bl=document.getElementsByClassName('thinking-block');" +
            "var b=_bl.length?_bl[_bl.length-1]:null;if(!b)return;" +
            "var wr=b.parentNode;" +
            "var r=b.getBoundingClientRect(),rw=wr?wr.getBoundingClientRect():{top:0,left:0,width:0,height:0};" +
            "var cs=getComputedStyle(b),ws=wr?getComputedStyle(wr):cs;" +
            "var st=b.scrollHeight,ch=b.clientHeight;" +
            "var vh=window.innerHeight||0,med=document.documentElement?document.documentElement.clientHeight:vh;" +
            "var out='geom '+tag+' w='+Math.round(r.width)+' h='+Math.round(r.height)+" +
            "' top='+Math.round(r.top)+' bot='+Math.round(r.bottom)+" +
            "' scrollH='+st+' clientH='+ch+" +
            "' vh='+vh+' docH='+med+" +
            // 页面上并存几份思考块：>1 说明有容器没清干净，用户点到的
            // 那份和脚本操作的那份不是同一个节点，展开状态必然对不上。
            "' wrapN='+(document.getElementsByClassName('thinking-wrap')||[]).length+" +
            "' blkN='+(document.getElementsByClassName('thinking-block')||[]).length+" +
            // 把每一份 wrap 的 key 都列出来：重复节点时能直接看出是哪几份子，
            // 比只有一个总数有用得多。
            "' wrapKeys='+(function(){var ws=document.getElementsByClassName('thinking-wrap');var a=[];" +
            "for(var i=0;i<ws.length&&i<6;i++){try{a.push(window.__thinkKey(ws[i]));}catch(e){a.push('?');}}" +
            "return a.join(',');})()+" +
            "' key='+(function(){try{return window.__thinkKey(b.parentNode);}catch(e){return '?';}})()+" +
            "' open0='+(b.parentNode?b.parentNode.__thOpen:'-')+" +
            "' live='+(b.parentNode?b.parentNode.getAttribute('data-think-live'):'-')+" +
            "' blkDisp='+(cs.display||'-')+' blkVis='+(cs.visibility||'-')+' blkOp='+(cs.opacity||'-')+" +
            "' blkPos='+(cs.position||'-')+' blkCls='+(''+(b.className||'')).slice(0,24)+" +
            "' wrapTop='+Math.round(rw.top)+' wrapH='+Math.round(rw.height)+" +
            "' wrapDisp='+(ws.display||'-')+' wrapOverflow='+(ws.overflow||'-')+" +
            "' parentCls='+(''+((wr&&wr.parentNode&&wr.parentNode.className)||'')).slice(0,24);" +
            "if(r.width>0&&r.height>0){" +
            "var px=r.left+Math.min(r.width/2,40),py=r.top+Math.min(r.height/2,20);" +
            "var el=document.elementFromPoint(px,py);" +
            "out+=' hitAtCenter='+(el?((el.tagName||'')+'.'+(''+(el.className||'')).slice(0,20)):'null')+" +
            "' hitInBlock='+(el&&b.contains(el)?1:0);}" +
            "var p=b,chain='';" +
            "for(var i=0;i<6&&p&&p.nodeType===1;i++){" +
            "var pc=getComputedStyle(p);" +
            "chain+='<'+(p.tagName||'')+'.'+(''+(p.className||'')).slice(0,12)+" +
            "':h'+Math.round(p.getBoundingClientRect().height)+',ov='+(pc.overflow||'-')+'>';" +
            "p=p.parentNode;}" +
            "window.__thRec(out+' chain='+chain);};" +
            "try{window.__thWatch();}catch(e){};" +
            // 点击去重：一次物理点击只能翻转一次。
            //
            // 实测日志（v1.21.33）里出现同一毫秒的两条 click：
            //   key=p5#w-1 click open=1 disp=block
            //   key=p5#t0  click open=0 disp=none
            // 两次操作的是同一个 .thinking-block，5ms 内互相抵消，于是
            // 「点了没反应 / 偶尔能偶尔不能」。原因是思考块在流式途中被
            // 套成了 wrap>toggle>toggle>…>block 的多层结构（日志 chain 里
            // 连出 5 层 thinking-tog），每个 toggle 都带 onclick，点一次
            // 事件冒泡就把它们全触发一遍，奇数层展开、偶数层折回。
            //
            // 两道保险：
            //   1) 新页面传 event 进来，同一个事件对象只处理一次（根治）；
            //   2) 旧页面（已写进 DOM 的 onclick 只传 this）没有 event 可比，
            //      就按「同一个 block 元素在 12ms 内只响应一次」兜住。
            //      冒泡实测间隔 0~5ms，12ms 有 2 倍余量；用户连点最快 80ms 以上，不会误吞。
            "window.toggleThinking=function(a,b2){" +
            "var btn=null,ev=null;" +
            "if(a&&a.nodeType===1){btn=a;}else{ev=a;btn=b2;}" +
            "if(!btn)return;" +
            "if(ev&&ev.__thDone){return;}if(ev){ev.__thDone=1;}" +
            "var b=btn.nextElementSibling;" +
            // 不能只认 btn.parentNode：实测日志里点击时取到的父节点并不是
            // .thinking-wrap（同一时刻页面上并存两份思考块，其中一份的
            // toggle 被包在别的容器里）。那样算出来的 key 少了块序号，
            // 写进去的折叠/展开记录和 applyThinkingStates 读的 key 对不上，
            // 用户点展开就永远不生效——这就是「点了折不动 / 点不开」。
            // 这里向上找最近的 .thinking-wrap，找不到再退回父节点。
            "var w=btn.parentNode,q=btn,g=0;" +
            "while(q&&q.nodeType===1&&g++<8){" +
            "if((''+(q.className||'')).indexOf('thinking-wrap')>=0){w=q;break;}" +
            "q=q.parentNode;}" +
            "if(b&&b.parentNode&&(''+(b.parentNode.className||'')).indexOf('thinking-wrap')>=0){w=b.parentNode;}" +
            // 结构错位时 nextElementSibling 未必是正文块（toggle 被中间层包住），
            // 那时按容器再找一次，避免「点了没反应」。
            "if(!b||(''+(b.className||'')).indexOf('thinking-block')<0){" +
            "b=w?w.querySelector('.thinking-block'):null;}" +
            "if(!b)return;" +
            // 旧 DOM 的 onclick 只传了 this，没有 event 可比；多层 toggle
            // 嵌套时仍会被触发多次。这里按 block 元素做 70ms 窗口去重。
            "var now=Date.now(),cs=window.__thClick;" +
            "if(cs&&cs.el===b&&now-cs.t<12)return;" +
            "window.__thClick={el:b,t:now};" +
            "var k=w?window.__thinkKey(w):null;" +
            "var open=b.style.display==='none';" +
            "b.style.display=open?'block':'none';" +
            // 展开后折叠条写「折叠思考」；已经估出数字的块把数字带上，
            // 与收起时那条「展开思考（N tokens）」保持同一个口径。
            "if(open){var tf=parseInt(btn.getAttribute('data-tok'),10);" +
            "btn.textContent=(tf>0)?('@@L_FOLD_N@@'+window.fmtTok(tf)+' tokens）──')" +
            ":'@@L_FOLD@@';}" +
            "else{" +
            // 数值取渲染时写进 data-tok 的源码估算值。data-tok=0 表示思考还在输出
            // （还没有可信的最终数字），这时显示「思考中…」而不是拿正文长度瞎估一个数。
            "var t=parseInt(btn.getAttribute('data-tok'),10);" +
            "btn.textContent=(t>0)?('@@L_EXPAND@@'+window.fmtTok(t)+' tokens）──')" +
            ":'@@L_EXPAND@@@@L_THINKING@@';}" +
            "if(k)window.__thinkOpen[k]=open;" +
            // 记录戳上当前轮次：applyThinkingStates 靠它区分「本轮用户点的」
            // 与「上一轮遗留的」，避免同一轮内的重建把这条记录当残留删掉。
            "if(k)window.__thinkRnd[k]=window.__thRound;" +
            "window.__thMark(w,b,'click',open);" +
            // 节点上的定态标记同步改掉：否则下一次重建时这个节点还是「已定态」，
            // 会被 applyThinkingStates 直接跳过，于是折叠/展开状态看起来没生效
            "if(w)w.__thOpen=open?1:0;};" +
            "window.__toolOpen=window.__toolOpen||{};" +
            // 工具卡片同样逐「张」记状态（__tcSet），不再用内容指纹：
            // 指纹里带最后一张卡片的 textContent.length，一次搜索/抓取在返回结果时
            // 会反复长，指纹每帧都变 → 每次都把所有卡片重扫一遍并重写 display。
            // 卡片越多，这段开销越大，而且 style 抖动会让整块区域跟着重排。
            //
            // __tcSet 取卡片集合：用按类名的活动集合读，length/索引都是常数级，
            // 不必每帧在整棵 tail 子树里做选择器查询。
            "window.__tcSet=function(root){" +
            "if(!root||!root.getElementsByClassName)return null;" +
            "return root.getElementsByClassName('tool-card');};" +
            "window.applyToolStates=function(root){" +
            "if(!root)return;" +
            "var cards=window.__tcSet(root);" +
            "if(!cards||cards.length===0)return;" +
            "for(var i=0;i<cards.length;i++){" +
            "if(cards[i].__tcDone===1)continue;" +
            "var k=cards[i].getAttribute('data-tool-key');" +
            "var b=cards[i].querySelector('.tool-body');" +
            "var h=cards[i].querySelector('.tool-head-click');" +
            "if(!b)continue;" +
            "var a=h?h.querySelector('.tool-arrow'):null;" +
            "var hint=h?h.querySelector('.tool-hint'):null;" +
            "var open=!!(k&&window.__toolOpen[k]);" +
            "if(open){b.style.display='block';" +
            "if(a)a.textContent='▼';if(hint)hint.textContent='';}" +
            "else{b.style.display='none';" +
            "if(a)a.textContent='▶';" +
            "if(hint)hint.textContent='@@L_TAP@@';}" +
            "cards[i].__tcDone=1;}" +
            // 每次重绘落地后按 key 恢复思考块展开状态（跨重建不丢）。
            "window.applyThinkingStates(root);" +
            "window.applyToolFold(root);};" +
            // ---- 工具卡片折叠：只折显示，不删数据 ----
            // trace 里的卡片一条都不删，这里只控制可见性：单条消息超过 40 张时，
            // 更早的折成一行「更早的 N 条工具记录 · 点开」，点一下原地展开。
            // 早先的做法是从 trace 里真删，用户看到的是卡片凭空消失，重开会话也回不来；
            // 而现在的增量渲染（streamAppendBase）只解析新增块，本来就不需要靠删数据控成本。
            "window.__toolFoldLimit=40;" +
            "window.applyToolFold=function(root){" +
            "if(!root||!root.querySelectorAll)return;" +
            "var msgs=[];" +
            "var cn=' '+((root.className&&root.className.baseVal)||root.className||'')+' ';" +
            "if(cn.indexOf(' msg ')>=0){msgs=[root];}" +
            "else{var up=root.parentNode;" +
            "while(up&&up.nodeType===1){" +
            "var ucn=' '+(up.className||'')+' ';" +
            "if(ucn.indexOf(' msg ')>=0){msgs=[up];break;}up=up.parentNode;}" +
            "if(msgs.length===0)msgs=root.querySelectorAll('.msg');}" +
            "for(var i=0;i<msgs.length;i++)foldMsgTools(msgs[i]);};" +
            "function foldMsgTools(msg){" +
            // 活动集合：length 常数级，不必每帧在整条消息里做选择器查询
            "var cards=msg.getElementsByClassName('tool-card');" +
            "var n=cards.length;var bar=msg.querySelector('.tool-fold');" +
            "if(n<=window.__toolFoldLimit){" +
            "if(bar&&bar.parentNode)bar.parentNode.removeChild(bar);" +
            "msg.__foldHidden=-1;return;}" +
            "var hidden=n-window.__toolFoldLimit;var open=!!msg.__foldOpen;" +
            "var created=false;" +
            "if(!bar){bar=document.createElement('div');bar.className='tool-fold';" +
            "bar.setAttribute('onclick','toggleToolFold(this)');" +
            "cards[0].parentNode.insertBefore(bar,cards[0]);created=true;}" +
            // 新建的 bar 必须无条件重写：updateLastMsg/streamBase 用 innerHTML 重建
            // 消息体时会把旧 bar 一起销毁，但 __foldHidden/__foldShown 还挂在 msg 上，
            // 命中下面的 memo 就会留下一个空 bar，且前 hidden 张卡全是展开状态。
            "if(!created&&msg.__foldHidden===hidden&&msg.__foldShown===open)return;" +
            "msg.__foldHidden=hidden;msg.__foldShown=open;" +
            // 只改需要改的那几张：几百张卡片全量写 display 会让每次追加都重排一遍
            "for(var j=0;j<hidden;j++)cards[j].style.display=open?'':'none';" +
            "for(var k=hidden;k<n;k++)if(cards[k].style.display)cards[k].style.display='';" +
            "bar.innerHTML=open?('@@L_FOLD_EARLIER@@'.replace('{n}',hidden))" +
            ":('@@L_EARLIER_TOOLS@@'.replace('{n}',hidden));}" +
            "window.toggleToolFold=function(bar){" +
            "var msg=bar.parentNode;" +
            "while(msg&&msg.nodeType===1){" +
            "var cn=' '+(msg.className||'')+' ';" +
            "if(cn.indexOf(' msg ')>=0)break;msg=msg.parentNode;}" +
            "if(!msg||msg.nodeType!==1)return;" +
            // 展开状态挂在消息节点上：增量追加不会销毁这个节点，状态自然保持；
            // 整段重绘（切会话）会重建节点，重新折叠也是对的。
            "msg.__foldOpen=!msg.__foldOpen;window.applyToolFold(msg);};" +
            "window.toggleTool=function(h){" +
            "var card=h.parentNode;while(card&&card.className.indexOf('tool-card')<0)card=card.parentNode;" +
            "var b=h.nextElementSibling;if(!b)return;" +
            "var k=card?card.getAttribute('data-tool-key'):null;" +
            "var open=b.style.display==='none';" +
            "b.style.display=open?'block':'none';" +
            "var a=h.querySelector('.tool-arrow');var hint=h.querySelector('.tool-hint');" +
            "if(a)a.textContent=open?'▼':'▶';" +
            "if(hint)hint.textContent=open?'':'@@L_TAP@@';" +
            "if(k)window.__toolOpen[k]=open;" +
            "if(card)card.__tcDone=1;};" +
            "window.toggleSources=function(h){" +
            "var b=h.nextElementSibling;if(!b)return;" +
            "var open=b.style.display==='none';" +
            "b.style.display=open?'block':'none';" +
            "var a=h.querySelector('.src-arrow');if(a)a.textContent=open?'▼':'▶';};" +
            "window.updateLastSources=function(html){" +
            "var ms=document.querySelectorAll('.msg.ai .sources');" +
            "if(ms.length>0){ms[ms.length-1].innerHTML=html;}" +
            "smartScrollToBottom();};" +
            // 收尾时补一条「时间 · token · 速度」。挂在 .content 之外，
            // 必须在 finalizeLast 之前调用——之后 data-idx 就变成真实下标了。
            "window.updateLastMeta=function(html){" +
            "var ms=document.querySelectorAll('.msg.ai');" +
            "if(ms.length===0)return;" +
            "var last=ms[ms.length-1];" +
            // 旧的 meta 与逐轮明细一起清掉：meta 现在可能是两个节点（主行 + 明细行），
            // 只替换第一个会把上一次的明细留在原地，越刷越多行。
            "var olds=last.querySelectorAll('.msg-meta,.msg-meta-sub');" +
            "for(var oi=0;oi<olds.length;oi++){if(olds[oi].parentNode)olds[oi].parentNode.removeChild(olds[oi]);}" +
            "var d=document.createElement('div');d.innerHTML=html||'';" +
            "var menu=last.querySelector('.ai-menu-wrap');" +
            "while(d.firstChild){" +
            "var node=d.firstChild;d.removeChild(node);" +
            "if(menu)last.insertBefore(node,menu);else last.appendChild(node);}};" +
            // ---- 更早消息的懒加载 ----
            "window.setOlderHint=function(remaining){" +
            "window.__loadingOlder=false;" +
            "var h=document.getElementById('older-hint');if(!h)return;" +
            "if(remaining>0){window.__olderDone=false;h.style.display='block';" +
            "h.textContent='@@L_SCROLL_EARLIER@@'.replace('{n}',remaining);}" +
            "else{window.__olderDone=true;h.style.display='block';h.textContent='@@L_EARLIEST@@';" +
            "setTimeout(function(){h.style.display='none';},1200);}};" +
            "window.loadOlderNow=function(){" +
            "if(window.__olderDone||window.__loadingOlder)return;" +
            "window.__loadingOlder=true;" +
            "if(window.Android)Android.loadOlderMessages();};" +
            // 只复位闸门，不改提示文案（用于 Java 侧丢弃批次时）
            "window.resetOlderGate=function(){window.__loadingOlder=false;};" +
            "window.prependOlder=function(html,remaining){" +
            "var msgs=document.getElementById('msgs');if(!msgs)return;" +
            "var d=document.createElement('div');d.innerHTML=html;" +
            "var frag=document.createDocumentFragment();" +
            "while(d.firstChild)frag.appendChild(d.firstChild);" +
            "var doc=document.documentElement;" +
            // 记住插入前的总高度，插入后按高度差把视口下移，视觉上内容不动
            "var before=doc.scrollHeight||document.body.scrollHeight;" +
            "msgs.insertBefore(frag,msgs.firstChild);" +
            "var after=doc.scrollHeight||document.body.scrollHeight;" +
            "var delta=after-before;" +
            "if(delta>0)window.scrollTo(0,(window.pageYOffset||doc.scrollTop||0)+delta);" +
            "addCopyBtns();applyToolStates(document);" +
            "window.__loadingOlder=false;setOlderHint(remaining);};" +
            "var __olderTicking=false;" +
            "window.addEventListener('scroll',function(){" +
            "if(__olderTicking)return;__olderTicking=true;" +
            "setTimeout(function(){" +
            "__olderTicking=false;" +
            "var st=document.documentElement.scrollTop||document.body.scrollTop||window.pageYOffset||0;" +
            "var sh=Math.max(document.documentElement.scrollHeight||0,document.body.scrollHeight||0);" +
            "var ch=document.documentElement.clientHeight||document.body.clientHeight||window.innerHeight||0;" +
            // 回到底部气泡：离底超过 160px 才出现。用 160 而不是 0——
            // 正常阅读时最后一行常被键盘/输入栏挡掉一点，差几十像素就弹个按钮太吵。
            "if(window.Android&&Android.setScrollBubble)Android.setScrollBubble(st+ch<sh-160);" +
            // 用户自己滚回底部（或内容变短后自然贴底）就恢复跟随，
            // 否则上翻过一次之后，这一整轮都不会再自动滚了
            "if(st+ch>=sh-24)window.__userUp=false;" +
            "if(window.__olderDone||window.__loadingOlder)return;" +
            "if(st<=40)loadOlderNow();},150);});" +
            // 首屏也要判一次：否则从历史会话打开、停在顶部时气泡不会出现
            "setTimeout(function(){var st=document.documentElement.scrollTop||document.body.scrollTop||window.pageYOffset||0;" +
            "var sh=Math.max(document.documentElement.scrollHeight||0,document.body.scrollHeight||0);" +
            "var ch=document.documentElement.clientHeight||document.body.clientHeight||window.innerHeight||0;" +
            "if(window.Android&&Android.setScrollBubble)Android.setScrollBubble(st+ch<sh-160);},400);" +
            "window.markUserFailed=function(idx,cardHtml){" +
            "var all=document.querySelectorAll('.msg.user');" +
            "for(var i=0;i<all.length;i++){" +
            "if(all[i].getAttribute('data-idx')===''+idx){" +
            "all[i].className='msg user failed';" +
            "var old=all[i].querySelector('.error-card');" +
            "if(old&&old.parentNode)old.parentNode.removeChild(old);" +
            "var d=document.createElement('div');d.innerHTML=cardHtml;" +
            "var card=d.firstElementChild;" +
            "var menu=all[i].querySelector('.user-menu-wrap');" +
            "if(card){if(menu)all[i].insertBefore(card,menu);else all[i].appendChild(card);}" +
            // 用户消息失败：这条是他自己刚发的，必须滚到能看见，不能等用户去找
            "window.__userUp=false;smartScrollToBottom(true);" +
            "return;}}};" +
            "window.__userUp=false;" +
            "window.__touchY=null;" +
            // 只认「手指往上拖」（看历史）这一个信号。早先靠「离底超过 80px」判断，
            // 生成快的时候一帧追加就能顶出几百像素，程序自己把视口甩开，然后被当成
            // 用户上翻，从此再也不跟随——表现就是「回答一快就停在半截不滚了」。
            "document.addEventListener('touchstart',function(e){" +
            "if(e.touches&&e.touches.length)window.__touchY=e.touches[0].clientY;},true);" +
            "document.addEventListener('touchmove',function(e){" +
            "if(!e.touches||!e.touches.length)return;" +
            "var y=e.touches[0].clientY;" +
            "if(window.__touchY!=null){" +
            // 只认「手指下滑 = 往回看」。反方向（上滑）一律不解除 __userUp：
            // 生成过程中内容一直在长，用户常常只是往上推一点想看被输入栏挡住的行，
            // 旧版 8px 就把它当成「我要回到底部」，于是刚滑开就被自动滚底吸回去。
            // 想回底的人会一直滑到底，scroll 事件里「贴底即恢复跟随」那条足够。
            "if(y>window.__touchY+8)window.__userUp=true;}" +
            "window.__touchY=y;},true);" +
            "window.smartScrollToBottom=function(force){" +
            // 手指按在可横滑容器上：不要拽视口，否则表格被滚出屏幕，看起来就是「滑不动」
            "if(window.__holdRender)return;" +
            // 强制滚动（点气泡、切会话、发新消息）视为用户想回到最新，顺手解除「上翻」状态，
            // 否则滚到底之后新内容还是不跟随
            "if(force)window.__userUp=false;" +
            // 非强制滚动时只看用户意图，不看距离：内容长高造成的落后必须继续跟随，
            // 否则生成越快越容易「自动滚动停住」。用户真的往上翻时 __userUp 已置位。
            "if(!force&&window.__userUp)return;" +
            // 浏览器允许滚动的范围是「内容高 - 视口高」，直接写内容高会被夹到上限；
            // 夹不上去时（低端机上 innerHeight 与外层布局偶尔对不上）末尾那几行
            // 会永远停在视口下方，看起来就是「滚到底了但最后两行被挡住」。
            // 上限外再给 200px，剩下的交给浏览器自己夹。同一次任务里不重复设置：
            // 值没变时写 scrollTo 也会触发一次排版，压帧率。
            // 读 scrollHeight / clientHeight 会强制排版（回流）。这里必须只读一次：
            // 旧实现在同一个函数体里把同一段计算写了两遍，每帧多出两次同步回流，
            // 而流式每 100ms 就调一次——长回答上这是掉帧的固定开销。
            "var maxY=Math.max(document.documentElement.scrollHeight||0," +
            "document.body.scrollHeight||0);" +
            "var topY=maxY-(document.documentElement.clientHeight||document.body.clientHeight||0)+200;" +
            "if(topY<maxY)topY=maxY;" +
            "var real=(document.documentElement.scrollTop||document.body.scrollTop||0)" +
            "+document.documentElement.clientHeight;" +
            "if(window.__smLastTop!==topY||real<maxY-40){" +
            "window.__smLastTop=topY;window.scrollTo(0,topY);}" +
            // 滚到底了，气泡立刻收起来，不用等下一次 scroll 事件（force 滚动不一定触发）
            "if(window.Android&&Android.setScrollBubble)Android.setScrollBubble(false);};" +
            "window.runScripts=function(root){" +
            "if(!root||!root.querySelectorAll)return;" +
            "var ss=root.querySelectorAll('script');" +
            "for(var i=0;i<ss.length;i++){" +
            "var o=ss[i],n=document.createElement('script');" +
            "for(var j=0;j<o.attributes.length;j++){n.setAttribute(o.attributes[j].name,o.attributes[j].value);}" +
            "if(!o.src)n.text=o.textContent;" +
            "o.parentNode.replaceChild(n,o);}};" +
            // 空对话的欢迎语是铺满整屏的固定层：它只在「一条消息都没有」时有意义，
            // 一旦有消息进来必须立刻摘掉，否则会一直盖在气泡上（用户看到的
            // 「发了消息欢迎语不消失」）。整页重建时由 Java 侧决定不再插入，
            // 这里负责「往同一页里追加消息」这条路径。
            "window.__dropWelcome=function(){" +
            "var w=document.querySelector('.welcome-empty');" +
            "if(w&&w.parentNode)w.parentNode.removeChild(w);};" +
            "window.appendMsg=function(html){" +
            "window.__dropWelcome();" +
            "var d=document.createElement('div');d.innerHTML=html;" +
            "var el=d.firstElementChild;" +
            "if(el){document.getElementById('msgs').appendChild(el);runScripts(el);}" +
            // 刚发出的消息必须看得见：非强制滚动会被「用户上翻过」的标记拦住
            "addCopyBtns();applyToolStates(document);if(el)window.__scrollNew();};" +
            // 状态行挂在 .msg.ai 下、.content 之外。以前它被拼进 .content 的
            // innerHTML，下一次 streamTail 重建容器时会被当作 base 的一部分留下，
            // 表现为「一直卡在正在整理搜索结果」。
            "window.__lastAi=function(){var a=document.querySelectorAll('.msg.ai');" +
            "return a.length?a[a.length-1]:null;};" +
            // 追加类脚本可以带上目标气泡的下标：页面在这条消息之后又被整页重建过
            // （主题切换、上滑加载、另一版页面就绪更晚），按「最后一个 .msg.ai」去找
            // 会写进别人的气泡，再滚动到别处——用户看到的就是「发完消息没反应」。
            // 带了 idx 就按下标精确找；找不到说明这一版根本没画这条消息，
            // 交给下面按尾部气泡兜底（宁可写在最后，也不要什么都不写）。
            "window.__msgAi=function(idx){" +
            "if(idx==null||idx===''||idx==='-1')return window.__lastAi();" +
            // 属性选择器必须带引号：不带引号时 data-idx=3 会被当成标签名解析
            "var el=document.querySelector(\".msg.ai[data-idx='\"+idx+\"']\");" +
            "return el||window.__lastAi();};" +
            // 新消息追加后一律强制滚到底：非强制滚动会被「用户上翻过」的标记拦住，
            // 而刚发出的气泡本就必须可见。
            "window.__scrollNew=function(){smartScrollToBottom(true);};" +
            "window.__aiContent=function(idx){" +
            "var m=window.__msgAi(idx);return m?m.querySelector('.content'):null;};" +
            "window.setStatus=function(text){" +
            "var m=window.__lastAi();if(!m)return;" +
            "var s=m.querySelector('.tool-status');" +
            "if(!s){s=document.createElement('div');s.className='tool-status';" +
            "var c=m.querySelector('.content');" +
            "if(c&&c.nextSibling)m.insertBefore(s,c.nextSibling);else m.appendChild(s);}" +
            "s.textContent=text;smartScrollToBottom();};" +
            // clearStatus 只负责移除工具/状态节点，不碰 __retryTimer：它在流式收尾里
            // 被调用 9 次，若顺带停表，429 等待期间任意一帧收尾都会把倒计时掐死。
            // 定时器改为在 tick 里自检节点是否已脱离文档后自行收表（见下）。
            "window.clearStatus=function(){" +
            "var m=window.__lastAi();if(!m)return;window.__clearStatusIn(m);};" +
            // 只清「进度提示」，不清 429 等待卡片：后者带倒计时与「立即重试」，
            // 被正文到达顺手抹掉的话，用户就没法在退避期间点重试了。
            "window.__clearStatusIn=function(el){" +
            "var m=el;while(m&&m!==document.body){" +
            "var cn=' '+(m.className||'')+' ';" +
            "if(cn.indexOf(' msg ')>=0)break;m=m.parentElement;}" +
            "if(!m||m===document.body)return;" +
            "var ss=m.querySelectorAll('.tool-status');" +
            "for(var i=ss.length-1;i>=0;i--){" +
            "if(ss[i].querySelector('.retry-btn'))continue;" +
            "if(ss[i].parentNode)ss[i].parentNode.removeChild(ss[i]);}};" +
            // 429 等待卡片。按钮点击绕道 Java bridge：请求线程在 Java 侧，
            // 只有那边能叫醒它，JS 自己改不了任何东西。
            // reason 是服务端 429 响应体里解析出的具体原因（配额/频率/TPM）。
            // 放在卡片里常驻，不用 Toast——Toast 一闪而过根本看不清。
            "window.showRetryCard=function(attempt,max,waitMs,reason){" +
            "var m=window.__lastAi();if(!m)return;" +
            "var s=m.querySelector('.tool-status');" +
            "if(!s){s=document.createElement('div');s.className='tool-status retry';" +
            "var c=m.querySelector('.content');" +
            "if(c&&c.nextSibling)m.insertBefore(s,c.nextSibling);else m.appendChild(s);}" +
            "s.className='tool-status retry';" +
            "var left=Math.max(1,Math.ceil(waitMs/1000));" +
            "var paint=function(){" +
            "s.innerHTML='<button class=\"retry-btn\" onclick=\"Android.retryNow()\">@@L_RETRY@@</button>'" +
            "+'<span class=\"retry-text\">'+'@@L_RETRY_IN@@'.replace('{n}',left).replace('{a}',attempt).replace('{m}',max)+'</span>'" +
            "+'<div class=\"retry-reason\"></div>';" +
            // 用 textContent 而不是拼进 innerHTML：服务端文案可能带引号/尖括号
            "var r=s.querySelector('.retry-reason');" +
            "if(r)r.textContent=reason&&reason.length?('@@L_429_PREFIX@@'+reason)" +
            ":'@@L_429_DEFAULT@@';};" +
            "paint();" +
            "if(window.__retryTimer)clearInterval(window.__retryTimer);" +
            "window.__retryTimer=setInterval(function(){" +
            // 卡片被 clearStatus/hideRetryCard 移除后，闭包里的 s 已脱离文档：
            // 自行收表，避免旧定时器每秒空转、倒计时结束还往 detached 节点写文案。
            "if(!s.parentNode){clearInterval(window.__retryTimer);window.__retryTimer=null;return;}" +
            "left--;" +
            "var t=s.querySelector('.retry-text');" +
            "if(left<=0){clearInterval(window.__retryTimer);window.__retryTimer=null;" +
            "if(t)t.textContent='@@L_RETRYING@@';return;}" +
            "if(t)t.textContent='@@L_RETRY_IN@@'.replace('{n}',left).replace('{a}',attempt).replace('{m}',max);},1000);" +
            // 卡片插进来本身会顶高内容，不滚就藏在输入栏下面，用户只看到「卡住了」
            "smartScrollToBottom();};" +
            "window.hideRetryCard=function(){" +
            "if(window.__retryTimer){clearInterval(window.__retryTimer);window.__retryTimer=null;}" +
            "var m=window.__lastAi();if(!m)return;" +
            "var s=m.querySelector('.tool-status');" +
            "if(s&&s.parentNode)s.parentNode.removeChild(s);" +
            // 移除后高度骤降，随后的错误卡片或收尾内容同样需要贴底
            "smartScrollToBottom();};" +
            "window.updateLastMsg=function(html,idx){" +
            // 指定了目标气泡就按 data-idx 精确取，见 __msgAi
            "var c=window.__aiContent(idx);" +
            "if(!c){" +
            // 页面里一条 AI 气泡都没有（换版瞬间、或只剩用户消息）：先建一个，
            // 否则这段正文会被静默丢弃
            "window.appendAiDiv(idx);c=window.__aiContent(idx);}" +
            "if(!c)return;" +
            // 这里不缓存：它是「整条消息定稿」，多半发生在页面重灌之后，
            // 缓存下来的旧内容会在下一次新建气泡时被错误写入。页面未就绪的情况
            // 由 Java 侧的 JS 队列兜住（onPageFinished 后重放）。
            // html 是这条消息的完整内容，被闸门挡住的缓存帧已经在里面了：
            // 直接丢弃，否则收尾后 tail 会再追加一次，末尾一段重复。
            "window.__pendingTailHtml=null;window.__pendingTailText=null;" +
            "window.__pendingBaseHtml=null;window.__endHold();" +
            // 已有 base/tail 骨架时把整段 HTML 写进 base、清空 tail：
            // 若这里改回「整体替换 c.innerHTML」，收尾一次就把骨架抹掉，
            // 之后续跑只能走 fallback 重建，等于又闪一次。
            "var b=c.querySelector('.stream-base');" +
            "var t=c.querySelector('.stream-tail');" +
            // 清空内容的同时必须把 plain 类摘掉：.stream-tail.plain::after 画的是
            // 流式光标 ▍，只清 innerHTML 不摘类的话，光标会孤零零留在最后占一整行
            // 且永不消失（用户反馈的「生成完末尾多一行光标」）。
            "if(b){b.innerHTML=html;if(t){" +
            // 骨架补回渐显两层，否则续跑时渐显没有落点
            "t.innerHTML='';" +
            "t.className='stream-tail';}}" +
            "else{c.className='content';c.innerHTML=html;}" +
            "applyToolStates(c);" +
            "clearStatus();" +
            "addCopyBtns();smartScrollToBottom();};" +
            // 收尾定型：先把被触摸闸门挡住的最后一帧落地，再把 tail 并进 base。
            // 顺序反了会把这帧搬到定型后的 base 后面，末尾一段重复。
            "window.streamFinalize=function(idx){" +
            "window.__flushPending(idx);window.streamCommitTail(idx);clearStatus();};" +
            // 流式输出拆成两段：base（已完成的思考/工具卡片）+ tail（本轮增量）。
            // 以前每个分片都整体重设 .content，工具轮次结束后上面的卡片和已渲染好的
            // markdown 会被打回纯文本，看起来像「又变回没渲染的样子」。
            // 把 tail 里的临时卡片容器并进 base：base 里已有的按 key 跳过，
            // 既不丢卡片，也不会「base 一份、tail 一份」地重复。
            "window.__absorbLive=function(t,b){" +
            "var live=t.querySelector?t.querySelector('.tool-live'):null;" +
            "if(!live)return;" +
            // 每次都重新取：上面每搬一张卡片，b 的子树都在变
            "var have=b.getElementsByClassName('tool-card');" +
            "while(live.firstChild){" +
            "var card=live.firstChild;" +
            "var key=card.getAttribute?card.getAttribute('data-tool-key'):null;" +
            "if(key){" +
            "var dup=false;" +
            "for(var hi=0;hi<have.length;hi++){" +
            "if(have[hi].getAttribute('data-tool-key')===key){dup=true;break;}}" +
            "if(dup){live.removeChild(card);continue;}}" +
            "b.appendChild(card);}" +
            "if(live.parentNode)live.parentNode.removeChild(live);};" +
            "window.streamBase=function(html,idx){" +
            // 指定了目标气泡就按 data-idx 精确取，见 __msgAi
            "var c=window.__aiContent(idx);" +
            "if(!c){" +
            // 内容比气泡先到：记下来，appendAiDiv 建好气泡后会补写（见下）
            "window.__orphanBase=html;return;}" +
            // 手指按在表格/代码块上：先缓存，抬手后由 __flushPending 写入。
            // 直接吞掉不行——Java 侧的 lastStreamTrace 已按「已推送」记账，
            // 这一帧丢了就再也不会补，卡片会凭空消失。
            "window.__guardHold();" +
            "if(window.__holdRender){if(html)window.__pendingBaseHtml=html;return;}" +
            "var b=c.querySelector('.stream-base');" +
            "var t=c.querySelector('.stream-tail');" +
            "if(!b||!t){" +
            // 历史消息 / 续跑等没有骨架的情况：把已有内容整体当 base 保住，
            // 再补出 tail，后续写入就只动各自的层
            "var keep=c.innerHTML;" +
            "c.className='content';" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "b=c.querySelector('.stream-base');t=c.querySelector('.stream-tail');" +
            "if(keep)b.innerHTML=keep;}" +
            "if(html)b.innerHTML=html;" +
            // 关掉实时渲染时，base 重建必须带上此前已经流出去的正文：同一轮的正文
            // 在调用工具前是纯文本 tail，调用结束后若只重渲染「卡片 + 新一轮正文」，
            // 那一段会被直接从屏幕上抹掉（切走再回来才对得上）。
            "var tw=window.__takeTailText(t);" +
            "if(tw&&tw.length)b.appendChild(tw);" +
            "window.__absorbLive(t,b);" +
            "applyToolStates(b);addCopyBtns(b);clearStatus();};" +
            // 取出 tail 里的正文纯文本（含实时卡片容器里的），搬成 base 里的一个块。
            // 结构约定见 liveTools：正文按出现顺序被 .tool-live 分成若干段。
            "window.__takeTailText=function(t){" +
            "if(!t)return null;" +
            "var out='',nodes=t.childNodes;" +
            "for(var i=0;i<nodes.length;i++){var n=nodes[i];" +
            "if(n.className&&(''+n.className).indexOf('tool-live')>=0)continue;" +
            "var s=n.textContent||'';if(s.length)out+=(out?String.fromCharCode(10):'')+s;}" +
            "if(!out.length)return null;" +
            "var pw=document.createElement('div');pw.className='stream-plain';pw.textContent=out;" +
            "return pw;};" +
            // 增量追加：只把「本轮新增的那几张卡片」解析后挂到 base 末尾。
            // 与 streamBase 的区别是不动已有子树——已经渲染好的公式图片、代码高亮、
            // 展开状态全部原样保留，也不重新解析几万字的历史 markdown。
            // 没有骨架时按 streamBase 的兜底把现有内容整体当 base，绝不丢内容。
            "window.streamAppendBase=function(html,idx){" +
            // 指定了目标气泡就按 data-idx 精确取，见 __msgAi
            "var c=window.__aiContent(idx);" +
            "if(!c)return;" +
            "var b=c.querySelector('.stream-base');" +
            "if(!b){" +
            "var keep=c.innerHTML;" +
            "c.className='content';" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "b=c.querySelector('.stream-base');" +
            "if(keep)b.innerHTML=keep;}" +
            "if(html){var d=document.createElement('div');d.innerHTML=html;" +
            "var frag=document.createDocumentFragment();" +
            "while(d.firstChild)frag.appendChild(d.firstChild);" +
            "b.appendChild(frag);}" +
            "applyToolStates(b);addCopyBtns(b);clearStatus();};" +
            // 轮次切换：把本轮已经流出去的 tail 并进 base。
            // 这里用 DOM 节点搬移而不是 innerHTML 拼接 —— 不重新解析 markdown、
            // 不重新解码公式图片，视觉上完全不闪，内容也不会重复或丢字。
            // 骨架不存在时（收尾后 updateLastMsg 平铺、续跑的历史消息）必须现建：
            // 直接 return 会让 Java 侧以为 base 已推送，续跑时工具卡片永远不出现。
            "window.streamCommitTail=function(idx){" +
            "window.__smSettle(idx>=0?window.__aiContent(idx):null);" +
            // commit 已把本轮 tail 整体搬进 base，hold 期间缓存的那一帧也在其中。
            // 不清掉的话，抬手后 __flushPending 会把旧 tail 再写回新 tail，正文重复一遍。
            // 只清「帧缓存」，不清纯文本前缀：前缀就是上一轮已经流出去的正文，
            // 它马上就要被搬进 base；清掉会让屏幕上少一段（下一轮重渲染时才补回来，
            // 中间那段空白用户是看得见的）。
            "window.__pendingTailHtml=null;window.__pendingTailText=null;window.__pendingBaseHtml=null;" +
            "window.__endHold();" +
            // 指定了目标气泡就按 data-idx 精确取，见 __msgAi
            "var c=window.__aiContent(idx);" +
            "if(!c)return;" +
            "var b=c.querySelector('.stream-base');" +
            "var t=c.querySelector('.stream-tail');" +
            "if(!b||!t){" +
            "var keep=c.innerHTML;" +
            "c.className='content';" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "b=c.querySelector('.stream-base');t=c.querySelector('.stream-tail');" +
            "if(keep){b.innerHTML=keep;window.applyThinkingStates(b);}return;}" +
            // 纯文本 tail 不能直接搬文本节点：base 里装的是 HTML 卡片，没有
            // pre-wrap，裸文本落进去会被塌成一行 —— 关掉实时渲染时，回答一
            // 结束整条就变成一坨未渲染的原文，必须重进会话才恢复。
            // 包一层 .stream-plain 再搬，换行与缩进都能保住。
            "if((''+t.className).indexOf('plain')>=0){" +
            "var tx=t.textContent;" +
            "if(tx&&tx.length){var pw=document.createElement('div');" +
            "pw.className='stream-plain';pw.textContent=tx;b.appendChild(pw);}" +
            "t.innerHTML='';}" +
            // 关掉实时渲染时的临时卡片容器：卡片内容搬进 base，容器本身丢掉。
            // 这里必须搬节点而不是删容器——base 是从 trace 重建的，而 trace 的
            // 「已渲染」记账已经推进到含这些卡片，删掉就再也没人补回来，用户看到
            // 的就是「工具卡片显示完成后消失，直到整条消息结束才重新出现」。
            "window.__absorbLive(t,b);" +
            "while(t.firstChild){var ck=t.firstChild;" +
            // 渐显的显示层里装的是真实已显示内容，必须搬——只丢容器本身。
            // （旧代码把整层连同内容一起删掉，base 又被记成「已渲染到全文」，
            // 这一轮已经显示出来的正文就永久消失了。）
            "if(ck.nodeType===1&&(' '+ck.className+' ').indexOf('smooth-shown')>=0){" +
            "while(ck.firstChild)b.appendChild(ck.firstChild);" +
            "t.removeChild(ck);continue;}" +
            "b.appendChild(ck);}" +
            "t.className='stream-tail';t.innerHTML='';" +
            // 归档之后必须把渐显层的记账一起清掉：__sm.arrived 里还是「包含
            // 刚搬进 base 的全部内容」的源码，st.root/stack 指着已经被搬走的
            // 旧显示层。下一帧增量渲染会拿这份 arrived 在新建的 .smooth-shown
            // 里重画一遍，于是 base 一份、tail 又一份——同一段思考在页面上
            // 并存两个节点，用户点的是其中一份，脚本操作的是另一份，
            // 展开状态永远对不上（日志里 key 尾巴为空、open0 反复 undefined
            // 就是这么来的）。清成空串后，下一帧只会画真正的新内容。
            "try{window.__sm.arrived='';window.__sm.root=null;window.__sm.stack=[];" +
            "window.__sm.cur=0;window.__sm.paintUnits=null;window.__sm.gi=null;" +
            "window.__sm.stackChars=null;window.__sm.stackPending=null;" +
            "window.__sm.units=[];window.__sm.chars=0;window.__sm.show=0;}catch(e){}" +
            // 搬进来的 tail 节点按其 key 恢复展开状态（状态存在 __thinkOpen 里）
            "clearStatus();window.applyThinkingStates(b);};" +
            // ---- 横向滚动保护 ----
            // 流式每 200ms 重建一次 tail，正在拖动的表格节点会被整棵销毁：
            // scrollLeft 归零、手指的拖拽序列中断，表现就是「生成时表格滑不动」。
            // 手指按在可横滑容器上时暂停重绘，抬手 180ms 后补最后一帧；
            // 非触摸场景（工具卡片追加）则保存并恢复 scrollLeft。
            "window.__holdRender=false;window.__holdToken=0;window.__holdSince=0;" +
            "window.__HOLD_MAX_MS=1200;window.__holdWatchdog=0;" +
            "window.__pendingTailHtml=null;window.__pendingTailText=null;window.__pendingBaseHtml=null;" +
            // 气泡还没出现时先到的内容（见 appendAiDiv 里的补写逻辑）
            "window.__orphanBase=null;window.__orphanTail=null;" +
            // 闸门必须有不依赖触摸事件的释放路径：流式每 200ms 用 innerHTML=
            // 重建 tail，会销毁正在被触摸的 <pre>/表格节点，touchend 可能根本
            // 不派发；连续两次命中横滑区也会让 180ms 定时器的 token 失效。
            // 任一情况都会让 __holdRender 永久为 true，之后所有帧只进缓存不落地，
            // 表现为「整条 AI 消息空白」，直到用户点一次菜单（非横滑区 touchstart）
            // 触发 else 分支才 flush 出来。这里加超时 + watchdog 双兜底。
            "window.__endHold=function(){window.__holdRender=false;window.__holdSince=0;" +
            "window.__holdToken++;if(window.__holdWatchdog){" +
            "clearTimeout(window.__holdWatchdog);window.__holdWatchdog=0;}};" +
            "window.__forceLand=function(){window.__endHold();" +
            "window.__pendingTailHtml=null;window.__pendingTailText=null;" +
            "window.__pendingBaseHtml=null;};" +
            "window.__armHold=function(){window.__holdRender=true;window.__holdToken++;" +
            "window.__holdSince=Date.now();" +
            "if(window.__holdWatchdog)clearTimeout(window.__holdWatchdog);" +
            "window.__holdWatchdog=setTimeout(function(){window.__holdWatchdog=0;" +
            "if(window.__holdRender){window.__endHold();window.__flushPending();}}," +
            "window.__HOLD_MAX_MS+400);};" +
            "window.__holdExpired=function(){return window.__holdRender&&window.__holdSince>0&&" +
            "(Date.now()-window.__holdSince)>window.__HOLD_MAX_MS;};" +
            "window.__inHScroll=function(el){while(el&&el!==document.body){" +
            "var cn=''+(el.className||'');" +
            "if(cn.indexOf('table-scroll')>=0||cn.indexOf('svg-scroll')>=0||el.tagName==='PRE')return true;" +
            "el=el.parentElement;}return false;};" +
            "window.__snapScroll=function(root){var a=root.querySelectorAll('.table-scroll,.svg-scroll,pre'),r=[];" +
            "for(var i=0;i<a.length;i++)r.push([a[i].scrollLeft,a[i].scrollTop]);return r;};" +
            "window.__restoreScroll=function(root,s){var a=root.querySelectorAll('.table-scroll,.svg-scroll,pre');" +
            "var need=false;for(var i=0;i<a.length&&i<s.length;i++){if(s[i][0]){need=true;break;}}" +
            "if(need)void root.offsetHeight;" +              // 先强制布局，否则 scrollLeft 设置会被丢弃
            "for(var i=0;i<a.length&&i<s.length;i++){" +
            "if(s[i][0])a[i].scrollLeft=s[i][0];if(s[i][1])a[i].scrollTop=s[i][1];}};" +
            // ---- 逐字渐显 ----
            // 单位序列 + 增量绘制。
            //
            // 旧实现把「还在增长的块」（未闭合的思考块、工具卡片）当成不可分割的原子，
            // 并在切点上设了硬闸：块没闭合，切点就停在它前面。结果是开着实时渲染
            // 对话思考模型时，整条消息一直是空白，只有背景在长高；块写完之后字才
            // 一个个冒出来。原子插入还有第二个硬伤：__smAppend 没把元素压进栈，
            // 思考块、折叠条、代码块、列表全被摊平成同级节点——折叠条里的文字跑到
            // 折叠条外面、样式接不上（「展开思考不是蓝色、点不动」就是这么来的）。
            //
            // 现在一律按真实结构进出栈，没有任何「等块闭合」的闸门；已显示的节点
            // 只追加不重建。
            //
            // 「回答写了一半整段消失、回到顶部再一个字一个字地长回来」的根因不在这里，
            // 而在调用方：新一轮开始、甚至只是补一帧 base，都会把渐显缓冲清空重画。
            // 现在重新起步由 Java 侧用一个稳定键压制（__smoothStart 的 key），
            // 这里只负责「同一份内容永远往同一个方向长」。
            "window.__smSettleChars=80;" +
            "window.__sm={on:false,arrived:'',show:0,units:[],chars:0,cut:0," +
            "stack:[],root:null,cur:0,paintUnits:null,fi:null,gi:null," +
            "advA:null,advI:null,run:null,natLast:0,stackChars:null,stackPending:null," +
            "src:'',common:0};" +
            "window.__smEsc=function(c){" +
            "return c==='&'?'&amp;':(c==='<'?'&lt;':(c==='>'?'&gt;':c));};" +
            // 正文 → 最小单位：每个字符一个单位、每段标签一个单位。
            // 每个单位记下它在源码里的位置（at），末段内容没变时直接截断复用前缀，
            // 不必重新解析整份 HTML——长回答每帧都重新解析才是「越写越慢」的主因。
            //
            // 为什么不直接对 DOM 做文本差量：Chromium 30 对文本节点只替换不更新，
            // 每次切点前移都要整段重做，公式位图、代码高亮、展开状态全跟着重建，
            // 渐显就退化成「整段一闪一闪」。这里一帧只解析一次 HTML，之后所有帧
            // 只在单位序列上移动切点。
            "window.__smUnitize=function(html,st,from){" +
            "var s=html||'';" +
            // 片段没变（只推进显示进度）：单位序列就是当前这份，直接交回去。
            // 少了这一条，每一拍都要把整份 HTML 重新解析一遍才算「什么都没变」。
            "if(st&&st.units&&st.src===s)return st.units;" +
            // ---- 一档：末尾续写几个字（流式最常见的帧）----
            //
            // 最后一段文字后面又多了几个字，前面的标签、公式、代码块一个字都没变。
            // 只要把新字补进单位序列就行，不必重解析整份 HTML——一帧几万字、
            // 单位两万个，4.4 上单帧几十毫秒，用户看到的就是「越写越慢」。
            //
            // 切点 `from` 由调用方给出（Java 侧增量渲染算出的精确前缀长度），
            // 不靠 at 反推：at 是估算值，实体与自闭合标签会让它和源码错开几格。
            "if(from>0&&st&&st.units&&st.src!==undefined&&st.chars>=0){" +
            "var o0=st.src;" +
            "if(from<o0.length&&from<s.length&&o0.substring(0,from)===s.substring(0,from)){" +
            // 公共后缀：末尾那串闭合标签通常没动
            "var q=0,mq=Math.min(o0.length-from,s.length-from,512);" +
            "while(q<mq&&o0.charAt(o0.length-1-q)===s.charAt(s.length-1-q))q++;" +
            "var co=o0.substring(from,o0.length-q),cn=s.substring(from,s.length-q);" +
            "var io=co.lastIndexOf('>'),in2=cn.lastIndexOf('>');" +
            // 最后一个「>」之前的标签序列逐字节相同：结构没变，只有它之后的文字在长
            "if(io>=0&&in2>=0&&co.substring(0,io+1)===cn.substring(0,in2+1)){" +
            "var del=co.substring(io+1),ins=cn.substring(in2+1);" +
            "if(del.indexOf('<')<0&&ins.indexOf('<')<0&&del.indexOf('&')<0&&ins.indexOf('&')<0" +
            "&&ins.indexOf(del)===0&&ins.length>del.length){" +
            "var after=window.__smTextChars(s.substring(s.length-q));" +
            "var nu=window.__smSplice(st,del,ins.substring(del.length),after);" +
            "if(nu){st.src=s;return nu;}" +
            "}}}}" +
            // ---- 二档：末段是没有闭合标签的裸文字 ----
            //
            // 判定用「三段式」：开头（到末段文字起点）、末段文字本身、结尾闭合标签。
            // 开头与结尾都一样、只有末段文字在后面接长，就说明结构完全没变。
            // 这一档只在 HTML 以「未闭合的文字」收尾时命中（卡片、代码块内部），
            // 段落永远以 </p> 收尾，所以真正兜住流式的是上面那一档。
            // d 为空说明这一帧的差异不在末段文字里：必须往下走整份解析，
            // 直接返回旧序列会把这一帧的字整批丢掉（改完不显示、要等下一帧才补）。
            "if(st&&st.run&&st.stackChars){" +
            "var r=window.__smRun(s);" +
            "if(r&&r.rs===st.run.rs&&r.head===st.run.head&&r.tail===st.run.tail" +
            "&&r.raw.length>=st.run.raw.length&&r.raw.indexOf(st.run.raw)===0){" +
            "var d=r.raw.substring(st.run.raw.length);" +
            "if(d.length>0&&d.indexOf('<')<0&&d.indexOf('&')<0){" +
            "var u0=st.units,nat0=st.natLast;" +
            // 新字必须插在「上一帧遗留在末尾的闭合标签」之前。
            //
            // 切点停在最后一个字后面：末尾那段 </p> 还没画出去，仍留在序列尾部。
            // 直接 push 会让序列变成「<p>文字</p>新字」——下一帧先把段落闭合、
            // 再把新字写进父级，段落就此断开，正文一节一节落到根节点上，
            // 每节一个文本节点（相邻文本成百上千、排版越来越慢就是这么来的）。
            "var li=u0.length-1;while(li>=0&&u0[li].ch===undefined)li--;" +
            "var tl=li+1<u0.length?u0.slice(li+1):null;" +
            "if(tl)u0.length=li+1;" +
            "for(var q0=0;q0<d.length;q0++){" +
            "u0.push({ch:d.charAt(q0),at:nat0});nat0++;}" +
            "if(tl)for(var t0=0;t0<tl.length;t0++)u0.push(tl[t0]);" +
            "st.natLast=nat0;st.chars+=d.length;st.run=r;st.common=li+1;" +
            // 追加要从「上一帧最后那个字所在的元素」里接，不能从根节点接：
            // 结构没变，所以那串元素引用仍然有效
            "st.stackPending=st.stackChars;" +
            "return u0;}}}" +
            // ---- 三档：整份解析 ----
            "var all=window.__smParse(s,0);" +
            "if(st){st.chars=all.cnt;st.natLast=all.fi;st.run=window.__smRun(s);st.src=s;" +
            "st.common=0;st.advA=null;st.advI=null;st.stackChars=null;st.stackPending=null;}" +
            "return all.units;};" +
            // 把一份 HTML 切成「开头 / 末段文字 / 结尾闭合标签」三段。
            // 结尾必须是闭合标签，否则没有稳定的追加锚点。
            "window.__smRun=function(s){" +
            "var ci=s.lastIndexOf('</');if(ci<0)return null;" +
            "var rs=s.lastIndexOf('>',ci-1)+1;" +
            "return {rs:rs,head:s.substring(0,rs),raw:s.substring(rs,ci),tail:s.substring(ci)};};" +
            // 把一段 HTML 解析成单位序列；nat 是这段源码在整个正文里的起点。
            // 每个单位记下位置（at）：续写时只解析尾部，不必每帧重解析整份 HTML——
            // 长回答每帧都重新解析才是「越写越慢」的主因。
            "window.__smParse=function(src,nat){" +
            "var box=document.createElement('div');box.innerHTML=src;" +
            "var units=[];var cnt=0;var fi=nat;" +
            // 空元素没有闭合标签，单独标 void：既不压栈，也不生成关闭单位
            "var VOID={br:1,img:1,hr:1,input:1,meta:1,link:1,source:1,wbr:1," +
            "area:1,base:1,col:1,embed:1,param:1,track:1};" +
            "var add=function(el){" +
            "var kids=el.childNodes;" +
            "for(var i=0;i<kids.length;i++){" +
            "var n=kids[i];" +
            "if(n.nodeType===1){" +
            "var nm=n.tagName.toLowerCase();" +
            "var keep='';var at=n.attributes;" +
            "for(var a=0;a<at.length;a++){" +
            "keep+=' '+at[a].name+'=\"'+(''+at[a].value)" +
            ".replace(/&/g,'&amp;').replace(/\"/g,'&quot;')+'\"';}" +
            "var vo=VOID[nm]?1:0;" +
            "units.push({tok:'<'+nm+keep+'>',open:nm+keep,tag:nm,void:vo,at:nat});" +
            "nat+=units[units.length-1].tok.length;fi=nat;" +
            "if(!vo){add(n);units.push({tok:'</'+nm+'>',close:nm,at:nat});" +
            "nat+=nm.length+3;fi=nat;}}" +
            "else if(n.nodeType===3){" +
            "var tx=''+n.nodeValue;" +
            "for(var q=0;q<tx.length;q++){units.push({ch:tx.charAt(q),at:nat});nat++;cnt++;}" +
            "fi=nat;}" +
            "else if(n.nodeType===8){units.push({tok:'<!--'+n.nodeValue+'-->',at:nat});" +
            "nat+=n.nodeValue.length+7;fi=nat;}}};" +
            "add(box);" +
            "return {units:units,fi:fi,cnt:cnt};};" +
            // 一段 HTML 里有多少「正文字符」（标签不算，实体按一个字算）。
            // 用来定位末尾续写的插入点：单位序列里每个字都带 ci，
            // 「从末尾倒数 after 个字」就是插入位置，与源码偏移无关。
            "window.__smTextChars=function(x){" +
            "if(!x)return 0;var n=0,i=0,L=x.length;" +
            "while(i<L){" +
            "var c=x.charAt(i);" +
            "if(c==='<'){var g=x.indexOf('>',i);if(g<0)break;i=g+1;continue;}" +
            "if(c==='&'){var sc=x.indexOf(';',i);if(sc>i&&sc-i<=8){i=sc+1;n++;continue;}}" +
            "i++;n++;}" +
            "return n;};" +
            // 把末尾新到的几个字补进单位序列，返回新的单位序列（失败返回 null）。
            //
            // 为什么重建数组而不是就地插入：绘图层的「上一帧画到哪」是按单位下标记的，
            // 就地插入会让新旧数组变成同一个对象，差分判定（old===u）直接短路，
            // 这一帧的改动就看不见了。新数组里 [0,k) 是原样搬过去的同一批对象，
            // 差分从 k 开始比，代价是一次指针拷贝，远小于重解析整份 HTML。
            "window.__smSplice=function(st,del,ins,after){" +
            // 变更点后面还有大段内容（说明改写发生在很早的位置）：交给整份重解析
            "if(after<0||after>400)return null;" +
            "var u=st.units,len=u.length,k=len,seen=0;" +
            // 先跳过末尾的闭合标签：插入点在「最后一个字」之后，而不是数组末尾
            "while(k>0&&u[k-1].ch===undefined)k--;" +
            "while(k>0&&seen<after){k--;if(u[k].ch!==undefined)seen++;}" +
            "if(seen<after)return null;" +
            // del 必须正好是 k 之前连续的那几个字，否则切点不可信
            "var j=k-1,cnt=del.length;" +
            "while(cnt>0){if(j<0||u[j].ch===undefined)return null;cnt--;j--;}" +
            "var back='';" +
            "for(var x=j+1;x<k;x++)back+=u[x].ch;" +
            "if(back!==del)return null;" +
            "var at=(k<len&&u[k].at!==undefined)?u[k].at:((k>0)?u[k-1].at:0);" +
            "var nu=u.slice(0,k);" +
            "for(var q=0;q<ins.length;q++)nu.push({ch:ins.charAt(q),at:at+q});" +
            "for(var y=k;y<len;y++)nu.push(u[y]);" +
            "st.chars+=ins.length;" +
            // 进度游标（字数 → 单位下标）跟着右移；锚点栈按旧的单位序列记的，作废
            //
            // 两个数必须一起挪：游标是「扫过前 advI 个单位正好得到 advA 个字」这一对。
            // 只挪下标不挪字数（旧实现在这里只做了 advI+=ins.length），插入点又落在
            // 游标之前时，这对数就对不上了——重新扫描会多算一截，游标一路冲到数组末尾，
            // 把末尾那几个闭合标签也当成「待追加」画出去，元素栈被弹回根节点，
            // 之后所有新字都写到块外面（思考块里的文字停在原地不再增长）。
            "if(st.advI!=null&&st.advI>=k){st.advI+=ins.length;" +
            "if(st.advA!=null)st.advA+=ins.length;}" +
            "st.stackChars=null;st.stackPending=null;" +
            "st.common=k;" +
            "return nu;};" +
            // 重画：只往已显示区追加新增单位，已画过的节点一律不动。
            "window.__smPaint=function(st,t){" +
            "var root=t.querySelector('.smooth-shown');" +
            "if(!root){root=document.createElement('div');root.className='smooth-shown';" +
            "if(t.firstChild)t.insertBefore(root,t.firstChild);else t.appendChild(root);}" +
            // 显示层一旦被换掉（换版、tail 重建），旧栈里的元素全作废：
            // 不重置的话，下一次追加会写进一个已经脱离文档的节点，
            // 屏幕上就一直停在旧内容，看起来像「卡住了」。
            "if(st.root&&st.root!==root){st.root=null;st.cur=0;st.gi=null;st.fi=null;" +
            "st.paintUnits=null;st.stack=[];st.advA=null;st.advI=null;}" +
            "var u=st.units,cut=st.cut;" +
            "var dp=(st.root===root)?window.__smDiff(st,u):-2;" +
            "var same=(dp===-1)&&cut>=st.cur;" +
            // 帧首的纯文本续写：结构与上一帧完全一致，只是末尾文字接着长。
            // 这种情况下不必动已有的渐变 span，也不要逐字重新建节点——
            // 每帧都重建一遍，不仅慢，还会让整段文字在「半透明 → 实色 → 半透明」
            // 之间来回跳，看起来就是屏幕在闪。
            "var pureTail=window.__smTailGrow(st,u);" +
            "var prevCur=st.cur;" +
            "if(!same){" +
            // 结构变了（markdown 补全、代码高亮落地、思考块又长了一段）：从第一处
            // 分叉所在的位置往后重画，前面已经定稿的节点一个都不动。
            //
            // 整段清空重画最省事，但三万字的回答要重建三万个节点，低端机上是
            // 几百毫秒的停顿；重建期间思考块的展开状态、公式位图、代码高亮全跟着
            // 重来，屏幕一闪一闪——「开着实时渲染就卡顿」的真正来源。
            // 只拆最后一块也不行：思考块的正文在长、末尾那段正文很短时，分叉落在
            // 前面的思考块里，思考内容就会停在最初那几段。
            "var rp=window.__smRewind(st,root,dp);" +
            "if(rp>=0){prevCur=rp;" +
            "}else{" +
            "root.innerHTML='';st.root=root;st.stack=[{el:root,n:''}];st.cur=0;" +
            "st.gi=null;prevCur=0;st.stackChars=null;st.stackPending=null;" +
            "}" +
            "}" +
            // 末段文字续写的那一帧：从上一帧最后那个字所在的元素接着写
            "if(st.stackPending){st.stack=st.stackPending;st.stackPending=null;}" +
            // 从「已经画到哪」接着画；重画后从 0 开始整段补一次
            "window.__smAppend(st,u,prevCur,cut);" +
            "st.cur=cut;st.paintUnits=u;" +
            // 纯文本续写时保留已有的渐变窗口：窗口里那几十个字本来就是
            // 刚写下的，重新切一遍等于把同一段字重建两次
            "if(!pureTail)window.__smFade(st);" +
            "applyThinkingStates(t);" +
            // 正文已经出来，进度提示（正在整理搜索结果…）就该收掉：
            // 它挂在 .content 之后，不收的话正文会一直显示在提示上方
            "window.__clearStatusIn(t);" +
            // 跟着内容走：逐字期间高度一直在长，不滚就停在半截。
            // 100ms 一次足够跟手：滚到底要读 scrollHeight（强制排版），
            // 按 90 帧/秒每次都做，在 4.4 的低端机会把排版时间翻倍。
            "var now=Date.now();" +
            "if(!window.__smScrollAt||now-window.__smScrollAt>100){" +
            "window.__smScrollAt=now;if(!window.__userUp)smartScrollToBottom();}};" +
            // 上一帧画过的单位序列与本帧的第一处分叉下标：-1 表示已画前缀完全没变。
            //
            // 同一个数组对象 = 这一帧是增量续写的（只在尾部 push，元素引用没变），
            // 前缀必然一致，直接返回 -1。旧实现在这里 return false，于是每一帧都走
            // 「清空 + 全量重画」：3 万字的回答每帧重建几万个节点，实时渲染越写越卡、
            // 思考块与公式跟着重建闪一下，根因就在这一行。
            "window.__smDiff=function(st,u){" +
            "var old=st.paintUnits;" +
            "if(!old)return -2;" +
            "if(old===u)return st.cur<=u.length?-1:u.length;" +
            "var n=Math.min(st.cur,u.length);" +
            // st.common 是「这一帧确定没变的单位个数」（续写时由 unitize 给出）。
            // 从它开始比，而不是每帧从 0 比到切点——两万个单位的逐项比较
            // 在 4.4 上本身就要几毫秒，也是要消掉的那类随长度增长的开销。
            "var i=st.common||0;if(i>n)i=n;" +
            "for(;i<n;i++){" +
            "var a=u[i],b=old[i];" +
            "if(a.ch!==b.ch||a.tok!==b.tok)return i;}" +
            "return -1;};" +
            // 一颗节点占多少个单位：文本按字数，元素按「开标签 + 子节点 + 闭标签」。
            // 只有「整颗跳过」时才用得到，所以递归一次是可以接受的。
            "window.__smVoidEl=/^(br|img|hr|input|meta|link|source|wbr|area|base|col|embed|param|track)$/;" +
            "window.__smUnits=function(n){" +
            "if(!n)return 0;" +
            "if(n.nodeType===3)return (n.nodeValue||'').length;" +
            "if(n.nodeType!==1)return 0;" +
            // 渐显窗口切出来的 span 不对应任何单位，只代表里面的字
            "if(n.__at==null)return (n.textContent||'').length;" +
            "var t=1,c=n.childNodes;" +
            "for(var i=0;i<c.length;i++)t+=window.__smUnits(c[i]);" +
            "return window.__smVacant(n)?t:t+1;};" +
            "window.__smVacant=function(n){" +
            "return window.__smVoidEl.test((''+n.tagName).toLowerCase());};" +
            // 把显示层回退到分叉处：拆掉 dp 之后的节点，返回真正能接着画的下标。
            //
            // 每个由 __smAppend 建出来的元素都记着自己的开标签下标（__at），
            // 所以可以顺着 dp 一路下潜到最深一层包住它的元素，只在那一层截断
            // 文本——代价是「深度 + 兄弟数」，与已经写了多少字无关。
            // 返回 -1 表示这一帧的结构对不上（少见），调用方退回整段重画。
            "window.__smRewind=function(st,root,dp){" +
            "if(dp<0||!root)return -1;" +
            "var path=[],el=root,i,j;" +
            "for(;;){" +
            "var kids=el.childNodes,best=null;" +
            // 子节点按文档顺序，最后一个「起点不晚于 dp」的元素就是 dp 所在的容器
            "for(i=0;i<kids.length;i++){" +
            "var n=kids[i];" +
            "if(n.nodeType!==1||n.__at==null)continue;" +
            "if(n.__at<=dp)best=n;}" +
            "if(!best)break;" +
            // 它的结束边界：下一个元素兄弟的起点；没有下一个兄弟时按自身单位数算。
            // dp 越过了边界就说明分叉不在它里面，停在这一层。
            "var nx=Infinity;" +
            "for(j=0;j<kids.length;j++){" +
            "var m=kids[j];" +
            "if(m.nodeType===1&&m.__at!=null&&m.__at>best.__at){nx=m.__at;break;}}" +
            "if(nx===Infinity)nx=best.__at+window.__smUnits(best);" +
            "if(dp>=nx)break;" +
            "path.push(best);el=best;}" +
            // 在这一层按 dp 截断：文本节点可以精确切，整颗元素只能退到它的起点
            "var ci=(el===root)?0:(el.__at+1);" +
            "var ks=el.childNodes,cut=-1,rp=dp;" +
            "for(i=0;i<ks.length;i++){" +
            "var n2=ks[i];" +
            "if(n2.nodeType===3){" +
            "var L=(n2.nodeValue||'').length;" +
            "if(ci+L>dp){" +
            "var kp=dp-ci;if(kp<0)kp=0;" +
            "if(kp<n2.nodeValue.length)n2.nodeValue=n2.nodeValue.substring(0,kp);" +
            "cut=i+1;rp=dp;break;}" +
            "ci+=L;" +
            "}else if(n2.nodeType!==1){" +
            "cut=i;rp=ci;break;" +
            "}else if(n2.__at==null){" +
            // 没有起点下标的是渐显窗口切出来的 span：整颗算长度，切不进去就退到它的起点
            "var L2=window.__smUnits(n2);" +
            "if(ci+L2>dp){cut=i;rp=ci;break;}" +
            "ci+=L2;" +
            "}else{" +
            "if(n2.__at>=dp){cut=i;rp=ci;break;}" +
            "ci=n2.__at+window.__smUnits(n2);}" +
            "}" +
            // 所有子节点都整体落在分叉之前：不用拆任何节点，直接从这里往后接
            "if(cut<0){cut=ks.length;rp=ci;}" +
            "if(rp<0||rp>dp)return -1;" +
            "while(el.childNodes.length>cut)el.removeChild(el.lastChild);" +
            // 分叉之后的节点不只在最深处：祖先的后续兄弟同样对应 dp 之后的单位，
            // 不拆掉的话旧段落的尾巴会留在屏幕上（新的一段接在它后面长出来）
            "for(i=path.length-1;i>=0;i--){" +
            "var nd=path[i];" +
            "while(nd.nextSibling)nd.parentNode.removeChild(nd.nextSibling);}" +
            // 元素栈按路径重建：下一帧从这里继续往里写
            "var sk=[{el:root,n:''}];" +
            "for(i=0;i<path.length;i++){" +
            "sk.push({el:path[i],n:(''+path[i].tagName).toLowerCase()});}" +
            "st.stack=sk;st.cur=rp;st.gi=null;st.stackChars=null;st.stackPending=null;" +
            "return rp;};" +
            // 这一帧是不是「末段文字接着长」：从 st.cur 往后，上一帧画过的单位
            // 与新序列逐项相同，且剩下的都是纯字符（没有标签开合）。
            // 满足时说明结构没变，渐变窗口可以原样留着，只把新字补进去。
            "window.__smTailGrow=function(st,u){" +
            "var old=st.paintUnits;" +
            "if(!old||old===u||st.cur<=0)return false;" +
            "var n=Math.min(u.length,old.length);" +
            "if(n<=st.cur)return false;" +
            "for(var i=st.cur;i<n;i++){" +
            "var a=u[i],b=old[i];" +
            "if(a.ch!==b.ch||a.tok!==b.tok)return false;" +
            "if(a.ch===undefined)return false;}" +
            "for(var j=n;j<u.length;j++){if(u[j].ch===undefined)return false;}" +
            "return true;};" +
            // 收掉元素尾部连续的文本节点，压到只剩一个。
            //
            // 为什么必须有这一步：markdown 渲染器每帧都可能改写末段（行内格式、
            // 实体、高亮），「上一帧写入的文本节点」和「这一帧新写入的文本节点」
            // 就会在同一位置首尾相接。不合并的话，一条长回答会积累上万个相邻文本
            // 节点，每个都要单独参与换行与对齐计算——这是「越写越卡」在下层真正
            // 的原因，光把 innerHTML 换成 appendData 只能缓解，不能消除。
            //
            // 每次只压两层，所以循环到不再成对为止；上限 8 次足够，避免异常时死循环。
            "window.__smJoin=function(nd){" +
            "if(!nd)return;" +
            "for(var t=0;t<8;t++){" +
            "var c=nd.lastChild;" +
            "if(!c||c.nodeType!==3)return;" +
            "var pv=c.previousSibling;" +
            "if(!pv||pv.nodeType!==3)return;" +
            "pv.appendData(c.nodeValue||'');nd.removeChild(c);}};" +
            // 把一颗文本节点与左右相邻的文本节点并成一颗，返回合并后的那颗。
            //
            // 渐显每帧要做两件事：把上一帧切出来的 span 落回裸文本、再从尾部切出
            // 新的窗口。落回时新字所在的那颗文本节点停在窗口的后面，就地变成一个
            // 与前者首尾相接的独立节点——一帧一颗，几十帧下来就是几十个相邻文本
            // 节点挂在同一个父级上，每个都单独参与换行计算。
            "window.__smBare=function(tn){" +
            "if(!tn||tn.nodeType!==3||!tn.parentNode)return tn;" +
            "var p=tn.parentNode,pv=tn.previousSibling;" +
            "if(pv&&pv.nodeType===3){pv.appendData(tn.nodeValue||'');p.removeChild(tn);tn=pv;}" +
            "var nx=tn.nextSibling;" +
            "if(nx&&nx.nodeType===3){tn.appendData(nx.nodeValue||'');p.removeChild(nx);}" +
            "return tn;};" +
            // 重画后找回上一个仍在文档里的渐变节点，接着往后挂
            "window.__smLastNew=function(root){" +
            "var ns=root.querySelectorAll('.stream-new');" +
            "return ns.length?ns[ns.length-1]:null;};" +
            // 追加单位：逐字建 span，标签按真实结构进出栈。
            // 不写闭合标签：DOM 元素本身就是「开着」的，把追加位置退回父级
            // 就等于闭合了它，序列化时浏览器会自动补全。
            //
            // 渐显范围由 __smFade 统一设置，这里只负责建节点。旧版把「最近 36 字」
            // 降级成裸文本、却从不收掉旧 span 的 .stream-new，于是正文越长、
            // 带半透明样的字越多——「工具上方最后几个字一直是灰色」就是这么来的。
            "window.__smAppend=function(st,u,from,to){" +
            // 记下「最后一个字」在处理到它时的元素栈：下一帧若只是这段文字续写，
            // 就从同一串元素继续往里写，不必重解析、也不必从根节点重新定位
            "var lcIdx=-1;" +
            "for(var q=to-1;q>=from;q--){if(u[q].ch!==undefined){lcIdx=q;break;}}" +
            // 这一帧里「第一个闭合标签之后」的元素栈：本帧新开又已闭合的段落，
            // 在帧尾不该继续留在栈上。留着的话，下一帧的新字会写进上一帧那个
            // 已经结束的段落里，段落结构越写越乱
            "for(var i=from;i<to;i++){" +
            "var x=u[i];" +
            "var top=st.stack[st.stack.length-1].el;" +
            "var lastCh=null;" +
            "if(x.ch!==undefined){" +
            // 直接 appendChild：Chromium 30 上 innerHTML 那一套会把相邻节点合并，
            //「按节点定位渐变窗口」就失效了
            // appendData 而不是 appendChild：同一段连续文字写进一个文本节点，
            // 否则 WebView 会把几百个相邻文本节点一个个当排版单元，长回答明显变卡
            // 上一帧在这里断过（标签边界、换段），尾部可能已经有个文本节点停在
            // 边上——新字直接接进去，不要再开一个新节点
            "window.__smJoin(top);" +
            "var lc=top.lastChild;" +
            "var tn=(lc&&lc.nodeType===3)?(lc.appendData(x.ch),lc):null;" +
            "if(!tn){tn=document.createTextNode(x.ch);top.appendChild(tn);}" +
            // 渐变锚点指在文本节点上，由 __smFade 按需切开成 span。
            // 不再「一字一 span」：一屏几百个 span 在 4.4 的 WebView 上排版很贵，
            // 而且每个都要单独设 opacity，滚动跟随时会明显掉帧。
            "st.gi=tn;lastCh=tn;" +
            "if(i===lcIdx)st.stackChars=st.stack.slice();" +
            "continue;}" +
            "if(x.close!==undefined){" +
            // 闭合前先合并这一段文字：一个标签里最后写的那几句话，
            // 往往是好几帧各写一段，不合并就一段一个文本节点
            "if(lcIdx>=0)window.__smJoin(top);" +
            "for(var k=st.stack.length-1;k>0;k--){" +
            "if(st.stack[k].n===x.close){st.stack.length=k;break;}}" +
            "continue;}" +
            "if(x.open!==undefined){" +
            // 开新标签前先合并：上一段文字写入时留下的相邻文本节点，
            // 会在标签边界处各成一段，长回答里越积越多
            "if(lcIdx>=0)window.__smJoin(top);" +
            // 插入「开始标签」时补成完整闭合的一对：只给 <div ...> 时解析器会
            // 自动闭合，lastElementChild 未必是刚建的那个空元素（父节点是 <p>
            // 时还会被 foster parenting 提到外面）。一旦取错，st.stack 就压进了
            // 一个旧节点，后面所有子节点都往那儿长，思考块被套成
            // wrap>toggle>toggle>…>block，一次点击冒泡触发多个 onclick，
            // 展开与折叠互相抵消——这就是「偶尔能点开、偶尔点了没反应」。
            "if(x.void){top.insertAdjacentHTML('beforeend','<'+x.open+'>');}" +
            "else{top.insertAdjacentHTML('beforeend','<'+x.open+'></'+x.tag+'>');" +
            "var el=top.lastElementChild;" +
            "if(el)el.__at=i;" +
            "if(el)st.stack.push({el:el,n:x.tag});}" +
            "continue;}" +
            // 注释 / 无法归类的原样片段
            "if(x.tok!==undefined){top.insertAdjacentHTML('beforeend',x.tok);}" +
            "}" +
            // 追加结束后把锚点指到「最后一个字所在的元素」：本帧新写的字如果
            // 落在新开的代码块/段落里，锚点还停在上一个段落的话，渐变窗口
            // 就找不到这一帧真正的尾巴，新字会一直停在正文色、看不出渐现。
            "if(lastCh&&lastCh.parentNode){st.gi=lastCh;window.__smJoin(lastCh.parentNode);}};" +
            // 帧尾不再把元素栈回退到「本帧第一个闭合标签之前」。
            //
            // 旧实现在帧尾把栈复位成那一刻的快照，注释说「新开又闭合的段落不该留在
            // 栈上」。但自然进出栈本来就不会留下已闭合的元素，这个复位只在一帧跨过
            // 多个块时才生效——比如本帧从思考块的折叠条一路画到块内正文：中途有
            // 好几个闭合标签，快照停在第一个闭合标签之前（折叠条那层），下一帧的
            // 新字就被追加进折叠条里，表现为「思考内容变蓝贴在展开按钮上」，再下一帧
            // 结构重算又把它挪回块内（看起来像「字闪一下消失、又钻回思考内容」）。
            // 同一处复位也让表格单元格、行内加粗的字序颠倒（td/strong 之后的新字
            // 被写回父级）。栈只由本帧真正处理过的标签决定，才是它的正确语义。
            // 渐变窗口：从「最新显示的那个字」往前数 GRAD 个字保留浅色，
            // 再往前的一律落成正文色。
            // 窗口是按节点位置倒着数的，不是按创建顺序：这样窗口里永远是「末尾那一串」，
            // 不会因为答得长、写得快而把整页字都留在半透明状态。
            "window.__smFade=function(st){" +
            "if(!st.root)return;" +
            "var GRAD=24;" +
            // 这一帧新写的字所在的那个裸文本节点就是锚点。
            // 上一帧切开之后，新字跟在「最后一个渐变 span」后面，锚点要顺着找回来。
            "var g=st.gi;" +
            "while(g&&g.nodeType===1&&(''+g.className).indexOf('stream-new')>=0){" +
            "g=g.lastChild;}" +
            "var anchor=(g&&g.nodeType===3&&g.parentNode)?g:null;" +
            // 先收掉旧的渐变字：全部落回裸文本，节点数不随回答长度增长
            //
            // 必须从前往后收。从后往前收时，每颗 span 的前一个兄弟都是 span，
            // 走 replaceChild 分支各建一颗文本节点，收完就是「一字一节点」，
            // 相邻文本节点越攒越多——排版成本随回答长度线性上涨。
            // 只取渐变用的 span。旧实现遍历整棵显示层的 span 再按类名筛：代码高亮
            // 一多就是几千个节点，每帧全扫一遍，成本随正文长度线性增长。
            // 按类名取活动集合，长度就是真正要处理的个数。
            "var old=st.root.getElementsByClassName('stream-new'),list=[];" +
            "for(var i=0;i<old.length;i++){" +
            "list.push(old[i]);}" +
            // 每一颗都落回裸文本：相邻的前一个文本节点直接 appendData，
            // 否则新建一个文本节点。留一颗 span 当锚点会让下一帧的窗口
            // 从它旁边重新切，span 一段段累积，节点数随帧数增长。
            "for(var i=0;i<list.length;i++){" +
            "var sp=list[i];" +
            "var tn=sp.textContent,prev=sp.previousSibling;" +
            "if(prev&&prev.nodeType===3){prev.appendData(tn);sp.parentNode.removeChild(sp);}" +
            "else sp.parentNode.replaceChild(document.createTextNode(tn),sp);}" +
            // 收完之后锚点若已脱离文档，退回「最后一个字所在的元素」重新定位
            "if(!anchor||!anchor.parentNode){" +
            "var pe=st.gi;while(pe&&pe.nodeType!==1)pe=pe.parentNode;" +
            "var c=pe?pe.lastChild:null;" +
            "anchor=(c&&c.nodeType===3)?c:null;}" +
            // 锚点先与左右相邻文本合并：旧窗口落回裸文本后，新字那颗节点就停在
            // 它们后面，不合并的话每帧都多留一颗相邻文本节点
            "if(anchor)anchor=window.__smBare(anchor);" +
            // 从锚点取最后 GRAD 个字切开：同一段文本一旦切开，切出来的字就在
            // 锚点后面，下一次锚点自然移到新写的字上，窗口随之前移
            "if(anchor){" +
            "var t=anchor.nodeValue||'';" +
            "var keep=t.length>GRAD?t.length-GRAD:0;" +
            "var tail=t.substring(keep);" +
            "if(keep>0)anchor.nodeValue=t.substring(0,keep);" +
            "else anchor.nodeValue='';" +
            "var fr=document.createDocumentFragment();" +
            "for(var q=0;q<tail.length;q++){" +
            "var sp2=document.createElement('span');sp2.className='stream-new';" +
            // 数字越小离末尾越近、越浅：末尾的字几乎看不见，往前一路加深到正文色
            "sp2.style.opacity=(Math.round(((tail.length-q)/GRAD)*10)/10);" +
            "sp2.appendChild(document.createTextNode(tail.charAt(q)));" +
            "fr.appendChild(sp2);}" +
            // 空文本节点留着（挪到前面），下次的锚点还能顺着「最后一个 span」找回来
            "anchor.parentNode.insertBefore(fr,anchor.nextSibling);}" +
            "st.gi=null;};" +
            // 换段之前把尾巴上的字一次落定：换段后显示区会被搬走，渐变窗口
            // 从零开始，留在旧位置的半透明字就再也没人加深了（停在灰色）。
            "window.__smSettle=function(t){" +
            "if(!t)return;" +
            "var ns=t.querySelectorAll?t.querySelectorAll('.stream-new'):[];" +
            "for(var i=0;i<ns.length;i++){ns[i].style.opacity=1;}" +
            "window.__sm.gi=null;};" +
            // 每帧推进：把切点按「已到达字符数」移到该到的位置
            "window.__smAdvance=function(st,t,adv){" +
            "var a=adv|0,cnt=0,i=0,u=st.units;" +
            // 从上一帧数到的位置继续：正文到几万字时，每帧从 0 数一遍
            // 就是几十万次循环，纯白烧 CPU（每拍都要来一次）。
            // 前提是序列前缀没变——advA/advI 在序列被重建时由 __smUnitize 清空。
            "if(st.advI!=null&&st.advA!=null&&a>=st.advA&&st.advI<=u.length){" +
            "i=st.advI;cnt=st.advA;}" +
            "for(;i<u.length;i++){if(cnt>=a)break;if(u[i].ch!==undefined)cnt++;}" +
            "st.cut=i;st.advA=cnt;st.advI=i;" +
            "window.__smPaint(st,t);return true;};" +
            // 纯文本渐显：只在正文节点上挪内容，不重建任何结构。
            //
            // 关掉实时渲染时 tail 有两种形态：直接承载正文（这一轮还没调工具），
            // 或 tail > .tool-live(卡片) + .stream-plain(正文)。后者只能改最后一段
            // .stream-plain —— 一旦 t.innerHTML=''，正在显示的工具卡片会被一起
            // 删掉，表现就是「卡片一闪就没了」。
            "window.__smPlainBox=function(t){" +
            "if(!t.querySelector('.tool-live'))return t;" +
            "var ps=t.querySelectorAll('.stream-plain');" +
            "var last=ps.length?ps[ps.length-1]:null;" +
            "if(last&&last===t.lastElementChild)return last;" +
            "var pw=document.createElement('div');pw.className='stream-plain';" +
            "t.appendChild(pw);return pw;};" +
            // 尾部渐变：末尾 GRAD 个字各包一颗 span，越靠末尾越浅；前面的落成裸文本。
            // 只在「新显示的一段字」上重建，不碰更早的节点，所以已加深的字不会闪。
            "window.__smFadeText=function(el,text,from){" +
            "var GRAD=24,n=text.length,start=from>0?from:0;" +
            "if(start>0&&el.firstChild&&el.firstChild.nodeType===3){" +
            "el.firstChild.nodeValue=text.substring(0,Math.min(start,Math.max(0,n-GRAD)));" +
            "}else{" +
            "while(el.firstChild)el.removeChild(el.firstChild);" +
            "start=Math.max(0,n-GRAD);" +
            "if(start>0)el.appendChild(document.createTextNode(text.substring(0,start)));}" +
            "for(var i=start;i<n;i++){" +
            "var sp=document.createElement('span');sp.className='stream-new';" +
            "sp.style.opacity=(Math.round(((n-i)/GRAD)*10)/10);" +
            "sp.appendChild(document.createTextNode(text.charAt(i)));el.appendChild(sp);}" +
            // 重新标一遍透明度：旧节点的位置会随新字到来前移，不重算的话
            // 它们会停在「刚出现时那一档浅色」，一直不变深
            "window.__smRestyle(el,GRAD);};" +
            "window.__smRestyle=function(el,GRAD){" +
            // 同上：按类名取，不扫整棵子树再筛
            "var ns=el.getElementsByClassName('stream-new'),len=0,j;" +
            "for(j=ns.length-1;j>=0;j--){" +
            "len++;ns[j].style.opacity=(Math.round((len/GRAD)*10)/10);}};" +
            // 纯文本进度独立成一状态，不和 HTML 的单位序列混用：
            // 两套口径（偏移 / 字数）混在一起时，一个「<」就能让显示量跳掉几十字
            //
            // 正文按「块」存放（chunks）：每帧只从队头取要显示的那几个字，取过的块
            // 不再搬运。旧版每帧都把整段文本写进一个文本节点、每帧 substring 一遍
            // 全文、渐变 span 只增不减（2 万字时约 1800 个），单帧耗时随正文长度线性
            // 增长，越写越慢就是这么来的。
            "window.__smPlain={on:false,shown:0,drawn:0,len:0,box:null," +
            "chunks:[],ci:0,co:0,win:[],settle:'',cls:'',tail:null};" +
            "window.__smGRAD=24;" +
            // 到达的文本入队：只追加，不复制
            "window.__smPlainAdd=function(d){" +
            "var s=window.__smPlain;if(d==null)return;d=''+d;if(!d.length)return;" +
            "s.chunks.push(d);s.len+=d.length;" +
            // 队列只保留「还没画完」的那一段：前面已经画过的块整体丢掉
            "if(s.ci>256){s.chunks=s.chunks.slice(s.ci);s.ci=0;}};" +
            // 从队头取 k 个字。每块只被取一次，整轮下来是线性的
            "window.__smPlainNext=function(k){" +
            "var s=window.__smPlain,out='';" +
            "while(k>0&&s.ci<s.chunks.length){" +
            "var cur=s.chunks[s.ci],av=cur.length-s.co;" +
            "if(av<=0){s.ci++;s.co=0;continue;}" +
            "var take=av<k?av:k;" +
            "out+=cur.substr(s.co,take);s.co+=take;k-=take;" +
            "if(s.co>=cur.length){s.ci++;s.co=0;}}" +
            "return out;};" +
            // 窗口外的字合并进文本节点：节点数不随正文长度增长。攒够一批再插一个
            // 节点，避免「一个字一次 appendData」把文本节点的缓冲反复搬家
            "window.__smPlainFlush=function(){" +
            "var s=window.__smPlain,box=s.box;if(!box||!s.settle.length)return;" +
            "var buf=s.settle;" +
            "var tn=document.createTextNode(buf);s.settle='';" +
            // 锚点必须是「窗口里第一颗 span」：box 在没卡片时就是 t 本身，
            // 拿 t 当参照会把这一段插到气泡外面去
            "var anchor=s.win.length?s.win[0]:null,h=s.tail,joined=false;" +
            // 并入上一段文本节点：每帧新插一个文本节点的话，节点数会随帧数
            // 一直涨（4 万字时一千多个），每帧排版都要过一遍全部兄弟节点
            "if(h&&h.nodeType===3&&h.parentNode===box){" +
            "if(anchor&&anchor.previousSibling===h){h.appendData(buf);joined=true;}" +
            "else if(!anchor&&h===box.lastChild){h.appendData(buf);joined=true;}}" +
            "if(joined)return;" +
            "if(anchor&&anchor.parentNode===box)box.insertBefore(tn,anchor);" +
            "else box.appendChild(tn);s.tail=tn;};" +
            // 透明度按「离末尾的距离」给：末尾几乎看不见，往前一路加深到正文色
            "window.__smPlainTint=function(){" +
            "var s=window.__smPlain,G=window.__smGRAD,w=s.win.length,i,o,d;" +
            "for(i=0;i<w;i++){d=w-1-i;o=Math.round(((d+1)/G)*10)/10;if(o>1)o=1;" +
            "s.win[i].style.opacity=o;}};" +
            // 画一段字：只有末尾 GRAD 个建 span（它们在渐变窗口里），更早的落成文本
            "window.__smPlainDraw=function(piece){" +
            "var s=window.__smPlain,box=s.box;if(!box||!piece.length)return;" +
            "var G=window.__smGRAD,i,sp,head=piece.length>G?piece.length-G:0;" +
            "if(head>0)s.settle+=piece.substring(0,head);" +
            "for(i=head;i<piece.length;i++){" +
            "sp=document.createElement('span');sp.className='stream-new';" +
            "sp.appendChild(document.createTextNode(piece.charAt(i)));" +
            "box.appendChild(sp);s.win.push(sp);}" +
            "var drop=s.win.length-G;" +
            "if(drop>0){" +
            "for(i=0;i<drop;i++){s.settle+=s.win[i].textContent;" +
            "if(s.win[i].parentNode)box.removeChild(s.win[i]);}" +
            "s.win.splice(0,drop);}" +
            // 每帧末尾统一落一次：攒到一定量才插节点的话，那几千个字其实一直
            // 躺在变量里没进 DOM，屏幕上的正文会比进度短一大截
            "window.__smPlainFlush();" +
            "window.__smPlainTint();};" +
            // 显示层被换掉（tail 重建、切会话）：从队头按当前进度重画一遍
            "window.__smPlainRedraw=function(){" +
            "var s=window.__smPlain,box=s.box;if(!box)return;" +
            "while(box.firstChild)box.removeChild(box.firstChild);" +
            "s.ci=0;s.co=0;s.drawn=0;s.win=[];s.settle='';s.tail=null;" +
            "if(s.shown>0){var piece=window.__smPlainNext(s.shown);" +
            "if(piece.length){s.drawn=piece.length;window.__smPlainDraw(piece);}}};" +
            // 推进一帧：把「该显示的字数」和「已经画出的字数」之间的差补上
            "window.__smPlainAdvance=function(idx){" +
            "var s=window.__smPlain;if(!s.on)return false;" +
            "var k=s.shown-s.drawn;if(k<=0)return false;" +
            "var t=window.__ensureTail(idx);if(!t)return false;" +
            "var box=window.__smPlainBox(t);if(!box)return false;" +
            "if(box!==s.box){s.box=box;window.__smPlainRedraw();" +
            "window.__smPlainMark(t);return true;}" +
            "var piece=window.__smPlainNext(k);" +
            "if(!piece.length)return false;" +
            "s.drawn+=piece.length;" +
            "window.__smPlainDraw(piece);" +
            "window.__smPlainMark(t);return true;};" +
            // 光标（.plain 的伪元素）与滚动：class 没变就不重写，一次赋值会触发
            // 整棵子树的样式重算，逐帧写等于每帧重排一遍正文
            "window.__smPlainMark=function(t){" +
            "var s=window.__smPlain;" +
            "if(s.box===t){var want=s.shown<s.len?'stream-tail plain':'stream-tail';" +
            "if(s.cls!==want){t.className=want;s.cls=want;}}" +
            "window.__clearStatusIn(t);" +
            "if(!window.__userUp)smartScrollToBottom();};" +
            "window.__smPaintPlain=function(t,text,show){" +
            // 显示量缺失（NaN）时按 0 处理：NaN 会让后面每个比较都判假，
            // 文本既不画出来也不清掉，屏幕上就是一片空白
            "var s=window.__smPlain,n=show>0?Math.floor(show):0;" +
            "var box=window.__smPlainBox(t);if(!box)return;" +
            "s.on=true;s.box=box;s.chunks=[];s.ci=0;s.co=0;s.len=0;" +
            "s.win=[];s.settle='';s.drawn=0;s.shown=0;" +
            "while(box.firstChild)box.removeChild(box.firstChild);" +
            "window.__smPlainAdd(text);" +
            "if(n>s.len)n=s.len;s.shown=n;" +
            "if(n>0){var piece=window.__smPlainNext(n);s.drawn=piece.length;" +
            "window.__smPlainDraw(piece);}" +
            "window.__smPlainMark(t);};" +
            "window.__ensureTail=function(idx){" +
            "var c=window.__aiContent(idx);if(!c)return null;" +
            "var t=c.querySelector('.stream-tail');" +
            "if(!t){var b=c.querySelector('.stream-base');var keep=b?b.innerHTML:c.innerHTML;" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "c.querySelector('.stream-base').innerHTML=keep;t=c.querySelector('.stream-tail');}" +
            "return t;};" +
            // 整段重设：内容被改写（换轮、重试、收尾补最后一帧）时才走这里
            // 渐显是否正在进行（HTML 或纯文本任一通道）。关掉实时渲染时只有后者会置位
            "window.__smoothPlainActive=function(){" +
            "return !!(window.__sm.on||window.__smPlain.on);};" +
            "window.__smoothPlainSet=function(text,adv,idx){" +
            "var t=window.__ensureTail(idx);if(!t)return false;" +
            "var tt=text||'',a=adv>0?Math.floor(adv):0;" +
            "var s=window.__smPlain;" +
            "s.on=true;" +
            "var box=window.__smPlainBox(t);if(!box)return false;" +
            "while(box.firstChild)box.removeChild(box.firstChild);" +
            "window.__smPlainReset();" +
            "s.box=box;" +
            "window.__smPlainAdd(tt);" +
            "if(a>s.len)a=s.len;s.shown=a;" +
            "if(a>0){var piece=window.__smPlainNext(a);s.drawn=piece.length;" +
            "window.__smPlainDraw(piece);}" +
            "window.__smPlainMark(t);return true;};" +
            // 只追加到达的增量：正文越长，这一帧要传的字节也不变
            "window.__smoothPlainAppend=function(delta,total,adv,idx){" +
            "var s=window.__smPlain;" +
            "s.on=true;" +
            "window.__smPlainAdd(delta);" +
            "if(total>s.len)s.len=total;" +
            "var a=adv>0?Math.floor(adv):0;if(a>s.len)a=s.len;" +
            "s.shown=a;" +
            "window.__smPlainAdvance(idx);return true;};" +
            "window.__smoothPlainStep=function(adv,idx){" +
            "var s=window.__smPlain;if(!s.on)return false;" +
            "var a=adv>0?Math.floor(adv):0;if(a>s.len)a=s.len;" +
            "if(a===s.shown)return false;s.shown=a;" +
            "window.__smPlainAdvance(idx);return true;};" +
            // 复位：整段丢弃缓冲与已画的节点引用。内容被外部改写（换轮、重试、
            // 页面重建）时才走这里；正常流式帧一律走 Append / Step，不重扫全文
            "window.__smPlainReset=function(){" +
            "var s=window.__smPlain;" +
            "s.chunks=[];s.ci=0;s.co=0;s.len=0;s.win=[];s.settle='';s.tail=null;" +
            "s.drawn=0;s.shown=0;s.box=null;s.cls='';};" +
            "window.__smoothPlainFinish=function(idx){" +
            "var s=window.__smPlain;if(!s.on)return false;" +
            "s.shown=s.len;" +
            "window.__smPlainAdvance(idx);" +
            "s.on=false;return true;};" +
            // 新一轮：复位渐显状态。key 相同表示还是同一条回答（同一代、同一段正文），
            // 此时只复位计数器、不动已经画在屏幕上的字——旧版每次都把缓冲清空，
            // 而调用方（每一轮新请求、每一帧 base 补渲染）都可能触发，屏幕上就
            // 「整段消失，再一个字一个字地从头长回来」。
            "window.__smoothStart=function(idx,key){" +
            "var t=window.__ensureTail(idx);if(!t)return;" +
            "var st=window.__sm;" +
            "if(key&&st.key===key){" +
            // 只复位渐变锚点，不动已经画出的节点；中间被换成纯文本/被清空过才重建
            "st.on=true;" +
            "if(!st.root||!t.contains(st.root)){st.root=null;st.cur=0;st.paintUnits=null;}" +
            "window.__smPlain.on=false;return;}" +
            "st.key=key||'';" +
            "st.on=true;st.arrived='';st.show=0;st.units=[];st.chars=0;st.cut=0;st.plain=false;" +
            "st.root=null;st.cur=0;st.paintUnits=null;st.fi=null;st.gi=null;" +
            "st.advA=null;st.advI=null;st.run=null;st.natLast=0;" +
            "st.stackChars=null;st.stackPending=null;" +
            "st.stack=[];st.grad=[];st.src='';st.common=0;" +
            "window.__smPlain.on=false;" +
            "window.__smPlainReset();};" +
            // 已到达的完整片段 + 已经放出的字数：两者一起下发，进度落后时也不会漏内容
            "window.__smoothPush=function(html,adv,idx,t){" +
            "var st=window.__sm;if(!st.on)return false;" +
            "if(!t||!t.parentNode){var c=window.__aiContent(idx);if(!c)return false;" +
            "t=c.querySelector('.stream-tail');if(!t)return false;}" +
            "var src=html||'',a=adv|0;if(a<0)a=0;" +
            "if(src===st.arrived&&a===st.show)return false;" +
            // 纯文本 tail（关掉实时渲染）：显示量直接切在文本上
            "if((''+t.className).indexOf('plain')>=0){" +
            "st.plain=true;st.arrived=src;st.chars=src.length;" +
            "if(a>st.chars)a=st.chars;st.show=a;" +
            "window.__smoothPlainSet(src,a,idx);return true;}" +
            "var grew=src!==st.arrived;" +
            "st.arrived=src;st.show=a;" +
            "if(grew){st.units=window.__smUnitize(src,st);}" +
            "if(a>st.chars)a=st.chars;st.show=a;" +
            "window.__smAdvance(st,t,a);return true;};" +
            // 只追加到达的增量：与 __smoothPush 的唯一区别是「上一帧的片段是这一帧的前缀」，
            // 由调用方保证。正文写到几万字时，每拍要跨 JsBridge 的字节数从「总长度」
            // 降到「本次新增长度」，帧耗时不再随正文增长。
            //
            // 必须先把旧片段与新片段拼成完整的 arrived：__smUnitize 依赖 st.arrived
            // 判断能不能只解析尾段（st.fi 之后的裸文本），跳过拼接会让前缀丢字。
            // replaceFrom 可选：这一帧的片段从该下标起才可能不同，之前的都是上一帧的原样
            // 前缀。给了它就把 arrived 按前缀截断再接 delta，而不是直接拼接——流式的
            // 尾段每帧都在长（"<p>abc" → "<p>abcd"），只拼会叠出旧尾段。
            "window.__smoothAppend=function(delta,adv,idx,replaceFrom){" +
            "var st=window.__sm;if(!st.on)return false;" +
            "var c=window.__aiContent(idx);if(!c)return false;" +
            "var t=c.querySelector('.stream-tail');if(!t)return false;" +
            "if((''+t.className).indexOf('plain')>=0)return false;" +
            "if(!delta)return window.__smoothPush('',adv,idx,t);" +
            "var base=st.arrived||'';" +
            "if(replaceFrom!=null&&replaceFrom>=0&&replaceFrom<=base.length){" +
            "base=base.substring(0,replaceFrom);}" +
            "var src=base+delta;" +
            "var a=adv|0;if(a<0)a=0;" +
            "st.arrived=src;st.show=a;" +
            // replaceFrom 就是「这一帧从该下标起才可能不同」的精确位置：
            // 交给 __smUnitize，它只重解析这一小截，前面的单位原样复用。
            // 流式的常态帧（末尾又多了几个字）从这里走，单帧成本与正文长度无关。
            "st.units=window.__smUnitize(src,st,replaceFrom>0?replaceFrom:0);" +
            "if(a>st.chars)a=st.chars;st.show=a;" +
            "window.__smAdvance(st,t,a);return true;};" +
            // 纯文本帧：保持当前进度，只把到达的全文记下来
            "window.__smoothPushText=function(text,idx){" +
            "var st=window.__smPlain;var a=st.on?st.shown:0;" +
            "return window.__smoothPlainSet(text,a,idx==null?-1:idx);};" +
            // 只推进度（新片段还没到）：不动片段，只把切点往后挪
            "window.__smoothStep=function(adv,idx){" +
            "if(window.__smPlain.on)return window.__smoothPlainStep(adv,idx);" +
            "var st=window.__sm;if(!st.on)return false;" +
            "var a=adv|0;if(a<0)a=0;if(a>st.chars)a=st.chars;" +
            "if(a===st.show)return false;st.show=a;" +
            "var c=window.__aiContent(idx);if(!c)return false;" +
            "var t=c.querySelector('.stream-tail');if(!t)return false;" +
            "window.__smAdvance(st,t,a);return true;};" +
            "window.__smoothStop=function(){" +
            "window.__sm.settled=1;" +
            "window.__sm.on=false;window.__smPlain.on=false;};" +
            // 收尾：按最终片段一次放完，然后退出渐显
            "window.__smoothFinish=function(html,idx){" +
            "window.__sm.settled=1;" +
            // 纯文本模式下以缓冲里的全文为准：调用方传进来的 html 参数在这里是空的
            "if(window.__smPlain.on)return window.__smoothPlainFinish(idx);" +
            "var changed=false;" +
            "if(html!=null&&window.__sm.on)changed=window.__smoothPush(html,999999,idx);" +
            "window.__sm.on=false;" +
            "return changed;};" +
            // 手指按在气泡上：暂停往前推，抬手后继续（滚动时不至于边滑边跳字）
            "document.addEventListener('touchstart',function(e){" +
            "var n=e.target;while(n&&n!==document.body){" +
            "if(n.className&&(' '+n.className+' ').indexOf(' msg ')>=0){window.__sm.hold=true;return;}" +
            "n=n.parentNode;}},true);" +
            "document.addEventListener('touchend',function(){window.__sm.hold=false;},true);" +
            "document.addEventListener('touchcancel',function(){window.__sm.hold=false;},true);" +
            "window.__applyTailHtml=function(t,html){" +
            // 渐显通道自己维护 DOM：这里再写一次 innerHTML，等于每帧把已经画好的
            // 节点全部销毁重建（思考块、公式、代码高亮、展开状态跟着重来），
            // 屏幕上闪、手机上卡，而且紧接着 __smoothPush 还要再增量画一遍。
            // 只把「已到达的片段」交给渐显层，它按单位序列追加，已画的节点一律不动。
            "if(window.__sm.on){" +
            // 第一次进入渐显（或显示层被换掉）：tail 里可能还留着上一帧的纯文本节点，
            // 不清一次会和追加出来的正文叠在一起。之后各帧一律不清。
            "if(!window.__sm.root||!t.contains(window.__sm.root)){t.innerHTML='';" +
            "window.__sm.root=null;window.__sm.cur=0;window.__sm.stack=[];}" +
            "t.className='stream-tail';" +
            "window.__smoothPush(html||'',window.__sm.show,t);" +
            // 卡片折叠状态仍要套一次：渐显层只恢复思考块，卡片走另一条状态表。
            // applyToolStates 逐张带完成标记，重复调用不会重写已定态的卡片。
            "applyToolStates(t);" +
            "window.__clearStatusIn(t);" +
            "smartScrollToBottom();return;}" +
            "var s=window.__snapScroll(t);" +
            // 重建前不需要手动捕获：展开状态存在 __thinkOpen 里，重建后按 key 恢复
            "t.className='stream-tail';t.innerHTML=html;window.__restoreScroll(t,s);" +
            "clearStatus();addCopyBtns(t);applyToolStates(t);" +
            "smartScrollToBottom();};" +
            "window.__applyTailText=function(t,text){var s=window.__snapScroll(t);" +
            // tail 里已经有实时写入的工具卡片时，纯文本帧会把它们整段抹掉——
            // 关掉实时渲染时「工具调用的卡片一闪就没了」就是这个原因。
            // 有卡片时只更新卡片之后那段纯文本，没有卡片才退回原来的整段纯文本。
            "if(t.querySelector('.tool-live')){" +
            "window.__setLiveText(t,text);window.__restoreScroll(t,s);" +
            "clearStatus();smartScrollToBottom();return;}" +
            // 关掉实时渲染的整段落地同样要经过渐显层。早先这里判的是 HTML 通道的
            // 状态位（__sm.on），而纯文本通道用的是另一颗状态（__smPlain.on），
            // 关掉实时渲染时它恒为假——于是每一次整段写入都走下面的裸写分支，
            // 「关闭实时渲染后逐字渐显失效」就是这么来的。
            "if(window.__smoothPlainActive()){" +
            // 第一帧：先把全文放进缓冲、进度从 0 起步，再交给渐显层。
            // 直接按「当前进度」画会画个空，屏幕上要等下一拍才出字。
            "var st=window.__smPlain;" +
            "var grow=!st.on;" +
            // 第一次进入纯文本通道：从零起步（真正的逐字浮出）。
            // 已经在通道里：保留当前进度，内容只在到达量内往前长，不会倒退。
            "var a=grow?st.shown:(text||'').length;" +
            "window.__smoothPlainSet(text||'',a,t);" +
            "window.__restoreScroll(t,s);window.__clearStatusIn(t);smartScrollToBottom();return;}" +
            // 空文本不挂 plain：挂了就会画出一个孤零零的流式光标 ▍
            "t.innerHTML='';t.className=text?'stream-tail plain':'stream-tail';t.textContent=text;" +
            "window.__restoreScroll(t,s);" +
            "clearStatus();" +
            "smartScrollToBottom();};" +
            "window.__guardHold=function(){" +
            // 每次渲染帧落地前先检查：闸门超时就强制释放并丢弃缓存帧，
            // 让「当前这一帧」直接落地。只释放不清缓存的话，旧帧会在之后的
            // __flushPending 里再写一遍，末尾出现重复段落。
            "if(window.__holdExpired())window.__forceLand();};" +
            "window.__flushPending=function(idx){" +
            // 走到这里说明触摸手势结束了（抬手，或请求收尾）：解除闸门再落地。
            // 必须先落地再 commitTail —— 否则缓存帧会被搬进一个已经定型的 base，
            // 表现是最后一段内容重复出现。
            "window.__endHold();" +
            "var bh=window.__pendingBaseHtml;window.__pendingBaseHtml=null;" +
            "if(bh!==null)window.streamBase(bh,idx);" +      // hold 已清，这里可以正常写入
            "var h=window.__pendingTailHtml,x=window.__pendingTailText;" +
            "window.__pendingTailHtml=null;window.__pendingTailText=null;" +
            "if(h===null&&x===null)return;" +
            "var c=window.__aiContent(idx);if(!c)return;" +
            "var t=c.querySelector('.stream-tail');if(!t)return;" +
            "if(h!==null)window.__applyTailHtml(t,h);else window.__applyTailText(t,x);};" +
            // 用「时间戳 + watchdog」而不是纯 token 判等：token 会被任意一次
            // 新 touchstart 作废，是原来闸门卡死的第二个来源。
            "window.__releaseHold=function(){if(!window.__holdRender)return;" +
            "var s=window.__holdSince;setTimeout(function(){" +
            "if(!window.__holdRender||window.__holdSince!==s)return;" +
            "window.__endHold();window.__flushPending();},180);};" +
            "document.addEventListener('touchstart',function(e){" +
            "if(window.__inHScroll(e.target)){window.__armHold();}" +
            // 手指已经离开横滑容器：立刻收口
            "else if(window.__holdRender){window.__endHold();window.__flushPending();}},true);" +
            "document.addEventListener('touchend',function(){window.__releaseHold();},true);" +
            "document.addEventListener('touchcancel',function(){window.__releaseHold();},true);" +
            // 页面重新可见时也收一次：切后台/弹对话框回来，触摸序列可能已断。
            "document.addEventListener('visibilitychange',function(){" +
            "if(!document.hidden&&window.__holdRender){window.__endHold();window.__flushPending();}},true);" +
            "window.streamTailHtml=function(html,idx){" +
            // 指定了目标气泡就按 data-idx 精确取，见 __msgAi
            "var c=window.__aiContent(idx);" +
            "if(!c){window.__orphanTail={h:html,t:null};return;}" +
            "var t=c.querySelector('.stream-tail');" +
            "if(!t){var b=c.querySelector('.stream-base');" +
            "var keep=b?b.innerHTML:c.innerHTML;" +
            "c.className='content';" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "c.querySelector('.stream-base').innerHTML=keep;" +
            "t=c.querySelector('.stream-tail');}" +
            "window.__guardHold();" +
            "if(window.__holdRender){window.__pendingTailHtml=html;window.__pendingTailText=null;return;}" +
            "window.__applyTailHtml(t,html);};" +
            "window.streamTailText=function(text,idx){" +
            // 指定了目标气泡就按 data-idx 精确取，见 __msgAi
            "var c=window.__aiContent(idx);" +
            "if(!c){window.__orphanTail={h:null,t:text};return;}" +
            "var t=c.querySelector('.stream-tail');" +
            "if(!t){var b=c.querySelector('.stream-base');" +
            "var keep=b?b.innerHTML:c.innerHTML;" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "c.querySelector('.stream-base').innerHTML=keep;" +
            "t=c.querySelector('.stream-tail');}" +
            "window.__guardHold();" +
            "if(window.__holdRender){window.__pendingTailText=text;window.__pendingTailHtml=null;return;}" +
            "window.__applyTailText(t,text);};" +
            // 工具进行中的实时卡片（关掉实时渲染时专用）。
            //
            // 关掉实时渲染后 tail 走纯文本、base 只在轮次切换时才重渲染，也就是说
            // 「模型正在调工具」这一段时间屏幕上是空的，直到这一轮结束才一次性出现
            // 全部卡片；中途失败的轮次更是什么都留不下。这里提供一个临时容器，
            // 用纯 DOM 往 tail 里写卡片：不解析 markdown，开销远小于实时渲染，
            // 轮次结束时由 streamCommitTail 一并搬进 base。
            "window.liveTools=function(html,reset,idx){" +
            "if(!html||html.length===0)return;" +
            // 指定了目标气泡就按 data-idx 精确取，见 __msgAi
            "var c=window.__aiContent(idx);" +
            "if(!c)return;" +
            "var t=c.querySelector('.stream-tail');" +
            "if(!t){var b=c.querySelector('.stream-base');var keep=b?b.innerHTML:c.innerHTML;" +
            "c.className='content';" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "c.querySelector('.stream-base').innerHTML=keep;" +
            "t=c.querySelector('.stream-tail');}" +
            "window.__guardHold();" +
            "if(window.__holdRender)return;" +
            // 这一轮的临时卡片单独包一层，整段替换而不去碰已经渲染好的历史卡片
            "if(reset){var olds=t.querySelector('.tool-live');" +
            // 只清这一轮临时写入的卡片，不碰已经渲染进 tail 的历史卡片
            "if(olds&&olds.parentNode)olds.parentNode.removeChild(olds);" +
            "var wrap=document.createElement('div');wrap.className='tool-live';t.appendChild(wrap);" +
            "t=wrap;}" +
            "var d=document.createElement('div');d.innerHTML=html;" +
            "var frag=document.createDocumentFragment();" +
            "while(d.firstChild)frag.appendChild(d.firstChild);" +
            "t.appendChild(frag);" +
            "var host=t.parentNode||t;" +
            "applyToolStates(host);smartScrollToBottom();};" +
            // 卡片之后那段「本轮正文」的唯一载体。结构固定为：
            //   tail > .stream-plain(正文纯文本) | .tool-live(卡片) ... | .stream-plain(正文)
            // 两段 .stream-plain 分开存，是因为卡片夹在中间：把它们合成一段就没有
            // 地方插卡片，实测表现是卡片被正文顶到气泡外面。
            "window.__setLiveText=function(t,text){" +
            "if(!text||text.length===0)return;" +
            // 渐显进行中：正文由渐显层画。直接写进去的话，下一帧渐显又会把
            // 这一段按字数重画一遍，屏幕上先整段出现、再缩回几个字。
            "if(window.__smoothPlainActive()){window.__smoothPlainSet(text,window.__smPlain.shown,t);return;}" +
            "var ps=t.querySelectorAll('.stream-plain');" +
            "var last=ps.length?ps[ps.length-1]:null;" +
            // 只有最后一个节点是纯文本时才续写，否则说明卡片之后还没有正文段落
            "if(last&&last===t.lastElementChild){last.textContent=text;return;}" +
            "var pw=document.createElement('div');pw.className='stream-plain';pw.textContent=text;" +
            "t.appendChild(pw);};" +
            // 写入失败卡片（工具轮次中途失败时挂在 AI 气泡下方，支持从出错处重试）
            "window.markAssistantFailed=function(idx,cardHtml){" +
            "var all=document.querySelectorAll('.msg.ai');" +
            "for(var i=0;i<all.length;i++){" +
            "if(all[i].getAttribute('data-idx')===''+idx){" +
            "var old=all[i].querySelector('.error-card');" +
            "if(old&&old.parentNode)old.parentNode.removeChild(old);" +
            "var d=document.createElement('div');d.innerHTML=cardHtml;" +
            "var card=d.firstElementChild;" +
            "var menu=all[i].querySelector('.ai-menu-wrap');" +
            "if(card){if(menu)all[i].insertBefore(card,menu);else all[i].appendChild(card);}" +
            // 重试耗尽后的错误卡片：高度已经变化，不滚就停在视口外
            "smartScrollToBottom();" +
            "return;}}};" +
            "window.clearAssistantFailed=function(idx){" +
            "var all=document.querySelectorAll('.msg.ai');" +
            "for(var i=0;i<all.length;i++){" +
            "if(all[i].getAttribute('data-idx')===''+idx){" +
            "var c=all[i].querySelector('.error-card');" +
            "if(c&&c.parentNode)c.parentNode.removeChild(c);" +
            "return;}}};" +
            "window.updateLastText=function(text,idx){" +
            // 指定了目标气泡就按 data-idx 精确取，见 __msgAi
            "var c=window.__aiContent(idx);" +
            "if(c)c.textContent=text;smartScrollToBottom();};" +
            "window.setLastMsgStatus=function(text,idx){" +
            "var c=window.__aiContent(idx);" +
            "if(c)c.textContent=text;smartScrollToBottom();};" +
            // 骨架从第一帧就固定成 base + tail 两层：后续所有流式写入只动其中一层。
            // 以前这里只有一个空 .content，第一片到达时才在 JS 里临时拆骨架，
            // 而 updateLastMsg 又会把 .content 整个 innerHTML 重设，把正在流出的
            // tail 一起抹掉 —— 用户看到的就是「回答闪一下然后凭空消失」。
            "window.appendAiDiv=function(idx){" +
            // 新的一条回答 = 新一轮生成：折叠记录换一轮，上一轮留下的收起状态
            // 不再压制这一轮的自动展开（见 applyThinkingStates 里的轮次判定）。
            "window.__thRound=(window.__thRound||1)+1;" +
            // 新回答：记下「当前这一版页面里最后一条 AI 是它」。
            // 页面一旦换版，气泡跟着一起没了，onPageFinished 后按这个标记补建，
            // 不让内容落空（否则整段回答会写进旧文档，屏幕上空空如也）。
            "window.__needLastAi=true;" +
            // AI 占位也可能先于用户消息落地（队列/重发路径），一并摘掉欢迎语
            "window.__dropWelcome();" +
            // 知道下标就先标上：流式帧一律按 data-idx 找气泡（见 __msgAi），
            // 标成 -1 的话这一整轮回答的每一帧都只能退回「最后一个气泡」，
            // 页面若在这期间被重建过，正文就会写进上一条回答里
            "var start=(idx==null||idx===''||idx==='-1')?'-1':''+idx;" +
            "var d=document.createElement('div');" +
            "d.className='msg ai';d.setAttribute('data-idx',start);" +
            "d.innerHTML='<div class=\"content\"><div class=\"stream-base\"></div>" +
            "<div class=\"stream-tail\"></div></div><div class=\"sources\"></div>" +
            // 属性定界符必须是真引号（Java 侧 \"）：写成 &quot; 时它不是定界符，
            // 解码后会变成类名/处理代码里实实在在的引号字符——
            // class=&quot;msg-menu-btn&quot; 的类名带上了引号，CSS 匹配不上，
            // 按钮就成了默认样式（「三个点样式异常」的根因）；
            // onclick 同理解码出引号，脚本直接语法错误，点了没反应。
            // 处理代码里的单引号用 &#39; 实体：JS 字符串本身以单引号定界，
            // 裸单引号会把字符串截断。
            "<div class=\"ai-menu-wrap\"><button class=\"msg-menu-btn\" " +
            "onclick=\"if(window.Android)Android.messageMenu(&#39;-1&#39;);" +
            "event.stopPropagation();return false;\">" +
            "<span class=\"mdot\"></span><span class=\"mdot\"></span><span class=\"mdot\"></span>" +
            "</button></div>';" +
            "document.getElementById('msgs').appendChild(d);" +
            // 内容比气泡先到：appendAiDiv 之前到达的 streamBase / streamTail 会
            // 因为找不到 .msg.ai 而只能丢弃，那一段就永久丢了（用户看到的
            // 「有时候消息不渲染」）。这里把孤儿内容补回来。
            "if(window.__orphanBase!=null){var ob=window.__orphanBase;window.__orphanBase=null;" +
            "window.streamBase(ob);}" +
            "if(window.__orphanTail!=null){var ot=window.__orphanTail;window.__orphanTail=null;" +
            "if(ot.h!=null)window.streamTailHtml(ot.h);else window.streamTailText(ot.t);}" +
            "smartScrollToBottom();};" +
            "window.updateMsgAt=function(idx,html){" +
            "var all=document.querySelectorAll('.msg');" +
            "for(var i=0;i<all.length;i++){" +
            "if(all[i].getAttribute('data-idx')===''+idx){" +
            "var isAi=all[i].className.indexOf('msg ai')>=0;" +
            "if(isAi){var c=all[i].querySelector('.content');" +
            "if(c){c.className='content';" +
            // 保留 base/tail 骨架：整段替换会把它抹掉，之后续跑只能走 fallback
            // 重建，等于又闪一次；增量渲染也失去可靠的落点。
            "var b=c.querySelector('.stream-base'),t=c.querySelector('.stream-tail');" +
            // 与 updateLastMsg 同理：只清内容不摘 plain 类，流式光标 ▍ 会永久留在末尾
            "if(b){b.innerHTML=html;if(t){t.className='stream-tail';t.innerHTML='';}}" +
            "else{c.innerHTML=html;}" +
            "runScripts(c);}}" +
            "else{var bb=all[i].querySelector('.bubble');if(bb){bb.innerHTML=html;runScripts(bb);}}" +
            "clearStatus();addCopyBtns();return;}}};" +
            // 整条消息替换（含气泡外的附件卡片）。编辑用户消息走这里：
            // 只换 .bubble 的话，附件卡片会留在旧位置、也不会因为正文变化重新排布。
            "window.replaceMsg=function(idx,outerHtml){" +
            "var all=document.querySelectorAll('.msg');" +
            "for(var i=0;i<all.length;i++){" +
            "if(all[i].getAttribute('data-idx')===''+idx){" +
            "var box=document.createElement('div');box.innerHTML=outerHtml;" +
            "var fresh=box.firstElementChild;" +
            "if(!fresh)return;" +
            "all[i].parentNode.replaceChild(fresh,all[i]);" +
            "runScripts(fresh);clearStatus();addCopyBtns();return;}}};" +
            "window.reindexFrom=function(start){" +
            "var all=document.querySelectorAll('.msg');" +
            "for(var i=0;i<all.length;i++){" +
            "var cur=parseInt(all[i].getAttribute('data-idx'));" +
            "if(cur>=start)all[i].setAttribute('data-idx',''+(cur-1));}};" +
            "window.finalizeLast=function(idx){" +
            // 收尾之后这条消息已经在列表里，重新灌页自然会画出来，不再需要补建
            "window.__needLastAi=false;" +
            // 这条气泡不再是「流式中」：之后任何缓存帧都不该再落到它身上
            // （失败路径紧接着会用完整内容重写 base，残留的 tail 帧会重复）
            "window.__pendingTailHtml=null;window.__pendingTailText=null;" +
            "window.__pendingBaseHtml=null;window.__endHold();" +
            "var ms=document.querySelectorAll('.msg.ai');" +
            "if(ms.length>0){" +
            "var last=ms[ms.length-1];" +
            "last.setAttribute('data-idx',''+idx);" +
            // 还在渐显的内容先全部放完：渐显层里透明压着的字，定型后就没人再推了
            "window.__smoothFinish(window.__sm.arrived);" +
            "if(window.__sm){window.__sm.on=false;}" +
            // 定型后这条气泡不再是「流式中」：tail 里残留的 .plain 类会让
            // .stream-tail.plain::after 的流式光标 ▍ 一直挂在末尾，占掉一行。
            // 只摘类不动内容：中断路径下 tail 里可能还有没并进 base 的增量，
            // 清空就等于把用户已经看到的最后一段删掉。
            "var tt=last.querySelector('.stream-tail');" +
            "if(tt)tt.className='stream-tail';" +
            "var btn=last.querySelector('.msg-menu-btn');" +
            "if(btn)btn.setAttribute('onclick'," +
            // 这里拼的是一段内联事件代码：外层用双引号定界，参数里的单引号
            // 直接写即可。用 ' 定界再往内容里塞 \' 会连成 \\'，整段脚本直接不执行
            "\"if(window.Android)Android.messageMenu('\"+idx+\"');event.stopPropagation();return false;\");" +
            "var c=last.querySelector('.content');if(c)runScripts(c);" +
            "}};" +
            "window.removeFromIdx=function(idx){" +
            "var msgs=document.getElementById('msgs');" +
            "var all=msgs.querySelectorAll('.msg');" +
            "for(var i=all.length-1;i>=0;i--){" +
            "var attr=all[i].getAttribute('data-idx');" +
            "if(attr&&parseInt(attr)==idx){all[i].parentNode.removeChild(all[i]);return;}}" +
            "};" +
            "window.removeRangeFrom=function(idx){" +
            "var msgs=document.getElementById('msgs');" +
            "var all=msgs.querySelectorAll('.msg');" +
            "for(var i=all.length-1;i>=0;i--){" +
            "var attr=all[i].getAttribute('data-idx');" +
            "if(attr&&parseInt(attr)>=idx)all[i].parentNode.removeChild(all[i]);}" +
            "};" +
            "var __oh=document.getElementById('older-hint');" +
            "window.__olderDone=(!__oh||__oh.style.display==='none');" +
            "addCopyBtns();" +
            "applyToolStates(document);" +
            "window.scrollTo(0,document.body.scrollHeight);" +
            // 页面就绪这一刻把「最后一条气泡」补出来：内容若早于页面到达
            // （气泡先建在即将被冲掉的旧文档上），这里补一次，不让它落空。
            "if(window.__needLastAi){window.__needLastAi=false;" +
            "var la=window.__lastAi();" +
            "if(!la||la.querySelector('.content').childNodes.length===0){window.appendAiDiv(-1);}}" +
            "})();";
}

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

    /** 气泡、工具卡片、代码块、表格、错误卡片等全部样式。 */
    static final String CSS =
            "*{margin:0;padding:0;box-sizing:border-box;}" +
            "body{font-family:Roboto,sans-serif;font-size:14px;color:#1a1a1a;" +
            "line-height:1.6;background:#fff;padding:8px;word-wrap:break-word;" +
            "-webkit-text-size-adjust:100%;}" +
            ".msg{margin:8px 0;}" +
            ".msg.user{text-align:right;}" +
            ".msg.user .bubble{display:inline-block;background:#E3F2FD;" +
            "padding:8px 12px;border-radius:12px;max-width:85%;text-align:left;}" +
            ".msg.ai{border-top:1px solid #e0e0e0;padding-top:12px;margin-top:8px;}" +
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
            "pre{position:relative;background:#f6f8fa;border:1px solid #e1e4e8;" +
            "border-radius:4px;padding:28px 12px 10px 12px;margin:8px 0;" +
            "overflow-x:auto;-webkit-overflow-scrolling:touch;}" +
            "pre code{background:transparent;padding:0;color:#333;white-space:pre;" +
            "word-wrap:normal;font-size:13px;line-height:1.5;display:block;}" +
            "pre .lang-tag{position:absolute;top:4px;left:12px;font-size:10px;" +
            "color:#999;font-family:Roboto,sans-serif;}" +
            "pre .copy-btn{position:absolute;top:4px;right:8px;padding:2px 8px;" +
            "font-size:11px;background:#e1e4e8;border:1px solid #ccc;border-radius:3px;" +
            "color:#555;font-family:Roboto,sans-serif;}" +
            "pre .save-btn{position:absolute;top:4px;right:56px;padding:2px 8px;" +
            "font-size:11px;background:#e8f5e9;border:1px solid #a5d6a7;border-radius:3px;" +
            "color:#2e7d32;font-family:Roboto,sans-serif;}" +
            "pre .import-btn{position:absolute;top:4px;right:56px;padding:2px 8px;" +
            "font-size:11px;background:#f3e5f5;border:1px solid #ce93d8;border-radius:3px;" +
            "color:#6a1b9a;font-family:Roboto,sans-serif;}" +
            "pre .preview-btn{position:absolute;top:4px;right:104px;padding:2px 8px;" +
            "font-size:11px;background:#e1f5fe;border:1px solid #81d4fa;border-radius:3px;" +
            "color:#0277bd;font-family:Roboto,sans-serif;}" +
            "pre .run-btn{position:absolute;top:4px;right:104px;padding:2px 8px;" +
            "font-size:11px;background:#fff3e0;border:1px solid #ffcc80;border-radius:3px;" +
            "color:#e65100;font-family:Roboto,sans-serif;}" +
            "table{border-collapse:collapse;margin:8px 0;font-size:13px;white-space:nowrap;}" +
            "th,td{border:1px solid #ddd;padding:6px 10px;text-align:left;}" +
            "th{background:#f0f0f0;font-weight:bold;}" +
            ".table-scroll{overflow-x:auto;-webkit-overflow-scrolling:touch;" +
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
            ".stream-tail{word-wrap:break-word;}" +
            ".stream-tail.plain{white-space:pre-wrap;}" +
            ".stream-tail.plain::after{content:'▍';color:#90a4ae;}" +
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
            "if(pre.querySelector('.copy-btn'))return;" +
            "var btn=document.createElement('button');" +
            "btn.className='copy-btn';btn.textContent='复制';" +
            "btn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:pre.textContent;" +
            "if(window.Android)Android.copyCode(text);" +
            "btn.textContent='已复制';" +
            "setTimeout(function(){btn.textContent='复制';},2000);};" +
            "pre.appendChild(btn);" +
            "var langTag=pre.querySelector('.lang-tag');" +
            "var lang=langTag?langTag.textContent.trim().toLowerCase():'';" +
            "var narrow=(window.innerWidth<320);" +
            "var sbtn=document.createElement('button');" +
            "sbtn.className='save-btn';sbtn.textContent='保存';" +
            "sbtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:pre.textContent;" +
            "if(window.Android)Android.saveCode(lang,text);};" +
            "if(lang==='actheme'){" +
            "if(narrow)sbtn.style.display='none';else sbtn.style.right='104px';}" +
            "pre.appendChild(sbtn);" +
            "if(lang==='actheme'&&!pre.querySelector('.import-btn')){" +
            "var ibtn=document.createElement('button');" +
            "ibtn.className='import-btn';ibtn.textContent='导入主题';" +
            "ibtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:'';" +
            "if(window.Android)Android.importTheme(text);" +
            "ibtn.textContent='已导入';" +
            "setTimeout(function(){ibtn.textContent='导入主题';},2000);};" +
            "pre.appendChild(ibtn);}" +
            "if(langTag){" +
            "if((lang==='js'||lang==='javascript')&&!pre.querySelector('.run-btn')){" +
            "var rbtn=document.createElement('button');" +
            "rbtn.className='run-btn';rbtn.textContent='运行';" +
            "rbtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:'';" +
            "if(window.Android)Android.runJs(text);};" +
            "pre.appendChild(rbtn);}" +
            "if((lang==='html'||lang==='svg')&&!pre.querySelector('.preview-btn')){" +
            "var pbtn=document.createElement('button');" +
            "pbtn.className='preview-btn';pbtn.textContent='预览';" +
            "pbtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:'';" +
            "if(window.Android)Android.previewCode(lang,text);};" +
            "pre.appendChild(pbtn);}}})" +
            "(pres[i]);}}" +
            // 长按目标必须在 touchstart 时就把 data-idx 解析出来：流式期间 tail
            // 每 200ms 整段重建，700ms 后原节点早已脱离文档，parentElement 链断掉，
            // 再向上找 data-idx 只会走到 null —— 菜单永远不弹。
            "var lpTimer=null,lpStartX=0,lpStartY=0,lpIdx=null;" +
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
            "if(window.Android)Android.messageLongPress(lpIdx);},700);});" +
            "document.addEventListener('touchmove',function(e){" +
            "if(!lpTimer||!e.touches||e.touches.length!==1)return;" +
            "var t=e.touches[0];" +
            "if(Math.abs(t.clientX-lpStartX)>10||Math.abs(t.clientY-lpStartY)>10)" +
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
            // 思考块的展开状态必须存在 JS 变量里：流式每 200ms 会整段重建
            // .stream-tail 的 innerHTML，只改 DOM style 的话下一次重绘就回到
            // HTML 里写死的 display:none，表现为「展开闪一下又折叠回去」。
            // __thinkOpen 只记录「用户手动点过 toggle」的块（hasOwnProperty 区分
            // 显式折叠 false 与「从未操作过」），手动状态优先级最高，不被自动流程覆盖。
            "window.__thinkOpen=window.__thinkOpen||{};" +
            // 只有仍在 .stream-tail 里的块才算「正在输出」：commit 进 base、或整页
            // 重建后落在 .content 里的历史消息，一律视为已结束，不自动展开。
            "window.__inTail=function(n){var p=n,i=0;" +
            "while(p&&i++<10){var cn=''+(p.className||'');" +
            "if(cn.indexOf('stream-tail')>=0)return true;" +
            "if(cn.indexOf('stream-base')>=0)return false;" +
            "p=p.parentElement||p.parentNode;}return false;};" +
            "window.applyThinkingStates=function(root){" +
            "if(!root||!root.querySelectorAll)return;" +
            "var w=root.querySelectorAll('.thinking-wrap');" +
            "for(var i=0;i<w.length;i++){" +
            "var k=w[i].getAttribute('data-think-key');" +
            "var b=w[i].querySelector('.thinking-block');" +
            "var t=w[i].querySelector('.thinking-toggle');" +
            "if(!b||!t)continue;" +
            // 用户点过 → 尊重手动；否则：进行中(live=1 且在 tail) 自动展开，结束自动折叠
            "var m=(k&&Object.prototype.hasOwnProperty.call(window.__thinkOpen,k))" +
            "?!!window.__thinkOpen[k]:null;" +
            "var live=w[i].getAttribute('data-think-live')==='1'&&window.__inTail(w[i]);" +
            "var open=(m===null)?live:m;" +
            "b.style.display=open?'block':'none';" +
            "if(open){t.textContent='── 折叠思考 ──';}" +
            "else{var n=parseInt(t.getAttribute('data-tok'),10);if(!(n>0))n=1;" +
            "t.textContent='── 展开思考（'+window.fmtTok(n)+' tokens）──';}}};" +
            // 重复内容不在这里靠 DOM 去重治理：根因在 Java 侧插入工具卡片前
            // 漏了 commitTail，导致同一轮 display 同时留在 tail 又被当增量
            // 追加进 base。已在 ChatStreamRunner 修源头；DOM 侧只负责恢复
            // 展开状态，硬去重反而可能误删不同轮次、前缀相同的思考块。
            "window.toggleThinking=function(btn){" +
            "var b=btn.nextElementSibling;if(!b)return;" +
            "var w=btn.parentNode;var k=w?w.getAttribute('data-think-key'):null;" +
            "var open=b.style.display==='none';" +
            "b.style.display=open?'block':'none';" +
            "if(open){btn.textContent='── 折叠思考 ──';}" +
            "else{" +
            // 数值取渲染时写进 data-tok 的源码估算值；缺失时（老 DOM）退回按可见文本估算
            "var t=parseInt(btn.getAttribute('data-tok'),10);" +
            "if(!(t>0))t=Math.max(1,Math.round(b.textContent.length*2/3));" +
            "btn.textContent='── 展开思考（'+window.fmtTok(t)+' tokens）──';}" +
            "if(k)window.__thinkOpen[k]=open;};" +
            "window.__toolOpen=window.__toolOpen||{};" +
            "window.applyToolStates=function(root){" +
            "if(!root||!root.querySelectorAll)return;" +
            "var cards=root.querySelectorAll('.tool-card');" +
            "for(var i=0;i<cards.length;i++){" +
            "var k=cards[i].getAttribute('data-tool-key');" +
            "var b=cards[i].querySelector('.tool-body');" +
            "var h=cards[i].querySelector('.tool-head-click');" +
            "if(!b)continue;" +
            "var a=h?h.querySelector('.tool-arrow'):null;" +
            "var hint=h?h.querySelector('.tool-hint'):null;" +
            "if(k&&window.__toolOpen[k]){b.style.display='block';" +
            "if(a)a.textContent='▼';if(hint)hint.textContent='';}" +
            "else{b.style.display='none';" +
            "if(a)a.textContent='▶';" +
            "if(hint)hint.textContent='（点击展开）';}};" +
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
            "var cards=msg.querySelectorAll('.tool-card');" +
            "var n=cards.length;var bar=msg.querySelector('.tool-fold');" +
            "if(n<=window.__toolFoldLimit){" +
            "if(bar&&bar.parentNode)bar.parentNode.removeChild(bar);" +
            "msg.__foldHidden=-1;return;}" +
            "var hidden=n-window.__toolFoldLimit;var open=!!msg.__foldOpen;" +
            "if(!bar){bar=document.createElement('div');bar.className='tool-fold';" +
            "bar.setAttribute('onclick','toggleToolFold(this)');" +
            "cards[0].parentNode.insertBefore(bar,cards[0]);}" +
            "if(msg.__foldHidden===hidden&&msg.__foldShown===open)return;" +
            "msg.__foldHidden=hidden;msg.__foldShown=open;" +
            // 只改需要改的那几张：几百张卡片全量写 display 会让每次追加都重排一遍
            "for(var j=0;j<hidden;j++)cards[j].style.display=open?'':'none';" +
            "for(var k=hidden;k<n;k++)if(cards[k].style.display)cards[k].style.display='';" +
            "bar.innerHTML=open?('收起更早的 '+hidden+' 条')" +
            ":('更早的 '+hidden+' 条工具记录 · 点开');}" +
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
            "if(hint)hint.textContent=open?'':'（点击展开）';" +
            "if(k)window.__toolOpen[k]=open;};" +
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
            "h.textContent='上滑加载更早的 '+remaining+' 条消息';}" +
            "else{window.__olderDone=true;h.style.display='block';h.textContent='已到最早一条';" +
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
            "if(y>window.__touchY+8)window.__userUp=true;" +      // 手指下滑 = 往回看
            "else if(y<window.__touchY-8)window.__userUp=false;}" + // 手指上滑 = 回到底部
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
            "window.scrollTo(0,Math.max(" +
            "document.documentElement.scrollHeight||0," +
            "document.body.scrollHeight||0));" +
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
            "window.appendMsg=function(html){" +
            "var d=document.createElement('div');d.innerHTML=html;" +
            "var el=d.firstElementChild;" +
            "if(el){document.getElementById('msgs').appendChild(el);runScripts(el);}" +
            "addCopyBtns();applyToolStates(document);smartScrollToBottom();};" +
            // 状态行挂在 .msg.ai 下、.content 之外。以前它被拼进 .content 的
            // innerHTML，下一次 streamTail 重建容器时会被当作 base 的一部分留下，
            // 表现为「一直卡在正在整理搜索结果」。
            "window.__lastAi=function(){var a=document.querySelectorAll('.msg.ai');" +
            "return a.length?a[a.length-1]:null;};" +
            "window.setStatus=function(text){" +
            "var m=window.__lastAi();if(!m)return;" +
            "var s=m.querySelector('.tool-status');" +
            "if(!s){s=document.createElement('div');s.className='tool-status';" +
            "var c=m.querySelector('.content');" +
            "if(c&&c.nextSibling)m.insertBefore(s,c.nextSibling);else m.appendChild(s);}" +
            "s.textContent=text;smartScrollToBottom();};" +
            "window.clearStatus=function(){" +
            "var m=window.__lastAi();if(!m)return;" +
            "var s=m.querySelector('.tool-status');" +
            "if(s&&s.parentNode)s.parentNode.removeChild(s);};" +
            // 429 等待卡片。按钮点击绕道 Java bridge：请求线程在 Java 侧，
            // 只有那边能叫醒它，JS 自己改不了任何东西。
            // reason 是服务端 429 响应体里解析出的具体原因（配额/频率/TPM）。
            // 放在卡片里常驻，不用 Toast——Toast 一闪而过根本看不清。
            "window.showRetryCard=function(attempt,max,waitMs,reason){" +
            "var m=window.__lastAi();if(!m)return;" +
            "var s=m.querySelector('.tool-status');" +
            "if(!s){s=document.createElement('div');s.className='tool-status';" +
            "var c=m.querySelector('.content');" +
            "if(c&&c.nextSibling)m.insertBefore(s,c.nextSibling);else m.appendChild(s);}" +
            "var left=Math.max(1,Math.ceil(waitMs/1000));" +
            "var paint=function(){" +
            "s.innerHTML='<button class=\"retry-btn\" onclick=\"Android.retryNow()\">立即重试</button>'" +
            "+'<span class=\"retry-text\">'+left+' 秒后重试（'+attempt+'/'+max+'）</span>'" +
            "+'<div class=\"retry-reason\"></div>';" +
            // 用 textContent 而不是拼进 innerHTML：服务端文案可能带引号/尖括号
            "var r=s.querySelector('.retry-reason');" +
            "if(r)r.textContent=reason&&reason.length?('HTTP 429：'+reason)" +
            ":'HTTP 429：请求过于频繁（服务端未返回具体原因）';};" +
            "paint();" +
            "if(window.__retryTimer)clearInterval(window.__retryTimer);" +
            "window.__retryTimer=setInterval(function(){" +
            "left--;" +
            "var t=s.querySelector('.retry-text');" +
            "if(left<=0){clearInterval(window.__retryTimer);window.__retryTimer=null;" +
            "if(t)t.textContent='正在重试…';return;}" +
            "if(t)t.textContent=left+' 秒后重试（'+attempt+'/'+max+'）';},1000);" +
            // 卡片插进来本身会顶高内容，不滚就藏在输入栏下面，用户只看到「卡住了」
            "smartScrollToBottom();};" +
            "window.hideRetryCard=function(){" +
            "if(window.__retryTimer){clearInterval(window.__retryTimer);window.__retryTimer=null;}" +
            "var m=window.__lastAi();if(!m)return;" +
            "var s=m.querySelector('.tool-status');" +
            "if(s&&s.parentNode)s.parentNode.removeChild(s);" +
            // 移除后高度骤降，随后的错误卡片或收尾内容同样需要贴底
            "smartScrollToBottom();};" +
            "window.updateLastMsg=function(html){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            // 这里不缓存：它是「整条消息定稿」，多半发生在页面重灌之后，
            // 缓存下来的旧内容会在下一次新建气泡时被错误写入。页面未就绪的情况
            // 由 Java 侧的 JS 队列兜住（onPageFinished 后重放）。
            "if(ms.length===0)return;" +
            "var c=ms[ms.length-1];" +
            // html 是这条消息的完整内容，被闸门挡住的缓存帧已经在里面了：
            // 直接丢弃，否则收尾后 tail 会再追加一次，末尾一段重复。
            "window.__pendingTailHtml=null;window.__pendingTailText=null;" +
            "window.__pendingBaseHtml=null;window.__endHold();" +
            // 已有 base/tail 骨架时把整段 HTML 写进 base、清空 tail：
            // 若这里改回「整体替换 c.innerHTML」，收尾一次就把骨架抹掉，
            // 之后续跑只能走 fallback 重建，等于又闪一次。
            "var b=c.querySelector('.stream-base');" +
            "var t=c.querySelector('.stream-tail');" +
            "if(b){b.innerHTML=html;if(t)t.innerHTML='';}" +
            "else{c.className='content';c.innerHTML=html;}" +
            "applyToolStates(c);" +
            "clearStatus();" +
            "addCopyBtns();smartScrollToBottom();};" +
            // 收尾定型：先把被触摸闸门挡住的最后一帧落地，再把 tail 并进 base。
            // 顺序反了会把这帧搬到定型后的 base 后面，末尾一段重复。
            "window.streamFinalize=function(){" +
            "window.__flushPending();window.streamCommitTail();clearStatus();};" +
            // 流式输出拆成两段：base（已完成的思考/工具卡片）+ tail（本轮增量）。
            // 以前每个分片都整体重设 .content，工具轮次结束后上面的卡片和已渲染好的
            // markdown 会被打回纯文本，看起来像「又变回没渲染的样子」。
            "window.streamBase=function(html){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length===0){window.__orphanBase=html;return;}" +
            "var c=ms[ms.length-1];" +
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
            "applyToolStates(b);addCopyBtns(b);clearStatus();};" +
            // 增量追加：只把「本轮新增的那几张卡片」解析后挂到 base 末尾。
            // 与 streamBase 的区别是不动已有子树——已经渲染好的公式图片、代码高亮、
            // 展开状态全部原样保留，也不重新解析几万字的历史 markdown。
            // 没有骨架时按 streamBase 的兜底把现有内容整体当 base，绝不丢内容。
            "window.streamAppendBase=function(html){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length===0)return;" +
            "var c=ms[ms.length-1];" +
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
            "window.streamCommitTail=function(){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length===0)return;" +
            "var c=ms[ms.length-1];" +
            "var b=c.querySelector('.stream-base');" +
            "var t=c.querySelector('.stream-tail');" +
            "if(!b||!t){" +
            "var keep=c.innerHTML;" +
            "c.className='content';" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "b=c.querySelector('.stream-base');t=c.querySelector('.stream-tail');" +
            "if(keep){b.innerHTML=keep;window.applyThinkingStates(b);}return;}" +
            "while(t.firstChild)b.appendChild(t.firstChild);" +
            "t.className='stream-tail';t.innerHTML='';" +
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
            "window.__applyTailHtml=function(t,html){var s=window.__snapScroll(t);" +
            // 重建前不需要手动捕获：展开状态存在 __thinkOpen 里，重建后按 key 恢复
            "t.className='stream-tail';t.innerHTML=html;window.__restoreScroll(t,s);" +
            "clearStatus();addCopyBtns(t);applyToolStates(t);smartScrollToBottom();};" +
            "window.__applyTailText=function(t,text){var s=window.__snapScroll(t);" +
            "t.className='stream-tail plain';t.textContent=text;window.__restoreScroll(t,s);" +
            "clearStatus();smartScrollToBottom();};" +
            "window.__guardHold=function(){" +
            // 每次渲染帧落地前先检查：闸门超时就强制释放并丢弃缓存帧，
            // 让「当前这一帧」直接落地。只释放不清缓存的话，旧帧会在之后的
            // __flushPending 里再写一遍，末尾出现重复段落。
            "if(window.__holdExpired())window.__forceLand();};" +
            "window.__flushPending=function(){" +
            // 走到这里说明触摸手势结束了（抬手，或请求收尾）：解除闸门再落地。
            // 必须先落地再 commitTail —— 否则缓存帧会被搬进一个已经定型的 base，
            // 表现是最后一段内容重复出现。
            "window.__endHold();" +
            "var bh=window.__pendingBaseHtml;window.__pendingBaseHtml=null;" +
            "if(bh!==null)window.streamBase(bh);" +          // hold 已清，这里可以正常写入
            "var h=window.__pendingTailHtml,x=window.__pendingTailText;" +
            "window.__pendingTailHtml=null;window.__pendingTailText=null;" +
            "if(h===null&&x===null)return;" +
            "var ms=document.querySelectorAll('.msg.ai .content');if(ms.length===0)return;" +
            "var t=ms[ms.length-1].querySelector('.stream-tail');if(!t)return;" +
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
            "window.streamTailHtml=function(html){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length===0){window.__orphanTail={h:html,t:null};return;}" +
            "var c=ms[ms.length-1];var t=c.querySelector('.stream-tail');" +
            "if(!t){var b=c.querySelector('.stream-base');" +
            "var keep=b?b.innerHTML:c.innerHTML;" +
            "c.className='content';" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "c.querySelector('.stream-base').innerHTML=keep;" +
            "t=c.querySelector('.stream-tail');}" +
            "window.__guardHold();" +
            "if(window.__holdRender){window.__pendingTailHtml=html;window.__pendingTailText=null;return;}" +
            "window.__applyTailHtml(t,html);};" +
            "window.streamTailText=function(text){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length===0){window.__orphanTail={h:null,t:text};return;}" +
            "var c=ms[ms.length-1];var t=c.querySelector('.stream-tail');" +
            "if(!t){var b=c.querySelector('.stream-base');" +
            "var keep=b?b.innerHTML:c.innerHTML;" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "c.querySelector('.stream-base').innerHTML=keep;" +
            "t=c.querySelector('.stream-tail');}" +
            "window.__guardHold();" +
            "if(window.__holdRender){window.__pendingTailText=text;window.__pendingTailHtml=null;return;}" +
            "window.__applyTailText(t,text);};" +
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
            "window.updateLastText=function(text){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length>0){ms[ms.length-1].textContent=text;}" +
            "smartScrollToBottom();};" +
            "window.setLastMsgStatus=function(text){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length>0){ms[ms.length-1].textContent=text;}" +
            "smartScrollToBottom();};" +
            // 骨架从第一帧就固定成 base + tail 两层：后续所有流式写入只动其中一层。
            // 以前这里只有一个空 .content，第一片到达时才在 JS 里临时拆骨架，
            // 而 updateLastMsg 又会把 .content 整个 innerHTML 重设，把正在流出的
            // tail 一起抹掉 —— 用户看到的就是「回答闪一下然后凭空消失」。
            "window.appendAiDiv=function(){" +
            "var d=document.createElement('div');" +
            "d.className='msg ai';d.setAttribute('data-idx','-1');" +
            "d.innerHTML='<div class=\"content\"><div class=\"stream-base\"></div>" +
            "<div class=\"stream-tail\"></div></div><div class=\"sources\"></div>" +
            "<div class=\"ai-menu-wrap\"><button class=\"msg-menu-btn\" " +
            "onclick=\"if(window.Android)Android.messageMenu(\\'-1\\');" +
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
            "if(b){b.innerHTML=html;if(t)t.innerHTML='';}else{c.innerHTML=html;}" +
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
            // 这条气泡不再是「流式中」：之后任何缓存帧都不该再落到它身上
            // （失败路径紧接着会用完整内容重写 base，残留的 tail 帧会重复）
            "window.__pendingTailHtml=null;window.__pendingTailText=null;" +
            "window.__pendingBaseHtml=null;window.__endHold();" +
            "var ms=document.querySelectorAll('.msg.ai');" +
            "if(ms.length>0){" +
            "var last=ms[ms.length-1];" +
            "last.setAttribute('data-idx',''+idx);" +
            "var btn=last.querySelector('.msg-menu-btn');" +
            "if(btn)btn.setAttribute('onclick'," +
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
            "})();";
}

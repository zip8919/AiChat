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
            ".tool-head-click{cursor:pointer;-webkit-tap-highlight-color:rgba(100,180,255,0.3);}" +
            ".tool-arrow{display:inline-block;width:12px;color:#1565c0;font-size:10px;}" +
            ".tool-hint{color:#90a4ae;font-weight:normal;font-size:11px;}" +
            ".tool-body{display:none;}" +
            ".tool-item{padding:8px 10px;border-top:1px solid #e6eef8;}" +
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
            ".msg-menu-btn{display:inline-block;background:none;border:none;" +
            "color:#bbb;font-size:17px;padding:2px 8px;line-height:1;" +
            "-webkit-tap-highlight-color:transparent;}" +
            ".msg-menu-btn:active{color:#555;background:#e8e8e8;border-radius:12px;}" +
            ".ai-menu-wrap{text-align:left;margin-top:4px;}" +
            ".user-menu-wrap{text-align:right;margin-top:2px;}" +
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
            "var sbtn=document.createElement('button');" +
            "sbtn.className='save-btn';sbtn.textContent='保存';" +
            "sbtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:pre.textContent;" +
            "var saveLang=langTag?langTag.textContent.trim().toLowerCase():'';" +
            "if(window.Android)Android.saveCode(saveLang,text);};" +
            "pre.appendChild(sbtn);" +
            "if(langTag){" +
            "var lang=langTag.textContent.trim().toLowerCase();" +
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
            "var lpTimer=null,lpStartX=0,lpStartY=0,lpEl=null;" +
            "document.addEventListener('touchstart',function(e){" +
            "if(!e.touches||e.touches.length!==1)return;" +
            "var t=e.touches[0];" +
            "lpStartX=t.clientX;lpStartY=t.clientY;lpEl=e.target;" +
            "lpTimer=setTimeout(function(){" +
            "var el=lpEl;" +
            "while(el&&el!==document.body){" +
            "var idx=el.getAttribute&&el.getAttribute('data-idx');" +
            "if(idx!==null&&idx!==undefined){window.__suppressClickUntil=Date.now()+700;" +
            "if(window.Android)Android.messageLongPress(idx);return;}" +
            "el=el.parentElement;}" +
            "},700);});" +
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
            "window.toggleThinking=function(btn){" +
            "var b=btn.nextElementSibling;if(!b)return;" +
            "if(b.style.display==='none'){b.style.display='block';" +
            "btn.textContent='── 折叠思考 ──';}" +
            "else{b.style.display='none';" +
            "btn.textContent='── 展开思考（'+b.textContent.length+'字）──';}};" +
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
            "if(hint)hint.textContent='（点击展开）';}}};" +
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
            "if(window.__olderDone||window.__loadingOlder)return;" +
            "var st=document.documentElement.scrollTop||document.body.scrollTop||window.pageYOffset||0;" +
            "if(st<=40)loadOlderNow();},150);});" +
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
            "return;}}};" +
            "window.smartScrollToBottom=function(force){" +
            "if(!force){" +
            "var st=document.documentElement.scrollTop||document.body.scrollTop||window.pageYOffset||0;" +
            "var sh=Math.max(document.documentElement.scrollHeight||0,document.body.scrollHeight||0);" +
            "var ch=document.documentElement.clientHeight||document.body.clientHeight||window.innerHeight||0;" +
            "if(sh-st-ch>80)return;}" +                     // 距底 >80px → 用户上翻了，不滚动
            "window.scrollTo(0,Math.max(" +
            "document.documentElement.scrollHeight||0," +
            "document.body.scrollHeight||0));};" +
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
            "window.updateLastMsg=function(html){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length===0)return;" +
            "var c=ms[ms.length-1];" +
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
            // 收尾定型：内容已经完整时只把 tail 并进 base，不重新解析 markdown
            "window.streamFinalize=function(){" +
            "window.streamCommitTail();clearStatus();};" +
            // 流式输出拆成两段：base（已完成的思考/工具卡片）+ tail（本轮增量）。
            // 以前每个分片都整体重设 .content，工具轮次结束后上面的卡片和已渲染好的
            // markdown 会被打回纯文本，看起来像「又变回没渲染的样子」。
            "window.streamBase=function(html){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length===0)return;" +
            "var c=ms[ms.length-1];" +
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
            "if(keep)b.innerHTML=keep;return;}" +
            "while(t.firstChild)b.appendChild(t.firstChild);" +
            "t.className='stream-tail';t.innerHTML='';" +
            "clearStatus();};" +
            "window.streamTailHtml=function(html){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length===0)return;" +
            "var c=ms[ms.length-1];var t=c.querySelector('.stream-tail');" +
            "if(!t){var b=c.querySelector('.stream-base');" +
            "var keep=b?b.innerHTML:c.innerHTML;" +
            "c.className='content';" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "c.querySelector('.stream-base').innerHTML=keep;" +
            "t=c.querySelector('.stream-tail');}" +
            "t.className='stream-tail';t.innerHTML=html;" +
            "clearStatus();addCopyBtns(t);smartScrollToBottom();};" +
            "window.streamTailText=function(text){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length===0)return;" +
            "var c=ms[ms.length-1];var t=c.querySelector('.stream-tail');" +
            "if(!t){var b=c.querySelector('.stream-base');" +
            "var keep=b?b.innerHTML:c.innerHTML;" +
            "c.innerHTML='<div class=\"stream-base\"></div><div class=\"stream-tail\"></div>';" +
            "c.querySelector('.stream-base').innerHTML=keep;" +
            "t=c.querySelector('.stream-tail');}" +
            "t.className='stream-tail plain';t.textContent=text;" +
            "clearStatus();smartScrollToBottom();};" +
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
            "event.stopPropagation();return false;\">⋯</button></div>';" +
            "document.getElementById('msgs').appendChild(d);" +
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
            "window.reindexFrom=function(start){" +
            "var all=document.querySelectorAll('.msg');" +
            "for(var i=0;i<all.length;i++){" +
            "var cur=parseInt(all[i].getAttribute('data-idx'));" +
            "if(cur>=start)all[i].setAttribute('data-idx',''+(cur-1));}};" +
            "window.finalizeLast=function(idx){" +
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

(function () {
  function text(el) { return (el.textContent || '').replace(/\s+/g, ' ').trim(); }

  var doc = document.createElement('div');
  doc.appendChild(document.body.cloneNode(true));
  var junk = doc.querySelectorAll('script,style,noscript,iframe,form,nav,header,footer,aside,svg,button,input,select,textarea,[role=navigation],[role=banner],[role=complementary],[aria-hidden=true],.ad,.ads,.advert,.share,.social,.comments,#comments,.related,.newsletter,.cookie,.popup,.modal');
  for (var i = 0; i < junk.length; i++) if (junk[i].parentNode) junk[i].parentNode.removeChild(junk[i]);

  var scores = [], nodes = [];
  function add(node, s) {
    if (!node || node.nodeType !== 1) return;
    var i = nodes.indexOf(node);
    if (i < 0) { nodes.push(node); scores.push(s); } else scores[i] += s;
  }
  var ps = doc.querySelectorAll('p');
  for (var j = 0; j < ps.length; j++) {
    var t = text(ps[j]);
    if (t.length < 30) continue;
    var s = 1 + Math.min(3, Math.floor(t.length / 100)) + (t.split(',').length - 1) * 0.5;
    add(ps[j].parentNode, s);
    if (ps[j].parentNode) add(ps[j].parentNode.parentNode, s / 2);
  }
  var best = null, bestScore = 0;
  for (var k = 0; k < nodes.length; k++) {
    var links = nodes[k].querySelectorAll('a'), linkText = 0;
    for (var l = 0; l < links.length; l++) linkText += text(links[l]).length;
    var total = text(nodes[k]).length || 1;
    var sc = scores[k] * (1 - Math.min(0.9, linkText / total));
    if (sc > bestScore) { bestScore = sc; best = nodes[k]; }
  }
  var known = doc.querySelector('.mw-parser-output,[itemprop=articleBody],article,main,#content');
  if (known) {
    var kp = known.querySelectorAll('p'), kt = 0;
    for (var q = 0; q < kp.length; q++) kt += text(kp[q]).length;
    var bp = best ? best.querySelectorAll('p') : [], bt = 0;
    for (var r = 0; r < bp.length; r++) bt += text(bp[r]).length;
    if (!best || kt >= bt) best = known;
  }
  if (!best) best = doc.firstChild;
  var extra = best.querySelectorAll('.reflist,.references,.navbox,.infobox,.mw-editsection,.toc,#toc,.metadata,.ambox,.hatnote,sup.reference,.mw-empty-elt');
  for (var e = 0; e < extra.length; e++) if (extra[e].parentNode) extra[e].parentNode.removeChild(extra[e]);

  var keep = { P: 1, H1: 1, H2: 1, H3: 1, H4: 1, UL: 1, OL: 1, LI: 1, BLOCKQUOTE: 1, PRE: 1, CODE: 1,
               EM: 1, I: 1, STRONG: 1, B: 1, A: 1, IMG: 1, FIGURE: 1, FIGCAPTION: 1, BR: 1, HR: 1,
               TABLE: 1, TR: 1, TD: 1, TH: 1, TBODY: 1, THEAD: 1, SUP: 1, SUB: 1 };
  function clean(node) {
    var out = [];
    for (var c = node.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3) { out.push(c.textContent.replace(/[<>&]/g, function (m) { return {'<': '&lt;', '>': '&gt;', '&': '&amp;'}[m]; })); continue; }
      if (c.nodeType !== 1) continue;
      var tag = c.tagName;
      var inner = clean(c);
      if (!keep[tag]) { out.push(inner); if (/^(DIV|SECTION|ARTICLE|MAIN)$/.test(tag)) out.push('\n'); continue; }
      if (tag === 'IMG') {
        var src = c.getAttribute('data-src') || c.getAttribute('src');
        if (src && !/^data:/.test(src)) out.push('<img src="' + src.replace(/"/g, '&quot;') + '" alt="">');
        continue;
      }
      if (tag === 'A') {
        var href = c.getAttribute('href');
        out.push(href ? '<a href="' + href.replace(/"/g, '&quot;') + '">' + inner + '</a>' : inner);
        continue;
      }
      if ((tag === 'P' || tag === 'LI') && !inner.replace(/<[^>]*>/g, '').trim() && inner.indexOf('<img') < 0) continue;
      out.push('<' + tag.toLowerCase() + '>' + inner + '</' + tag.toLowerCase() + '>');
    }
    return out.join('');
  }

  var meta = function (sel) { var m = document.querySelector(sel); return m ? m.getAttribute('content') : ''; };
  var title = (document.querySelector('h1') ? text(document.querySelector('h1')) : '') || meta('meta[property="og:title"]') || (document.querySelector('h1') ? text(document.querySelector('h1')) : '') || document.title;
  var byline = meta('meta[name="author"]') || meta('meta[property="article:author"]') || '';
  var site = meta('meta[property="og:site_name"]') || location.hostname.replace(/^www\./, '');
  var body = clean(best);
  body = body.replace(/^\s*<h1>[\s\S]*?<\/h1>/, '');
  InkiosBridge.deliver(JSON.stringify({ title: title, byline: byline, site: site, html: body, words: text(best).split(' ').length }));
})();

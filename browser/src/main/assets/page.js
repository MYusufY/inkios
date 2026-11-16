(function () {
  if (window.__inkios) return;
  window.__inkios = 1;
  function enc(s) { return encodeURIComponent(s).replace(/%20/g, '+'); }
  document.addEventListener('submit', function (e) {
    var f = e.target;
    if (!f || !f.getAttribute || (f.getAttribute('method') || '').toLowerCase() !== 'post') return;
    if (!window.InkiosBridge || !InkiosBridge.post) return;
    var parts = [], els = f.elements;
    for (var i = 0; i < els.length; i++) {
      var el = els[i], t = (el.type || '').toLowerCase();
      if (!el.name || el.disabled) continue;
      if ((t === 'checkbox' || t === 'radio') && !el.checked) continue;
      if (t === 'file' || t === 'submit' || t === 'button' || t === 'image' || t === 'reset') continue;
      if (t === 'select-multiple') {
        for (var j = 0; j < el.options.length; j++) {
          if (el.options[j].selected) parts.push(enc(el.name) + '=' + enc(el.options[j].value));
        }
        continue;
      }
      parts.push(enc(el.name) + '=' + enc(el.value));
    }
    var a = document.activeElement;
    if (a && a.form === f && a.name && (a.type || '').toLowerCase() === 'submit') {
      parts.push(enc(a.name) + '=' + enc(a.value));
    }
    e.preventDefault();
    InkiosBridge.post(f.action || location.href, parts.join('&'));
  }, true);
  function unpin() {
    if (!document.body) return;
    var n = document.querySelectorAll('body>*,body>*>*,body>*>*>*');
    for (var i = 0; i < n.length && i < 800; i++) {
      var p = window.getComputedStyle(n[i]).position;
      if (p === 'fixed' || p === 'sticky' || p === '-webkit-sticky') n[i].style.setProperty('position', 'static', 'important');
    }
  }
  document.addEventListener('DOMContentLoaded', unpin, false);
  window.addEventListener('load', unpin, false);
})();

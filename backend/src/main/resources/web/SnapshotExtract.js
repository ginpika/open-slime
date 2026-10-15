/* Keep knowledge-page structures before applying reader heuristics to ordinary articles. */
function extractSnapshot(options) {
  const clone = document.cloneNode(true);
  const infoboxSelector = '.infobox,.infobox_v2,.infobox_v3,.infobox-table,.unit-infobox,.portable-infobox,.character-infobox,.character-info,.profile-box,table.floatright';
  const chromeSelector = 'nav,[role="navigation"],[role="menu"],[role="menubar"],[role="banner"],#mw-navigation,#mw-head,#mw-panel,#site-navigation,#site-header,#site-footer,#toc,.toc,.navbox,.navbar,.mw-editsection,.mw-jump-link,.mw-indicators,.printfooter,.catlinks,.site-header,.site-footer,.site-sidebar,.sidebar-menu,.nav-menu,.vector-menu,.vector-header,.vector-sidebar,.citizen-header,.citizen-page-sidebar,.wds-global-navigation,.fandom-community-header,.page-side-tools,.page-header__actions';
  const liveMedia = [...document.querySelectorAll('img,audio,video')];
  clone.querySelectorAll('img,audio,video').forEach((e, i) => {
    const live = liveMedia[i];
    if (live?.currentSrc || e.getAttribute('src')) e.setAttribute('data-snapshot-fallback', live?.currentSrc || e.getAttribute('src'));
    const lazy = ['data-original', 'data-original-src', 'data-src', 'data-lazy-src'].map(attr => e.getAttribute(attr)).find(Boolean);
    if (lazy && e.getAttribute('data-snapshot-fallback')?.startsWith('data:')) e.removeAttribute('data-snapshot-fallback');
    if (lazy) e.setAttribute('src', lazy);
    else if (live?.currentSrc) e.setAttribute('src', live.currentSrc);
    if (e.tagName === 'IMG') {
      if (!e.getAttribute('alt')) e.setAttribute('alt', e.getAttribute('title') || e.getAttribute('data-image-name') || '');
      const srcset = e.getAttribute('data-srcset') || e.getAttribute('srcset');
      if (srcset && !lazy) {
        const candidates = srcset.split(',').map(item => item.trim().split(/\s+/)).filter(item => item[0]);
        candidates.sort((a, b) => (parseFloat(b[1]) || 1) - (parseFloat(a[1]) || 1));
        if (candidates[0]) e.setAttribute('src', candidates[0][0]);
      }
      e.removeAttribute('srcset');
      e.removeAttribute('sizes');
      const href = e.closest('a')?.getAttribute('href');
      const isGif = value => /\.gif(?:[/?#]|$)/i.test(value || '');
      const directImage = href && /\.(?:png|jpe?g|webp|gif)(?:[/?#]|$)/i.test(href) && !/\/wiki\//i.test(href);
      let original = directImage ? href : e.getAttribute('src');
      try {
        const url = new URL(original, document.baseURI);
        // MediaWiki's file thumbnail path carries the original filename before the final size segment.
        if (/\/thumb\/.*\.gif\//i.test(url.pathname)) {
          url.pathname = url.pathname.replace('/thumb/', '/').replace(/(\.gif)\/.*$/i, '$1');
          original = url.href;
        }
        if (url.hostname.endsWith('.wikia.nocookie.net')) {
          url.pathname = url.pathname.replace(/\/(?:scale-to-width-down|scale-to-width|scale-to-height-down)\/\d+$/i, '');
          url.searchParams.set('format', 'original'); // Fandom otherwise negotiates WebP, even for .gif URLs.
          original = url.href;
          e.setAttribute('data-snapshot-original', original);
        }
        if (isGif(original)) { e.setAttribute('data-snapshot-original', original); e.setAttribute('data-snapshot-gif', 'true'); }
        else if (directImage) e.setAttribute('data-snapshot-original', original);
      } catch { /* downloader will use the original src if URL parsing fails */ }
    }
  });
  clone.querySelectorAll('a[title]').forEach(e => { if (/[<>]/.test(e.getAttribute('title'))) e.removeAttribute('title'); });
  clone.querySelectorAll('noscript').forEach(e => e.remove());

  // Protect fact boxes even when a skin calls them a sidebar or complementary region.
  clone.querySelectorAll(infoboxSelector).forEach(e => e.classList.add('snapshot-infobox'));
  const liveTables = [...document.querySelectorAll('table')];
  clone.querySelectorAll('table').forEach((e, i) => {
    if (e.closest('nav,[role="navigation"],.navbox,.navbar,#toc')) return;
    const labels = [...e.querySelectorAll('th')].map(th => th.textContent.trim()).join(' ');
    if ((liveTables[i] && getComputedStyle(liveTables[i]).cssFloat === 'right')
        || /日本語|日文|英文|英語|官方|本名|姓名|名前|称号|Japanese|English|Official name/i.test(labels)) e.classList.add('snapshot-infobox');
  });
  clone.querySelectorAll(chromeSelector).forEach(e => {
    if (!e.closest('.snapshot-infobox')) e.remove();
  });
  clone.querySelectorAll('header,footer,aside,[role="complementary"],[class*="sidebar" i],[id*="sidebar" i]').forEach(e => {
    if (e.closest('.snapshot-infobox') || e.querySelector('.snapshot-infobox')) return;
    const insideArticle = !!e.closest('article,main,[role="main"],.mw-parser-output,.wiki-content');
    const links = [...e.querySelectorAll('a')].reduce((sum, a) => sum + a.textContent.trim().length, 0);
    const text = e.textContent.trim().length;
    const menu = e.querySelector('[role="menu"],nav,button[aria-expanded],details') || (links > 30 && links / Math.max(text, 1) > .65);
    if (!insideArticle || menu) e.remove();
  });

  const candidates = [...clone.querySelectorAll('#mw-content-text .mw-parser-output,.mw-parser-output,#WikiaArticle,.wiki-content')]
    .filter(e => !e.closest('nav,header,footer,.snapshot-infobox'));
  const score = e => e.textContent.trim().length + e.querySelectorAll('.snapshot-infobox').length * 1000;
  candidates.sort((a, b) => score(b) - score(a));
  const wiki = candidates[0];
  const mainCandidates = [...clone.querySelectorAll('main,[role="main"],article')].filter(e => !e.closest('nav,header,footer'));
  mainCandidates.sort((a, b) => score(b) - score(a));
  const main = wiki || mainCandidates[0] || clone.body;

  // Rebuild the content relationships, never copy or execute the site's toggler JavaScript.
  const makeTabs = entries => {
    const group = clone.createElement('div'); group.className = 'snapshot-tabs';
    for (const { label, nodes } of entries) {
      const panel = clone.createElement('section'); panel.className = 'snapshot-tab-panel';
      panel.setAttribute('data-snapshot-label', label);
      const heading = panel.appendChild(clone.createElement('h3')); heading.className = 'snapshot-tab-heading'; heading.textContent = label;
      nodes.forEach(node => panel.append(node)); group.append(panel);
    }
    return group;
  };
  const togglers = new Map();
  main.querySelectorAll('a[href^="javascript:"]').forEach(link => {
    const control = link.parentElement;
    const box = link.closest('.unit-infobox');
    const row = box ? link.closest('.ui-text') : link.closest('table');
    if (!row) return;
    const scope = box || main;
    const targets = [...control.classList].filter(name => name.startsWith('_toggler_show-'))
      .map(name => name.slice('_toggler_show-'.length)).filter(name => !/link$/i.test(name));
    for (const target of targets) {
      if (!box && !/Gallery$/.test(target)) continue;
      const nodes = [...scope.getElementsByClassName(target)]
        .filter(node => !row.contains(node) && node.matches('div,section,table,figure,aside'));
      if (!nodes.length) continue;
      if (!togglers.has(row)) togglers.set(row, new Map());
      togglers.get(row).set(target, { label: link.textContent.trim(), nodes });
    }
  });
  for (const [row, entries] of togglers) {
    if (entries.size < 2 || !row.isConnected) continue;
    const group = makeTabs([...entries.values()]); row.replaceWith(group);
  }
  // Standard MediaWiki Tabber markup has the labels on its already-loaded panels.
  main.querySelectorAll('.tabber').forEach(tabber => {
    const panels = [...tabber.children].filter(node => node.classList.contains('tabbertab'));
    if (panels.length < 2) return;
    const entries = panels.map(panel => ({ label: panel.getAttribute('title') || panel.getAttribute('data-title') || '', nodes: [...panel.childNodes] }));
    if (entries.some(entry => !entry.label)) return;
    tabber.replaceWith(makeTabs(entries));
  });
  // Keep every loaded gallery item, including inactive versions and non-animated images.
  main.querySelectorAll('.wikia-gallery,ul.gallery').forEach(gallery => {
    const grid = clone.createElement('div'); grid.className = 'snapshot-gallery';
    [...gallery.querySelectorAll('.wikia-gallery-item,.gallerybox')].forEach(item => {
      const image = item.querySelector('img:not(noscript img)'); if (!image) return;
      const figure = clone.createElement('figure');
      const caption = item.querySelector('.lightbox-caption,.gallerytext');
      figure.append(image);
      const text = caption?.textContent.trim() || image.getAttribute('alt');
      if (text) { const label = clone.createElement('figcaption'); label.textContent = text; figure.append(label); }
      grid.append(figure);
    });
    if (grid.children.length) gallery.replaceWith(grid);
  });

  // Aigis/Fandom unit boxes use custom divs, with inactive portraits hidden behind script-only tabs.
  main.querySelectorAll('.unit-infobox').forEach(box => {
    box.querySelectorAll('.ui-title,.ui-text').forEach(e => {
      if (e.querySelector('a[href^="javascript:"]')) { e.remove(); return; }
      const paragraph = clone.createElement(e.classList.contains('ui-title') ? 'h2' : 'p');
      paragraph.innerHTML = e.innerHTML; e.replaceWith(paragraph);
    });
    box.querySelectorAll('.ui-image').forEach(e => {
      const figure = clone.createElement('figure'); figure.innerHTML = e.innerHTML;
      const image = figure.querySelector('img');
      if (image?.getAttribute('alt')) { const caption = clone.createElement('figcaption'); caption.textContent = image.getAttribute('alt'); figure.prepend(caption); }
      e.replaceWith(figure);
    });
  });

  // Portable infoboxes represent label/value pairs as divs; use semantic, indexable definitions.
  main.querySelectorAll('.snapshot-infobox .pi-data,.snapshot-infobox .ui-databox').forEach(e => {
    const label = e.querySelector('.pi-data-label,.ui-label'), value = e.querySelector('.pi-data-value,.ui-data');
    if (!label || !value) return;
    const dl = clone.createElement('dl');
    const dt = dl.appendChild(clone.createElement('dt')), dd = dl.appendChild(clone.createElement('dd'));
    dt.innerHTML = label.innerHTML; dd.innerHTML = value.innerHTML; e.replaceWith(dl);
  });
  if (!options.images) main.querySelectorAll('img').forEach(e => e.remove());
  const retained = [];
  const protect = (e, required) => {
    const marker = options.marker + retained.length + '_END';
    retained.push({ marker, html: e.outerHTML, required });
    const placeholder = clone.createElement(e.matches('img') ? 'span' : 'p');
    placeholder.textContent = marker; e.replaceWith(placeholder);
  };
  // Preserve positions and restore a core fact box at the beginning if reader scoring drops its marker.
  [...main.querySelectorAll('.snapshot-infobox')].filter(e => !e.parentElement?.closest('.snapshot-infobox')).forEach(e => protect(e, true));
  [...main.querySelectorAll('.snapshot-tabs,.snapshot-gallery')]
    .filter(e => !e.parentElement?.closest('.snapshot-tabs,.snapshot-gallery')).forEach(e => protect(e, true));
  main.querySelectorAll('audio,video').forEach(e => {
    if (options[e.tagName.toLowerCase()]) protect(e, false); else e.remove();
  });
  main.querySelectorAll('img[data-snapshot-gif]').forEach(e => protect(e, true));
  let article = null;
  try { article = new Readability(clone.cloneNode(true), { charThreshold: 80, maxElemsToParse: 50000 }).parse(); } catch { /* selected main remains the safe fallback */ }
  const result = clone.createElement('div');
  result.innerHTML = wiki ? main.innerHTML : article?.content || main.innerHTML;
  const missing = [];
  for (const item of retained) {
    const walker = clone.createTreeWalker(result, NodeFilter.SHOW_TEXT), texts = [];
    while (walker.nextNode()) if (walker.currentNode.textContent.includes(item.marker)) texts.push(walker.currentNode);
    if (!texts.length && item.required) { missing.push(item.html); continue; }
    for (const node of texts) {
      const parts = node.textContent.split(item.marker), fragment = clone.createDocumentFragment();
      parts.forEach((part, i) => {
        if (i) { const template = clone.createElement('template'); template.innerHTML = item.html; fragment.append(template.content); }
        fragment.append(clone.createTextNode(part));
      });
      // Block-level fact boxes must not be restored inside a paragraph.
      if (node.parentElement?.tagName === 'P' && node.parentElement.textContent.trim() === item.marker) node.parentElement.replaceWith(fragment);
      else node.replaceWith(fragment);
    }
  }
  if (missing.length) result.insertAdjacentHTML('afterbegin', missing.join(''));
  return { title: article?.title || document.title, author: article?.byline || '',
    publishedAt: article?.publishedTime || document.querySelector('meta[property="article:published_time"]')?.content || '',
    content: result.innerHTML, fallback: !wiki && !article };
}

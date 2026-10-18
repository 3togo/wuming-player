import cheerio from './cheerio.min.js';

function parts(parse) {
    const value = String(parse || '').replace(/^(?:jq|jsp):/, '');
    const split = value.includes('&&') ? value.split('&&') : value.trim().split(/\s+(?=[^\s]+$)/);
    return { selector: split.slice(0, -1).join('&&') || split[0] || '', option: split.length > 1 ? split.at(-1) : 'Text' };
}

function root(html) {
    if (html && typeof html === 'object' && html.__tvboxRoot) return html.__tvboxRoot;
    return cheerio.load(String(html || ''));
}

function selection(html, selector) {
    if (html && typeof html === 'object' && html.__tvboxSelection) return html.__tvboxSelection.find(selector);
    return root(html)(selector);
}

function valueOf(item, option) {
    const lower = String(option || '').toLowerCase();
    if (lower === 'text') return item.text().trim();
    if (lower === 'html') return item.html() || '';
    if (lower === 'all') return item.toString();
    return item.attr(option) || '';
}

globalThis.pdfh = (html, parse) => {
    const parsed = parts(parse);
    return valueOf(selection(html, parsed.selector).first(), parsed.option);
};

globalThis.pdfa = (html, parse) => {
    const parsed = parts(parse);
    const base = root(html);
    return selection(html, parsed.selector).toArray().map(element => ({
        __tvboxRoot: base,
        __tvboxSelection: base(element)
    }));
};

globalThis.pd = (html, parse, baseUrl = '') => {
    const value = globalThis.pdfh(html, parse);
    if (!value || /^(?:[a-z]+:)?\/\//i.test(value)) return value;
    try { return new URL(value, baseUrl || globalThis.MY_URL || '').toString(); } catch (_) { return value; }
};

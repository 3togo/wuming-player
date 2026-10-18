import { Buffer } from 'buffer';
import Crypto from 'crypto-js';
import Uri from 'urijs';
import _ from 'lodash';
import dayjs from 'dayjs';
import * as dom from 'cheerio';
import nunjucks from 'nunjucks/browser/nunjucks.js';
import { JSONPath } from 'jsonpath-plus';
if (!globalThis.Buffer) globalThis.Buffer = Buffer;
const cheerio = dom.load([]);
const { contains, html, load, merge, parseHTML, root, text, xml } = dom;
function jp(path, json) { return JSONPath({ path, json }); }
function jpo(options) { return JSONPath(options); }
function jinja2(template, context) { return nunjucks.renderString(template, context); }
export { Crypto, Uri, _, dayjs, cheerio, contains, html, load, merge, parseHTML, root, text, xml, jp, jpo, jinja2 };

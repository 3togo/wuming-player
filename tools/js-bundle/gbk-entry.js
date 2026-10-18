import iconv from 'iconv-lite';
import { Buffer } from 'buffer';
if (!globalThis.Buffer) globalThis.Buffer = Buffer;
export function gbkTool() {
    return {
        encode(value) {
            return Array.from(iconv.encode(String(value), 'gbk'), b => b < 128
                ? encodeURIComponent(String.fromCharCode(b))
                : '%' + b.toString(16).toUpperCase().padStart(2, '0')).join('');
        },
        decode(value) {
            return String(value).replace(/(?:%[0-9a-f]{2})+/gi, run =>
                iconv.decode(Buffer.from(run.match(/[0-9a-f]{2}/gi).map(v => parseInt(v, 16))), 'gbk'));
        }
    };
}

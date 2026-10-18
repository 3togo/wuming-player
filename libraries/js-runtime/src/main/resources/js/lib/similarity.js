// Project-authored compatibility helpers, GPL-3.0. No inherited implementation.
export function compareTwoStrings(a, b) {
    a = String(a).replace(/\s/g, ''); b = String(b).replace(/\s/g, '');
    if (a === b) return 1;
    if (a.length < 2 || b.length < 2) return 0;
    const counts = new Map();
    for (let i = 1; i < a.length; i++) { const pair = a.slice(i - 1, i + 1); counts.set(pair, (counts.get(pair) || 0) + 1); }
    let overlap = 0;
    for (let i = 1; i < b.length; i++) { const pair = b.slice(i - 1, i + 1), n = counts.get(pair) || 0; if (n) { overlap++; counts.set(pair, n - 1); } }
    return 2 * overlap / (a.length + b.length - 2);
}
export function findBestMatch(value, targets) {
    const ratings = targets.map(target => ({ target, rating: compareTwoStrings(value, target) }));
    const bestMatchIndex = ratings.reduce((best, item, i) => item.rating > ratings[best].rating ? i : best, 0);
    return { ratings, bestMatch: ratings[bestMatchIndex], bestMatchIndex };
}
function longestCommonSequence(a, b) {
    let previous = Array(b.length + 1).fill(0), length = 0, offset = 0;
    for (let i = 1; i <= a.length; i++) {
        const current = Array(b.length + 1).fill(0);
        for (let j = 1; j <= b.length; j++) if (a[i - 1] === b[j - 1]) {
            current[j] = previous[j - 1] + 1;
            if (current[j] > length) { length = current[j]; offset = i - length; }
        }
        previous = current;
    }
    return { length, sequence: a.slice(offset, offset + length), offset };
}
export function findBestLCS(value, targets) {
    const allLCS = targets.map(target => ({ target, lcs: longestCommonSequence(String(value), String(target)) }));
    const bestMatchIndex = allLCS.reduce((best, item, i) => item.lcs.length > allLCS[best].lcs.length ? i : best, 0);
    return { allLCS, bestMatch: allLCS[bestMatchIndex], bestMatchIndex };
}

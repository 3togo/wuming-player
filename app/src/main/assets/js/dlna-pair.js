'use strict';
(() => {
    const chinese = navigator.language.toLowerCase().startsWith('zh');
    const traditional = /tw|hk|hant/i.test(navigator.language);
    const words = chinese ? (traditional ? {
        title: 'DLNA 掃碼配對', instructions: '點擊配對，然後回到投放應用選擇此電視。手機與電視需連接同一 Wi-Fi。',
        pair: '配對此手機', duration: '一次僅允許一部手機，授權有效期為 2 小時，電視應用重新啟動後失效。可在電視配對頁中斷連線。',
        invalid: '請在電視設定中開啟 DLNA 掃碼配對，重新產生 QR 碼並掃描。',
        success: '配對成功！現在可回到投放應用選擇此電視。', pending: '正在配對…', failed: '配對失敗或 QR 碼已使用／失效。請重新產生 QR 碼並掃描。'
    } : {
        title: 'DLNA 扫码配对', instructions: '点击配对，然后回到投屏应用选择此电视。手机与电视需连接同一 Wi-Fi。',
        pair: '配对此手机', duration: '一次仅允许一部手机，授权有效期为 2 小时，电视应用重启后失效。可在电视配对页断开连接。',
        invalid: '请在电视设置中打开 DLNA 扫码配对，刷新二维码并扫描。',
        success: '配对成功！现在可回到投屏应用选择此电视。', pending: '正在配对…', failed: '配对失败或二维码已使用／失效。请刷新二维码并扫描。'
    }) : {
        title: 'DLNA pairing', instructions: 'Pair this phone, then return to your casting app and select this TV. Keep both devices on the same Wi-Fi.',
        pair: 'Pair this phone', duration: 'One phone at a time. Access lasts 2 hours or until the TV app restarts. Disconnect from the TV pairing screen to revoke access.',
        invalid: 'Open TV Settings, then DLNA pairing, refresh the QR code and scan it.',
        success: 'Paired! Return to your casting app and select this TV.', pending: 'Pairing…', failed: 'Pairing failed or the QR code expired / was already used. Refresh it on the TV and scan again.'
    };
    for (const id of ['title', 'instructions', 'pair', 'duration']) document.getElementById(id).textContent = words[id];
    let code = new URLSearchParams(location.hash.slice(1)).get('code');
    history.replaceState(null, '', location.pathname);
    const button = document.getElementById('pair');
    const status = document.getElementById('status');
    button.disabled = !/^[0-9a-f]{64}$/.test(code || '');
    if (button.disabled) status.textContent = words.invalid;
    button.addEventListener('click', async () => {
        button.disabled = true;
        status.textContent = words.pending;
        try {
            const response = await fetch('/dlna-pair', { method: 'POST', headers: { 'X-Pairing-Code': code }, cache: 'no-store', credentials: 'omit' });
            status.textContent = response.ok ? words.success : words.failed;
        } catch (_) {
            status.textContent = words.failed;
        } finally {
            code = null;
        }
    });
})();

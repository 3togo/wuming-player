const icDir = `data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='%23F5A623'><path d='M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z'/></svg>`;
const icFile = `data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><path fill='none' stroke='%23717970' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round' d='M6 3 H14 L19 8 V21 H6 Z M14 3 V8 H19 M9 12 H16 M9 16 H16'/></svg>`;
let currentRoot = '';
let currentFile = '';
let currentParent = '';
let longPressTimer = null;
let longPressTriggered = false;
let pendingDelFolder = null;
let warnToastTimer = null;
let remoteToken = new URLSearchParams(window.location.search).get('token');
try { if (!remoteToken) remoteToken = sessionStorage.getItem('remoteToken'); } catch (_) {}
const remoteReady = $.Deferred();
function applyRemoteToken(token) {
    remoteToken = token;
    try { if (token) sessionStorage.setItem('remoteToken', token); } catch (_) {}
    if (remoteToken) $.ajaxSetup({ headers: { Authorization: `Bearer ${remoteToken}` } });
    remoteReady.resolve();
}
const pairingCode = new URLSearchParams(window.location.hash.slice(1)).get('pair');
history.replaceState(null, '', window.location.pathname + window.location.search);
if (/^[0-9a-f]{64}$/.test(pairingCode || '')) {
    $.ajax({ url: '/pair', method: 'POST', headers: { 'X-Pairing-Code': pairingCode } })
        .done(token => applyRemoteToken(token)).fail(() => remoteReady.resolve());
} else if (remoteToken) applyRemoteToken(remoteToken);
else remoteReady.resolve();

function search() {
    doAction('search', { word: $('#keyword').val() });
}

function push() {
    doAction('push', { url: $('#push_url').val() });
}

function setting() {
    doAction('setting', { text: $('#setting_text').val(), name: $('#setting_name').val() });
}

function doAction(action, kv) {
    remoteReady.done(() => $.post('/action', { ...kv, do: action }));
}

function startLongPress(callback) {
    longPressTriggered = false;
    longPressTimer = setTimeout(() => {
        longPressTriggered = true;
        callback();
    }, 500);
}

function cancelLongPress() {
    if (longPressTimer) { clearTimeout(longPressTimer); longPressTimer = null; }
}

function escPath(s) {
    return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

function escHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function buildParentItem() {
    return `<a class="file-item" href="javascript:void(0)" onclick="history.back()">
    <img class="file-icon" src="${icDir}" alt="">
    <div class="file-info"><div class="file-name">..</div></div>
    </a>`;
}

function buildDirItem(name, time, path) {
    const ep = escPath(path);
    return `<a class="file-item" href="javascript:void(0)" ontouchstart="startLongPress(()=>showDelFolderDialog('${ep}',currentRoot))" ontouchmove="cancelLongPress()" ontouchend="cancelLongPress()" oncontextmenu="return false" onclick="if(!longPressTriggered)listFile('${ep}',true)">
    <img class="file-icon" src="${icDir}" alt="">
    <div class="file-info"><div class="file-name">${escHtml(name)}</div><div class="file-time">${escHtml(time)}</div></div>
    </a>`;
}

function buildFileItem(name, time, path) {
    const ep = escPath(path);
    return `<a class="file-item" href="javascript:void(0)" ontouchstart="startLongPress(()=>showDelFileDialog('${ep}'))" ontouchmove="cancelLongPress()" ontouchend="cancelLongPress()" oncontextmenu="return false" onclick="if(!longPressTriggered)selectFile('${ep}')">
    <img class="file-icon" src="${icFile}" alt="">
    <div class="file-info"><div class="file-name">${escHtml(name)}</div><div class="file-time">${escHtml(time)}</div></div>
    </a>`;
}

function addFile(node) {
    $('#file_list').append(node);
}

function selectFile(path) {
    currentFile = path;
    $("#fileUrl").text("file:/" + path);
    $("#fileInfoDialog").show();
}

function pushFile(yes) {
    $("#fileInfoDialog").hide();
    if (yes === 1) doAction('file', { path: "file:/" + currentFile });
}

function listFile(path, addHistory = false) {
    if (remoteReady.state() === 'pending') {
        remoteReady.done(() => listFile(path, addHistory));
        return;
    }
    const loadingTimer = setTimeout(() => $('#loadingToast').show(), 200);
    $.get('/file' + path, function (res) {
        clearTimeout(loadingTimer);
        let info;
        try {
            info = JSON.parse(res);
        } catch (e) {
            $('#loadingToast').hide();
            warnToast('回應格式錯誤');
            return;
        }
        const parent = info.parent;
        currentRoot = path;
        currentParent = parent;
        const array = info.files;
        if (path === '' && array.length === 0) warnToast('可能沒有存儲權限');
        $('#file_list').html('');
        if (parent !== '.') addFile(buildParentItem());
        array.forEach(node => {
            if (node.dir === 1) addFile(buildDirItem(node.name, node.time, node.path));
            else addFile(buildFileItem(node.name, node.time, node.path));
        });
        if (addHistory) history.pushState(path, '');
        $('#loadingToast').hide();
    }).fail(function () {
        clearTimeout(loadingTimer);
        $('#loadingToast').hide();
        warnToast('載入失敗');
    });
}

function uploadFile() {
    $('#file_uploader').click();
}

function onFileSelected() {
    const files = $('#file_uploader')[0].files;
    if (files.length === 0) return;
    const tip = Array.from(files).map(f => f.name).join(', ');
    $('#uploadTipContent').text(tip);
    $('#uploadTip').show();
}

function confirmUpload(yes) {
    $('#uploadTip').hide();
    if (yes !== 1) return;
    const files = $('#file_uploader')[0].files;
    if (files.length === 0) return;
    const formData = new FormData();
    formData.append('path', currentRoot);
    Array.from(files).forEach((f, i) => formData.append('files-' + i, f));
    $('#loadingToast').show();
    $.ajax({
        url: '/upload',
        type: 'post',
        data: formData,
        processData: false,
        contentType: false,
        complete: function () {
            $('#loadingToast').hide();
            $('#file_uploader').val('');
            listFile(currentRoot);
        }
    });
}

function showNewFolderDialog() {
    $('#newFolder').show();
}

function confirmNewFolder(yes) {
    $('#newFolder').hide();
    const name = $('#newFolderContent').val().trim();
    $('#newFolderContent').val('');
    if (yes !== 1 || name.length === 0) return;
    $('#loadingToast').show();
    $.post('/newFolder', { path: currentRoot, name }, function () {
        $('#loadingToast').hide();
        listFile(currentRoot);
    }).fail(function () {
        $('#loadingToast').hide();
        warnToast('新增失敗');
    });
}

function showDelFolderDialog(path, refreshPath) {
    pendingDelFolder = { path, refreshPath };
    $('#delFolderContent').text('是否刪除 ' + path);
    $('#delFolder').show();
}

function confirmDelFolder(yes) {
    $('#delFolder').hide();
    if (yes !== 1 || !pendingDelFolder) { pendingDelFolder = null; return; }
    const { path, refreshPath } = pendingDelFolder;
    pendingDelFolder = null;
    $('#loadingToast').show();
    $.post('/delFolder', { path }, function () {
        $('#loadingToast').hide();
        listFile(refreshPath);
    }).fail(function () {
        $('#loadingToast').hide();
        warnToast('刪除失敗');
    });
}

function showDelFileDialog(path) {
    currentFile = path;
    $('#delFileContent').text('是否刪除 ' + path);
    $('#delFile').show();
}

function confirmDelFile(yes) {
    $('#delFile').hide();
    if (yes !== 1) return;
    $('#loadingToast').show();
    $.post('/delFile', { path: currentFile }, function () {
        $('#loadingToast').hide();
        listFile(currentRoot);
    }).fail(function () {
        $('#loadingToast').hide();
        warnToast('刪除失敗');
    });
}

function warnToast(msg) {
    $('#warnToastContent').text(msg);
    $('#warnToast').show();
    if (warnToastTimer) clearTimeout(warnToastTimer);
    const duration = msg.length > 80 || msg.includes('\n') ? 4500 : 2500;
    warnToastTimer = setTimeout(() => { $('#warnToast').hide(); warnToastTimer = null; }, duration);
}

function showPanel(id) {
    for (let i = 1; i <= 4; i++) {
        document.getElementById('panel' + i).classList.toggle('active', i === id);
        document.getElementById('tab' + i).classList.toggle('active', i === id);
    }
    if (id === 4 && document.getElementById('file_list').innerHTML === '') listFile('');
}

const tab = parseInt(new URLSearchParams(window.location.search).get('tab')) || 1;
history.replaceState(null, '');
showPanel(tab);

window.addEventListener('popstate', function () {
    listFile(currentParent);
});

// Source package inspection is separate from ordinary file uploads and never runs providers.
let sourcePackagePreview = null;
function inspectSourcePackage() {
    const file = document.getElementById('package_file').files[0];
    if (!file) return;
    const data = new FormData(); data.append('package', file);
    $('#package_status').text('正在檢查本地檔案…');
    $.ajax({url:'/packages/inspect', method:'POST', data, processData:false, contentType:false,
        success:function(result) { sourcePackagePreview = result; renderPackagePreview(); },
        error:function(xhr) { $('#package_status').text(xhr.responseText || '檢查失敗'); }
    });
}
function renderPackagePreview() {
    const root = $('#package_preview').empty();
    sourcePackagePreview.packages.forEach(function(pkg) {
        const box = $('<div>').addClass('panel-content');
        const name = $('<input>').addClass('md-input').val(pkg.name);
        box.append($('<h3>').text(pkg.name + ' · ' + pkg.sites.length + ' 個來源'));
        box.append($('<p>').text('名稱相同會更新現有包；選擇要啟用的來源。匯入不代表播放已驗證。'));
        box.append(name);
        box.append($('<pre>').css({'white-space':'pre-wrap'}).text(pkg.errors.concat(pkg.warnings).join('\n')));
        const selected = $('<select multiple>').css({width:'100%',height:'180px'});
        pkg.sites.forEach(function(site, index) { selected.append($('<option>').val(site.key).text(site.name).prop('selected',(pkg.enabledKeys || []).includes(site.key))); });
        box.append(selected);
        box.append($('<button>').addClass('md-btn md-btn-tonal').text('安裝所選來源').prop('disabled',pkg.errors.length > 0).on('click',function() {
            const button = $(this).prop('disabled',true);
            $.post('/packages/install',{token:sourcePackagePreview.token,index:pkg.index,name:name.val(),keys:JSON.stringify(selected.val() || [])})
                .done(function() { $('#package_status').text('已安裝。請在電視的來源倉庫選用，或複製下方配置網址。'); listSourcePackages(); })
                .fail(function(xhr) { $('#package_status').text(xhr.responseText || '安裝失敗'); })
                .always(function() { button.prop('disabled',false); });
        }));
        root.append(box);
    });
    $('#package_status').text('檢查完成；尚未執行任何來源。');
}
function listSourcePackages() {
    $.getJSON('/packages/list',function(items) {
        const root = $('#package_installed').empty();
        items.forEach(function(item) {
            const box = $('<div>');
            box.append($('<p>').text(item.name));
            box.append($('<input readonly>').addClass('md-input').val('package://' + item.id));
            box.append($('<button>').addClass('md-btn md-btn-tonal').text('回復上一版').prop('disabled',!item.previous).on('click',function() {
                $.post('/packages/rollback',{id:item.id}).done(function() { $('#package_status').text('已回復；請重新載入配置。'); listSourcePackages(); })
                    .fail(function(xhr) { $('#package_status').text(xhr.responseText || '回復失敗'); });
            }));
            root.append(box);
        });
    });
}

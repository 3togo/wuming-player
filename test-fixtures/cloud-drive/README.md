# Cloud streaming fixtures

Run `python3 scripts/prepare-cloud-stream-fixtures.py`. It creates four provider-specific MP4 files in this directory and opens the official cloud-drive pages in a dedicated Chromium profile.

For each official page:

1. Scan the QR code when the site requests login.
2. Upload the matching `wuming-<provider>-stream-test.mp4` file.
3. Select the uploaded file and choose **Share / 分享**.
4. Create the share and choose **Copy link / 复制链接**.
5. Return to the terminal and paste the complete copied message. The script extracts both the URL and any extraction code and saves them in `shares.json`.

The generated MP4 files are ignored by Git. If you skipped the terminal prompt, capture a link later with:

```bash
python3 scripts/prepare-cloud-stream-fixtures.py --capture-share quark
```

To pass a share message from a phone, start a temporary local handoff page:

```bash
python3 scripts/prepare-cloud-stream-fixtures.py --qr-share
```

Scan the displayed QR code while the phone and computer are on the same network. On the
phone, paste the complete text produced by **Copy link / 复制链接** and tap **Send to
computer**. The page recognizes the provider from its official share domain, saves the
URL and extraction code, and closes the receiver after one successful submission. The
random handoff address expires after ten minutes by default; change this with
`--qr-timeout SECONDS`.

You can also record copied text non-interactively, preserving quotes around the complete message:

```bash
python3 scripts/prepare-cloud-stream-fixtures.py --record-share quark '链接：https://example.com/public-share 提取码：A1b2'
```

Browser sessions and recorded share URLs are stored under `~/.local/share/wuming-cloud-fixtures/chromium-profile/` by default with user-only permissions. Raw credentials are not exported to the repository or a separate plaintext credential file.

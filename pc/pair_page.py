"""Write an HTML page with two QR codes: (1) download the Android app, (2) pair it with this PC.

    python pair_page.py <out.html> <apk_url> <pair_link>

Needs `segno` (pure Python). install.ps1 installs it into launchers\\pylib and puts that on PYTHONPATH.
The pairing link contains the API key: the page is written to the user's own folder and never served.
"""
import html, sys

import segno


def qr_svg(data: str) -> str:
    q = segno.make(data, error="m")
    return q.svg_inline(scale=6, border=2, dark="#000", light="#fff")


def main() -> None:
    out, apk_url, pair = sys.argv[1], sys.argv[2], sys.argv[3]
    page = f"""<!doctype html><html lang="zh"><meta charset="utf-8"><title>Hermes Android</title>
<style>body{{font-family:system-ui,'Microsoft YaHei',sans-serif;max-width:860px;margin:32px auto;padding:0 16px;color:#111}}
.row{{display:flex;gap:40px;flex-wrap:wrap}} .card{{flex:1;min-width:300px}} code{{background:#eee;padding:2px 4px;word-break:break-all}}
h2{{margin-bottom:4px}} .warn{{color:#a40}}</style>
<h1>Hermes Android · 手机配对</h1>
<div class="row">
 <div class="card"><h2>① 下载 App / Get the app</h2>
  <p>用手机相机扫码下载并安装（首次需允许"安装未知应用"）。<br>Scan with the phone camera, download and install.</p>
  {qr_svg(apk_url)}<p><code>{html.escape(apk_url)}</code></p></div>
 <div class="card"><h2>② 配对 / Pair</h2>
  <p>装好后用相机扫这个码，选择用 Hermes 打开，就会自动填好地址和密钥。<br>After installing, scan this and open it with Hermes.</p>
  {qr_svg(pair)}
  <p class="warn">此二维码包含 API 密钥，不要分享。<br>This code contains your API key. Don't share it.</p></div>
</div>
<p>手机需要和电脑在同一个 Tailscale 网络（或同一 Wi-Fi）。Phone and PC must be on the same Tailscale network (or Wi-Fi).</p>
</html>"""
    with open(out, "w", encoding="utf-8") as f:
        f.write(page)


if __name__ == "__main__":
    main()

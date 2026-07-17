"""쇼핑몰 상품 페이지 메타데이터(OpenGraph) 수집.

유저가 입력한 URL을 워커가 서버측에서 가져오므로 SSRF 방어가 필수다:
- http/https만 허용, 호스트가 해석되는 모든 IP가 공인망(public)이어야 함
- 리다이렉트마다 목적지 재검증, 응답 크기·시간 제한

수집 실패는 언제나 폴백 가능(촬영본으로 분석)하므로 예외를 그대로 올린다.
"""
import html as html_lib
import ipaddress
import re
import socket
import urllib.error
import urllib.parse
import urllib.request

FETCH_TIMEOUT_SECONDS = 5
MAX_HTML_BYTES = 512 * 1024
MAX_IMAGE_BYTES = 10 * 1024 * 1024
USER_AGENT = "Mozilla/5.0 (compatible; FashionRadar/1.0)"


def is_safe_url(url: str) -> bool:
    """http/https + 공인 IP만 허용. 사설망/루프백/링크로컬 등은 전부 차단."""
    try:
        parsed = urllib.parse.urlparse(url)
    except ValueError:
        return False
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        return False
    try:
        infos = socket.getaddrinfo(parsed.hostname, None)
    except (socket.gaierror, UnicodeError):
        return False
    for info in infos:
        try:
            ip = ipaddress.ip_address(info[4][0])
        except ValueError:
            return False
        if not ip.is_global:
            return False
    return True


class _SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    """리다이렉트로 내부망을 우회 접근하는 것 방지 — 매 홉 재검증."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not is_safe_url(newurl):
            raise urllib.error.URLError(f"허용되지 않는 리다이렉트: {newurl}")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


_opener = urllib.request.build_opener(_SafeRedirectHandler)


def _get(url: str, max_bytes: int) -> tuple:
    if not is_safe_url(url):
        raise ValueError(f"허용되지 않는 URL: {url}")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with _opener.open(req, timeout=FETCH_TIMEOUT_SECONDS) as res:
        return res.read(max_bytes), res.headers


def parse_og(page_html: str) -> dict:
    """og:title / og:image / og:site_name 추출. 속성 순서·따옴표 종류에 관대하게."""
    og = {}
    for tag in re.findall(r"<meta\b[^>]*>", page_html, re.IGNORECASE):
        prop = re.search(
            r"""(?:property|name)\s*=\s*["']og:([a-z_:]+)["']""", tag, re.IGNORECASE)
        content = re.search(r"""content\s*=\s*["']([^"']*)["']""", tag, re.IGNORECASE)
        if prop and content:
            key = prop.group(1).lower()
            if key in ("title", "image", "site_name") and key not in og:
                og[key] = html_lib.unescape(content.group(1)).strip()
    return {k: v for k, v in og.items() if v}


def fetch_og(url: str) -> dict:
    """상품 페이지에서 OG 메타데이터를 가져온다. og:image는 절대 URL로 변환."""
    body, _headers = _get(url, MAX_HTML_BYTES)
    og = parse_og(body.decode("utf-8", errors="replace"))
    if "image" in og:
        og["image"] = urllib.parse.urljoin(url, og["image"])
    return og


def download_image(url: str) -> bytes:
    """상품컷 다운로드 — 이미지 응답만 허용 (HTML 방어)."""
    body, headers = _get(url, MAX_IMAGE_BYTES)
    content_type = headers.get("Content-Type", "")
    if not content_type.lower().startswith("image/"):
        raise ValueError(f"이미지가 아닌 응답: {content_type}")
    return body

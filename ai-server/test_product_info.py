"""product_info 단위 테스트 — 네트워크 없이 실행: python test_product_info.py"""
import product_info


def test_unsafe_urls_are_rejected():
    # SSRF 방어: 사설망/루프백/링크로컬/비HTTP 스킴 전부 차단
    for url in (
        "http://127.0.0.1/admin",
        "http://localhost:8080/api",
        "http://10.0.0.5/secret",
        "http://192.168.0.1/",
        "http://169.254.169.254/latest/meta-data",  # 클라우드 메타데이터 엔드포인트
        "http://[::1]/",
        "ftp://example.com/file",
        "file:///etc/passwd",
        "javascript:alert(1)",
        "https://",
        "not a url",
    ):
        assert not product_info.is_safe_url(url), f"차단됐어야 함: {url}"


def test_og_parsing_handles_attribute_order_and_entities():
    html = """
    <html><head>
      <meta property="og:title" content="오버사이즈 울 자켓 &amp; 코트"/>
      <meta content="https://cdn.mall.com/item.jpg" property="og:image">
      <meta name="og:site_name" content='무신사'>
      <meta property="og:description" content="무시되는 필드">
    </head></html>
    """
    og = product_info.parse_og(html)
    assert og == {
        "title": "오버사이즈 울 자켓 & 코트",
        "image": "https://cdn.mall.com/item.jpg",
        "site_name": "무신사",
    }


def test_og_parsing_ignores_pages_without_og():
    assert product_info.parse_og("<html><body>no og here</body></html>") == {}
    assert product_info.parse_og('<meta property="og:title" content="">') == {}


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print(f"PASS {name}")
    print("모든 테스트 통과")

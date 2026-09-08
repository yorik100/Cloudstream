#!/usr/bin/env python3

import argparse
import io
import os
import re
import sys
import tempfile
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import quote, urljoin, urlparse

from curl_cffi import requests
from PIL import Image, UnidentifiedImageError


MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
MAX_IMAGE_PIXELS = 16_000_000
Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS


class IconParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.icons = []

    def handle_starttag(self, tag, attrs):
        if tag.lower() != "link":
            return

        attributes = {key.lower(): value or "" for key, value in attrs}
        rel = set(attributes.get("rel", "").lower().split())
        href = attributes.get("href", "").strip()
        if "icon" not in rel or not href:
            return

        dimensions = [
            int(width) * int(height)
            for width, height in re.findall(
                r"(\d+)x(\d+)",
                attributes.get("sizes", ""),
                flags=re.IGNORECASE,
            )
        ]
        self.icons.append((max(dimensions, default=0), href))


def https_url(value):
    parsed = urlparse(value)
    return parsed.scheme == "https" and bool(parsed.hostname)


def candidate_urls(page_source, page_url, origin):
    parser = IconParser()
    parser.feed(page_source)

    candidates = [
        urljoin(page_url, href)
        for _, href in sorted(parser.icons, key=lambda item: item[0], reverse=True)
    ]
    candidates.append(urljoin(origin, "/favicon.ico"))
    candidates.append(
        "https://www.google.com/s2/favicons?domain_url="
        f"{quote(origin, safe='')}&sz=256"
    )

    unique = []
    seen = set()
    for candidate in candidates:
        if not https_url(candidate) or candidate in seen:
            continue
        seen.add(candidate)
        unique.append(candidate)
    return unique


def fetch_icon(url, page_url, tor_port):
    attempts = []
    if urlparse(url).hostname == "www.google.com":
        attempts.append(None)
    attempts.extend(
        f"socks5h://keepicon-{circuit}:isolated@127.0.0.1:{tor_port}"
        for circuit in range(1, 7)
    )

    for proxy in attempts:
        proxies = None if proxy is None else {"http": proxy, "https": proxy}
        try:
            response = requests.get(
                url,
                impersonate="chrome",
                headers={
                    "Accept": "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                    "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.7",
                    "Referer": page_url,
                },
                proxies=proxies,
                allow_redirects=True,
                timeout=45,
            )
        except requests.RequestsError:
            continue

        redirect_urls = [item.url for item in [*response.history, response]]
        if response.status_code != 200:
            continue
        if any(not https_url(item) for item in redirect_urls):
            continue
        if not response.content or len(response.content) > MAX_DOWNLOAD_BYTES:
            continue
        return response.content

    return None


def convert_to_png(content):
    try:
        with Image.open(io.BytesIO(content)) as source:
            source.seek(0)
            image = source.convert("RGBA")
    except (UnidentifiedImageError, OSError, ValueError):
        return None

    if image.width < 1 or image.height < 1:
        return None

    image.thumbnail((512, 512), Image.Resampling.LANCZOS)
    side = max(image.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.alpha_composite(
        image,
        ((side - image.width) // 2, (side - image.height) // 2),
    )

    output = io.BytesIO()
    canvas.save(output, format="PNG", optimize=True)
    return output.getvalue()


def write_if_changed(output_path, content):
    output_path.parent.mkdir(parents=True, exist_ok=True)
    if output_path.exists() and output_path.read_bytes() == content:
        return False

    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output_path.name}.",
        dir=output_path.parent,
    )
    try:
        with os.fdopen(descriptor, "wb") as temporary:
            temporary.write(content)
        os.replace(temporary_name, output_path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--page-file", required=True, type=Path)
    parser.add_argument("--page-url", required=True)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--tor-port", required=True, type=int)
    args = parser.parse_args()

    if not https_url(args.page_url) or not https_url(args.origin):
        parser.error("page-url and origin must use HTTPS")

    page_source = args.page_file.read_text(encoding="utf-8", errors="ignore")
    for candidate in candidate_urls(page_source, args.page_url, args.origin):
        content = fetch_icon(candidate, args.page_url, args.tor_port)
        if content is None:
            continue
        png = convert_to_png(content)
        if png is None:
            continue

        changed = write_if_changed(args.output, png)
        state = "updated" if changed else "unchanged"
        print(f"Icon {state}: {args.output} <- {candidate}")
        return 0

    print(f"No usable icon found for {args.origin}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

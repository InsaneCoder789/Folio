"""Serve the permanent design website locally, without third-party packages."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import threading
from urllib.error import URLError
from urllib.request import urlopen
import webbrowser


def main():
    parser = argparse.ArgumentParser(description="Open the OCR Scanner design website")
    parser.add_argument("--port", type=int, default=8770)
    parser.add_argument("--open", action="store_true", help="Open the website in your browser")
    parser.add_argument("--check", action="store_true", help="Check an already running preview")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / "design"
    url = f"http://127.0.0.1:{args.port}/"
    try:
        with urlopen(url, timeout=2) as response:
            content = response.read(8192).decode("utf-8", errors="replace")
        if not any(name in content for name in ("OCR Scanner", "Folio")) or "Design workspace" not in content:
            raise SystemExit(f"Port {args.port} is serving something else. Choose --port with a free port.")
        print(f"OCR Scanner design website is already available: {url}")
        if args.open:
            webbrowser.open(url)
        return
    except (URLError, TimeoutError, ConnectionError):
        if args.check:
            raise SystemExit("The preview is not running. Launch without --check to start it.")

    handler = partial(SimpleHTTPRequestHandler, directory=str(root))
    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    except OSError as error:
        raise SystemExit(f"Cannot start preview on port {args.port}: {error}") from error
    print(f"OCR Scanner design website: {url}", flush=True)
    print("Keep this terminal open. Ctrl+C stops only the server; all website files stay in the project.", flush=True)
    if args.open:
        threading.Timer(0.3, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

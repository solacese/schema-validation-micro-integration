#!/usr/bin/env python3
"""Register one JSON Schema, Avro, XML Schema, or Protobuf artifact using API v3."""
import argparse
import base64
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path, help=".json, .avsc, .xsd, or .proto schema")
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--group", default="default")
    parser.add_argument("--version", default="1.0.0")
    parser.add_argument("--type", choices=["JSON", "AVRO", "XML", "PROTOBUF"])
    parser.add_argument("--references", type=Path, help="JSON array of v3 registry reference objects")
    args = parser.parse_args()
    kind = args.type or {".json": "JSON", ".avsc": "AVRO", ".xsd": "XML", ".proto": "PROTOBUF"}.get(args.file.suffix.lower())
    if kind is None:
        parser.error("Cannot infer schema format; specify --type")
    base = os.environ.get("SCHEMA_REGISTRY_BASE_URL", "http://localhost:18081/apis/registry/v3").rstrip("/")
    username = os.environ.get("SCHEMA_REGISTRY_USERNAME", "sr-developer")
    password = os.environ.get("SCHEMA_REGISTRY_PASSWORD", "devPassword")
    auth = base64.b64encode(f"{username}:{password}".encode()).decode()

    def request(method, path, data=None):
        body = json.dumps(data).encode() if data is not None else None
        req = Request(base + path, body, {"Authorization": "Basic " + auth, "Content-Type": "application/json"}, method=method)
        with urlopen(req, timeout=15) as response:
            return response.read()

    content = {"content": args.file.read_text(), "contentType": "application/xml" if kind == "XML" else "application/json"}
    if args.references:
        content["references"] = json.loads(args.references.read_text())
        if not isinstance(content["references"], list):
            parser.error("References file must contain a JSON array")
    path = f"/groups/{quote(args.group, safe='')}/artifacts/{quote(args.artifact, safe='')}"
    try:
        request("GET", path)
    except HTTPError as error:
        if error.code != 404:
            raise
        request("POST", path.rsplit("/", 1)[0], {"artifactId": args.artifact, "artifactType": kind,
            "firstVersion": {"version": args.version, "content": content}})
    else:
        request("POST", path + "/versions", {"version": args.version, "content": content})
    print(f"Registered {args.group}/{args.artifact}:{args.version} ({kind})")


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        sys.exit(f"Registry HTTP {error.code}: {error.read().decode(errors='replace')}")
    except (URLError, OSError, ValueError) as error:
        sys.exit(f"Registration failed: {error}")

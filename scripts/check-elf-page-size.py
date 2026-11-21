#!/usr/bin/env python3
"""Validate 16 KB page-size compatibility of native ELF libraries in an APK."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path

PAGE_SIZE = 16 * 1024
PT_LOAD = 1


class ElfError(ValueError):
    pass


def inspect_elf(name: str, data: bytes) -> list[int]:
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise ElfError(f"{name}: not a valid ELF file")

    elf_class = data[4]
    encoding = data[5]
    if elf_class not in (1, 2) or encoding not in (1, 2):
        raise ElfError(f"{name}: unsupported ELF class or byte order")

    endian = "<" if encoding == 1 else ">"
    if elf_class == 2:
        header_fmt = endian + "Q"
        phoff = struct.unpack_from(header_fmt, data, 32)[0]
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 54)
        align_offset, align_fmt = 48, endian + "Q"
        minimum_phentsize = 56
    else:
        phoff = struct.unpack_from(endian + "I", data, 28)[0]
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 42)
        align_offset, align_fmt = 28, endian + "I"
        minimum_phentsize = 32

    if phentsize < minimum_phentsize or phnum == 0:
        raise ElfError(f"{name}: invalid or missing program-header table")
    if phoff + phentsize * phnum > len(data):
        raise ElfError(f"{name}: program-header table exceeds file size")

    alignments: list[int] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type = struct.unpack_from(endian + "I", data, offset)[0]
        if p_type == PT_LOAD:
            alignment = struct.unpack_from(align_fmt, data, offset + align_offset)[0]
            alignments.append(alignment)

    if not alignments:
        raise ElfError(f"{name}: contains no PT_LOAD segments")
    return alignments


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path, help="APK to inspect")
    args = parser.parse_args()

    if not args.apk.is_file():
        parser.error(f"APK does not exist: {args.apk}")

    failures = 0
    inspected = 0
    try:
        with zipfile.ZipFile(args.apk) as apk:
            libraries = sorted(
                item for item in apk.namelist()
                if item.startswith("lib/") and item.endswith(".so")
            )
            if not libraries:
                print(f"ERROR: no native .so libraries found in {args.apk}", file=sys.stderr)
                return 1

            for name in libraries:
                inspected += 1
                try:
                    alignments = inspect_elf(name, apk.read(name))
                    rendered = ", ".join(f"0x{value:x}" for value in alignments)
                    if any(value < PAGE_SIZE for value in alignments):
                        failures += 1
                        print(f"FAIL {name}: PT_LOAD p_align=[{rendered}] (requires >= 0x4000)")
                    else:
                        print(f"PASS {name}: PT_LOAD p_align=[{rendered}]")
                except (ElfError, struct.error, KeyError) as error:
                    failures += 1
                    print(f"FAIL {name}: {error}")
    except (OSError, zipfile.BadZipFile) as error:
        print(f"ERROR: cannot inspect {args.apk}: {error}", file=sys.stderr)
        return 1

    print(f"Inspected {inspected} native libraries; {failures} failure(s).")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

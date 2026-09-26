#!/usr/bin/env python3
"""Prepare libmpv for this Java 17 app and namespace its private ELF dependencies."""

from __future__ import annotations

import shutil
import struct
import sys
import tempfile
import zipfile
from pathlib import Path


RENAMES = {
    "libavcodec.so": "libsaabmpv_avcodec.so",
    "libavdevice.so": "libsaabmpv_avdevice.so",
    "libavfilter.so": "libsaabmpv_avfilter.so",
    "libavformat.so": "libsaabmpv_avformat.so",
    "libavutil.so": "libsaabmpv_avutil.so",
    "libswresample.so": "libsaabmpv_swresample.so",
    "libswscale.so": "libsaabmpv_swscale.so",
    "libc++_shared.so": "libsaabmpv_cxx.so",
}


def patch_elf(source: Path, destination: Path) -> None:
    import lief

    binary = lief.parse(str(source))
    if binary is None:
        raise RuntimeError(f"Unable to parse ELF library: {source}")

    for old_name, new_name in RENAMES.items():
        if binary.has_library(old_name):
            binary.get_library(old_name).name = new_name

    original_name = source.name
    if original_name in RENAMES:
        for entry in binary.dynamic_entries:
            if entry.tag == lief.ELF.DynamicEntry.TAG.SONAME:
                entry.name = RENAMES[original_name]

    destination.parent.mkdir(parents=True, exist_ok=True)
    binary.write(str(destination))
    repair_version_requirements(destination)


def repair_version_requirements(library: Path) -> int:
    """Point ELF VERNEED filenames at the renamed DT_NEEDED strings.

    LIEF updates DT_NEEDED and SONAME, but currently leaves vn_file entries in
    .gnu.version_r pointing at the old FFmpeg filenames. Android's dynamic
    linker rejects that mismatch before JNI can initialize.
    """
    data = bytearray(library.read_bytes())
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise RuntimeError(f"Unsupported ELF encoding: {library}")

    elf_class = data[4]
    if elf_class == 1:
        section_offset = struct.unpack_from("<I", data, 0x20)[0]
        section_entry_size = struct.unpack_from("<H", data, 0x2E)[0]
        section_count = struct.unpack_from("<H", data, 0x30)[0]
        offset_field = 16
        size_field = 20
        link_field = 24
        address_format = "<I"
    elif elf_class == 2:
        section_offset = struct.unpack_from("<Q", data, 0x28)[0]
        section_entry_size = struct.unpack_from("<H", data, 0x3A)[0]
        section_count = struct.unpack_from("<H", data, 0x3C)[0]
        offset_field = 24
        size_field = 32
        link_field = 40
        address_format = "<Q"
    else:
        raise RuntimeError(f"Unsupported ELF class: {library}")

    version_section = None
    for index in range(section_count):
        header = section_offset + index * section_entry_size
        section_type = struct.unpack_from("<I", data, header + 4)[0]
        if section_type == 0x6FFFFFFE:  # SHT_GNU_verneed
            version_section = header
            break
    if version_section is None:
        return 0

    version_offset = struct.unpack_from(address_format, data, version_section + offset_field)[0]
    version_size = struct.unpack_from(address_format, data, version_section + size_field)[0]
    string_section_index = struct.unpack_from("<I", data, version_section + link_field)[0]
    string_header = section_offset + string_section_index * section_entry_size
    string_offset = struct.unpack_from(address_format, data, string_header + offset_field)[0]
    string_size = struct.unpack_from(address_format, data, string_header + size_field)[0]
    string_table = bytes(data[string_offset:string_offset + string_size])

    replacements = 0
    cursor = version_offset
    limit = version_offset + version_size
    while cursor + 16 <= limit:
        _, _, filename_index, _, next_offset = struct.unpack_from("<HHIII", data, cursor)
        end = string_table.find(b"\0", filename_index)
        if end < 0:
            raise RuntimeError(f"Invalid VERNEED filename in {library}")
        old_name = string_table[filename_index:end].decode("utf-8")
        new_name = RENAMES.get(old_name)
        if new_name is not None:
            new_index = string_table.find(new_name.encode("utf-8") + b"\0")
            if new_index < 0:
                raise RuntimeError(f"Missing renamed dynamic string {new_name} in {library}")
            struct.pack_into("<I", data, cursor + 4, new_index)
            replacements += 1
        if next_offset == 0:
            break
        cursor += next_offset

    if replacements:
        library.write_bytes(data)
    return replacements


def namespace_aar(input_aar: Path, output_aar: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="saab-libmpv-") as temp_name:
        temp = Path(temp_name)
        with zipfile.ZipFile(input_aar) as archive:
            archive.extractall(temp)

        jni_root = temp / "jni"
        for abi_directory in sorted(path for path in jni_root.iterdir() if path.is_dir()):
            originals = list(abi_directory.glob("*.so"))
            patched = abi_directory / ".patched"
            for library in originals:
                output_name = RENAMES.get(library.name, library.name)
                patch_elf(library, patched / output_name)
            for library in originals:
                library.unlink()
            for library in patched.iterdir():
                shutil.move(str(library), abi_directory / library.name)
            patched.rmdir()

        # v1.0.0's published wrapper was compiled as Java 21 bytecode. The app
        # ships the same MIT-licensed wrapper from source so it can target Java
        # 17; retain a valid but empty classes.jar in the native-only AAR.
        classes_jar = temp / "classes.jar"
        with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")

        output_aar.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output_aar, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(temp.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(temp).as_posix())


def repair_namespaced_aar(input_aar: Path, output_aar: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="saab-libmpv-repair-") as temp_name:
        temp = Path(temp_name)
        with zipfile.ZipFile(input_aar) as archive:
            archive.extractall(temp)
        repaired = 0
        for library in sorted((temp / "jni").glob("*/*.so")):
            repaired += repair_version_requirements(library)
        if repaired == 0:
            raise RuntimeError("No mismatched ELF version requirements were found")
        output_aar.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output_aar, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(temp.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(temp).as_posix())
        print(f"Repaired {repaired} ELF version-requirement references")


def main() -> None:
    if len(sys.argv) == 4 and sys.argv[1] == "--repair-existing":
        repair_namespaced_aar(Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve())
        return
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: namespace_libmpv_aar.py INPUT_AAR OUTPUT_AAR\n"
            "   or: namespace_libmpv_aar.py --repair-existing INPUT_AAR OUTPUT_AAR"
        )
    namespace_aar(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())


if __name__ == "__main__":
    main()

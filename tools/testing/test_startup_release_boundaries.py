#!/usr/bin/env python3
"""Synthetic parser/checker fixtures, never APK/R8 execution or device evidence."""

import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
import zlib


SPEC = importlib.util.spec_from_file_location(
    "startup_release_boundaries", Path(__file__).with_name("verify-startup-release-boundaries.py"),
)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)
ORIGINAL = "com.lezi.babylog.core.common.validation.StartupBoundaryObservation"
SENTINEL = "R8$$REMOVED$$CLASS$$7"
DESCRIPTOR = "L" + SENTINEL + ";"


def uleb(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return result


def utf16_units(value):
    encoded = value.encode("utf-16-le", errors="surrogatepass")
    return tuple(struct.unpack("<" + "H" * (len(encoded) // 2), encoded))


def seal(data):
    data = bytearray(data)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)
    return bytes(data)


def dex(extra_strings=(), extra_types=(), classes=("Lfixture/Root;",), field_owner=None, method_owner=None):
    """Small identity-table-complete DEX, including external member-reference fixtures."""
    types = set(classes) | set(extra_types) | {"Ljava/lang/Object;"}
    if field_owner:
        types |= {field_owner, "I"}
    if method_owner:
        types |= {method_owner, "V"}
    strings = sorted(types | set(extra_strings) | ({"field"} if field_owner else set()) |
                     ({"method"} if method_owner else set()), key=utf16_units)
    string_index = {value: i for i, value in enumerate(strings)}
    types = sorted(types, key=string_index.get)
    type_index = {value: i for i, value in enumerate(types)}
    counts = [len(strings), len(types), int(bool(method_owner)), int(bool(field_owner)),
              int(bool(method_owner)), len(classes)]
    widths = [4, 4, 12, 8, 8, 32]
    offsets = []
    position = 112
    for count, width in zip(counts, widths):
        offsets.append(position if count else 0)
        position += count * width
    data_offset = position
    data = bytearray(position)
    string_offsets = []
    for value in strings:
        string_offsets.append(len(data))
        units = utf16_units(value)
        data += uleb(len(units))
        for unit in units:
            if 0 < unit < 128:
                data.append(unit)
            elif unit < 0x800:
                data += bytes((0xC0 | unit >> 6, 0x80 | unit & 63))
            else:
                data += bytes((0xE0 | unit >> 12, 0x80 | unit >> 6 & 63, 0x80 | unit & 63))
        data.append(0)
    for i, offset in enumerate(string_offsets):
        struct.pack_into("<I", data, offsets[0] + i * 4, offset)
    for i, value in enumerate(types):
        struct.pack_into("<I", data, offsets[1] + i * 4, string_index[value])
    if method_owner:
        struct.pack_into("<III", data, offsets[2], string_index["V"], type_index["V"], 0)
        struct.pack_into("<HHI", data, offsets[4], type_index[method_owner], 0, string_index["method"])
    if field_owner:
        struct.pack_into("<HHI", data, offsets[3], type_index[field_owner], type_index["I"], string_index["field"])
    for i, value in enumerate(classes):
        parent = 0xFFFFFFFF if value == "Ljava/lang/Object;" else type_index["Ljava/lang/Object;"]
        struct.pack_into("<8I", data, offsets[5] + i * 32, type_index[value], 0x401,
                         parent, 0, 0xFFFFFFFF, 0, 0, 0)
    while len(data) % 4:
        data.append(0)
    map_offset = len(data)
    maps = [(0, 1, 0)] + [(i + 1, n, o) for i, (n, o) in enumerate(zip(counts, offsets)) if n]
    maps += [(0x2002, len(strings), data_offset), (0x1000, 1, map_offset)]
    maps.sort(key=lambda entry: entry[2])
    data += struct.pack("<I", len(maps))
    for kind, count, offset in maps:
        data += struct.pack("<HHII", kind, 0, count, offset)
    data[:8] = b"dex\n038\0"
    struct.pack_into("<6I", data, 32, len(data), 112, 0x12345678, 0, 0, map_offset)
    for index, (count, offset) in enumerate(zip(counts, offsets)):
        struct.pack_into("<II", data, 56 + index * 8, count, offset)
    struct.pack_into("<II", data, 104, len(data) - data_offset, data_offset)
    return seal(data)


class StartupReleaseBoundaryCheckerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.apk = self.root / "synthetic-parser-fixture.zip"
        self.mapping = self.root / "mapping.txt"
        self.mapping.write_text("com.lezi.babylog.LeziApp -> a:\n")
        self.usage = self.root / "usage.txt"
        self.usage.write_text("")
        self.manifest = self.root / "AndroidManifest.xml"
        self.manifest.write_text("<manifest />")

    def tearDown(self):
        self.temporary.cleanup()

    def package(self, payload=None, manifest=b"<manifest />"):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("classes.dex", dex() if payload is None else payload)
            archive.writestr("AndroidManifest.xml", manifest)

    def direct(self, payload=None, usage=True):
        (self.root / "classes.dex").write_bytes(dex() if payload is None else payload)
        return CHECKER.verify_r8(self.root, self.manifest, self.mapping, self.usage if usage else None)

    def removed_mapping(self):
        self.mapping.write_text(f'{ORIGINAL} -> {SENTINEL}:\n# {{"id":"sourceFile","fileName":"StartupBoundaryObservation.kt"}}\n')
        self.usage.write_text(ORIGINAL + "\n")

    def test_absent_markers_pass_parser_fixture_only(self):
        self.package()
        result = CHECKER.verify(self.apk, self.mapping, self.usage)
        self.assertEqual("passed", result["status"])
        self.assertIn("no device execution", result["scope"])
        self.assertEqual(1, result["dex_identity_counts"]["classes.dex"]["classes"])

    def test_every_class_and_marker_in_dex_fails(self):
        for marker in CHECKER.CLASSES + CHECKER.MARKERS:
            with self.subTest(marker=marker):
                self.package(dex(extra_strings=[marker]))
                self.assertEqual("failed", CHECKER.verify(self.apk, self.mapping, self.usage)["status"])

    def test_utf16_manifest_diagnostics_fail(self):
        self.package(manifest="ProductionStartupTestRunner".encode("utf-16le"))
        self.assertEqual("failed", CHECKER.verify(self.apk, self.mapping, self.usage)["status"])

    def test_ordinary_obfuscated_mapping_still_fails_even_without_definition(self):
        for name in CHECKER.CLASSES:
            with self.subTest(name=name):
                self.mapping.write_text(f"com.lezi.babylog.validation.{name} -> a:\n")
                self.assertEqual("failed", self.direct()["status"])

    def test_empty_mapping_is_not_r8_evidence(self):
        self.mapping.write_text("")
        with self.assertRaises(ValueError):
            self.direct()

    def test_missing_dex_is_rejected(self):
        with self.assertRaises(ValueError):
            CHECKER.verify_r8(self.root, self.manifest, self.mapping, self.usage)

    def test_direct_r8_requires_no_apk(self):
        result = self.direct()
        self.assertEqual("passed", result["status"])
        self.assertNotIn("apk", result)
        self.assertFalse(self.apk.exists())

    def test_secondary_dex_is_checked(self):
        (self.root / "classes2.dex").write_bytes(dex(extra_strings=["sync:activate"]))
        self.assertEqual("failed", self.direct()["status"])

    def test_duplicate_zip_entries_cannot_hide_dex_or_manifest(self):
        for duplicate in ["classes.dex", "AndroidManifest.xml"]:
            with self.subTest(duplicate=duplicate):
                self.package()
                with zipfile.ZipFile(self.apk, "a") as archive:
                    with self.assertWarns(UserWarning):
                        archive.writestr(duplicate, dex() if duplicate.endswith(".dex") else b"<manifest />")
                with self.assertRaises(ValueError):
                    CHECKER.verify(self.apk, self.mapping, self.usage)

    def test_unknown_direct_dex_filename_cannot_be_silently_omitted(self):
        (self.root / "unexpected.dex").write_bytes(dex(extra_types=[DESCRIPTOR]))
        with self.assertRaises(ValueError):
            self.direct()

    def test_exact_removed_sentinel_requires_usage_and_all_identity_tables_absent(self):
        self.removed_mapping()
        result = self.direct()
        self.assertEqual("passed", result["status"])
        self.assertEqual(1, len(result["corroborated_removed_mapping_entries"]))

    def test_missing_usage_fails_for_removed_sentinel(self):
        self.removed_mapping()
        self.assertEqual("failed", self.direct(usage=False)["status"])
        self.usage.unlink()
        with self.assertRaises(FileNotFoundError):
            self.direct()

    def test_member_only_usage_removal_is_not_whole_class_removal(self):
        self.removed_mapping()
        self.usage.write_text(ORIGINAL + ":\n    void record(java.lang.String)\n")
        self.assertEqual("failed", self.direct()["status"])

    def test_sentinel_with_actual_class_definition_fails(self):
        self.removed_mapping()
        self.assertEqual("failed", self.direct(dex(classes=[DESCRIPTOR]))["status"])

    def test_sentinel_with_type_reference_but_no_class_definition_fails(self):
        self.removed_mapping()
        self.assertEqual("failed", self.direct(dex(extra_types=[DESCRIPTOR]))["status"])

    def test_sentinel_with_member_owners_fails(self):
        self.removed_mapping()
        for kwargs in [dict(field_owner=DESCRIPTOR), dict(method_owner=DESCRIPTOR),
                       dict(method_owner="[" + DESCRIPTOR)]:
            with self.subTest(kwargs=kwargs):
                payload = dex(**kwargs)
                identities = CHECKER.read_dex_identities(payload)
                self.assertTrue(identities["field_owners"] or identities["method_owners"])
                self.assertEqual("failed", self.direct(payload)["status"])

    def test_secondary_dex_cannot_hide_sentinel_type(self):
        self.removed_mapping()
        (self.root / "classes2.dex").write_bytes(dex(extra_types=[DESCRIPTOR]))
        self.assertEqual("failed", self.direct()["status"])

    def test_unknown_removed_spelling_is_rejected(self):
        self.removed_mapping()
        self.mapping.write_text(f"{ORIGINAL} -> R8$$REMOVED$$CLASS$$unknown:\n")
        self.assertEqual("failed", self.direct()["status"])

    def test_removed_sentinel_with_member_mapping_is_rejected(self):
        self.removed_mapping()
        with self.mapping.open("a") as f:
            f.write("    void record(java.lang.String) -> a\n")
        self.assertEqual("failed", self.direct()["status"])

    def test_unrecognized_mapping_lines_fail_closed(self):
        for text in ["not a mapping", f"{ORIGINAL}->a:\n", f"\t{ORIGINAL} -> a:\n",
                     "# only comments are not a class map\n", "    void orphan() -> a\n"]:
            with self.subTest(text=text):
                self.mapping.write_text(text)
                with self.assertRaises(ValueError):
                    self.direct()

    def test_blank_or_tab_cannot_hide_removed_sentinel_member(self):
        self.removed_mapping()
        with self.mapping.open("a") as f:
            f.write("\n    void record(java.lang.String) -> a\n")
        self.assertEqual("failed", self.direct()["status"])
        self.removed_mapping()
        with self.mapping.open("a") as f:
            f.write("\tvoid record(java.lang.String) -> a\n")
        with self.assertRaises(ValueError):
            self.direct()

    def test_unknown_truncated_and_hash_corrupted_dex_fail_closed(self):
        original = dex()
        unknown = bytearray(original)
        unknown[4:7] = b"041"
        for payload in [b"not-dex", original[:100], original[:-1],
                        original[:-1] + bytes([original[-1] ^ 1]), seal(unknown)]:
            with self.subTest(size=len(payload)):
                with self.assertRaises(ValueError):
                    self.direct(payload)

    def test_malformed_identity_table_indices_fail_closed_after_rehashing(self):
        for header, relative, value in [(56, 0, 0xFFFFFFFF), (64, 0, 0xFFFFFFFF),
                                        (80, 0, 0xFFFF), (88, 0, 0xFFFF),
                                        (88, 2, 0xFFFF), (96, 0, 0xFFFFFFFF)]:
            with self.subTest(header=header, relative=relative):
                payload = bytearray(dex(field_owner="Lfixture/Root;", method_owner="Lfixture/Root;"))
                offset = struct.unpack_from("<I", payload, header + 4)[0] + relative
                struct.pack_into("<H" if header in (80, 88) else "<I", payload, offset, value)
                with self.assertRaises(ValueError):
                    self.direct(seal(payload))

    def test_non_ascii_mutf8_and_array_method_owners_are_parsed(self):
        result = self.direct(dex(extra_strings=["合成\0照片🙂"], method_owner="[B"))
        self.assertEqual("passed", result["status"])
        self.assertEqual(1, next(iter(result["dex_identity_counts"].values()))["method_owners"])

    def test_malformed_prototype_shorty_fails_after_rehashing(self):
        payload = bytearray(dex(method_owner="Lfixture/Root;"))
        proto_offset = struct.unpack_from("<I", payload, 76)[0]
        # The first sorted string is a class descriptor, never a valid shorty.
        struct.pack_into("<I", payload, proto_offset, 0)
        with self.assertRaises(ValueError):
            self.direct(seal(payload))


if __name__ == "__main__":
    unittest.main()

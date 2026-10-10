"""Fail-closed identity-table reader for standard little-endian DEX (035–040).

Not a bytecode execution/verifier implementation. The exclusion gate needs the
complete type/class/member-owner census, including references without definitions.
Unsupported containers, bad hashes, malformed encodings, indices and table layouts
are rejected rather than treated as an empty census.
Format authority: https://source.android.com/docs/core/runtime/dex-format
"""

import hashlib
import re
import struct
import zlib


def read_dex_identities(data: bytes) -> dict[str, set[str]]:
    def require(condition, message):
        if not condition:
            raise ValueError(f"Invalid/unsupported DEX: {message}")

    def span(offset, size):
        require(0 <= offset <= len(data) and 0 <= size <= len(data) - offset, "out-of-bounds range")

    def u32(offset):
        span(offset, 4)
        return struct.unpack_from("<I", data, offset)[0]

    require(len(data) >= 112, "truncated header")
    require(data[:4] == b"dex\n" and data[4:7] in {b"035", b"037", b"038", b"039", b"040"}
            and data[7] == 0, "unknown version/container")
    require(u32(32) == len(data) and u32(36) == 112, "file/header size")
    require(u32(40) == 0x12345678, "unsupported endian tag")
    require(u32(44) == u32(48) == 0, "unsupported linked DEX")
    require(u32(8) == zlib.adler32(data[12:]) & 0xFFFFFFFF, "checksum mismatch")
    require(data[12:32] == hashlib.sha1(data[32:]).digest(), "signature mismatch")
    data_size, data_offset = u32(104), u32(108)
    require(data_offset >= 112 and data_offset % 4 == 0
            and data_offset + data_size == len(data), "data section")

    tables = []
    for header, width in [(56, 4), (64, 4), (72, 12), (80, 8), (88, 8), (96, 32)]:
        count, offset = u32(header), u32(header + 4)
        if count:
            require(offset >= 112 and offset % 4 == 0, "table alignment")
            span(offset, count * width)
            require(offset + count * width <= data_offset, "table overlaps data")
        else:
            require(offset == 0, "nonzero offset for empty table")
        tables.append((count, offset, width))
    ranges = sorted((off, off + count * width) for count, off, width in tables if count)
    require(all(left[1] <= right[0] for left, right in zip(ranges, ranges[1:])), "overlapping tables")

    map_offset = u32(52)
    require(map_offset >= data_offset and map_offset % 4 == 0, "map offset")
    map_count = u32(map_offset)
    require(map_count > 0, "empty map")
    span(map_offset + 4, map_count * 12)
    known_map_types = set(range(9)) | {0x1000, 0x1001, 0x1002, 0x1003,
                                         0x2000, 0x2001, 0x2002, 0x2003,
                                         0x2004, 0x2005, 0x2006, 0xF000}
    maps = {}
    previous = -1
    for index in range(map_count):
        kind, unused, count, offset = struct.unpack_from("<HHII", data, map_offset + 4 + 12 * index)
        require(kind in known_map_types and kind not in maps and unused == 0 and count > 0,
                "unknown/duplicate/malformed map entry")
        require(previous < offset < len(data), "unordered/out-of-bounds map entry")
        previous = offset
        maps[kind] = (count, offset)
    require(maps.get(0) == (1, 0) and maps.get(0x1000) == (1, map_offset), "missing header/map entry")
    for kind, (count, offset, _) in enumerate(tables, 1):
        require(maps.get(kind) == (count, offset) if count else kind not in maps,
                "map/header table disagreement")

    def uleb(offset):
        require(data_offset <= offset < len(data), "uleb offset")
        result = 0
        for index in range(5):
            span(offset, 1)
            byte = data[offset]
            offset += 1
            require(index < 4 or byte < 16, "uleb overflow")
            result |= (byte & 127) << (7 * index)
            if not byte & 128:
                return result, offset
        raise ValueError("Invalid/unsupported DEX: unterminated uleb")

    strings, string_keys = [], []
    string_count, string_offset, _ = tables[0]
    string_data_offsets = []
    for index in range(string_count):
        offset = u32(string_offset + 4 * index)
        string_data_offsets.append(offset)
        expected_units, offset = uleb(offset)
        units = []
        while True:
            span(offset, 1)
            first = data[offset]
            offset += 1
            if first == 0:
                break
            if first < 128:
                value = first
            elif 0xC0 <= first < 0xE0:
                span(offset, 1)
                second = data[offset]
                offset += 1
                require(second & 0xC0 == 0x80, "invalid MUTF-8 continuation")
                value = ((first & 31) << 6) | (second & 63)
                require(value >= 128 or value == 0, "overlong MUTF-8")
            elif 0xE0 <= first < 0xF0:
                span(offset, 2)
                second, third = data[offset:offset + 2]
                offset += 2
                require(second & 0xC0 == third & 0xC0 == 0x80, "invalid MUTF-8 continuation")
                value = ((first & 15) << 12) | ((second & 63) << 6) | (third & 63)
                require(value >= 0x800, "overlong MUTF-8")
            else:
                raise ValueError("Invalid/unsupported DEX: invalid MUTF-8 lead")
            units.append(value)
            require(len(units) <= expected_units, "MUTF-8 length mismatch")
        require(len(units) == expected_units, "MUTF-8 length mismatch")
        key = tuple(units)
        require(not string_keys or string_keys[-1] < key, "unsorted/duplicate strings")
        string_keys.append(key)
        strings.append(b"".join(struct.pack("<H", unit) for unit in units)
                       .decode("utf-16-le", errors="surrogatepass"))
    require(len(set(string_data_offsets)) == string_count, "duplicate string data")
    require(maps.get(0x2002) == (string_count, min(string_data_offsets)) if string_count
            else 0x2002 not in maps, "string data map disagreement")

    def indexed(values, index, label):
        require(index < len(values), f"invalid {label} index")
        return values[index]

    type_count, type_offset, _ = tables[1]
    type_indices = [u32(type_offset + 4 * i) for i in range(type_count)]
    require(all(a < b for a, b in zip(type_indices, type_indices[1:])), "unsorted/duplicate types")
    types = [indexed(strings, index, "descriptor") for index in type_indices]
    require(all(re.fullmatch(r"(?:V|\[*[BCDFIJSZ]|\[*L[^\x00-\x20.;\[]+;)", t) for t in types),
            "invalid descriptor")

    def class_type(index):
        value = indexed(types, index, "class type")
        require(value.startswith("L"), "non-class owner")
        return value

    proto_count, proto_offset, _ = tables[2]
    previous_proto = None
    for i in range(proto_count):
        shorty, returns, parameters = struct.unpack_from("<III", data, proto_offset + 12 * i)
        declared_shorty = indexed(strings, shorty, "shorty")
        return_type = indexed(types, returns, "return type")
        parameter_indices = []
        if parameters:
            require(parameters >= data_offset and parameters % 4 == 0, "parameter list offset")
            count = u32(parameters)
            span(parameters + 4, count * 2)
            for j in range(count):
                type_index = struct.unpack_from("<H", data, parameters + 4 + j * 2)[0]
                parameter_indices.append(type_index)
                value = indexed(types, type_index, "parameter")
                require(value != "V", "void parameter")
        descriptor_types = [return_type] + [types[index] for index in parameter_indices]
        expected_shorty = "".join("L" if value.startswith(("L", "[")) else value
                                 for value in descriptor_types)
        require(declared_shorty == expected_shorty, "prototype shorty mismatch")
        key = (returns, tuple(parameter_indices))
        require(previous_proto is None or previous_proto < key, "unsorted/duplicate prototypes")
        previous_proto = key

    field_owners, method_owners = set(), set()
    for table_index, owners in [(3, field_owners), (4, method_owners)]:
        count, offset, _ = tables[table_index]
        previous = None
        for i in range(count):
            owner, value, name = struct.unpack_from("<HHI", data, offset + 8 * i)
            descriptor = indexed(types, owner, "member owner")
            require(descriptor.startswith("L") or (table_index == 4 and descriptor.startswith("[")),
                    "non-reference member owner")
            owners.add(descriptor)
            require(bool(indexed(strings, name, "member name")), "empty member name")
            if table_index == 3:
                require(indexed(types, value, "field type") != "V", "void field")
            else:
                require(value < proto_count, "invalid method prototype")
            key = (owner, name, value)
            require(previous is None or previous < key, "unsorted/duplicate members")
            previous = key

    class_count, class_offset, _ = tables[5]
    classes = set()
    for i in range(class_count):
        owner, flags, parent, interfaces, source, annotations, class_data, values = \
            struct.unpack_from("<8I", data, class_offset + 32 * i)
        name = class_type(owner)
        require(name not in classes, "duplicate class definition")
        classes.add(name)
        if parent != 0xFFFFFFFF:
            class_type(parent)
        if source != 0xFFFFFFFF:
            indexed(strings, source, "source file")
        for offset in [interfaces, annotations, class_data, values]:
            require(offset == 0 or data_offset <= offset < len(data), "class data offset")
    return {"types": set(types), "classes": classes, "field_owners": field_owners,
            "method_owners": method_owners}

"""Build synthetic DEX images for offline tests.

Keeping the generator next to the tests avoids committing third-party binaries, and lets
the suite assert what *our* reader extracts rather than trusting a fixture we cannot read.
"""

import struct

HEADER_SIZE = 0x70


def _uleb128(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | 0x80 if value else byte)
        if not value:
            return bytes(out)


def build_dex(strings, classes) -> bytes:
    """Assemble a minimal DEX image whose string pool and class table we control."""
    descriptors = ["L" + name.replace(".", "/") + ";" for name in classes]
    pool = list(strings) + [d for d in descriptors if d not in strings]

    data = bytearray()
    offsets = []
    for value in pool:
        offsets.append(HEADER_SIZE + len(data))
        data.extend(_uleb128(len(value)))
        data.extend(value.encode("utf-8"))
        data.append(0x00)
    while len(data) % 4:
        data.append(0x00)

    string_ids_off = HEADER_SIZE + len(data)
    data.extend(struct.pack(f"<{len(offsets)}I", *offsets))

    type_ids_off = HEADER_SIZE + len(data)
    type_indices = [pool.index(descriptor) for descriptor in descriptors]
    data.extend(struct.pack(f"<{len(type_indices)}I", *type_indices))

    class_defs_off = HEADER_SIZE + len(data)
    # The first field of `class_def_item` indexes type_ids, not string_ids.
    for position, _ in enumerate(type_indices):
        data.extend(struct.pack("<IIIIIIII", position, 1, 0, 0, 0, 0, 0, 0))

    header = bytearray(HEADER_SIZE)
    header[0:8] = b"dex\n035\x00"
    struct.pack_into("<I", header, 32, HEADER_SIZE + len(data))  # file_size
    struct.pack_into("<I", header, 36, HEADER_SIZE)              # header_size
    struct.pack_into("<I", header, 40, 0x12345678)               # endian_tag
    struct.pack_into("<I", header, 56, len(offsets))             # string_ids_size
    struct.pack_into("<I", header, 60, string_ids_off)
    struct.pack_into("<I", header, 64, len(type_indices))        # type_ids_size
    struct.pack_into("<I", header, 68, type_ids_off)
    struct.pack_into("<I", header, 96, len(type_indices))        # class_defs_size
    struct.pack_into("<I", header, 100, class_defs_off)
    return bytes(header) + bytes(data)

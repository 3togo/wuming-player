#!/usr/bin/env python3
"""Independently inventory a candidate DEX JAR; never converts, loads or approves provider code."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def inventory(path):
    with zipfile.ZipFile(path) as jar:
        entry = jar.getinfo('classes.dex')
        if entry.file_size > 64 * 1024 * 1024:
            raise ValueError('DEX payload exceeds review limit')
        dex = jar.read(entry)
    if not dex.startswith(b'dex\n'):
        raise ValueError('Not a DEX payload')

    def u32(offset):
        return struct.unpack_from('<I', dex, offset)[0]

    def string(index):
        offset = u32(u32(60) + index * 4)
        for _ in range(5):
            byte = dex[offset]
            offset += 1
            if not byte & 128:
                break
        else:
            raise ValueError('Malformed DEX string length')
        return dex[offset:dex.index(0, offset)].decode('utf-8')

    classes = []
    for index in range(u32(96)):
        type_index = u32(u32(100) + index * 32)
        descriptor = string(u32(u32(68) + type_index * 4))
        if not descriptor.startswith('L') or not descriptor.endswith(';'):
            raise ValueError('Invalid DEX class descriptor')
        classes.append(descriptor[1:-1].replace('/', '.'))
    return {'jarSha256': hashlib.sha256(Path(path).read_bytes()).hexdigest(),
            'dexSha256': hashlib.sha256(dex).hexdigest(), 'rawDexClassNames': sorted(classes)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    args = parser.parse_args()
    print(json.dumps(inventory(args.jar), indent=2))

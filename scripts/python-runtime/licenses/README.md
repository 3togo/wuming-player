# Native Python notices

These notices accompany the separately built runtime, not TV's own bridge implementation.

- CPython 3.13.15: source archive LICENSE (PSF and incorporated component notices).
- OpenSSL 3.0.21: upstream tagged LICENSE.txt, Apache-2.0.
- libffi 3.4.4: upstream tagged LICENSE, MIT.
- bzip2 1.0.8: upstream tagged LICENSE.
- XZ 5.4.6: COPYING from the pinned CPython Android dependency archive. Only liblzma is linked; the command-line tools are not packaged.
- libxml2 2.13.8 and libxslt 1.1.43: Copyright from their source archives.
- SQLite 3.50.4: public domain (https://sqlite.org/copyright.html).

Python wheels retain their .dist-info notices. Source-built lxml and PyCryptodome notices are copied beside their installed packages. The Linux runtime additionally carries the build machine's CPython stdlib LICENSE.txt when supplied by that SDK. Linux distributions remain tied to the builder's glibc/system-library baseline; this is not a universal static Linux binary.

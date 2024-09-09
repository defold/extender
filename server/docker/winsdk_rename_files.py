#!/usr/bin/env python3

import sys, os, re, shutil

INCLUDE_RE=re.compile(r'^.*\#\s*include\s*(?:"|<)(.*)?(?:"|>)$')

HEADER_EXTS=['.h', '.hpp', '.hxx', '.hh', '.inl']
LIB_EXTS=['.lib']

# Relative paths from current cwd
def find_files(searchdir):
    all_files = set()
    alternatives = dict()

    for root, dirs, files in os.walk(searchdir):
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if not ext in HEADER_EXTS:
                continue

            path = os.path.join(root, f)
            all_files.add(os.path.relpath(path, searchdir))

            lower = f.lower()
            if lower not in alternatives:
                alternatives[lower] = set()
            alternatives[lower].add(f)

    return all_files, alternatives

def parse_header(path):
    ext = os.path.splitext(path)[1].lower()
    if not ext in HEADER_EXTS:
        return set(), set()

    with open(path, errors='ignore') as f:
        lines = f.readlines()

    includes = set()
    libs = set()
    for line in lines:
        m = INCLUDE_RE.match(line)
        if m:
            include = os.path.basename(m.group(1))
            includes.add(include)
    return includes, libs

def insert_alternatives(alternatives, includes):
    for include in includes:
        # The current include spelling differs from any actual files
        lower = include.lower()
        if lower not in alternatives:
            alternatives[lower] = set()
        alternatives[lower].add(include)

def prune_alternatives(alternatives):
    # remove items that have a single item ( no need to rename them)
    deleted_keys = []
    for key in alternatives.keys():
        items = alternatives.get(key)
        if len(items) == 1:
            deleted_keys.append(key)
    for key in deleted_keys:
        del alternatives[key]

def do_copy_file(src, dst):
    print("COPY", src, dst)
    shutil.copy2(src, dst)


# Copy a file to to each alternative name
def copy_file_alternatives(relative_path, alternatives):
    name = os.path.basename(relative_path)
    dirname = os.path.dirname(relative_path)
    for alt in alternatives:
        if name == alt:
            print("KEEP", relative_path)
            continue
        altpath = os.path.join(dirname, alt)
        do_copy_file(relative_path, altpath)


def rename_files(all_files, unique_names):
    for f in all_files:
        name = os.path.basename(f)
        lower = name.lower()
        copy_file_alternatives(f, unique_names.get(lower, []))


if __name__ == '__main__':
    cwd = os.path.abspath(os.getcwd())

    # all_files:    contains headers and library files
    # unique_names: lower case filename to list of alternative spellings. E.g. test: [Test, tEST]
    all_files, unique_names = find_files(cwd) # Relative paths

    for f in all_files:
        includes, libs = parse_header(f)

        insert_alternatives(unique_names, includes)

    prune_alternatives(unique_names)

    rename_files(all_files, unique_names)

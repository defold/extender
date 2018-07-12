#!/usr/bin/env python

import os
import sys
import urllib
import json
import shutil
import argparse

def get_latest_version():
    url = "http://d.defold.com/stable/info.json"
    response = urllib.urlopen(url)
    if response.getcode() == 200:
        return json.loads(response.read())
    return {}

def download_file(url):
	print("Downloading %s" % url)
	tmpfile = '_dl.zip'
	os.system("wget -O %s %s" % (tmpfile, url))
	return tmpfile

def find_or_download_sdk(sha1):
	path = 'defoldsdk/%s.zip' % sha1
	if os.path.exists(path):
		print("%s already downloaded" % path)
		return path

	print("%s not found. Downloading" % path)

	dirpath = os.path.dirname(path)
	if not os.path.exists(dirpath):
		print("Created directory %s" % dirpath)
		os.makedirs(dirpath)

	url = "http://d.defold.com/archive/%s/engine/defoldsdk.zip" % sha1
	tmpfile = download_file(url)

	shutil.move(tmpfile, path)
	print("Downloaded %s" % path)
	return path

def unpack_file(path):
	unpack_path = os.path.join(os.path.dirname(path), sha1)
	if os.path.exists(unpack_path):
		return unpack_path
	print("Unpacking to %s" % unpack_path)
	os.system('unzip %s -d %s' % (path, unpack_path))
	return unpack_path

def Usage():
	print("Usage: ./debug_defoldsdk [<engine sha1>]")


if __name__ == '__main__':

	sha1 = None
	if len(sys.argv) == 1:
		d = sha1 = get_latest_version()
		sha1 = d.get('sha1', None)
		print("Latest version is %s : %s" % (d.get('version', ''), sha1))
	else:
		sha1 = sys.argv[1]

	if sha1 is None:
		Usage()
		sys.exit(1)

	print("SHA1: %s" % sha1)

	path = find_or_download_sdk(sha1)
	unpack_path = unpack_file(path)
	sdk_path = os.path.abspath(os.path.join(unpack_path, 'defoldsdk'))

	os.environ['DYNAMO_HOME'] = sdk_path
	print("export DYNAMO_HOME=%s" % sdk_path)

	print("Starting server")
	os.system("./server/scripts/run.sh")




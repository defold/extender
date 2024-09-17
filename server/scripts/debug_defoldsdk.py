#!/usr/bin/env python

import os
import sys
import urllib, urllib.request
import json
import shutil
import argparse

def get_latest_version():
    url = "http://d.defold.com/stable/info.json"
    response = urllib.request.urlopen(url)
    if response.getcode() == 200:
        return json.loads(response.read())
    return {}

def download_file(url):
	print("Downloading %s" % url)
	tmpfile = '_dl.zip'
	try:
		urllib.request.urlretrieve(url, tmpfile)
	except:
		return None
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

	domain=os.environ.get("DM_ARCHIVE_DOMAIN", "d.defold.com")
	urls = [
		"http://%s/archive/stable/%s/engine/defoldsdk.zip" % (domain, sha1),
		"http://%s/archive/beta/%s/engine/defoldsdk.zip" % (domain, sha1),
		"http://%s/archive/dev/%s/engine/defoldsdk.zip" % (domain, sha1),
		"http://%s/archive/%s/engine/defoldsdk.zip" % (domain, sha1),
		]

	for url in urls:
		tmpfile = download_file(url)
		if tmpfile is not None:
			break
		print("Downloaded from", url.replace(domain, '***'))

	if tmpfile is None or os.stat(tmpfile).st_size == 0:
		print("Downloaded file was empty. Are the credentials ok?")
		sys.exit(1)

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
	print("Docker compose profiles can be passed by variable COMPOSE_PROFILES")


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

	if not 'COMPOSE_PROFILES' in os.environ:
		os.environ['COMPOSE_PROFILES'] = 'all'
	os.environ['DYNAMO_HOME'] = '/dynamo_home'
	print('Create symlink from {} to ./dynamo_home'.format(sdk_path))
	os.system('ln -sf {} ./dynamo_home'.format(sdk_path))

	os.system("docker compose --file ./server/docker/docker-compose.yml up")

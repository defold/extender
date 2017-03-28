#!/bin/sh
echo "START"
wine /usr/bin/prepreg-vc10-dx09-32.exe
cd /tmp
wine cl hw.c
wine hw.exe
echo "END"

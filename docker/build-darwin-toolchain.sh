set -e
cd /tmp
git clone https://github.com/tpoechtrager/cctools-port.git
cd cctools-port/cctools
git checkout $1
./autogen.sh
./configure --prefix=/usr/local --target=arm-apple-darwin11
make -j8
make install

make distclean
./autogen.sh
./configure --prefix=/usr/local --target=x86_64-apple-darwin11
make -j8
make install

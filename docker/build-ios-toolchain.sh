set -e
git clone https://github.com/tpoechtrager/cctools-port.git
cd cctools-port/cctools
git checkout $1
./autogen.sh
./configure --prefix=/usr/local --target=arm-apple-darwin11
make -j8
make install

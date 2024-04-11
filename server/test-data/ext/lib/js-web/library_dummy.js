
// https://kripken.github.io/emscripten-site/docs/porting/connecting_cpp_and_javascript/Interacting-with-code.html

var LibraryMyTest = {

    // This can be accessed from the bootstrap code in the .html file
    $MYTESTLIBRARY: {
        _data: '',
        _cstr: null,

        GetTestData : function() {
            if (typeof window !== 'undefined') {
                return MYTESTLIBRARY._data;
            }
            else {
                return '';
            }
        },

        SetTestData : function(data) {
            if (typeof window !== 'undefined') {
                MYTESTLIBRARY._data = data;
            }
        },
    },

    // These can be called from within the extension, in C++
    testGetUserData: function() {
        if (null == MYTESTLIBRARY._cstr) {
            var str = MYTESTLIBRARY.GetTestData();                 // get the data from java script
            if (str != '') {
                MYTESTLIBRARY._cstr = stringToNewUTF8(str);         // allocate C++ memory to store it in
            }
        }
        return MYTESTLIBRARY._cstr;
    },

    testClearUserData: function() {
        MYTESTLIBRARY._data = '';
        _free(MYTESTLIBRARY._cstr);
        MYTESTLIBRARY._cstr = null;
    }
}

autoAddDeps(LibraryMyTest, '$MYTESTLIBRARY');
addToLibrary(LibraryMyTest);
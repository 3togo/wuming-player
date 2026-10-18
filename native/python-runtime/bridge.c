#define _GNU_SOURCE
#define PY_SSIZE_T_CLEAN
#include <Python.h>
#include <jni.h>
#include <pthread.h>
#include <dlfcn.h>

/* Providers serialize their own calls; the GIL permits different JVM threads.
 * Never finalize CPython: third-party extensions may retain process-global state. */
static pthread_mutex_t init_lock = PTHREAD_MUTEX_INITIALIZER;
static PyObject *volatile dispatch_function;
static volatile int interpreter_started;

static void fail(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}
static void python_error(JNIEnv *env) {
    PyObject *type = NULL, *value = NULL, *trace = NULL;
    PyErr_Fetch(&type, &value, &trace);
    PyErr_NormalizeException(&type, &value, &trace);
    PyObject *text = value ? PyObject_Str(value) : NULL;
    fail(env, text ? PyUnicode_AsUTF8(text) : "CPython bridge failed");
    Py_XDECREF(text); Py_XDECREF(type); Py_XDECREF(value); Py_XDECREF(trace);
}
static PyObject *jstring_unicode(JNIEnv *env, jstring text) {
    if (!text) return NULL;
    const jchar *chars = (*env)->GetStringChars(env, text, NULL);
    if (!chars) return NULL;
    int order = 0;
    PyObject *result = PyUnicode_DecodeUTF16((const char *)chars,
        (*env)->GetStringLength(env, text) * 2, "strict", &order);
    (*env)->ReleaseStringChars(env, text, chars);
    return result;
}
/* Adds host module paths ahead of the standard library. The caller must not hold the GIL. */
static int extend_path(JNIEnv *env, jobjectArray paths) {
    PyGILState_STATE gil = PyGILState_Ensure();
    PyObject *sys_path = PySys_GetObject("path");
    jsize count = paths ? (*env)->GetArrayLength(env, paths) : 0;
    if (!sys_path) { fail(env, "CPython runtime has no module search path"); PyGILState_Release(gil); return 0; }
    for (jsize i = count - 1; i >= 0; i--) {
        jstring path = (*env)->GetObjectArrayElement(env, paths, i);
        PyObject *value = jstring_unicode(env, path);
        (*env)->DeleteLocalRef(env, path);
        if (!value) { python_error(env); PyGILState_Release(gil); return 0; }
        /* Retries must not append duplicate entries to a path that already works. */
        int present = PySequence_Contains(sys_path, value);
        if (present < 0) PyErr_Clear();
        if (present == 0 && PyList_Insert(sys_path, 0, value) < 0) {
            Py_DECREF(value); python_error(env); PyGILState_Release(gil); return 0;
        }
        Py_DECREF(value);
    }
    PyGILState_Release(gil);
    return 1;
}
/* Imports the dispatcher. The caller must not hold the GIL. Returns 1 on success. */
static int load_dispatcher(JNIEnv *env) {
    PyGILState_STATE gil = PyGILState_Ensure();
    PyObject *module = PyImport_ImportModule("wuming_bridge");
    if (module) { dispatch_function = PyObject_GetAttrString(module, "dispatch"); Py_DECREF(module); }
    if (!dispatch_function) python_error(env);
    PyGILState_Release(gil);
    return dispatch_function != NULL;
}
JNIEXPORT void JNICALL Java_com_wuming_tv_core_python_NativePythonBridge_startNative
  (JNIEnv *env, jobject self, jstring home, jobjectArray paths) {
    (void)self;
    pthread_mutex_lock(&init_lock);
    if (dispatch_function) { pthread_mutex_unlock(&init_lock); return; }
    /* A failed dispatcher import must stay retryable; CPython itself is never restarted. */
    if (interpreter_started) {
        if (extend_path(env, paths)) load_dispatcher(env);
        pthread_mutex_unlock(&init_lock); return;
    }
    if (Py_IsInitialized()) {
        fail(env, "Another CPython runtime is already initialized");
        pthread_mutex_unlock(&init_lock); return;
    }
    /* Extension modules on Linux resolve the Python C API from global symbols. */
    Dl_info python_library;
    if (!dladdr((void *)&Py_Initialize, &python_library) ||
        !dlopen(python_library.dli_fname, RTLD_NOW | RTLD_GLOBAL)) {
        const char *reason = dlerror();
        fail(env, reason ? reason : "Cannot expose the CPython C API globally");
        pthread_mutex_unlock(&init_lock); return;
    }
    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);
    config.install_signal_handlers = 0;
    config.write_bytecode = 0;
    config.site_import = 0;
    PyStatus status = PyStatus_Ok();
    /* JNI modified UTF-8 must not be used for user paths. Decode UTF-16 explicitly. */
    if (!PyStatus_Exception(status)) {
        const jchar *chars = (*env)->GetStringChars(env, home, NULL);
        jsize len = (*env)->GetStringLength(env, home);
        if (!chars) { fail(env, "Cannot read the Python runtime home"); PyConfig_Clear(&config); pthread_mutex_unlock(&init_lock); return; }
        wchar_t *wide = PyMem_RawMalloc((len + 1) * sizeof(wchar_t));
        if (!wide) { (*env)->ReleaseStringChars(env, home, chars); PyConfig_Clear(&config); fail(env, "Out of memory"); pthread_mutex_unlock(&init_lock); return; }
        size_t n = 0;
        for (jsize i = 0; i < len; i++) {
            unsigned cp = chars[i];
            if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < len) cp = 0x10000 + ((cp - 0xd800) << 10) + (chars[++i] - 0xdc00);
            wide[n++] = cp;
        }
        wide[n] = 0;
        (*env)->ReleaseStringChars(env, home, chars);
        status = PyConfig_SetString(&config, &config.home, wide);
        PyMem_RawFree(wide);
    }
    if (!PyStatus_Exception(status)) status = Py_InitializeFromConfig(&config);
    if (PyStatus_Exception(status)) {
        fail(env, status.err_msg ? status.err_msg : "CPython initialization failed");
        PyConfig_Clear(&config); pthread_mutex_unlock(&init_lock); return;
    }
    PyConfig_Clear(&config);
    interpreter_started = 1;
    /* Calls release their own GIL; the initializing thread must not keep it either. */
    PyThreadState *released = PyEval_SaveThread();
    (void)released;
    if (extend_path(env, paths)) load_dispatcher(env);
    pthread_mutex_unlock(&init_lock);
}
JNIEXPORT jbyteArray JNICALL Java_com_wuming_tv_core_python_NativePythonBridge_dispatchNative
  (JNIEnv *env, jobject self, jbyteArray request) {
    (void)self;
    if (!dispatch_function) { fail(env, "CPython runtime has not started"); return NULL; }
    jsize length = (*env)->GetArrayLength(env, request);
    if (length > 8 * 1024 * 1024) { fail(env, "Python request exceeds 8 MiB"); return NULL; }
    PyGILState_STATE gil = PyGILState_Ensure();
    jbyte *bytes = request ? (*env)->GetByteArrayElements(env, request, NULL) : NULL;
    PyObject *arg = bytes ? PyUnicode_DecodeUTF8((char *)bytes, length, "strict") : NULL;
    if (bytes) (*env)->ReleaseByteArrayElements(env, request, bytes, JNI_ABORT);
    PyObject *result = arg ? PyObject_CallOneArg(dispatch_function, arg) : NULL;
    Py_XDECREF(arg);
    jbyteArray output = NULL;
    if (result) {
        Py_ssize_t size;
        const char *text = PyUnicode_AsUTF8AndSize(result, &size);
        if (text && size <= 16 * 1024 * 1024) {
            output = (*env)->NewByteArray(env, (jsize)size);
            if (output) (*env)->SetByteArrayRegion(env, output, 0, (jsize)size, (const jbyte *)text);
        } else if (text) fail(env, "Python result exceeds 16 MiB");
        else python_error(env);
        Py_DECREF(result);
    } else python_error(env);
    PyGILState_Release(gil);
    return output;
}

/* Keep the original exports for older hosts while the runtime module uses its new package. */
JNIEXPORT void JNICALL Java_com_wuming_tv_core_runtime_python_NativePythonBridge_startNative
  (JNIEnv *env, jobject self, jstring home, jobjectArray paths) {
    Java_com_wuming_tv_core_python_NativePythonBridge_startNative(env, self, home, paths);
}
JNIEXPORT jbyteArray JNICALL Java_com_wuming_tv_core_runtime_python_NativePythonBridge_dispatchNative
  (JNIEnv *env, jobject self, jbyteArray request) {
    return Java_com_wuming_tv_core_python_NativePythonBridge_dispatchNative(env, self, request);
}

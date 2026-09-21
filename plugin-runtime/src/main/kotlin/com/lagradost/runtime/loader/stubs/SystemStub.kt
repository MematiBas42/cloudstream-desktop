package com.lagradost.runtime.loader.stubs

import com.lagradost.common.logging.AppLogger

object SystemStub {
    @JvmStatic
    fun exit(status: Int) {
        AppLogger.i("SecuritySandbox", "Blocked System.exit($status)")
    }

    @JvmStatic
    fun loadLibrary(libname: String) {
        AppLogger.i("SecuritySandbox", "Blocked System.loadLibrary($libname)")
    }

    @JvmStatic
    fun load(filename: String) {
        AppLogger.i("SecuritySandbox", "Blocked System.load($filename)")
    }

    @JvmStatic
    fun setSecurityManager(s: SecurityManager?) {
        AppLogger.i("SecuritySandbox", "Blocked System.setSecurityManager()")
    }
}

package com.wuming.android.tv.db

import com.wuming.android.tv.App
import com.wuming.android.tv.utils.Task
import java.util.concurrent.Future
import java.util.function.Consumer

object AsyncDB {

    @JvmStatic
    fun <T> query(query: DBQuery<T>, callback: Consumer<T>?): Future<*> =
        Task.submit {
            try {
                val result = query.execute()
                App.post { callback?.accept(result) }
            } catch (e: Exception) {
                com.orhanobut.logger.Logger.e(e, "Database query failed")
            }
        }

    @JvmStatic
    fun execute(operation: DBOperation): Future<*> =
        Task.submit {
            try {
                operation.execute()
            } catch (e: Exception) {
                com.orhanobut.logger.Logger.e(e, "Database operation failed")
            }
        }

    @JvmStatic
    fun <T> queryBackground(query: DBQuery<T>): Future<*> =
        Task.submit {
            try {
                query.execute()
            } catch (e: Exception) {
                com.orhanobut.logger.Logger.e(e, "Background database query failed")
            }
        }

    @JvmStatic
    fun <T> queryList(query: DBQuery<List<T>>, callback: Consumer<List<T>>?): Future<*> =
        query(query, callback)

    fun interface DBQuery<T> {
        fun execute(): T
    }

    fun interface DBOperation {
        fun execute()
    }
}

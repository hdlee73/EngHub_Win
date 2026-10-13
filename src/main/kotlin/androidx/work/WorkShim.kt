package androidx.work

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class Data(private val map: Map<String, Any?> = emptyMap()) {
    fun getString(key: String): String? = map[key] as? String
    fun getInt(key: String, defaultValue: Int): Int = (map[key] as? Number)?.toInt() ?: defaultValue
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = (map[key] as? Boolean) ?: defaultValue
    @Suppress("UNCHECKED_CAST")
    fun getStringArray(key: String): Array<String>? = when (val v = map[key]) {
        is Array<*> -> v.mapNotNull { it?.toString() }.toTypedArray()
        is Collection<*> -> v.mapNotNull { it?.toString() }.toTypedArray()
        else -> null
    }
}

fun workDataOf(vararg pairs: Pair<String, Any?>): Data = Data(mapOf(*pairs))

enum class NetworkType {
    CONNECTED, NOT_REQUIRED
}

class Constraints {
    class Builder {
        fun setRequiredNetworkType(networkType: NetworkType): Builder = this
        fun build(): Constraints = Constraints()
    }
}

class WorkerParameters(val inputData: Data = Data())

abstract class CoroutineWorker(
    val applicationContext: Context,
    private val params: WorkerParameters
) {
    val inputData: Data get() = params.inputData
    val runAttemptCount: Int = 0

    sealed class Result {
        class Success : Result()
        class Failure : Result()
        class Retry : Result()

        companion object {
            fun success(): Result = Success()
            fun failure(): Result = Failure()
            fun retry(): Result = Retry()
        }
    }

    abstract suspend fun doWork(): Result
}

open class WorkRequest(val workerClass: Class<out CoroutineWorker>, val inputData: Data)
class OneTimeWorkRequest(workerClass: Class<out CoroutineWorker>, inputData: Data) : WorkRequest(workerClass, inputData)
class PeriodicWorkRequest(workerClass: Class<out CoroutineWorker>, inputData: Data) : WorkRequest(workerClass, inputData)

class OneTimeWorkRequestBuilder<W : CoroutineWorker>(private val workerClass: Class<W>) {
    private var data: Data = Data()
    fun setInputData(inputData: Data): OneTimeWorkRequestBuilder<W> {
        this.data = inputData
        return this
    }
    fun setConstraints(constraints: Constraints): OneTimeWorkRequestBuilder<W> = this
    fun build(): OneTimeWorkRequest = OneTimeWorkRequest(workerClass, data)
}

inline fun <reified W : CoroutineWorker> OneTimeWorkRequestBuilder(): OneTimeWorkRequestBuilder<W> =
    OneTimeWorkRequestBuilder(W::class.java)

class PeriodicWorkRequestBuilder<W : CoroutineWorker>(private val workerClass: Class<W>) {
    private var data: Data = Data()
    fun setInputData(inputData: Data): PeriodicWorkRequestBuilder<W> {
        this.data = inputData
        return this
    }
    fun setConstraints(constraints: Constraints): PeriodicWorkRequestBuilder<W> = this
    fun build(): PeriodicWorkRequest = PeriodicWorkRequest(workerClass, data)
}

inline fun <reified W : CoroutineWorker> PeriodicWorkRequestBuilder(
    repeatInterval: Long,
    repeatIntervalTimeUnit: TimeUnit
): PeriodicWorkRequestBuilder<W> = PeriodicWorkRequestBuilder(W::class.java)

enum class ExistingWorkPolicy {
    REPLACE, KEEP
}

enum class ExistingPeriodicWorkPolicy {
    KEEP, UPDATE, REPLACE
}

class WorkManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(work: WorkRequest) {
        scope.launch {
            runCatching {
                val worker = work.workerClass
                    .getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
                    .newInstance(context.applicationContext, WorkerParameters(work.inputData))
                worker.doWork()
            }
        }
    }

    fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        work: OneTimeWorkRequest
    ) {
        enqueue(work)
    }

    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        periodicWork: PeriodicWorkRequest
    ) {}

    companion object {
        private val instance by lazy { WorkManager(android.app.Application.instance) }
        fun getInstance(context: Context): WorkManager = instance
    }
}

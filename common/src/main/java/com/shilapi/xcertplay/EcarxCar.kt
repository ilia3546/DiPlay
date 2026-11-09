package com.shilapi.xcertplay

import android.content.Context
import android.content.ServiceConnection
import java.lang.reflect.InvocationTargetException

/** The head unit's `ecarx.car` service, in the boot classpath of ECARX firmware and reached by reflection. */
internal object EcarxCar {
    const val CLASS = "ecarx.car.ECarXCar"

    fun present(): Boolean = runCatching { Class.forName(CLASS) }.isSuccess

    /** A car whose [listener] hears the service once [connect] binds it. */
    fun create(app: Context, listener: ServiceConnection): Any =
        requireNotNull(Class.forName(CLASS).getMethod("createCar", Context::class.java, ServiceConnection::class.java)
            .invoke(null, app, listener)) { "no ecarx.car" }

    fun connect(car: Any) {
        Class.forName(CLASS).getMethod("connect").invoke(car)
    }

    fun disconnect(car: Any) {
        runCatching { car.javaClass.getMethod("disconnect").invoke(car) }
    }

    fun manager(car: Any, name: String): Any =
        requireNotNull(car.javaClass.getMethod("getCarManager", String::class.java).invoke(car, name)) { "no $name" }

    /** The class of what went wrong, through the reflection wrapper. */
    fun describe(error: Throwable): String =
        ((error as? InvocationTargetException)?.targetException ?: error).javaClass.simpleName
}
